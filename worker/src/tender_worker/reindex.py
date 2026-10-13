"""`python -m tender_worker reindex --collection NAME` (AI 3.4).

Reads `passages.jsonl` and `sections.jsonl` of every manifest in `tender-derived` and fills the
named collection. No message is involved and nothing is re-chunked: the files are the source.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import re
import sys
from uuid import UUID

from tender_worker.ingest.index import EmbeddingClient, QdrantIndexer
from tender_worker.ingest.pipeline import DocumentRef
from tender_worker.runner import S3Store
from tender_worker.settings import load_settings

_MANIFEST = re.compile(r"^parsed/([0-9a-f-]{36})/([0-9a-f-]{36})/manifest\.json$")


async def reindex(collection: str) -> int:
    s = load_settings()
    store = S3Store(s)
    indexer = QdrantIndexer(
        s.QDRANT_URL,
        s.QDRANT_API_KEY.get_secret_value(),
        collection,
        EmbeddingClient(
            base_url=s.EMBEDDING_BASE_URL or "",
            model=s.EMBEDDING_MODEL or "",
            api_key=s.EMBEDDING_API_KEY.get_secret_value() if s.EMBEDDING_API_KEY else "",
            dimensions=s.EMBEDDING_DIMENSIONS or 0,
            batch_size=s.EMBEDDING_BATCH_SIZE,
        ),
        upsert_batch=s.QDRANT_UPSERT_BATCH_SIZE,
    )
    done = 0
    async for key in store.list_keys(s.S3_BUCKET_DERIVED, "parsed/"):
        m = _MANIFEST.match(key)
        if not m:
            continue
        tender, version = m.group(1), m.group(2)
        base = f"parsed/{tender}/{version}"

        async def lines(name: str, base: str = base) -> list[dict]:
            raw = await store.get(s.S3_BUCKET_DERIVED, f"{base}/{name}.jsonl")
            return [json.loads(x) for x in raw.decode().splitlines() if x.strip()]

        ref = DocumentRef(
            tender_id=UUID(tender),
            version_id=UUID(version),
            job_id=None,
            file_format="pdf",
            source_bucket=s.S3_BUCKET_DOCUMENTS,
            source_key="",
        )
        await indexer.index(ref, await lines("passages"), await lines("sections"))
        done += 1
        print(f"indexed {version}", file=sys.stderr)
    print(f"reindexed {done} document versions into {collection}", file=sys.stderr)
    return done


def main(argv: list[str]) -> None:
    parser = argparse.ArgumentParser(prog="tender_worker reindex")
    parser.add_argument("--collection", required=True)
    args = parser.parse_args(argv)
    asyncio.run(reindex(args.collection))
