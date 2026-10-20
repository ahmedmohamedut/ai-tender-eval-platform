"""AI 3.4, 3.5: embedding client and Qdrant indexer against in-process fake services."""

from __future__ import annotations

import uuid

import pytest
from aiohttp import web
from aiohttp.test_utils import TestServer
from fixtures import synthetic

from tender_worker import stub_embedding
from tender_worker.ingest.index import (
    EmbeddingClient,
    QdrantIndexer,
    check_embedding_setup,
    embedding_text,
    sparse_vector,
)
from tender_worker.ingest.model import IngestFailure
from tender_worker.ingest.pdf import parse_pdf
from tender_worker.ingest.pipeline import (
    DocumentRef,
    serialise,  # noqa: F401
)

VERSION = uuid.UUID("0193a7a0-0000-7000-8000-000000000003")
TENDER = uuid.UUID("0193a7a0-0000-7000-8000-000000000002")


class FakeQdrant:
    def __init__(self) -> None:
        self.collections: dict[str, dict] = {}
        self.points: dict[str, dict] = {}
        self.indexes: list[str] = []
        self.fail_upserts = 0
        self.drop_on_upsert = False

    def app(self) -> web.Application:
        a = web.Application()
        a.router.add_get("/collections/{c}", self.get_c)
        a.router.add_put("/collections/{c}", self.put_c)
        a.router.add_put("/collections/{c}/index", self.put_index)
        a.router.add_put("/collections/{c}/points", self.put_points)
        a.router.add_post("/collections/{c}/points/count", self.count)
        return a

    async def get_c(self, r):
        c = r.match_info["c"]
        if c not in self.collections:
            return web.json_response({}, status=404)
        vectors = self.collections[c].get("vectors", {})
        return web.json_response({"result": {"config": {"params": {"vectors": vectors}}}})

    async def put_c(self, r):
        self.collections[r.match_info["c"]] = await r.json()
        return web.json_response({"result": True})

    async def put_index(self, r):
        self.indexes.append((await r.json())["field_name"])
        return web.json_response({"result": {}})

    async def put_points(self, r):
        if self.fail_upserts:
            self.fail_upserts -= 1
            return web.json_response({}, status=503)
        for p in (await r.json())["points"]:
            if self.drop_on_upsert:
                continue
            self.points[p["id"]] = p
        return web.json_response({"result": {}})

    async def count(self, r):
        want = (await r.json())["filter"]["must"][0]["match"]["value"]
        n = sum(1 for p in self.points.values() if p["payload"]["document_version_id"] == want)
        return web.json_response({"result": {"count": n}})


async def no_sleep(_: float) -> None:
    return None


@pytest.fixture
async def services():
    q = FakeQdrant()
    qs = TestServer(q.app())
    es = TestServer(stub_embedding.app())
    await qs.start_server()
    await es.start_server()
    yield q, str(qs.make_url("")).rstrip("/"), str(es.make_url("/v1")).rstrip("/")
    await qs.close()
    await es.close()


def indexer(qurl: str, eurl: str, collection: str = "passages", dims: int = 256) -> QdrantIndexer:
    return QdrantIndexer(
        qurl,
        "key",
        collection,
        EmbeddingClient(base_url=eurl, model="stub", api_key="x", dimensions=dims, batch_size=16),
        upsert_batch=40,
        sleep=no_sleep,
    )


def parsed_files():
    r = parse_pdf(synthetic.make_rfp(6), str(VERSION))
    from dataclasses import asdict

    return [asdict(p) for p in r.passages], [asdict(s) for s in r.sections]


def ref() -> DocumentRef:
    return DocumentRef(TENDER, VERSION, None, "pdf", "tender-documents", "k")


def test_sparse_vectors_are_stable_and_ignore_stop_words():
    a = sparse_vector("The supplier shall provide TLS 1.3 encryption")
    assert a == sparse_vector("the supplier shall provide tls 1.3 encryption")
    assert len(a["indices"]) == len(a["values"]) == len(set(a["indices"]))
    assert len(sparse_vector("the and of")["indices"]) == 0


