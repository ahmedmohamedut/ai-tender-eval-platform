"""One document through the ingest pipeline (AI 2.3, 3.3, 3.5).

fetch -> (convert) -> parse -> write -> index -> result -> completed.

A manifest in `tender-derived` marks a finished parse; a run that finds one never parses again,
even if a newer release would chunk differently (rule 13). Statuses are numbered 1000 x the
deliveries so far + 1 upward, so a number is never reused after a redelivery.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import random
import time
from collections.abc import Awaitable, Callable
from concurrent.futures import Executor
from concurrent.futures.process import BrokenProcessPool
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from typing import Protocol
from uuid import UUID

from tender_worker import metrics
from tender_worker.ingest.convert import docx_to_pdf, has_tracked_changes
from tender_worker.ingest.docx_markdown import (
    align_blocks,
    blocks_to_jsonl,
    parse_docx_blocks,
    render_markdown,
)
from tender_worker.ingest.model import IngestFailure, ParseResult, Warning_
from tender_worker.ingest.pdf import parse_pdf
from tender_worker.messages import (
    SCHEMA_RESULT,
    SCHEMA_STATUS,
    Failure,
    ParsedFiles,
    ResultBatch,
    RunInfo,
    Status,
    Target,
    WorkerInfo,
)
from tender_worker.messages import Warning_ as StatusWarning

log = logging.getLogger(__name__)

STATUS_SUBJECT = "documents.analysis.status.document_processing"
RESULT_SUBJECT = "documents.analysis.result.document_processing"
BACKOFF_BASE = 1.0
BACKOFF_CAP = 30.0
TRIES = 5


class Blobs(Protocol):
    async def get(self, bucket: str, key: str) -> bytes: ...
    async def put(self, bucket: str, key: str, data: bytes, content_type: str) -> None: ...
    async def exists(self, bucket: str, key: str) -> bool: ...


class Publisher(Protocol):
    async def publish(
        self, subject: str, payload: bytes, headers: dict[str, str], msg_id: str
    ) -> None: ...


class Indexer(Protocol):
    async def index(self, ref: DocumentRef, passages: list[dict], sections: list[dict]) -> None:
        """Put the passages of one version into the search index; idempotent by passage id."""


class NoIndex:
    """Used until the index exists (P2-4) and in tests that do not need one."""

    async def index(self, ref: DocumentRef, passages: list[dict], sections: list[dict]) -> None:
        return None


CancelCheck = Callable[[UUID], Awaitable[bool]]


@dataclass(frozen=True)
class DocumentRef:
    tender_id: UUID
    version_id: UUID
    job_id: UUID | None
    file_format: str  # pdf | docx
    source_bucket: str
    source_key: str

    @property
    def parsed_prefix(self) -> str:
        return f"parsed/{self.tender_id}/{self.version_id}"

    @property
    def rendered_key(self) -> str:
        return f"rendered/{self.tender_id}/{self.version_id}/document.pdf"

    @property
    def blocks_key(self) -> str:
        return f"{self.parsed_prefix}/blocks.jsonl"

    @property
    def markdown_key(self) -> str:
        return f"{self.parsed_prefix}/document.md"

    @property
    def manifest_key(self) -> str:
        return f"{self.parsed_prefix}/manifest.json"

    @property
    def status_key(self) -> str:
        return str(self.job_id) if self.job_id else f"dv:{self.version_id}"


async def with_retries[T](
    fn: Callable[[], Awaitable[T]],
    *,
    attempts: int = TRIES,
    base: float = BACKOFF_BASE,
    cap: float = BACKOFF_CAP,
    sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    retry_on: tuple[type[BaseException], ...] = (Exception,),
) -> T:
    """Retry in place with backoff (AI 2.3): 1 s growing to 30 s, five tries."""
    last: BaseException | None = None
    for attempt in range(attempts):
        try:
            return await fn()
        except IngestFailure:
            raise
        except retry_on as exc:
            last = exc
            if attempt + 1 == attempts:
                break
            await sleep(min(cap, base * (2**attempt)) * (0.8 + 0.4 * random.random()))
    raise IngestFailure(
        "source_unavailable", "A stored object could not be read or written", retryable=True
    ) from last


class Emitter:
    """Builds and publishes the numbered status messages of one delivery."""

    def __init__(
        self,
        ref: DocumentRef,
        publisher: Publisher,
        worker: WorkerInfo,
        deliveries: int,
        traceparent: str,
    ) -> None:
        self._ref = ref
        self._publisher = publisher
        self._worker = worker
        self._traceparent = traceparent
        self._seq = (deliveries - 1) * 1000

    async def _send(self, status: Status) -> None:
        msg_id = f"{self._ref.status_key}:s:{status.seq}"
        await self._publisher.publish(
            STATUS_SUBJECT,
            status.model_dump_json(exclude_none=True).encode(),
            {
                "Nats-Msg-Id": msg_id,
                "traceparent": self._traceparent,
                "Tender-Schema": SCHEMA_STATUS,
            },
            msg_id,
        )

    def _status(self, state: str, **kw) -> Status:
        self._seq += 1
        return Status(
            job_id=self._ref.job_id,
            job_type="document_processing",
            tender_id=self._ref.tender_id,
            target=Target(type="document_version", id=self._ref.version_id),
            seq=self._seq,
            state=state,  # type: ignore[arg-type]
            emitted_at=datetime.now(UTC),
            worker=self._worker,
            **kw,
        )

    async def running(
        self, step: str, progress: int, steps: list[str] | None = None, run: RunInfo | None = None
    ) -> None:
        await self._send(
            self._status("running", step=step, progress_percent=progress, steps=steps, run=run)
        )

    async def completed(self, counts: dict[str, int], warnings: list[Warning_]) -> None:
        await self._send(
            self._status(
                "completed_with_warnings" if warnings else "completed",
                step="index",
                progress_percent=100,
                result_parts=1,
                counts=counts,
                warnings=[
                    StatusWarning(code=w.code, message=w.message, count=w.count) for w in warnings
                ]
                or None,
            )
        )

    async def failed(self, failure: IngestFailure) -> None:
        await self._send(
            self._status(
                "failed",
                failure=Failure(
                    code=failure.code,  # type: ignore[arg-type]
                    message=failure.message,
                    retryable=failure.retryable,
                    cause=failure.cause_code,  # type: ignore[arg-type]
                    params=failure.params or None,
                ),
            )
        )

    async def cancelled(self) -> None:
        await self._send(self._status("cancelled"))

    async def result(self, files: ParsedFiles) -> None:
        batch = ResultBatch(
            job_id=self._ref.job_id,
            job_type="document_processing",
            tender_id=self._ref.tender_id,
            target=Target(type="document_version", id=self._ref.version_id),
            kind="parsed",
            part_no=1,
            final=True,
            items=[],
            parsed=files,
            emitted_at=datetime.now(UTC),
            worker=self._worker,
        )
        msg_id = f"{self._ref.status_key}:r:1"
        await self._publisher.publish(
            RESULT_SUBJECT,
            batch.model_dump_json(exclude_none=True).encode(),
            {
                "Nats-Msg-Id": msg_id,
                "traceparent": self._traceparent,
                "Tender-Schema": SCHEMA_RESULT,
            },
            msg_id,
        )


def _jsonl(rows: list[dict]) -> bytes:
    return (
        "\n".join(json.dumps(r, ensure_ascii=False, separators=(",", ":")) for r in rows) + "\n"
    ).encode()


def serialise(result: ParseResult) -> tuple[bytes, bytes, bytes]:
    pages = [
        {
            "page_no": p.page_no,
            "width": round(p.width, 2),
            "height": round(p.height, 2),
            "text": p.text,
            "textless": p.textless,
        }
        for p in result.pages
    ]
    sections = [asdict(s) for s in result.sections]
    passages = [asdict(p) for p in result.passages]
    return _jsonl(pages), _jsonl(sections), _jsonl(passages)


@dataclass
class IngestPipeline:
    blobs: Blobs
    publisher: Publisher
    worker: WorkerInfo
    bucket_derived: str
    executor: Executor
    indexer: Indexer
    cancelled: CancelCheck
    parse_timeout: float = 600.0
    convert_timeout: float = 300.0
    page_min_chars: int = 20
    max_textless_share: float = 0.5
    sleep: Callable[[float], Awaitable[None]] = asyncio.sleep
    new_executor: Callable[[], Executor] | None = None

    async def run(self, ref: DocumentRef, deliveries: int, traceparent: str) -> None:
        started = time.monotonic()
        metrics.gauge_add("ingest_documents_in_flight", 1)
        try:
            await self._run(ref, deliveries, traceparent)
        finally:
            metrics.gauge_add("ingest_documents_in_flight", -1)
            metrics.observe("ingest_document", time.monotonic() - started)

    async def _run(self, ref: DocumentRef, deliveries: int, traceparent: str) -> None:
        emitter = Emitter(ref, self.publisher, self.worker, deliveries, traceparent)
        steps = (
            ["fetch"]
            + (["convert"] if ref.file_format == "docx" else [])
            + [
                "parse",
                "write",
                "index",
            ]
        )
        try:
            if ref.job_id is not None and await self.cancelled(ref.job_id):
                await emitter.cancelled()
                return
            await emitter.running("fetch", 5, steps=steps)
            manifest = await self._read_manifest(ref)
            if manifest is None:
                warnings = await self._parse_and_write(ref, emitter)
                manifest = await self._read_manifest(ref)
                if manifest is None:
                    raise IngestFailure("source_unavailable", "The parsed files are not readable")
            else:
                log.info("manifest found, parse skipped", extra={"version": str(ref.version_id)})
                warnings = [Warning_(**w) for w in manifest.get("warnings", [])]
            passages = await self._read_jsonl(ref, "passages")
            sections = await self._read_jsonl(ref, "sections")
            if ref.job_id is not None and await self.cancelled(ref.job_id):
                await emitter.cancelled()
                return
            await emitter.running("index", 85)
            await self.indexer.index(ref, passages, sections)
            files = ParsedFiles(
                pages=f"{ref.parsed_prefix}/pages.jsonl",
                sections=f"{ref.parsed_prefix}/sections.jsonl",
                passages=f"{ref.parsed_prefix}/passages.jsonl",
                rendered_pdf=ref.rendered_key,
                manifest=ref.manifest_key,
                page_count=manifest["page_count"],
                counts={
                    "pages": manifest["page_count"],
                    "sections": manifest["section_count"],
                    "passages": manifest["passage_count"],
                },
                source_sha256=manifest["source_sha256"],
            )
            await emitter.result(files)
            await emitter.completed(files.counts, warnings)
            metrics.inc("ingest_documents_total", outcome="completed")
            metrics.inc("ingest_passages_total", float(files.counts["passages"]))
        except IngestFailure as failure:
            log.warning(
                "document failed",
                extra={
                    "version": str(ref.version_id),
                    "code": failure.code,
                    "reason": failure.message,
                    "retryable": failure.retryable,
                    "cause": type(failure.__cause__).__name__ if failure.__cause__ else None,
                    "cause_code": failure.cause_code,
                },
            )
            await emitter.failed(failure)
            metrics.inc("ingest_documents_total", outcome=failure.code)

    async def _write_markdown(
        self, ref: DocumentRef, original: bytes, result: ParseResult
    ) -> dict[str, int]:
        """The markdown blocks of a DOCX, written before the manifest (AI 3.3).

        A document whose blocks cannot be read still processes: the agent then reads its
        passages instead, so a failure here is a warning and not a failed document.
        """
        passages = [asdict(p) for p in result.passages]
        loop = asyncio.get_running_loop()
        try:
            blocks, markdown, count, unaligned = await asyncio.wait_for(
                loop.run_in_executor(self.executor, _markdown_job, original, passages),
                self.parse_timeout,
            )
        except Exception:  # noqa: BLE001 - the passages stay the reading of last resort
            log.warning("markdown skipped", extra={"version": str(ref.version_id)})
            return {}
        for key, body, kind in (
            (ref.blocks_key, blocks, "application/x-ndjson"),
            (ref.markdown_key, markdown, "text/markdown"),
        ):
            await with_retries(
                lambda k=key, b=body, t=kind: self.blobs.put(self.bucket_derived, k, b, t),
                sleep=self.sleep,
            )
        return {"block_count": count, "blocks_unaligned": unaligned}

    async def _read_manifest(self, ref: DocumentRef) -> dict | None:
        if not await with_retries(
            lambda: self.blobs.exists(self.bucket_derived, ref.manifest_key), sleep=self.sleep
        ):
            return None
        raw = await with_retries(
            lambda: self.blobs.get(self.bucket_derived, ref.manifest_key), sleep=self.sleep
        )
        try:
            return json.loads(raw)
        except ValueError:
            return None  # a partly written manifest is no manifest

    async def _read_jsonl(self, ref: DocumentRef, name: str) -> list[dict]:
        raw = await with_retries(
            lambda: self.blobs.get(self.bucket_derived, f"{ref.parsed_prefix}/{name}.jsonl"),
            sleep=self.sleep,
        )
        return [json.loads(line) for line in raw.decode().splitlines() if line.strip()]

    async def _parse_and_write(self, ref: DocumentRef, emitter: Emitter) -> list[Warning_]:
        original = await with_retries(
            lambda: self.blobs.get(ref.source_bucket, ref.source_key), sleep=self.sleep
        )
        sha = hashlib.sha256(original).hexdigest()
        extra: list[Warning_] = []
        if ref.file_format == "docx":
            if has_tracked_changes(original):
                extra.append(
                    Warning_("tracked_changes", "The document holds unresolved tracked changes", 1)
                )
            await emitter.running("convert", 15)
            pdf = await docx_to_pdf(original, timeout=self.convert_timeout)
        else:
            pdf = original
        await emitter.running("parse", 35)
        loop = asyncio.get_running_loop()
        try:
            result: ParseResult = await asyncio.wait_for(
                loop.run_in_executor(
                    self.executor,
                    _parse_job,
                    pdf,
                    str(ref.version_id),
                    self.page_min_chars,
                    self.max_textless_share,
                ),
                self.parse_timeout,
            )
        except TimeoutError:
            raise IngestFailure("timeout", "The document took too long to read", True) from None
        except BrokenProcessPool:
            # A parse process died (killed, out of memory): the pool is unusable. Make a new one
            # and let the message be redelivered, instead of failing every later document.
            self.executor = self.new_executor() if self.new_executor else self.executor
            raise
        await emitter.running("write", 70)
        pages, sections, passages = serialise(result)
        derived = self.bucket_derived
        await with_retries(
            lambda: self.blobs.put(derived, ref.rendered_key, pdf, "application/pdf"),
            sleep=self.sleep,
        )
        for name, body in (("pages", pages), ("sections", sections), ("passages", passages)):
            await with_retries(
                lambda n=name, b=body: self.blobs.put(
                    derived, f"{ref.parsed_prefix}/{n}.jsonl", b, "application/x-ndjson"
                ),
                sleep=self.sleep,
            )
        block_stats: dict[str, int] = {}
        if ref.file_format == "docx":
            block_stats = await self._write_markdown(ref, original, result)
            if block_stats.get("blocks_unaligned"):
                extra.append(
                    Warning_(
                        "blocks_unaligned",
                        "Some text of the document could not be matched to the converted pages",
                        block_stats["blocks_unaligned"],
                    )
                )
        warnings = extra + result.warnings
        manifest = {
            "source_sha256": sha,
            "worker_version": self.worker.version,
            "parser_versions": result.parser_versions,
            "page_count": len(result.pages),
            "section_count": len(result.sections),
            "passage_count": len(result.passages),
            **block_stats,
            "warnings": [asdict(w) for w in warnings],
            "steps_done": ["fetch", "parse", "write"],
        }
        # Last: its presence says the parse is complete.
        await with_retries(
            lambda: self.blobs.put(
                derived,
                ref.manifest_key,
                json.dumps(manifest, separators=(",", ":")).encode(),
                "application/json",
            ),
            sleep=self.sleep,
        )
        return warnings


def _markdown_job(docx: bytes, passages: list[dict]) -> tuple[bytes, bytes, int, int]:
    """Runs in the process pool: blocks, markdown, block count and unaligned count."""
    blocks = parse_docx_blocks(docx)
    unaligned = align_blocks(blocks, passages)
    return blocks_to_jsonl(blocks), render_markdown(blocks).encode(), len(blocks), unaligned


def _parse_job(pdf: bytes, version_id: str, page_min_chars: int, max_share: float) -> ParseResult:
    """Runs in the process pool: top-level so it can be pickled."""
    result = parse_pdf(pdf, version_id, page_min_chars=page_min_chars, max_textless_share=max_share)
    for page in result.pages:
        page.lines = []  # the lines are only needed while building passages
    return result
