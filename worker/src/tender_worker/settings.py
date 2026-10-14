"""Settings read from the environment and validated at start (PI 7.1, 7.4).

A missing or invalid required key stops the process before it opens a socket.
Only keys the code uses are declared; the rest of PI 7.4 arrives with the code
that needs it (docs/decisions.md).
"""

from __future__ import annotations

import socket
import sys
from typing import Literal

from pydantic import Field, SecretStr, ValidationError, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(extra="ignore", case_sensitive=True)

    WORKER_ROLE: Literal["ingest", "agent"]
    WORKER_ID: str = Field(default_factory=socket.gethostname)

    NATS_URL: str

    S3_ENDPOINT: str
    S3_REGION: str = "us-east-1"
    S3_PATH_STYLE: bool = True
    S3_ACCESS_KEY: SecretStr
    S3_SECRET_KEY: SecretStr
    S3_BUCKET_DOCUMENTS: str = "tender-documents"
    S3_BUCKET_DERIVED: str = "tender-derived"
    S3_BUCKET_JOBS: str = "tender-jobs"

    QDRANT_URL: str
    QDRANT_COLLECTION: str = "passages"
    QDRANT_API_KEY: SecretStr

    INGEST_CONCURRENCY: int = Field(default=2, ge=1)
    INGEST_PARSE_TIMEOUT_SECONDS: int = Field(default=600, ge=10)
    INGEST_CONVERT_TIMEOUT_SECONDS: int = Field(default=300, ge=10)
    INGEST_PAGE_MIN_CHARS: int = Field(default=20, ge=1)
    INGEST_MAX_TEXTLESS_SHARE: float = Field(default=0.5, ge=0, le=1)
    AGENT_CONCURRENCY_EVALUATION: int = Field(default=2, ge=1)
    AGENT_CONCURRENCY_EXTRACTION: int = Field(default=1, ge=1)
    AGENT_CONCURRENCY_PRECHECK: int = Field(default=1, ge=1)

    EMBEDDING_BASE_URL: str | None = None
    EMBEDDING_MODEL: str | None = None
    EMBEDDING_DIMENSIONS: int | None = Field(default=None, ge=8)
    EMBEDDING_API_KEY: SecretStr | None = None
    EMBEDDING_BATCH_SIZE: int = Field(default=64, ge=1)
    EMBEDDING_DOCUMENT_PREFIX: str = ""
    EMBEDDING_QUERY_PREFIX: str = ""
    QDRANT_UPSERT_BATCH_SIZE: int = Field(default=128, ge=1)

    LLM_BASE_URL: str | None = None
    LLM_API_KEY: SecretStr | None = None
    LLM_MODEL_EXTRACTION: str | None = None
    LLM_MODEL_VERSION: str = "unknown"
    LLM_TEMPERATURE: float = Field(default=0.0, ge=0, le=2)
    LLM_MAX_OUTPUT_TOKENS: int = Field(default=4096, ge=64)
    LLM_TIMEOUT_SECONDS: int = Field(default=120, ge=5)
    LLM_MAX_CONCURRENCY: int = Field(default=8, ge=1)
    LLM_STRUCTURED_OUTPUT: Literal["function_calling", "json_schema"] = "json_schema"
    LLM_OUTAGE_GIVE_UP_SECONDS: int = Field(default=600, ge=1)
    EXTRACTION_WINDOW_CHARS: int = Field(default=8000, ge=500)
    EXTRACTION_WINDOW_OVERLAP_PASSAGES: int = Field(default=2, ge=0)
    DEDUP_SIMILARITY_THRESHOLD: float = Field(default=0.85, ge=0, le=1)
    GROUNDING_MAX_REPAIR_ATTEMPTS: int = Field(default=1, ge=0, le=3)
    RESULT_BATCH_MAX_ITEMS: int = Field(default=50, ge=1, le=50)
    CANCEL_CHECK_SECONDS: int = Field(default=10, ge=1)

    HEALTH_PORT: int = 8080
    SHUTDOWN_GRACE_SECONDS: int = 45
    DEP_TIMEOUT_SECONDS: int = 30
    LOG_LEVEL: Literal["debug", "info", "warning", "error"] = "info"

    @model_validator(mode="after")
    def _no_empty_secrets(self) -> Settings:
        for name in ("S3_ACCESS_KEY", "S3_SECRET_KEY", "QDRANT_API_KEY"):
            if not getattr(self, name).get_secret_value():
                raise ValueError(f"{name} must not be empty")
        if self.WORKER_ROLE == "ingest":
            for name in ("EMBEDDING_BASE_URL", "EMBEDDING_MODEL", "EMBEDDING_DIMENSIONS"):
                if not getattr(self, name):
                    raise ValueError(f"{name} is required for the ingest role")
        # The agent role starts without a model endpoint only in the scaffold test setups; a
        # configured endpoint needs all its parts.
        if self.LLM_BASE_URL and not (self.LLM_MODEL_EXTRACTION and self.LLM_API_KEY):
            raise ValueError("LLM_MODEL_EXTRACTION and LLM_API_KEY are required with LLM_BASE_URL")
        return self

    @property
    def service_version(self) -> str:
        from tender_worker import __version__

        return __version__


def load_settings() -> Settings:
    """Load settings or exit non-zero, naming the keys but never their values."""
    try:
        return Settings()  # type: ignore[call-arg]
    except ValidationError as exc:
        problems = sorted({".".join(str(p) for p in e["loc"]) or e["msg"] for e in exc.errors()})
        print(f"configuration error: missing or invalid: {', '.join(problems)}", file=sys.stderr)
        raise SystemExit(2) from None
