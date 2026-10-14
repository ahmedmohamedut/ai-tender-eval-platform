"""Searching a proposal's passages (AI 4.2, 4.5, rule 7): every filter is set by code.

Hybrid retrieval over the dense `dense` vector and the sparse `bm25` vector with rank fusion done
by Qdrant, plus the passages around a hit. The filter always names the tender and the proposal's
document versions; nothing the model says reaches it. The agent only reads (rule 8).
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Protocol
from uuid import UUID

import aiohttp

from tender_worker.agent.retrieval import PassageRef
from tender_worker.ingest.index import sparse_vector
from tender_worker.ingest.pipeline import with_retries


@dataclass(frozen=True)
class Hit:
    passage: PassageRef
    score: float


class Searcher(Protocol):
    async def hybrid(
        self, tender_id: UUID, version_ids: list[str], dense: list[float], text: str, limit: int
    ) -> list[Hit]: ...

    async def around(
        self, tender_id: UUID, version_ids: list[str], hit: PassageRef, before: int, after: int
    ) -> list[PassageRef]: ...


def _ref(point: dict) -> PassageRef:
    p = point["payload"]
    return PassageRef(
        id=str(point["id"]),
        version_id=p["document_version_id"],
        ordinal=int(p.get("ordinal", 0)),
        page_no=int(p.get("page_no", 0)),
        text=p.get("text", ""),
        section_path_ids=tuple(p.get("section_path_ids", [])),
        table_header=p.get("table_header"),
        section_path=tuple(str(t) for t in p.get("section_path", [])),
    )


def _filter(tender_id: UUID, version_ids: list[str], extra: list[dict] | None = None) -> dict:
    must: list[dict] = [
        {"key": "tender_id", "match": {"value": str(tender_id)}},
        {"key": "document_version_id", "match": {"any": list(version_ids)}},
    ]
    must.extend(extra or [])
    return {"must": must}


class QdrantSearch:
    def __init__(
        self,
        url: str,
        api_key: str,
        collection: str,
        *,
        timeout: float = 30.0,
        min_score: float | None = None,
    ) -> None:
        self._url = url.rstrip("/")
        self._headers = {"api-key": api_key}
        self._collection = collection
        self._timeout = aiohttp.ClientTimeout(total=timeout)
        self._min_score = min_score

    async def _post(self, path: str, body: dict) -> dict:
        async def call() -> dict:
            async with aiohttp.ClientSession(timeout=self._timeout, headers=self._headers) as http:
                async with http.post(
                    f"{self._url}/collections/{self._collection}{path}", json=body
                ) as resp:
                    resp.raise_for_status()
                    return json.loads(await resp.text())

        return await with_retries(call)

    async def hybrid(
        self, tender_id: UUID, version_ids: list[str], dense: list[float], text: str, limit: int
    ) -> list[Hit]:
        flt = _filter(tender_id, version_ids)
        sparse = sparse_vector(text)
        prefetch: list[dict] = [
            {"query": dense, "using": "dense", "limit": limit * 2, "filter": flt},
        ]
        if sparse["indices"]:
            prefetch.append({"query": sparse, "using": "bm25", "limit": limit * 2, "filter": flt})
        body = {
            "prefetch": prefetch,
            "query": {"fusion": "rrf"},
            "limit": limit,
            "filter": flt,
            "with_payload": True,
        }
        out = await self._post("/points/query", body)
        hits = [
            Hit(_ref(p), float(p.get("score", 0.0)))
            for p in out.get("result", {}).get("points", [])
        ]
        if self._min_score is not None:
            hits = [h for h in hits if h.score >= self._min_score]
        return hits

    async def around(
        self, tender_id: UUID, version_ids: list[str], hit: PassageRef, before: int, after: int
    ) -> list[PassageRef]:
        flt = _filter(
            tender_id,
            [hit.version_id],
            [
                {
                    "key": "ordinal",
                    "range": {"gte": hit.ordinal - before, "lte": hit.ordinal + after},
                }
            ],
        )
        # the neighbours stay inside the proposal's document versions
        if hit.version_id not in version_ids:
            return []
        out = await self._post(
            "/points/scroll",
            {
                "filter": flt,
                "limit": before + after + 1,
                "with_payload": True,
                "with_vector": False,
            },
        )
        refs = [_ref(p) for p in out.get("result", {}).get("points", [])]
        return sorted(refs, key=lambda r: r.ordinal)
