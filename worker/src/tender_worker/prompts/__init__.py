"""Versioned prompt files. The build hashes every file here (AI 4.7); a change needs a new version
and an evaluation run (rule 14)."""

from __future__ import annotations

import hashlib
from functools import cache
from pathlib import Path

EXTRACTION_VERSION = "extraction-1.0.0"
PRECHECK_VERSION = "precheck-1.0.0"
_DIR = Path(__file__).parent


@cache
def load(name: str) -> str:
    return (_DIR / name).read_text(encoding="utf-8")


def bundle_hash() -> str:
    """SHA-256 over every prompt file, in name order."""
    digest = hashlib.sha256()
    for path in sorted(_DIR.glob("*.txt")):
        digest.update(path.name.encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()