def test_embedding_text_is_path_then_header_then_passage():
    text = embedding_text(
        {"text": "Row text", "table_header": "Id | Requirement"}, ["1. Scope", "1.2 Hosting"]
    )
    assert text == "1. Scope > 1.2 Hosting\nId | Requirement\nRow text"


async def test_the_collection_and_payload_indexes_are_created_once(services):
    q, qurl, eurl = services
    idx = indexer(qurl, eurl)
    await idx.ensure_collection()
    await idx.ensure_collection()
    cfg = q.collections["passages"]
    assert cfg["vectors"]["dense"] == {"size": 256, "distance": "Cosine"}
    assert cfg["sparse_vectors"]["bm25"]["modifier"] == "idf"
    assert q.indexes == [
        "tender_id",
        "document_version_id",
        "section_path_ids",
        "page_no",
        "ordinal",
    ]


async def test_passages_become_points_with_the_passage_id_and_a_section_path(services):
    q, qurl, eurl = services
    passages, sections = parsed_files()
    await indexer(qurl, eurl).index(ref(), passages, sections)
    assert set(q.points) == {p["passage_id"] for p in passages}
    point = q.points[passages[0]["passage_id"]]
    assert len(point["vector"]["dense"]) == 256
    assert point["vector"]["bm25"]["indices"]
    pay = point["payload"]
    assert pay["tender_id"] == str(TENDER) and pay["document_version_id"] == str(VERSION)
    assert pay["text"] == passages[0]["text"]  # verbatim
    assert pay["ordinal"] == 0 and pay["page_no"] == passages[0]["page_no"]
    assert pay["section_path"] and len(pay["section_path"]) == len(pay["section_path_ids"])
    # indexing again changes nothing: same ids, same count
    before = len(q.points)
    await indexer(qurl, eurl).index(ref(), passages, sections)
    assert len(q.points) == before


async def test_a_brief_qdrant_outage_is_survived(services):
    q, qurl, eurl = services
    passages, sections = parsed_files()
    q.fail_upserts = 2
    await indexer(qurl, eurl).index(ref(), passages, sections)
    assert len(q.points) == len(passages)


async def test_missing_points_end_in_index_unavailable(services):
    q, qurl, eurl = services
    passages, sections = parsed_files()
    q.drop_on_upsert = True
    with pytest.raises(IngestFailure) as err:
        await indexer(qurl, eurl).index(ref(), passages, sections)
    assert err.value.code == "index_unavailable" and err.value.retryable


async def test_an_unreachable_qdrant_is_index_unavailable():
    es = TestServer(stub_embedding.app())
    await es.start_server()
    try:
        passages, sections = parsed_files()
        with pytest.raises(IngestFailure) as err:
            await indexer("http://127.0.0.1:1", str(es.make_url("/v1"))).index(
                ref(), passages, sections
            )
        assert err.value.code == "index_unavailable"
    finally:
        await es.close()


async def test_an_unreachable_embedding_service_is_llm_unavailable(services):
    _, qurl, _ = services
    passages, sections = parsed_files()
    idx = indexer(qurl, "http://127.0.0.1:1/v1")
    with pytest.raises(IngestFailure) as err:
        await idx.index(ref(), passages, sections)
    assert err.value.code == "llm_unavailable"


async def test_role_prefixes_are_added_to_documents_and_queries():
    seen: list[list[str]] = []

    async def handler(request):
        body = await request.json()
        seen.append(body["input"])
        return web.json_response(
            {"data": [{"index": i, "embedding": [0.0] * 8} for i, _ in enumerate(body["input"])]}
        )

    app = web.Application()
    app.router.add_post("/v1/embeddings", handler)
    server = TestServer(app)
    await server.start_server()
    try:
        client = EmbeddingClient(
            base_url=str(server.make_url("/v1")).rstrip("/"),
            model="m",
            api_key="k",
            dimensions=8,
            document_prefix="passage: ",
            query_prefix="query: ",
        )
        await client.embed(["a", "b"])
        await client.embed(["q"], kind="query")
        plain = EmbeddingClient(
            base_url=str(server.make_url("/v1")).rstrip("/"), model="m", api_key="k", dimensions=8
        )
        await plain.embed(["c"])
    finally:
        await server.close()
    assert seen == [["passage: a", "passage: b"], ["query: q"], ["c"]]


