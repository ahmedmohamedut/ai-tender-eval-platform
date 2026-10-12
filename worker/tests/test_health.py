import asyncio

from tender_worker.health import HealthServer


async def get(port: int, path: str) -> tuple[str, str]:
    r, w = await asyncio.open_connection("127.0.0.1", port)
    w.write(f"GET {path} HTTP/1.1\r\nHost: x\r\n\r\n".encode())
    await w.drain()
    raw = (await r.read()).decode()
    w.close()
    head, _, body = raw.partition("\r\n\r\n")
    return head.splitlines()[0], body


async def test_healthz_and_readyz():
    state = {"nats": True}

    async def nats_ok():
        return state["nats"]

    server = HealthServer(0, {"nats": nats_ok})
    await server.start()
    try:
        assert (await get(server.port, "/healthz"))[0].endswith("200 OK")
        assert (await get(server.port, "/readyz"))[0].endswith("200 OK")
        state["nats"] = False
        status, body = await get(server.port, "/readyz")
        assert status.endswith("503 Service Unavailable") and '"nats": false' in body
        state["nats"] = True
        server.accepting = False  # SIGTERM received
        assert (await get(server.port, "/readyz"))[0].endswith("503 Service Unavailable")
        assert (await get(server.port, "/nope"))[0].endswith("404 Not Found")
    finally:
        await server.stop()
