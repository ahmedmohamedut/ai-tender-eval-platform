"""/healthz (event loop alive) and /readyz (NATS, RustFS, Qdrant reachable) (AI 2.1)."""

from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import Awaitable, Callable

log = logging.getLogger(__name__)

Check = Callable[[], Awaitable[bool]]


class HealthServer:
    def __init__(self, port: int, checks: dict[str, Check]) -> None:
        self._port = port
        self._checks = checks
        self._server: asyncio.Server | None = None
        self.accepting = True  # false once SIGTERM is received

    async def start(self) -> None:
        self._server = await asyncio.start_server(self._handle, "0.0.0.0", self._port)

    @property
    def port(self) -> int:
        assert self._server is not None
        return self._server.sockets[0].getsockname()[1]

    async def stop(self) -> None:
        if self._server:
            self._server.close()
            await self._server.wait_closed()

    async def _handle(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        try:
            line = await asyncio.wait_for(reader.readline(), timeout=5)
            path = line.decode(errors="replace").split(" ")[1] if line else ""
            status, body = await self._route(path)
            payload = json.dumps(body).encode()
            writer.write(
                f"HTTP/1.1 {status}\r\nContent-Type: application/json\r\n"
                f"Content-Length: {len(payload)}\r\nConnection: close\r\n\r\n".encode()
                + payload
            )
            await writer.drain()
        except Exception:  # noqa: BLE001 - a bad probe must never stop the worker
            log.debug("health request failed", exc_info=True)
        finally:
            writer.close()

    async def _route(self, path: str) -> tuple[str, dict]:
        if path == "/healthz":
            return "200 OK", {"status": "ok"}
        if path == "/readyz":
            results = {}
            for name, check in self._checks.items():
                try:
                    results[name] = await asyncio.wait_for(check(), timeout=5)
                except Exception:  # noqa: BLE001
                    results[name] = False
            ready = self.accepting and all(results.values())
            return ("200 OK" if ready else "503 Service Unavailable"), {
                "status": "ready" if ready else "not_ready",
                "checks": results,
            }
        return "404 Not Found", {"status": "not_found"}
