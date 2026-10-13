"""Reading passages for a job (AI 3.4, 4.3, rule 7): every filter is set by code.

The model never chooses a tender, a document version or a section: the scope of the command and
the tender of the job decide what is read. The agent only reads Qdrant (rule 8).
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Protocol
from uuid import UUID

import aiohttp

from tender_worker.ingest.pipeline import with_retries


@dataclass(frozen=True)
class PassageRef:
    id: str
    version_id: str
    ordinal: int
    page_no: int
    text: str
    section_path_ids: tuple[str, ...]
    table_header: str | None = None


@dataclass(frozen=True)
class ScopeItem:
    version_id: str
    section_id: str | None = None
    page_from: int | None = None
    page_to: int | None = None


class PassageSource(Protocol):
    async def load(self, tender_id: UUID, scope: list[ScopeItem]) -> list[PassageRef]:
        """Passages of the scope, in document order, without duplicates."""


class QdrantPassageSource:
    def __init__(
        self, url: str, api_key: str, collection: str, *, timeout: float = 30.0, page: int = 256
    ) -> None:
        self._url = url.rstrip("/")
        self._headers = {"api-key": api_key}
        self._collection = collection
        self._timeout = aiohttp.ClientTimeout(total=timeout)
        self._page = page

    async def _scroll(self, flt: dict, offset) -> dict:
        async def call() -> dict:
            async with aiohttp.ClientSession(timeout=self._timeout, headers=self._headers) as http:
                async with http.post(
                    f"{self._url}/collections/{self._collection}/points/scroll",
                    json={
                        "filter": flt,
                        "limit": self._page,
                        "with_payload": True,
                        "with_vector": False,
                        "offset": offset,
                    },
                ) as resp:
                    resp.raise_for_status()
                    return json.loads(await resp.text())

        return await with_retries(call)

    async def load(self, tender_id: UUID, scope: list[ScopeItem]) -> list[PassageRef]:
        seen: dict[str, PassageRef] = {}
        for item in scope:
            must: list[dict] = [
                {"key": "tender_id", "match": {"value": str(tender_id)}},
                {"key": "document_version_id", "match": {"value": item.version_id}},
            ]
            if item.section_id:
                must.append({"key": "section_path_ids", "match": {"value": item.section_id}})
            rng: dict[str, int] = {}
            if item.page_from is not None:
                rng["gte"] = item.page_from
            if item.page_to is not None:
                rng["lte"] = item.page_to
            if rng:
                must.append({"key": "page_no", "range": rng})
            offset = None
            while True:
                body = await self._scroll({"must": must}, offset)
                result = body.get("result", {})
                for point in result.get("points", []):
                    p = point["payload"]
                    ref = PassageRef(
                        id=str(point["id"]),
                        version_id=p["document_version_id"],
                        ordinal=int(p.get("ordinal", 0)),
                        page_no=int(p.get("page_no", 0)),
                        text=p.get("text", ""),
                        section_path_ids=tuple(p.get("section_path_ids", [])),
                        table_header=p.get("table_header"),
                    )
                    seen.setdefault(ref.id, ref)
                offset = result.get("next_page_offset")
                if offset is None:
                    break
        order = {s.version_id: i for i, s in enumerate(scope)}
        return sorted(seen.values(), key=lambda p: (order.get(p.version_id, 0), p.ordinal))
