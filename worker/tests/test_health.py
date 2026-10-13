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


async def test_metrics_are_served_as_prometheus_text_without_names():
    from tender_worker import metrics

    metrics.reset()
    metrics.inc("ingest_documents_total", outcome="completed")
    metrics.inc("ingest_documents_total", outcome="password_protected")
    metrics.observe("ingest_document", 1.5)
    server = HealthServer(0, {})
    await server.start()
    try:
        reader, writer = await asyncio.open_connection("127.0.0.1", server.port)
        writer.write(b"GET /metrics HTTP/1.1\r\n\r\n")
        await writer.drain()
        raw = (await reader.read()).decode()
        writer.close()
    finally:
        await server.stop()
    assert "200 OK" in raw and "text/plain" in raw
    assert 'ingest_documents_total{outcome="completed"} 1' in raw
    assert 'ingest_documents_total{outcome="password_protected"} 1' in raw
    assert "ingest_document_seconds_count 1" in raw
