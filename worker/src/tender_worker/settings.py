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
    AGENT_CONCURRENCY_EVALUATION: int = Field(default=2, ge=1)
    AGENT_CONCURRENCY_EXTRACTION: int = Field(default=1, ge=1)
    AGENT_CONCURRENCY_PRECHECK: int = Field(default=1, ge=1)

    HEALTH_PORT: int = 8080
    SHUTDOWN_GRACE_SECONDS: int = 45
    DEP_TIMEOUT_SECONDS: int = 30
    LOG_LEVEL: Literal["debug", "info", "warning", "error"] = "info"

    @model_validator(mode="after")
    def _no_empty_secrets(self) -> Settings:
        for name in ("S3_ACCESS_KEY", "S3_SECRET_KEY", "QDRANT_API_KEY"):
            if not getattr(self, name).get_secret_value():
                raise ValueError(f"{name} must not be empty")
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
