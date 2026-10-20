"""Running one extraction job (AI 2.3 to 2.5, 4.3; PI 5.4 to 5.9).

The loop: check cancel, resume from sealed parts, announce the run, build the graph's result,
seal every part in JOB_CONTROL, publish the parts, then the terminal status. A restart that finds
the final marker republishes the sealed parts unchanged and never calls the model again.
"""

from __future__ import annotations

import asyncio
import contextlib
import hashlib
import json
import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any, Protocol
from uuid import UUID

from tender_worker import metrics, tracing
from tender_worker.agent.evaluation import (
    EvaluationGraph,
    EvaluationSettings,
    Requirement,
)
from tender_worker.agent.extraction import (
    ExtractionGraph,
    ExtractionSettings,
    NoContentInScope,
    to_item,
)
from tender_worker.agent.llm import LlmOutputUnusable, LlmUnavailable, StructuredLlm
from tender_worker.agent.markdown import MarkdownSource, blocks_key
from tender_worker.agent.precheck import PrecheckGraph
from tender_worker.agent.retrieval import PassageSource, ScopeItem
from tender_worker.agent.search import Searcher
from tender_worker.messages import (
    SCHEMA_RESULT,
    SCHEMA_STATUS,
    Command,
    Failure,
    ResultBatch,
    RunInfo,
    Status,
    WorkerInfo,
)
from tender_worker.messages import Warning_ as StatusWarning
from tender_worker.prompts import EVALUATION_VERSION, EXTRACTION_VERSION, PRECHECK_VERSION

log = logging.getLogger(__name__)

STATUS_SUBJECT = "documents.analysis.status.{}"
RESULT_SUBJECT = "documents.analysis.result.{}"
STEPS = ["load_scope", "plan_windows", "extract", "merge", "compare", "flag", "seal"]
PRECHECK_STEPS = ["load_scope", "find_format", "list_expected", "match", "seal"]
EVALUATION_STEPS = ["load_input", "retrieve", "grade", "validate", "seal"]
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
    precheck_model: str | None = None
    evaluation_model: str | None = None
    search: Searcher | None = None
    embed_query: Callable[[list[str]], Awaitable[list[list[float]]]] | None = None
    evaluation_settings: EvaluationSettings = field(default_factory=EvaluationSettings)
    concurrency: int = 8
    bucket_derived: str = "tender-derived"


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


KEEPALIVE_SECONDS = 60


