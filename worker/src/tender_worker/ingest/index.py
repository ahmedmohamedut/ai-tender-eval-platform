"""Embedding and the Qdrant collection (AI 3.4, 3.5).

Dense vectors come from an OpenAI-compatible embeddings endpoint. The sparse `bm25` vector is a
local, deterministic term-frequency encoder (hashed term ids); Qdrant's IDF modifier supplies the
inverse document frequency. The collection holds every tender; the point id is the passage id.
"""

from __future__ import annotations

import hashlib
import json
import logging
import re
from collections import Counter
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any
from urllib.parse import urlsplit

import aiohttp

from tender_worker.ingest.model import IngestFailure
from tender_worker.ingest.pipeline import DocumentRef, with_retries

log = logging.getLogger(__name__)

SPARSE_BUCKETS = 1 << 20
_TOKEN = re.compile(r"[a-z0-9][a-z0-9\-./]*[a-z0-9]|[a-z0-9]")
_STOP = frozenset(
    "a an and are as at be by for from has have in is it its of on or that the this to was were "
    "will with shall must should may can not no".split()
)


def sparse_vector(text: str) -> dict[str, list]:
    """BM25-style term frequencies over hashed term ids (stable across runs and processes)."""
    counts: Counter[int] = Counter()
    for token in _TOKEN.findall(text.lower()):
        if token in _STOP:
            continue
        digest = hashlib.blake2b(token.encode(), digest_size=4).digest()
        counts[int.from_bytes(digest, "big") % SPARSE_BUCKETS] += 1
    items = sorted(counts.items())
    # term-frequency saturation with k1 = 1.2 (the length normalisation is left to Qdrant's IDF)
    return {
        "indices": [i for i, _ in items],
        "values": [round(c * 2.2 / (c + 1.2), 4) for _, c in items],
    }


def embedding_text(passage: dict, section_titles: list[str]) -> str:
    """Section path, then the table header of a row, then the passage (AI 3.4)."""
    parts = [" > ".join(section_titles)] if section_titles else []
    if passage.get("table_header"):
        parts.append(passage["table_header"])
    parts.append(passage["text"])
    return "\n".join(parts)


@dataclass
class EmbeddingClient:
    base_url: str
    model: str
    api_key: str
    dimensions: int
    batch_size: int = 64
    timeout: float = 30.0
    # Retrieval models such as Nemotron want a role marker on each input ("passage: ", "query: ").
    # Empty by default, so the hash stub and any model without markers are unchanged.
    document_prefix: str = ""
    query_prefix: str = ""

    @classmethod
    def from_settings(cls, s, *, timeout: float | None = None) -> EmbeddingClient:
        return cls(
            base_url=s.EMBEDDING_BASE_URL or "",
            model=s.EMBEDDING_MODEL or "",
            api_key=s.EMBEDDING_API_KEY.get_secret_value() if s.EMBEDDING_API_KEY else "",
            dimensions=s.EMBEDDING_DIMENSIONS or 0,
            batch_size=s.EMBEDDING_BATCH_SIZE,
            timeout=timeout if timeout is not None else s.DEP_TIMEOUT_SECONDS,
            document_prefix=s.EMBEDDING_DOCUMENT_PREFIX,
            query_prefix=s.EMBEDDING_QUERY_PREFIX,
        )

    async def embed(self, texts: list[str], kind: str = "document") -> list[list[float]]:
        """`kind` is "document" (indexed text and symmetric comparisons) or "query" (a search)."""
        prefix = self.query_prefix if kind == "query" else self.document_prefix
        if prefix:
            texts = [prefix + t for t in texts]
        out: list[list[float]] = []
        async with aiohttp.ClientSession(
            timeout=aiohttp.ClientTimeout(total=self.timeout),
            headers={"Authorization": f"Bearer {self.api_key}"},
        ) as http:
            for start in range(0, len(texts), self.batch_size):
                batch = texts[start : start + self.batch_size]

                async def call(batch=batch) -> list[list[float]]:
                    async with http.post(
                        f"{self.base_url.rstrip('/')}/embeddings",
                        json={"model": self.model, "input": batch},
                    ) as resp:
                        resp.raise_for_status()
                        body = await resp.json()
                    rows = sorted(body["data"], key=lambda d: d["index"])
                    vectors = [r["embedding"] for r in rows]
                    if len(vectors) != len(batch):
                        raise ValueError("embedding response has the wrong shape")
                    return vectors

                try:
                    vectors = await with_retries(call)
                except IngestFailure as exc:
                    message, cause_code, params = self._why(exc.__cause__)
                    log.warning(
                        "embedding call failed",
                        extra={
                            "endpoint": urlsplit(self.base_url).netloc,
                            "cause_code": cause_code,
                        },
                    )
                    raise IngestFailure(
                        "llm_unavailable", message, True, cause_code, params
                    ) from exc.__cause__
                # Not an outage: the model and the configuration disagree; a retry cannot fix it.
                wrong = next((len(v) for v in vectors if len(v) != self.dimensions), None)
                if wrong is not None:
                    raise IngestFailure(
                        "llm_unavailable",
                        f"The embedding model returned vectors of {wrong} values but "
                        f"{self.dimensions} are configured. Set EMBEDDING_DIMENSIONS to {wrong} "
                        "and use a new QDRANT_COLLECTION",
                        False,
                        "embedding_dimension_mismatch",
                        {"returned": wrong, "configured": self.dimensions},
                    )
                out.extend(vectors)
        return out

    def _why(self, cause: BaseException | None) -> tuple[str, str, dict[str, int]]:
        """(message, cause code, numbers) for a failed call; nothing from the documents."""
        if isinstance(cause, aiohttp.ClientResponseError):
            return (
                f"The embedding service answered with HTTP {cause.status}",
                "embedding_http_error",
                {"status": cause.status},
            )
        if isinstance(cause, TimeoutError):
            return (
                f"The embedding service did not answer within {self.timeout:g} seconds",
                "embedding_timeout",
                {"seconds": round(self.timeout)},
            )
        if isinstance(cause, aiohttp.ClientConnectionError):
            return "The embedding service could not be reached", "embedding_unreachable", {}
        return (
            "The embedding service sent an answer that could not be read",
            "embedding_unreadable",
            {},
        )


