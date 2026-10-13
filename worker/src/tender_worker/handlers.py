"""Message handlers.

Phase 0 scaffold: a handler validates its input, looks at the object it is told
about, publishes one terminal status and returns. It parses nothing and writes no
file. Real work arrives with the document pipeline (AI 3).
"""

from __future__ import annotations

import logging
import secrets
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Protocol
from uuid import UUID

from pydantic import ValidationError

from tender_worker.ingest.pipeline import DocumentRef, IngestPipeline
from tender_worker.keys import (
    ORIGINAL_KEY,
    MalformedEvent,
    UploadedDocument,
    parse_upload_event,
)
from tender_worker.messages import (
    SCHEMA_COMMAND,
    SCHEMA_STATUS,
    Command,
    Failure,
    Status,
    Target,
    WorkerInfo,
)

log = logging.getLogger(__name__)

STATUS_SUBJECT = "documents.analysis.status.{job_type}"
SCAFFOLD_STEP = "walking_skeleton"


class Publisher(Protocol):
    async def publish(
        self, subject: str, payload: bytes, headers: dict[str, str], msg_id: str
    ) -> None: ...


class ObjectStore(Protocol):
    async def head(self, bucket: str, key: str) -> tuple[int, str] | None:
        """Return (size, etag) or None when the object does not exist."""


class DeadLetter(Exception):
    """The message cannot be processed: terminate it and copy it to dlq.{subject}."""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


@dataclass
class Context:
    role: str
    worker: WorkerInfo
    publisher: Publisher
    store: ObjectStore
    bucket_documents: str
    # The real ingest pipeline. Without one (tests of the scaffold) the walking skeleton answers.
    pipeline: IngestPipeline | None = None


def new_traceparent() -> str:
    return f"00-{secrets.token_hex(16)}-{secrets.token_hex(8)}-01"


def _status_headers(msg_id: str, traceparent: str | None) -> dict[str, str]:
    return {
        "Nats-Msg-Id": msg_id,
        "traceparent": traceparent or new_traceparent(),
        "Tender-Schema": SCHEMA_STATUS,
    }


def check_schema(header: str | None, expected: str) -> None:
    """An unknown major version is terminated, never guessed at (PI 5.4)."""
    if header is None:
        return
    name, _, major = header.partition("/")
    exp_name, _, exp_major = expected.partition("/")
    if name != exp_name or major != exp_major:
        raise DeadLetter(f"unknown schema {header}")


async def handle_uploaded(
    ctx: Context, body: bytes, deliveries: int, traceparent: str | None
) -> None:
    """documents.uploaded: a RustFS event for a new original (PI 4.3)."""
    try:
        documents = parse_upload_event(body, ctx.bucket_documents)
    except MalformedEvent as exc:
        raise DeadLetter(str(exc)) from exc
    if not documents:
        log.info("event ignored: no matching original key")
        return
    for doc in documents:
        await _announce_upload(ctx, doc, deliveries, traceparent)


async def _announce_upload(
    ctx: Context, doc: UploadedDocument, deliveries: int, traceparent: str | None
) -> None:
    if ctx.pipeline is not None:
        ref = DocumentRef(
            tender_id=doc.tender_id,
            version_id=doc.document_version_id,
            job_id=None,
            file_format=doc.file_format,
            source_bucket=ctx.bucket_documents,
            source_key=doc.key,
        )
        await ctx.pipeline.run(ref, deliveries, traceparent or new_traceparent())
        return
    head = await ctx.store.head(ctx.bucket_documents, doc.key)
    # seq restarts at 1000 x deliveries so far + 1 after a redelivery (AI 2.3).
    seq = (deliveries - 1) * 1000 + 1
    failure = None
    state = "completed"
    if head is None:
        state = "failed"
        failure = Failure(code="source_unavailable", message="object not found", retryable=True)
    else:
        log.info(
            "original seen",
            extra={
                "document_version_id": str(doc.document_version_id),
                "size_bytes": head[0],
                "file_format": doc.file_format,
            },
        )
    status = Status(
        job_id=None,
        job_type="document_processing",
        tender_id=doc.tender_id,
        target=Target(type="document_version", id=doc.document_version_id),
        seq=seq,
        state=state,  # type: ignore[arg-type]
        step=SCAFFOLD_STEP,
        progress_percent=100 if state == "completed" else None,
        failure=failure,
        emitted_at=datetime.now(UTC),
        worker=ctx.worker,
    )
    msg_id = f"dv:{doc.document_version_id}:s:{seq}"
    await ctx.publisher.publish(
        STATUS_SUBJECT.format(job_type="document_processing"),
        status.model_dump_json(exclude_none=True).encode(),
        _status_headers(msg_id, traceparent),
        msg_id,
    )


async def handle_command(
    ctx: Context,
    body: bytes,
    schema_header: str | None,
    deliveries: int,
    traceparent: str | None,
) -> None:
    """documents.analysis.requested.document_processing: retry or recovery command."""
    check_schema(schema_header, SCHEMA_COMMAND)
    try:
        command = Command.model_validate_json(body)
    except ValidationError as exc:
        raise DeadLetter("invalid command body") from exc
    if command.job_type != "document_processing":
        raise DeadLetter(f"unknown job type {command.job_type}")

    if ctx.pipeline is not None and command.input_ref is not None:
        match = ORIGINAL_KEY.match(command.input_ref.key)
        if match is None or UUID(match.group(3)) != command.target.id:
            raise DeadLetter("input key does not match the target")
        ref = DocumentRef(
            tender_id=command.tender_id,
            version_id=command.target.id,
            job_id=command.job_id,
            file_format=match.group(4),
            source_bucket=command.input_ref.bucket,
            source_key=command.input_ref.key,
        )
        await ctx.pipeline.run(ref, deliveries, traceparent or new_traceparent())
        return

    seq = (deliveries - 1) * 1000 + 1
    status = Status(
        job_id=command.job_id,
        job_type=command.job_type,
        tender_id=command.tender_id,
        target=command.target,
        seq=seq,
        state="completed",
        step=SCAFFOLD_STEP,
        progress_percent=100,
        emitted_at=datetime.now(UTC),
        worker=ctx.worker,
    )
    msg_id = f"{command.job_id}:s:{seq}"
    log.info("command handled", extra={"job_id": str(command.job_id), "seq": seq})
    await ctx.publisher.publish(
        STATUS_SUBJECT.format(job_type=command.job_type),
        status.model_dump_json(exclude_none=True).encode(),
        _status_headers(msg_id, traceparent),
        msg_id,
    )
