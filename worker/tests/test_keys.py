import json

import pytest

from tender_worker.keys import MalformedEvent, parse_upload_event

T, D, V = (
    "11111111-1111-4111-8111-111111111111",
    "22222222-2222-4222-8222-222222222222",
    "33333333-3333-4333-8333-333333333333",
)


LETTERS = "abcdefab-1111-4111-8111-111111111111"


def event(key: str, bucket: str = "tender-documents") -> bytes:
    return json.dumps(
        {
            "EventName": "s3:ObjectCreated:Put",
            "Records": [{"s3": {"bucket": {"name": bucket}, "object": {"key": key}}}],
        }
    ).encode()


def test_decodes_url_encoded_key_and_takes_ids_from_it():
    key = f"originals%2F{T}%2F{D}%2F{V}.pdf"
    [doc] = parse_upload_event(event(key), "tender-documents")
    assert str(doc.tender_id) == T and str(doc.document_id) == D
    assert str(doc.document_version_id) == V and doc.file_format == "pdf"
    assert doc.key == f"originals/{T}/{D}/{V}.pdf"


@pytest.mark.parametrize(
    "key",
    [
        f"originals/{T}/{D}/{V}.txt",
        f"originals/{T}/{D}/{V}.PDF",
        f"derived/{T}/{D}/{V}.pdf",
        f"originals/{T}/{D}/not-a-uuid.pdf",
        f"originals/{T}/{D}/{V}.pdf/extra",
        f"originals/{LETTERS.upper()}/{D}/{V}.pdf",
        f"originals/../{T}/{D}/{V}.pdf",
    ],
)
def test_other_keys_are_ignored(key):
    assert parse_upload_event(event(key), "tender-documents") == []


def test_other_bucket_is_ignored():
    assert (
        parse_upload_event(event(f"originals/{T}/{D}/{V}.pdf", bucket="x"), "tender-documents")
        == []
    )


@pytest.mark.parametrize("body", [b"not json", b"{}", b'{"Records": 5}', b"[]"])
def test_malformed_body_is_rejected(body):
    with pytest.raises(MalformedEvent):
        parse_upload_event(body, "tender-documents")


def test_accepts_the_shape_rustfs_1_0_1_actually_sends():
    key = f"originals%2F{T}%2F{D}%2F{V}.pdf"
    body = json.dumps(
        {
            "EventName": "s3:ObjectCreated:Put",
            "Key": f"tender-documents/originals/{T}/{D}/{V}.pdf",
            "Records": [
                {
                    "object_name": key,
                    "bucket_name": "tender-documents",
                    "data": {
                        "s3": {"bucket": {"name": "tender-documents"}, "object": {"key": key}}
                    },
                }
            ],
        }
    ).encode()
    [doc] = parse_upload_event(body, "tender-documents")
    assert str(doc.document_version_id) == V


def test_falls_back_to_record_level_names():
    body = json.dumps(
        {
            "Records": [
                {
                    "bucket_name": "tender-documents",
                    "object_name": f"originals%2F{T}%2F{D}%2F{V}.docx",
                }
            ]
        }
    ).encode()
    [doc] = parse_upload_event(body, "tender-documents")
    assert doc.file_format == "docx"


def test_record_without_any_known_shape_is_skipped():
    assert parse_upload_event(b'{"Records":[{"x":1},"y",null]}', "tender-documents") == []
