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

    async def embed(self, texts: list[str]) -> list[list[float]]:
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
                    if len(vectors) != len(batch) or any(
                        len(v) != self.dimensions for v in vectors
                    ):
                        raise ValueError("embedding response has the wrong shape")
                    return vectors

                try:
                    out.extend(await with_retries(call))
                except IngestFailure as exc:
                    raise IngestFailure(
                        "llm_unavailable", "The embedding service did not answer", True
                    ) from exc.__cause__
        return out


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