class QdrantIndexer:
    """Creates the collection, upserts passages and checks the count (AI 3.5)."""

    def __init__(
        self,
        url: str,
        api_key: str,
        collection: str,
        embeddings: EmbeddingClient,
        *,
        upsert_batch: int = 128,
        timeout: float = 30.0,
        sleep: Callable[[float], Awaitable[None]] | None = None,
    ) -> None:
        self._url = url.rstrip("/")
        self._headers = {"api-key": api_key}
        self.collection = collection
        self._embeddings = embeddings
        self._batch = upsert_batch
        self._timeout = aiohttp.ClientTimeout(total=timeout)
        self._ready = False
        self._sleep = sleep

    async def _request(self, method: str, path: str, body: Any = None) -> tuple[int, dict]:
        async with aiohttp.ClientSession(timeout=self._timeout, headers=self._headers) as http:
            async with http.request(method, f"{self._url}{path}", json=body) as resp:
                text = await resp.text()
                return resp.status, (json.loads(text) if text else {})

    async def ensure_collection(self) -> None:
        if self._ready:
            return
        status, _ = await self._request("GET", f"/collections/{self.collection}")
        if status == 404:
            status, body = await self._request(
                "PUT",
                f"/collections/{self.collection}",
                {
                    "vectors": {
                        "dense": {"size": self._embeddings.dimensions, "distance": "Cosine"}
                    },
                    "sparse_vectors": {"bm25": {"modifier": "idf"}},
                },
            )
            if status not in (200, 409):
                raise ConnectionError(f"collection not created: {status}")
        elif status != 200:
            raise ConnectionError(f"collection check failed: {status}")
        indexes = {
            "tender_id": {"type": "keyword", "is_tenant": True},
            "document_version_id": {"type": "keyword"},
            "section_path_ids": {"type": "keyword"},
            "page_no": {"type": "integer"},
            "ordinal": {"type": "integer"},
        }
        for field, schema in indexes.items():
            await self._request(
                "PUT",
                f"/collections/{self.collection}/index?wait=true",
                {"field_name": field, "field_schema": schema},
            )
        self._ready = True

    async def index(self, ref: DocumentRef, passages: list[dict], sections: list[dict]) -> None:
        by_id = {s["section_id"]: s for s in sections}

        def path(section_id: str | None) -> list[dict]:
            chain: list[dict] = []
            while section_id and section_id in by_id and len(chain) < 12:
                chain.append(by_id[section_id])
                section_id = by_id[section_id].get("parent_section_id")
            return list(reversed(chain))

        async def prepare() -> None:
            await with_retries(self.ensure_collection, sleep=self._sleep or _sleep)

        try:
            await prepare()
        except IngestFailure as exc:
            raise IngestFailure(
                "index_unavailable", "The search index did not answer", True
            ) from exc.__cause__

        payloads: list[dict] = []
        texts: list[str] = []
        for ordinal, p in enumerate(passages):
            chain = path(p.get("section_id"))
            titles = [s["title"] for s in chain]
            texts.append(embedding_text(p, titles))
            payloads.append(
                {
                    "tender_id": str(ref.tender_id),
                    "document_version_id": str(ref.version_id),
                    "page_no": p["page_no"],
                    "passage_no": p["passage_no"],
                    "ordinal": ordinal,
                    "section_id": p.get("section_id"),
                    "section_path_ids": [s["section_id"] for s in chain],
                    "section_path": titles,
                    "text": p["text"],
                    "table_header": p.get("table_header"),
                    "content_hash": p["content_hash"],
                }
            )
        dense = await self._embeddings.embed(texts)

        async def upsert(points: list[dict]) -> None:
            status, body = await self._request(
                "PUT", f"/collections/{self.collection}/points?wait=true", {"points": points}
            )
            if status != 200:
                raise ConnectionError(f"upsert failed: {status}")

        try:
            for start in range(0, len(passages), self._batch):
                points = [
                    {
                        "id": passages[i]["passage_id"],
                        "vector": {"dense": dense[i], "bm25": sparse_vector(texts[i])},
                        "payload": payloads[i],
                    }
                    for i in range(start, min(len(passages), start + self._batch))
                ]
                await with_retries(
                    lambda points=points: upsert(points), sleep=self._sleep or _sleep
                )
            # A version is indexed when its points equal its passages; one more upsert, then fail.
            if await self.count(ref) != len(passages):
                for start in range(0, len(passages), self._batch):
                    points = [
                        {
                            "id": passages[i]["passage_id"],
                            "vector": {"dense": dense[i], "bm25": sparse_vector(texts[i])},
                            "payload": payloads[i],
                        }
                        for i in range(start, min(len(passages), start + self._batch))
                    ]
                    await upsert(points)
                if await self.count(ref) != len(passages):
                    raise IngestFailure("index_unavailable", "Not every passage was indexed", True)
        except IngestFailure as exc:
            if exc.code == "index_unavailable":
                raise
            raise IngestFailure(
                "index_unavailable", "The search index did not answer", True
            ) from exc.__cause__

    async def count(self, ref: DocumentRef) -> int:
        async def call() -> int:
            status, body = await self._request(
                "POST",
                f"/collections/{self.collection}/points/count",
                {
                    "exact": True,
                    "filter": {
                        "must": [
                            {"key": "document_version_id", "match": {"value": str(ref.version_id)}}
                        ]
                    },
                },
            )
            if status != 200:
                raise ConnectionError(f"count failed: {status}")
            return int(body["result"]["count"])

        return await with_retries(call, sleep=self._sleep or _sleep)


