"""The worker reads its secrets from OpenBao over AppRole (PI 7.2, AI 2.1; G3)."""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from tender_worker.settings import load_settings

ROLE_ID = "role-x"
SECRET_ID = "secret-id-x"
TOKEN = "token-x"

KV = {
    "worker-ingest/rustfs": {"access_key": "bao-access", "secret_key": "bao-secret"},
    "worker-ingest/qdrant": {"api_key": "bao-qdrant-rw"},
    "worker-ingest/embedding": {"api_key": "bao-embedding"},
    "worker-agent/rustfs": {"access_key": "bao-access-a", "secret_key": "bao-secret-a"},
    "worker-agent/qdrant": {"api_key": "bao-qdrant-ro"},
    "worker-agent/embedding": {"api_key": "bao-embedding-a"},
    "worker-agent/llm": {"api_key": "bao-llm"},
    "worker-agent/phoenix": {"api_key": "bao-phoenix"},
}


class FakeBao(BaseHTTPRequestHandler):
    reads: list[str] = []
    denied: set[str] = set()

    def log_message(self, *args):  # silence
        pass

    def _send(self, status: int, body: dict) -> None:
        raw = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.path == "/v1/auth/approle/login" and body == {
            "role_id": ROLE_ID,
            "secret_id": SECRET_ID,
        }:
            self._send(200, {"auth": {"client_token": TOKEN}})
        else:
            self._send(400, {"errors": ["invalid role or secret ID"]})

    def do_GET(self):
        prefix = "/v1/kv/data/tender-eval/"
        name = self.path[len(prefix) :]
        FakeBao.reads.append(name)
        if self.headers.get("X-Vault-Token") != TOKEN or name in FakeBao.denied:
            self._send(403, {"errors": ["permission denied"]})
        elif name in KV:
            self._send(200, {"data": {"data": KV[name]}})
        else:
            self._send(404, {"errors": []})


@pytest.fixture
def bao(env, monkeypatch):
    FakeBao.reads = []
    FakeBao.denied = set()
    server = HTTPServer(("127.0.0.1", 0), FakeBao)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    monkeypatch.setenv("OPENBAO_ADDR", f"http://127.0.0.1:{server.server_port}")
    monkeypatch.setenv("OPENBAO_ROLE_ID", ROLE_ID)
    monkeypatch.setenv("OPENBAO_SECRET_ID", SECRET_ID)
    yield server
    server.shutdown()


def test_without_openbao_the_environment_is_used(env):
    s = load_settings()
    assert s.S3_ACCESS_KEY.get_secret_value() == "test-access"


def test_ingest_reads_its_own_paths_and_openbao_wins_over_the_environment(bao):
    s = load_settings()
    assert s.S3_ACCESS_KEY.get_secret_value() == "bao-access"
    assert s.S3_SECRET_KEY.get_secret_value() == "bao-secret"
    assert s.QDRANT_API_KEY.get_secret_value() == "bao-qdrant-rw"
    assert s.EMBEDDING_API_KEY.get_secret_value() == "bao-embedding"
    assert sorted(FakeBao.reads) == [
        "worker-ingest/embedding",
        "worker-ingest/qdrant",
        "worker-ingest/rustfs",
    ]


def test_agent_reads_llm_and_phoenix_too(bao, monkeypatch):
    monkeypatch.setenv("WORKER_ROLE", "agent")
    monkeypatch.setenv("LLM_BASE_URL", "http://llm.test/v1")
    monkeypatch.setenv("LLM_MODEL_EXTRACTION", "m")
    s = load_settings()
    assert s.QDRANT_API_KEY.get_secret_value() == "bao-qdrant-ro"
    assert s.LLM_API_KEY.get_secret_value() == "bao-llm"
    assert s.PHOENIX_API_KEY.get_secret_value() == "bao-phoenix"
    assert all(p.startswith("worker-agent/") for p in FakeBao.reads)


def test_wrong_secret_id_exits_without_printing_values(bao, monkeypatch, capsys):
    monkeypatch.setenv("OPENBAO_SECRET_ID", "wrong-secret-id")
    with pytest.raises(SystemExit) as exc:
        load_settings()
    assert exc.value.code == 2
    err = capsys.readouterr().err
    assert "OpenBao" in err
    assert "wrong-secret-id" not in err


def test_refused_path_is_named_but_no_value_printed(bao, capsys):
    FakeBao.denied = {"worker-ingest/qdrant"}
    with pytest.raises(SystemExit) as exc:
        load_settings()
    assert exc.value.code == 2
    err = capsys.readouterr().err
    assert "worker-ingest/qdrant" in err and "403" in err
    assert "bao-access" not in err


def test_unreachable_openbao_exits(env, monkeypatch, capsys):
    monkeypatch.setenv("OPENBAO_ADDR", "http://127.0.0.1:1")
    monkeypatch.setenv("OPENBAO_ROLE_ID", ROLE_ID)
    monkeypatch.setenv("OPENBAO_SECRET_ID", SECRET_ID)
    with pytest.raises(SystemExit) as exc:
        load_settings()
    assert exc.value.code == 2
    assert "OpenBao" in capsys.readouterr().err


def test_addr_without_role_credentials_exits(env, monkeypatch, capsys):
    monkeypatch.setenv("OPENBAO_ADDR", "http://127.0.0.1:1")
    monkeypatch.delenv("OPENBAO_ROLE_ID", raising=False)
    monkeypatch.delenv("OPENBAO_SECRET_ID", raising=False)
    with pytest.raises(SystemExit) as exc:
        load_settings()
    assert exc.value.code == 2
    assert "OPENBAO_ROLE_ID" in capsys.readouterr().err
