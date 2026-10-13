"""Running one extraction job (AI 2.3 to 2.5, 4.3; PI 5.4 to 5.9).

The loop: check cancel, resume from sealed parts, announce the run, build the graph's result,
seal every part in JOB_CONTROL, publish the parts, then the terminal status. A restart that finds
the final marker republishes the sealed parts unchanged and never calls the model again.
"""

from __future__ import annotations

import hashlib
import json
import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any, Protocol
from uuid import UUID

from tender_worker import metrics
from tender_worker.agent.extraction import (
    ExtractionGraph,
    ExtractionSettings,
    NoContentInScope,
    to_item,
)
from tender_worker.agent.llm import LlmUnavailable, StructuredLlm
from tender_worker.agent.retrieval import PassageSource, ScopeItem
from tender_worker.messages import (
    SCHEMA_RESULT,
    SCHEMA_STATUS,
    Command,
    Failure,
    ResultBatch,
    RunInfo,
    Status,
    Target,
    WorkerInfo,
)
from tender_worker.messages import Warning_ as StatusWarning
from tender_worker.prompts import EXTRACTION_VERSION

log = logging.getLogger(__name__)

STATUS_SUBJECT = "documents.analysis.status.requirement_extraction"
RESULT_SUBJECT = "documents.analysis.result.requirement_extraction"
STEPS = ["load_scope", "plan_windows", "extract", "merge", "compare", "flag", "seal"]
BATCH_BYTES = 256 * 1024


class Publisher(Protocol):
    async def publish(
        self, subject: str, payload: bytes, headers: dict[str, str], msg_id: str
    ) -> None: ...


class Kv(Protocol):
    async def get(self, key: str) -> bytes | None: ...
    async def put(self, key: str, value: bytes) -> None: ...


class Blobs(Protocol):
    async def get(self, bucket: str, key: str) -> bytes: ...


@dataclass
class AgentDeps:
    publisher: Publisher
    kv: Kv
    blobs: Blobs
    source: PassageSource
    llm: StructuredLlm
    embed: Callable[[list[str]], Awaitable[list[list[float]]]]
    worker: WorkerInfo
    bucket_jobs: str
    model_name: str
    model_version: str
    settings: ExtractionSettings
    batch_max_items: int = 50
    cancel_check_seconds: float = 10.0


class BadInput(Exception):
    """The command's input is unreadable: terminate and copy to the dead-letter stream."""


async def _load_input(deps: AgentDeps, command: Command) -> dict[str, Any]:
    if command.input is not None:
        return command.input
    assert command.input_ref is not None
    raw = await deps.blobs.get(command.input_ref.bucket, command.input_ref.key)
    if hashlib.sha256(raw).hexdigest() != command.input_ref.sha256:
        raise BadInput("input does not match its hash")
    return json.loads(raw)


def _scope(data: dict[str, Any]) -> list[ScopeItem]:
    out: list[ScopeItem] = []
    for s in data.get("scope", []):
        out.append(
            ScopeItem(
                version_id=str(s["document_version_id"]),
                section_id=s.get("section_id"),
                page_from=s.get("page_from"),
                page_to=s.get("page_to"),
            )
        )
    if not out:
        raise BadInput("the scope is empty")
    return out


class _Emitter:
    def __init__(self, command: Command, deps: AgentDeps, deliveries: int, traceparent: str):
        self._c = command
        self._d = deps
        self._tp = traceparent
        self._seq = (deliveries - 1) * 1000

    def _headers(self, msg_id: str, schema: str) -> dict[str, str]:
        return {"Nats-Msg-Id": msg_id, "traceparent": self._tp, "Tender-Schema": schema}

    async def status(self, state: str, **kw: Any) -> None:
        self._seq += 1
        status = Status(
            job_id=self._c.job_id,
            job_type="requirement_extraction",
            tender_id=self._c.tender_id,
            target=Target(type="tender", id=self._c.tender_id),
            seq=self._seq,
            state=state,  # type: ignore[arg-type]
            emitted_at=datetime.now(UTC),
            worker=self._d.worker,
            **kw,
        )
        msg_id = f"{self._c.job_id}:s:{status.seq}"
        await self._d.publisher.publish(
            STATUS_SUBJECT,
            status.model_dump_json(exclude_none=True).encode(),
            self._headers(msg_id, SCHEMA_STATUS),
            msg_id,
        )

    async def batch(self, part_no: int, final: bool, items: list[dict]) -> None:
        batch = ResultBatch(
            job_id=self._c.job_id,
            job_type="requirement_extraction",
            tender_id=self._c.tender_id,
            target=Target(type="tender", id=self._c.tender_id),
            kind="requirements",
            part_no=part_no,
            final=final,
            items=items,
            emitted_at=datetime.now(UTC),
            worker=self._d.worker,
        )
        msg_id = f"{self._c.job_id}:r:{part_no}"
        await self._d.publisher.publish(
            RESULT_SUBJECT,
            batch.model_dump_json(exclude_none=True).encode(),
            self._headers(msg_id, SCHEMA_RESULT),
            msg_id,
        )


