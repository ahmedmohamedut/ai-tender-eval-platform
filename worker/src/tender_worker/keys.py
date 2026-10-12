"""Reading the RustFS upload event (PI 4.3).

The event is untrusted input: the key is decoded, matched against the one
pattern the backend writes, and anything else is acknowledged and ignored.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from urllib.parse import unquote
from uuid import UUID

_UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
ORIGINAL_KEY = re.compile(rf"^originals/({_UUID})/({_UUID})/({_UUID})\.(pdf|docx)$")


@dataclass(frozen=True)
class UploadedDocument:
    tender_id: UUID
    document_id: UUID
    document_version_id: UUID
    file_format: str
    key: str


class MalformedEvent(ValueError):
    """The body is not an S3 event at all (goes to the dead-letter stream)."""


def parse_upload_event(body: bytes, bucket: str) -> list[UploadedDocument]:
    """Return the documents named by an S3 event; skip records that do not match."""
    try:
        event = json.loads(body)
        records = event["Records"]
        if not isinstance(records, list):
            raise TypeError
    except (ValueError, KeyError, TypeError) as exc:
        raise MalformedEvent("not an S3 event") from exc

    found: list[UploadedDocument] = []
    for record in records:
        try:
            rec_bucket = record["s3"]["bucket"]["name"]
            key = unquote(record["s3"]["object"]["key"])
        except (KeyError, TypeError):
            continue
        if rec_bucket != bucket:
            continue
        match = ORIGINAL_KEY.match(key)
        if match:
            tender, document, version, fmt = match.groups()
            found.append(UploadedDocument(UUID(tender), UUID(document), UUID(version), fmt, key))
    return found
