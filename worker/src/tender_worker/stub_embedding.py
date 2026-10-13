"""A local OpenAI-compatible embeddings endpoint for development and tests (AGENTS rule 15).

Vectors are deterministic hashes of the text: the same text always gives the same unit vector, and
texts that share words are somewhat closer. It is not a model and is never used with a real
deployment. Run with `python -m tender_worker.stub_embedding`.
"""

from __future__ import annotations

import hashlib
import math
import os
import re

from aiohttp import web

DIMENSIONS = int(os.environ.get("EMBEDDING_DIMENSIONS", "256"))
_WORD = re.compile(r"[a-z0-9]+")


def embed(text: str, dimensions: int = DIMENSIONS) -> list[float]:
    vec = [0.0] * dimensions
    for word in _WORD.findall(text.lower()):
        h = hashlib.blake2b(word.encode(), digest_size=8).digest()
        n = int.from_bytes(h, "big")
        vec[n % dimensions] += 1.0 if (n >> 40) & 1 else -1.0
    norm = math.sqrt(sum(v * v for v in vec)) or 1.0
    return [round(v / norm, 6) for v in vec]


async def embeddings(request: web.Request) -> web.Response:
    body = await request.json()
    inputs = body.get("input", [])
    if isinstance(inputs, str):
        inputs = [inputs]
    return web.json_response(
        {
            "object": "list",
            "model": body.get("model", "stub"),
            "data": [
                {"object": "embedding", "index": i, "embedding": embed(t)}
                for i, t in enumerate(inputs)
            ],
        }
    )


async def health(_: web.Request) -> web.Response:
    return web.json_response({"status": "ok"})


def app() -> web.Application:
    a = web.Application()
    a.router.add_post("/v1/embeddings", embeddings)
    a.router.add_get("/health", health)
    return a


if __name__ == "__main__":
    web.run_app(app(), port=int(os.environ.get("PORT", "8000")), print=None)