def _parts(items: list[dict], max_items: int) -> list[list[dict]]:
    parts: list[list[dict]] = []
    current: list[dict] = []
    size = 0
    for item in items:
        n = len(json.dumps(item))
        if current and (len(current) >= max_items or size + n > BATCH_BYTES):
            parts.append(current)
            current, size = [], 0
        current.append(item)
        size += n
    if current or not parts:
        parts.append(current)
    return parts


def _warnings(counters: dict[str, int], items: list[Any]) -> list[StatusWarning]:
    out: list[StatusWarning] = []
    if counters.get("ungrounded_candidate"):
        out.append(
            StatusWarning(
                code="ungrounded_candidate",
                message="Candidates without a verifiable source were dropped",
                count=counters["ungrounded_candidate"],
            )
        )
    injected = sum(1 for i in items if getattr(i, "injection", False))
    if injected:
        out.append(
            StatusWarning(
                code="suspected_injection",
                message="A passage held instruction-like text",
                count=injected,
            )
        )
    return out


async def run_extraction(
    deps: AgentDeps,
    command: Command,
    deliveries: int,
    traceparent: str,
    cancelled: Callable[[UUID], Awaitable[bool]],
) -> None:
    emitter = _Emitter(command, deps, deliveries, traceparent)
    job = command.job_id
    if await cancelled(job):
        await emitter.status("cancelled")
        return

    # A restart after sealing republishes exactly what was sealed (Part, glossary).
    final_raw = await deps.kv.get(f"part.{job}.final")
    if final_raw is not None:
        await _republish(deps, emitter, job, json.loads(final_raw))
        return

    data = await _load_input(deps, command)
    scope = _scope(data)
    run = RunInfo(
        model_name=deps.model_name,
        model_version=deps.model_version,
        prompt_version=EXTRACTION_VERSION,
        input_scope={
            "document_versions": sorted({s.version_id for s in scope}),
            "existing_requirements": len(data.get("existing_requirements", [])),
            "window_chars": deps.settings.window_chars,
            "dedup_threshold": deps.settings.dedup_threshold,
        },
    )
    await emitter.status("running", step="load_scope", progress_percent=2, steps=STEPS, run=run)

    async def progress(step: str, percent: int) -> None:
        await emitter.status("running", step=step, progress_percent=percent)

    async def check() -> bool:
        return await cancelled(job)

    graph = ExtractionGraph(
        llm=deps.llm,
        passages=deps.source,
        embed=deps.embed,
        tender_id=command.tender_id,
        job_id=job,
        settings=deps.settings,
        on_progress=progress,
        cancelled=check,
    )
    try:
        grounded, counters = await graph.run(
            scope, data.get("categories", []), data.get("existing_requirements", [])
        )
    except NoContentInScope:
        await emitter.status(
            "failed",
            failure=Failure(
                code="no_content_in_scope",
                message="The chosen pages hold no text",
                retryable=False,
            ),
        )
        metrics.inc("extraction_jobs_total", outcome="no_content_in_scope")
        return
    except LlmUnavailable:
        await emitter.status(
            "failed",
            failure=Failure(
                code="llm_unavailable",
                message="The model did not answer",
                retryable=True,
            ),
        )
        metrics.inc("extraction_jobs_total", outcome="llm_unavailable")
        return

    if await cancelled(job):
        await emitter.status("cancelled")
        return

    await emitter.status("running", step="seal", progress_percent=95)
    items = [to_item(g) for g in grounded]
    parts = _parts(items, deps.batch_max_items)
    warnings = _warnings(counters, grounded)
    # Seal every part first, then the marker: only then is anything published.
    for n, part in enumerate(parts, start=1):
        await deps.kv.put(f"part.{job}.{n}", json.dumps(part).encode())
    final = {
        "parts": len(parts),
        "counts": {"requirements": len(items)},
        "warnings": [w.model_dump() for w in warnings],
    }
    await deps.kv.put(f"part.{job}.final", json.dumps(final).encode())
    await _republish(deps, emitter, job, final)
    metrics.inc("extraction_jobs_total", outcome="completed")
    metrics.inc("extraction_requirements_total", float(len(items)))


async def _republish(deps: AgentDeps, emitter: _Emitter, job: UUID, final: dict) -> None:
    count = int(final["parts"])
    for n in range(1, count + 1):
        raw = await deps.kv.get(f"part.{job}.{n}")
        if raw is None:
            raise RuntimeError("a sealed part is missing")
        await emitter.batch(n, n == count, json.loads(raw))
    warnings = [StatusWarning(**w) for w in final.get("warnings", [])]
    await emitter.status(
        "completed_with_warnings" if warnings else "completed",
        step="seal",
        progress_percent=100,
        result_parts=count,
        counts=final["counts"],
        warnings=warnings or None,
    )
