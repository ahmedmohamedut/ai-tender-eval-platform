from __future__ import annotations

import pytest

REQUIRED = {
    "WORKER_ROLE": "ingest",
    "NATS_URL": "nats://localhost:4222",
    "S3_ENDPOINT": "http://localhost:9000",
    "S3_ACCESS_KEY": "test-access",
    "S3_SECRET_KEY": "test-secret",
    "QDRANT_URL": "http://localhost:6333",
    "QDRANT_API_KEY": "test-qdrant",
    "EMBEDDING_BASE_URL": "http://embedding.test/v1",
    "EMBEDDING_MODEL": "stub",
    "EMBEDDING_DIMENSIONS": "256",
}


@pytest.fixture
def env(monkeypatch: pytest.MonkeyPatch) -> dict[str, str]:
    for key in list(REQUIRED) + ["WORKER_ID", "HEALTH_PORT", "INGEST_CONCURRENCY"]:
        monkeypatch.delenv(key, raising=False)
    for key, value in REQUIRED.items():
        monkeypatch.setenv(key, value)
    return dict(REQUIRED)
