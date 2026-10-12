"""JSON logs. A record carries ids, hashes and lengths, never document text (rule 10)."""

from __future__ import annotations

import json
import logging
import sys
from datetime import UTC, datetime

_RESERVED = set(logging.LogRecord("", 0, "", 0, "", (), None).__dict__) | {"message", "asctime"}


class JsonFormatter(logging.Formatter):
    def __init__(self, role: str, worker_id: str) -> None:
        super().__init__()
        self._static = {"role": role, "worker_id": worker_id}

    def format(self, record: logging.LogRecord) -> str:
        body = {
            "ts": datetime.fromtimestamp(record.created, UTC).isoformat(timespec="milliseconds"),
            "level": record.levelname.lower(),
            "logger": record.name,
            "msg": record.getMessage(),
            **self._static,
        }
        for key, value in record.__dict__.items():
            if key not in _RESERVED and not key.startswith("_"):
                body[key] = value
        if record.exc_info:
            body["exc_type"] = record.exc_info[0].__name__ if record.exc_info[0] else None
        return json.dumps(body, default=str)


def configure_logging(level: str, role: str, worker_id: str) -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter(role, worker_id))
    root = logging.getLogger()
    root.handlers[:] = [handler]
    root.setLevel(level.upper())
