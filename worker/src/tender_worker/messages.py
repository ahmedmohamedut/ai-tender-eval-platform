"""Pydantic models of the messages in PI 5.4 to 5.6 and the push event of the API.

JSON Schemas are exported from these models into contracts/messages/ and the
backend is tested against those files (implementation guide 4.2). Unknown fields
are ignored, because messages change only by adding fields within a major
version. Item bodies are opaque in Phase 0.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator

JobType = Literal[
    "document_processing", "requirement_extraction", "screening_precheck", "evaluation"
]
JobState = Literal["running", "completed", "completed_with_warnings", "failed", "cancelled"]

SCHEMA_COMMAND = "tender.analysis.requested/1"
SCHEMA_STATUS = "tender.analysis.status/1"
SCHEMA_RESULT = "tender.analysis.result/1"
SCHEMA_PUSH = "tender.push/1"


class _Message(BaseModel):
    model_config = ConfigDict(extra="ignore")


class Target(_Message):
    type: Literal["document_version", "proposal_version", "tender"]
    id: UUID


class InputRef(_Message):
    bucket: str
    key: str
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")


class Command(_Message):
    """Body of documents.analysis.requested.{job_type} (PI 5.4)."""

    job_id: UUID
    job_type: JobType
    tender_id: UUID
    target: Target
    requested_by: UUID | None = None
    attempt: int = Field(default=1, ge=1)
    created_at: datetime
    input: dict[str, Any] | None = None
    input_ref: InputRef | None = None

    @model_validator(mode="after")
    def _one_input(self) -> Command:
        if (self.input is None) == (self.input_ref is None):
            raise ValueError("exactly one of input and input_ref is required")
        return self


class Warning_(_Message):
    code: str
    message: str
    count: int = Field(default=1, ge=0)


class Failure(_Message):
    code: Literal[
        "password_protected",
        "no_selectable_text",
        "unsupported_format",
        "source_unavailable",
        "index_unavailable",
        "llm_unavailable",
        "model_output_unusable",
        "no_content_in_scope",
        "timeout",
        "other",
    ]
    message: str
    retryable: bool
    # A closed reason and whole numbers only: the backend writes the sentence from fixed
    # templates and never relays free text from a worker (rule 10).
    cause: (
        Literal[
            "embedding_dimension_mismatch",
            "embedding_http_error",
            "embedding_unreachable",
            "embedding_timeout",
            "embedding_unreadable",
        ]
        | None
    ) = None
    params: dict[str, int] | None = None


class RunInfo(_Message):
    model_name: str | None = None
    model_version: str | None = None
    prompt_version: str | None = None
    input_scope: dict[str, Any] | None = None


class WorkerInfo(_Message):
    id: str
    version: str


class Status(_Message):
    """Body of documents.analysis.status.{job_type} (PI 5.5)."""

    job_id: UUID | None = None
    job_type: JobType
    tender_id: UUID
    target: Target
    seq: int = Field(ge=1)
    state: JobState
    step: str | None = None
    progress_percent: int | None = Field(default=None, ge=0, le=100)
    estimated_remaining_seconds: int | None = Field(default=None, ge=0)
    steps: list[str] | None = None
    run: RunInfo | None = None
    result_parts: int | None = Field(default=None, ge=0)
    counts: dict[str, int] | None = None
    warnings: list[Warning_] | None = None
    failure: Failure | None = None
    emitted_at: datetime
    worker: WorkerInfo

    @model_validator(mode="after")
    def _failure_matches_state(self) -> Status:
        if (self.state == "failed") != (self.failure is not None):
            raise ValueError("failure is required when state is failed, and only then")
        return self


class ParsedFiles(_Message):
    """The hand-over of one parsed document version (PI 5.6, kind ``parsed``).

    Keys are in ``tender-derived`` and are the fixed keys of AI 3.3.
    """

    pages: str
    sections: str
    passages: str
    rendered_pdf: str
    manifest: str
    page_count: int = Field(ge=0)
    counts: dict[str, int]
    source_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")


class ResultBatch(_Message):
    """Body of documents.analysis.result.{job_type} (PI 5.6): at most 50 items.

    ``job_id`` is null only for a document run started by an upload event (as in Status).
    """

    job_id: UUID | None = None
    job_type: JobType
    tender_id: UUID
    target: Target
    kind: Literal["parsed", "requirements", "grades", "prechecks"]
    part_no: int = Field(ge=1)
    final: bool
    items: list[dict[str, Any]] = Field(default_factory=list, max_length=50)
    parsed: ParsedFiles | None = None
    emitted_at: datetime
    worker: WorkerInfo

    @model_validator(mode="after")
    def _parsed_only_for_parsed(self) -> ResultBatch:
        if (self.kind == "parsed") != (self.parsed is not None):
            raise ValueError("parsed is required for kind parsed, and only then")
        return self


class PushEvent(_Message):
    """Data of one server-sent event; mirrors schemas.PushEvent in api/openapi.yaml."""

    type: Literal[
        "job.updated",
        "notification.created",
        "document.updated",
        "evaluation.progress",
        "requirements.changed",
        "baseline.changed",
        "report.changed",
        "tender.changed",
        "resync",
    ]
    tender_id: UUID | None = None
    object_type: str | None = None
    object_id: UUID | None = None
    data: dict[str, Any] = Field(default_factory=dict)


MESSAGE_MODELS: dict[str, type[BaseModel]] = {
    "command": Command,
    "status": Status,
    "result_batch": ResultBatch,
    "push_event": PushEvent,
}