class _Emitter:
    def __init__(self, command: Command, deps: AgentDeps, deliveries: int, traceparent: str):
        self._c = command
        self._d = deps
        self._tp = traceparent
        self._seq = (deliveries - 1) * 1000
        self._last: dict[str, Any] = {}

    @contextlib.asynccontextmanager
    async def keepalive(self, every: float = KEEPALIVE_SECONDS):
        """Repeat the last running status while the job works.

        A slow model can take minutes for one call; without this the backend sees silence and its
        watchdog fails a healthy job. The repeat carries the step and percent already reported,
        nothing else.
        """

        async def beat() -> None:
            while True:
                await asyncio.sleep(every)
                if self._last:
                    try:
                        await self.status("running", **self._last)
                    except Exception:  # noqa: BLE001 - a missed beat must not stop the job
                        log.warning("keepalive status was not published")

        task = asyncio.create_task(beat())
        try:
            yield
        finally:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task

    def _headers(self, msg_id: str, schema: str) -> dict[str, str]:
        return {"Nats-Msg-Id": msg_id, "traceparent": self._tp, "Tender-Schema": schema}

    async def status(self, state: str, **kw: Any) -> None:
        if state == "running":
            self._last = {
                k: kw[k] for k in ("step", "progress_percent") if kw.get(k) is not None
            } or self._last
        self._seq += 1
        status = Status(
            job_id=self._c.job_id,
            job_type=self._c.job_type,
            tender_id=self._c.tender_id,
            target=self._c.target,
            seq=self._seq,
            state=state,  # type: ignore[arg-type]
            emitted_at=datetime.now(UTC),
            worker=self._d.worker,
            **kw,
        )
        msg_id = f"{self._c.job_id}:s:{status.seq}"
        if state in ("completed", "failed", "cancelled"):
            failure = kw.get("failure")
            tracing.set_attributes(
                {
                    "tender.outcome": state,
                    "tender.failure_code": getattr(failure, "code", "") or "",
                }
            )
        await self._d.publisher.publish(
            STATUS_SUBJECT.format(self._c.job_type),
            status.model_dump_json(exclude_none=True).encode(),
            self._headers(msg_id, SCHEMA_STATUS),
            msg_id,
        )

    async def batch(
        self, part_no: int, final: bool, items: list[dict], kind: str = "requirements"
    ) -> None:
        batch = ResultBatch(
            job_id=self._c.job_id,
            job_type=self._c.job_type,
            tender_id=self._c.tender_id,
            target=self._c.target,
            kind=kind,  # type: ignore[arg-type]
            part_no=part_no,
            final=final,
            items=items,
            emitted_at=datetime.now(UTC),
            worker=self._d.worker,
        )
        msg_id = f"{self._c.job_id}:r:{part_no}"
        await self._d.publisher.publish(
            RESULT_SUBJECT.format(self._c.job_type),
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


def _markdown_source(deps: AgentDeps) -> MarkdownSource:
    """The scope as markdown blocks. A version without stored blocks is read from its passages."""

    async def read_blocks(tender_id: UUID, version_id: str) -> bytes | None:
        try:
            return await deps.blobs.get(deps.bucket_derived, blocks_key(tender_id, version_id))
        except Exception:  # noqa: BLE001 - absent for PDFs and for versions that predate blocks
            return None

    return MarkdownSource(deps.source, read_blocks)


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
    if counters.get("under_extraction"):
        out.append(
            StatusWarning(
                code="under_extraction",
                message="Some sections hold more obligation wording than requirements were found",
                count=counters["under_extraction"],
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
    with tracing.span(
        "job.requirement_extraction",
        "CHAIN",
        {
            "tender.job_id": str(command.job_id),
            "tender.tender_id": str(command.tender_id),
            "tender.target_id": str(command.target.id),
            "tender.worker_version": deps.worker.version,
            "tender.prompt_version": EXTRACTION_VERSION,
            "tender.delivery": deliveries,
        },
        traceparent=traceparent,
    ):
        emitter = _Emitter(command, deps, deliveries, traceparent)
        async with emitter.keepalive():
            await _run_extraction(deps, command, deliveries, traceparent, cancelled, emitter)


async def _run_extraction(
    deps: AgentDeps,
    command: Command,
    deliveries: int,
    traceparent: str,
    cancelled: Callable[[UUID], Awaitable[bool]],
    emitter: _Emitter,
) -> None:
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
            "chunk_chars": deps.settings.chunk_chars,
            "chunk_lines": deps.settings.chunk_lines,
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
        source=_markdown_source(deps),
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
    except LlmOutputUnusable:
        await emitter.status(
            "failed",
            failure=Failure(
                code="model_output_unusable",
                message="The model's answers were cut off or unreadable",
                retryable=False,
            ),
        )
        metrics.inc("extraction_jobs_total", outcome="model_output_unusable")
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
        await emitter.batch(n, n == count, json.loads(raw), final.get("kind", "requirements"))
    warnings = [StatusWarning(**w) for w in final.get("warnings", [])]
    await emitter.status(
        "completed_with_warnings" if warnings else "completed",
        step="seal",
        progress_percent=100,
        result_parts=count,
        counts=final["counts"],
        warnings=warnings or None,
    )


_PRECHECK_WARNINGS = {
    "no_format_found": "The request for proposal does not say how a proposal must be organised",
}


def _job_span(command: Command, deps: AgentDeps, deliveries: int, version: str, traceparent: str):
    """The root span of a job, under the trace the backend started (AI 6.1, level 1)."""
    return tracing.span(
        f"job.{command.job_type}",
        "CHAIN",
        {
            "tender.job_id": str(command.job_id),
            "tender.tender_id": str(command.tender_id),
            "tender.target_id": str(command.target.id),
            "tender.worker_version": deps.worker.version,
            "tender.prompt_version": version,
            "tender.delivery": deliveries,
        },
        traceparent=traceparent,
    )


async def run_precheck(
    deps: AgentDeps,
    command: Command,
    deliveries: int,
    traceparent: str,
    cancelled: Callable[[UUID], Awaitable[bool]],
) -> None:
    with _job_span(command, deps, deliveries, PRECHECK_VERSION, traceparent):
        emitter = _Emitter(command, deps, deliveries, traceparent)
        async with emitter.keepalive():
            await _run_precheck(deps, command, deliveries, traceparent, cancelled, emitter)


async def _run_precheck(
    deps: AgentDeps,
    command: Command,
    deliveries: int,
    traceparent: str,
    cancelled: Callable[[UUID], Awaitable[bool]],
    emitter: _Emitter,
) -> None:
    """The pre-check job (AI 4.4): advisory findings, sealed and published like an extraction."""
    job = command.job_id
    if await cancelled(job):
        await emitter.status("cancelled")
        return
    final_raw = await deps.kv.get(f"part.{job}.final")
    if final_raw is not None:
        await _republish(deps, emitter, job, json.loads(final_raw))
        return

    data = await _load_input(deps, command)
    rfp = [ScopeItem(version_id=str(v)) for v in data.get("rfp_document_version_ids", [])]
    proposal = [ScopeItem(version_id=str(v)) for v in data.get("proposal_document_version_ids", [])]
    if not rfp or not proposal:
        raise BadInput("the scope is empty")
    run = RunInfo(
        model_name=deps.precheck_model or deps.model_name,
        model_version=deps.model_version,
        prompt_version=PRECHECK_VERSION,
        input_scope={
            "rfp_document_versions": sorted({s.version_id for s in rfp}),
            "proposal_document_versions": sorted({s.version_id for s in proposal}),
        },
    )
    await emitter.status(
        "running", step="load_scope", progress_percent=2, steps=PRECHECK_STEPS, run=run
    )

    async def progress(step: str, percent: int) -> None:
        await emitter.status("running", step=step, progress_percent=percent)

    async def check() -> bool:
        return await cancelled(job)

    graph = PrecheckGraph(
        llm=deps.llm,
        source=deps.source,
        tender_id=command.tender_id,
        job_id=job,
        model=deps.precheck_model,
        on_progress=progress,
        cancelled=check,
    )
    try:
        findings, warn_codes = await graph.run(rfp, proposal)
    except NoContentInScope:
        await emitter.status(
            "failed",
            failure=Failure(
                code="no_content_in_scope",
                message="The documents hold no text",
                retryable=False,
            ),
        )
        metrics.inc("precheck_jobs_total", outcome="no_content_in_scope")
        return
    except LlmOutputUnusable:
        await emitter.status(
            "failed",
            failure=Failure(
                code="model_output_unusable",
                message="The model's answers were cut off or unreadable",
                retryable=False,
            ),
        )
        metrics.inc("precheck_jobs_total", outcome="model_output_unusable")
        return
    except LlmUnavailable:
        await emitter.status(
            "failed",
            failure=Failure(
                code="llm_unavailable", message="The model did not answer", retryable=True
            ),
        )
        metrics.inc("precheck_jobs_total", outcome="llm_unavailable")
        return
    if await cancelled(job):
        await emitter.status("cancelled")
        return

    await emitter.status("running", step="seal", progress_percent=95)
    parts = _parts(findings, deps.batch_max_items)
    for n, part in enumerate(parts, start=1):
        await deps.kv.put(f"part.{job}.{n}", json.dumps(part).encode())
    warnings = [
        StatusWarning(code=c, message=_PRECHECK_WARNINGS.get(c, c), count=1) for c in warn_codes
    ]
    final = {
        "parts": len(parts),
        "kind": "prechecks",
        "counts": {"prechecks": len(findings)},
        "warnings": [w.model_dump() for w in warnings],
    }
    await deps.kv.put(f"part.{job}.final", json.dumps(final).encode())
    await _republish(deps, emitter, job, final)
    metrics.inc("precheck_jobs_total", outcome="completed")


async def _sealed_parts(deps: AgentDeps, job: UUID) -> list[list[dict]]:
    """The parts already sealed for this job, in order (a restart reads them, never redoes them)."""
    parts: list[list[dict]] = []
    n = 1
    while True:
        raw = await deps.kv.get(f"part.{job}.{n}")
        if raw is None:
            return parts
        parts.append(json.loads(raw))
        n += 1


def _grade_warnings(items: list[dict], injected: int) -> list[StatusWarning]:
    out: list[StatusWarning] = []
    failed = sum(1 for i in items if i.get("outcome") == "failed")
    if failed:
        out.append(
            StatusWarning(
                code="row_failed",
                message="Some requirements could not be graded with evidence",
                count=failed,
            )
        )
    if injected:
        out.append(
            StatusWarning(
                code="suspected_injection",
                message="Some passages held instruction-like text; those grades are low confidence",
                count=injected,
            )
        )
    return out


async def run_evaluation(
    deps: AgentDeps,
    command: Command,
    deliveries: int,
    traceparent: str,
    cancelled: Callable[[UUID], Awaitable[bool]],
) -> None:
    with _job_span(command, deps, deliveries, EVALUATION_VERSION, traceparent):
        emitter = _Emitter(command, deps, deliveries, traceparent)
        async with emitter.keepalive():
            await _run_evaluation(deps, command, deliveries, traceparent, cancelled, emitter)


async def _run_evaluation(
    deps: AgentDeps,
    command: Command,
    deliveries: int,
    traceparent: str,
    cancelled: Callable[[UUID], Awaitable[bool]],
    emitter: _Emitter,
) -> None:
    """The evaluation job (AI 4.2, 2.5): rows are graded in parallel and sealed as they finish.

    Every 50 rows (or at the end, or on a cancel) a part is sealed in JOB_CONTROL and then
    published, so rows reach the reviewers while the job runs. A restart republishes the sealed
    parts and grades only the requirements no sealed part holds.
    """
    job = command.job_id
    if await cancelled(job):
        await emitter.status("cancelled")
        return
    if deps.search is None or deps.embed_query is None:
        raise BadInput("retrieval is not configured")

    data = await _load_input(deps, command)
    versions = [str(v) for v in data.get("proposal_document_version_ids", [])]
    if not versions:
        raise BadInput("the proposal has no files in scope")
    requirements = [
        Requirement(
            id=str(r["id"]),
            number=str(r.get("number", "")),
            text=str(r["text"]),
            acceptance_criteria=str(r.get("acceptance_criteria") or ""),
            importance=str(r.get("importance") or "mandatory"),
            category=r.get("category"),
            requirement_hash=str(r["requirement_hash"]),
        )
        for r in data.get("requirements", [])
    ]
    run = RunInfo(
        model_name=deps.evaluation_model or deps.model_name,
        model_version=deps.model_version,
        prompt_version=EVALUATION_VERSION,
        input_scope={
            "baseline_id": data.get("baseline_id"),
            "proposal_version_id": data.get("proposal_version_id"),
            "document_versions": sorted(versions),
            "requirements": len(requirements),
        },
    )
    await emitter.status(
        "running", step="load_input", progress_percent=2, steps=EVALUATION_STEPS, run=run
    )

    # What is sealed stays; it is published again (the ids make that harmless).
    sealed = await _sealed_parts(deps, job)
    done_ids = {i["requirement_id"] for part in sealed for i in part}
    all_items: list[dict] = [i for part in sealed for i in part]
    for n, part in enumerate(sealed, start=1):
        await emitter.batch(n, False, part, "grades")
    todo = [r for r in requirements if r.id not in done_ids]
    total = len(requirements)
    injected = 0
    part_no = len(sealed)

    graph = EvaluationGraph(
        llm=deps.llm,
        search=deps.search,
        embed_query=deps.embed_query,
        tender_id=command.tender_id,
        job_id=job,
        version_ids=versions,
        settings=deps.evaluation_settings,
        model=deps.evaluation_model,
    )
    gate = asyncio.Semaphore(max(1, deps.concurrency))
    buffer: list[dict] = []
    lock = asyncio.Lock()
    stop = asyncio.Event()
    state = {"cancel_at": 0.0, "stopped": False}

    async def seal() -> None:
        nonlocal part_no, buffer
        if not buffer:
            return
        part_no += 1
        part, buffer = buffer, []
        await deps.kv.put(f"part.{job}.{part_no}", json.dumps(part).encode())
        await emitter.batch(part_no, False, part, "grades")

    async def one(batch: list[Requirement]) -> None:
        nonlocal injected
        async with gate:
            if stop.is_set():
                return
            loop_time = asyncio.get_running_loop().time()
            if loop_time - state["cancel_at"] >= deps.cancel_check_seconds:
                state["cancel_at"] = loop_time
                if await cancelled(job):
                    stop.set()
                    return
            rows = await graph.grade_batch(batch)
        async with lock:
            for row in rows:
                buffer.append(row.item)
                all_items.append(row.item)
                if row.injection and row.item.get("outcome") == "graded":
                    injected += 1
                if len(buffer) >= deps.batch_max_items:
                    await seal()
            percent = 5 + int(90 * len(all_items) / max(1, total))
            await emitter.status("running", step="grade", progress_percent=min(95, percent))

    size = max(1, deps.evaluation_settings.batch_size)
    batches = [todo[i : i + size] for i in range(0, len(todo), size)]
    try:
        await asyncio.gather(*(one(b) for b in batches))
    except LlmUnavailable:
        stop.set()
        async with lock:
            await seal()
        await emitter.status(
            "failed",
            failure=Failure(
                code="llm_unavailable", message="The model did not answer", retryable=True
            ),
        )
        metrics.inc("evaluation_jobs_total", outcome="llm_unavailable")
        return
    async with lock:
        await seal()
    if stop.is_set() or await cancelled(job):
        await emitter.status("cancelled")
        metrics.inc("evaluation_jobs_total", outcome="cancelled")
        return

    warnings = _grade_warnings(all_items, injected)
    await emitter.status(
        "completed_with_warnings" if warnings else "completed",
        step="seal",
        progress_percent=100,
        result_parts=part_no,
        counts={"grades": len(all_items)},
        warnings=warnings or None,
    )
    metrics.inc("evaluation_jobs_total", outcome="completed")