async def _embed_against(handler, **over):
    app = web.Application()
    app.router.add_post("/v1/embeddings", handler)
    server = TestServer(app)
    await server.start_server()
    try:
        client = EmbeddingClient(
            base_url=str(server.make_url("/v1")).rstrip("/"),
            model="m",
            api_key="k",
            dimensions=over.pop("dimensions", 8),
            **over,
        )
        await client.embed(["a"])
    finally:
        await server.close()


async def test_a_wrong_dimension_fails_at_once_and_names_both_sizes():
    calls = 0

    async def handler(request):
        nonlocal calls
        calls += 1
        body = await request.json()
        return web.json_response(
            {"data": [{"index": i, "embedding": [0.0] * 5} for i, _ in enumerate(body["input"])]}
        )

    with pytest.raises(IngestFailure) as caught:
        await _embed_against(handler)
    assert calls == 1  # a configuration error is not an outage; no retries
    assert caught.value.code == "llm_unavailable" and not caught.value.retryable
    assert "5 values" in caught.value.message and "8" in caught.value.message
    assert "EMBEDDING_DIMENSIONS" in caught.value.message
    assert caught.value.cause_code == "embedding_dimension_mismatch"
    assert caught.value.params == {"returned": 5, "configured": 8}


async def test_an_http_error_names_the_status():
    async def handler(request):
        return web.json_response({"error": "boom"}, status=503)

    with pytest.raises(IngestFailure) as caught:
        await _embed_against(handler)
    assert caught.value.code == "llm_unavailable" and caught.value.retryable
    assert "HTTP 503" in caught.value.message
    assert caught.value.cause_code == "embedding_http_error"
    assert caught.value.params == {"status": 503}


async def test_an_unreachable_service_is_named_as_such():
    with pytest.raises(IngestFailure) as caught:
        client = EmbeddingClient(
            base_url="http://127.0.0.1:9/v1", model="m", api_key="k", dimensions=8, timeout=2
        )
        await client.embed(["a"])
    assert "could not be reached" in caught.value.message
    assert caught.value.cause_code == "embedding_unreachable" and caught.value.params == {}


async def test_the_start_up_check_is_quiet_when_model_setting_and_collection_agree(services):
    q, qurl, eurl = services
    ix = indexer(qurl, eurl, collection="agree", dims=256)
    await ix.ensure_collection()
    assert await check_embedding_setup(ix._embeddings, qurl, "key", "agree") == []


async def test_the_start_up_check_reports_a_model_that_disagrees_with_the_setting(services):
    _, qurl, eurl = services
    ix = indexer(qurl, eurl, dims=512)  # the stub model answers with 256 values
    problems = await check_embedding_setup(ix._embeddings, qurl, "key", "not-yet-created")
    assert len(problems) == 1 and "256 values but 512 are configured" in problems[0]


async def test_the_start_up_check_reports_a_collection_of_another_size(services):
    q, qurl, eurl = services
    await indexer(qurl, eurl, collection="old", dims=256).ensure_collection()
    ix = indexer(qurl, eurl, collection="old", dims=256)
    ix._embeddings.dimensions = 256
    q.collections["old"]["vectors"]["dense"]["size"] = 2560
    problems = await check_embedding_setup(ix._embeddings, qurl, "key", "old")
    assert (
        len(problems) == 1 and "holds vectors of 2560 values but 256 are configured" in problems[0]
    )


async def test_the_start_up_check_does_not_stop_on_a_model_that_is_merely_down(services):
    _, qurl, _ = services
    down = EmbeddingClient(
        base_url="http://127.0.0.1:9/v1", model="m", api_key="k", dimensions=8, timeout=2
    )
    assert await check_embedding_setup(down, qurl, "key", "passages") == []
