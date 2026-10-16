"""Reads the worker's own secrets from OpenBao over AppRole (PI 7.2, AI 2.1).

Blocking on purpose: it runs once at start, before the event loop exists, as the backend's client
does. Nothing it reads is ever logged and an error names the path and the status, never a value.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from typing import Any

# secret name -> {key in OpenBao: Settings field}, per role (PI 7.2).
_COMMON = {
    "rustfs": {"access_key": "S3_ACCESS_KEY", "secret_key": "S3_SECRET_KEY"},
    "qdrant": {"api_key": "QDRANT_API_KEY"},
    "embedding": {"api_key": "EMBEDDING_API_KEY"},
}
_AGENT_ONLY = {
    "llm": {"api_key": "LLM_API_KEY"},
    "phoenix": {"api_key": "PHOENIX_API_KEY"},
}
_MAPS = {"ingest": _COMMON, "agent": {**_COMMON, **_AGENT_ONLY}}

TIMEOUT_SECONDS = 10


class SecretsError(Exception):
    """Carries a message safe to print: no secret value, no secret id."""


def _call(addr: str, what: str, token: str | None, payload: dict[str, str] | None) -> Any:
    request = urllib.request.Request(
        f"{addr.rstrip('/')}{what}",
        data=json.dumps(payload).encode() if payload is not None else None,
        headers={"Content-Type": "application/json", **({"X-Vault-Token": token} if token else {})},
        method="POST" if payload is not None else "GET",
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:  # noqa: S310
            return json.load(response)
    except urllib.error.HTTPError as exc:
        raise SecretsError(f"OpenBao refused {what} with status {exc.code}") from None
    except (urllib.error.URLError, OSError, ValueError):
        raise SecretsError(f"OpenBao could not be reached for {what}") from None


def fetch_worker_secrets(addr: str, role_id: str, secret_id: str, role: str) -> dict[str, str]:
    """Return Settings field -> value for the role's own paths `kv/tender-eval/worker-{role}/*`."""
    login = _call(
        addr, "/v1/auth/approle/login", None, {"role_id": role_id, "secret_id": secret_id}
    )
    token = (login.get("auth") or {}).get("client_token")
    if not token:
        raise SecretsError("OpenBao AppRole login returned no token")
    out: dict[str, str] = {}
    for name, fields in _MAPS[role].items():
        path = f"worker-{role}/{name}"
        body = _call(addr, f"/v1/kv/data/tender-eval/{path}", token, None)
        data = ((body.get("data") or {}).get("data")) or {}
        for key, field in fields.items():
            value = data.get(key)
            if not value:
                raise SecretsError(f"OpenBao path {path} has no value for {key}")
            out[field] = str(value)
    return out