async def _sleep(seconds: float) -> None:
    import asyncio

    await asyncio.sleep(seconds)


async def check_embedding_setup(
    embeddings: EmbeddingClient, qdrant_url: str, qdrant_api_key: str, collection: str
) -> list[str]:
    """What is certainly wrong with the embedding setup; empty when fine or not yet knowable.

    A model that is down or a collection not yet created is not a finding: both are retried at
    use. Only a disagreement between the model, `EMBEDDING_DIMENSIONS` and the collection is, since
    no retry mends it and every document would fail the same way.
    """
    problems: list[str] = []
    if embeddings.base_url:
        try:
            await embeddings.embed(["dimension check"])
        except IngestFailure as exc:
            if exc.cause_code == "embedding_dimension_mismatch":
                problems.append(exc.message)
            else:
                log.warning(
                    "embedding model not checked at start",
                    extra={"reason": exc.message, "endpoint": urlsplit(embeddings.base_url).netloc},
                )
    try:
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as http:
            async with http.get(
                f"{qdrant_url.rstrip('/')}/collections/{collection}",
                headers={"api-key": qdrant_api_key},
            ) as resp:
                if resp.status != 200:
                    return problems
                body = await resp.json()
    except (aiohttp.ClientError, TimeoutError):
        return problems
    vectors = (body.get("result", {}).get("config", {}).get("params", {}) or {}).get("vectors", {})
    size = (vectors.get("dense") or {}).get("size")
    if isinstance(size, int) and embeddings.dimensions and size != embeddings.dimensions:
        problems.append(
            f"The collection {collection} holds vectors of {size} values but "
            f"{embeddings.dimensions} are configured. Use a new QDRANT_COLLECTION "
            "or set EMBEDDING_DIMENSIONS to match"
        )
    return problems
