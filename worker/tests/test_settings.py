import pytest

from tender_worker.settings import Settings, load_settings


def test_loads_with_required_keys(env):
    s = load_settings()
    assert s.WORKER_ROLE == "ingest"
    assert s.S3_REGION == "us-east-1"
    assert s.S3_PATH_STYLE is True
    assert s.HEALTH_PORT == 8080


@pytest.mark.parametrize(
    "missing",
    [
        "WORKER_ROLE",
        "NATS_URL",
        "S3_ENDPOINT",
        "S3_ACCESS_KEY",
        "S3_SECRET_KEY",
        "QDRANT_URL",
        "QDRANT_API_KEY",
    ],
)
def test_missing_required_key_exits_with_name_only(env, monkeypatch, capsys, missing):
    monkeypatch.delenv(missing)
    with pytest.raises(SystemExit) as exc:
        load_settings()
    assert exc.value.code == 2
    err = capsys.readouterr().err
    assert missing in err
    assert "test-secret" not in err and "test-qdrant" not in err


def test_unknown_role_is_refused(env, monkeypatch):
    monkeypatch.setenv("WORKER_ROLE", "both")
    with pytest.raises(SystemExit):
        load_settings()


def test_secrets_are_not_printed(env):
    s = Settings()  # type: ignore[call-arg]
    assert "test-secret" not in repr(s)
    assert "test-qdrant" not in str(s)


def test_empty_secret_is_refused(env, monkeypatch):
    monkeypatch.setenv("S3_SECRET_KEY", "")
    with pytest.raises(SystemExit):
        load_settings()


def test_tracing_keys_default_to_off_and_content_capture_is_off(env):
    s = load_settings()
    assert s.PHOENIX_COLLECTOR_ENDPOINT is None
    assert s.PHOENIX_PROJECT_NAME == "tender-eval"
    assert s.PHOENIX_API_KEY is None
    assert s.TRACE_CAPTURE_CONTENT is False
