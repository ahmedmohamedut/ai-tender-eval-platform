import json
from datetime import UTC, datetime

import pytest

from tender_worker.handlers import Context, DeadLetter, handle_command, handle_uploaded
from tender_worker.messages import WorkerInfo

T, D, V = (
    "11111111-1111-4111-8111-111111111111",
    "22222222-2222-4222-8222-222222222222",
    "33333333-3333-4333-8333-333333333333",
)
KEY = f"originals/{T}/{D}/{V}.pdf"


class FakePublisher:
    def __init__(self):
        self.sent = []

    async def publish(self, subject, payload, headers, msg_id):
        self.sent.append((subject, json.loads(payload), headers, msg_id))


class FakeStore:
    def __init__(self, present=True):
        self.present = present

    async def head(self, bucket, key):
        return (123, "etag") if self.present else None


def ctx(present=True):
    pub = FakePublisher()
    return Context(
        "ingest", WorkerInfo(id="w1", version="0.1.0"), pub, FakeStore(present), "tender-documents"
    ), pub


def event(key=KEY):
    return json.dumps(
        {"Records": [{"s3": {"bucket": {"name": "tender-documents"}, "object": {"key": key}}}]}
    ).encode()


async def test_upload_publishes_one_completed_status_with_deterministic_id():
    c, pub = ctx()
    await handle_uploaded(c, event(), deliveries=1, traceparent=None)
    [(subject, body, headers, msg_id)] = pub.sent
    assert subject == "documents.analysis.status.document_processing"
    assert msg_id == f"dv:{V}:s:1" and headers["Nats-Msg-Id"] == msg_id
    assert headers["Tender-Schema"] == "tender.analysis.status/1"
    assert headers["traceparent"].startswith("00-")
    assert body["state"] == "completed" and "job_id" not in body
    assert body["target"] == {"type": "document_version", "id": V}


async def test_redelivery_restarts_numbering_at_next_thousand():
    c, pub = ctx()
    await handle_uploaded(c, event(), deliveries=3, traceparent="00-aa-bb-01")
    assert pub.sent[0][3] == f"dv:{V}:s:2001"
    assert pub.sent[0][2]["traceparent"] == "00-aa-bb-01"


async def test_missing_object_reports_retryable_source_unavailable():
    c, pub = ctx(present=False)
    await handle_uploaded(c, event(), deliveries=1, traceparent=None)
    body = pub.sent[0][1]
    assert body["state"] == "failed"
    assert body["failure"] == {
        "code": "source_unavailable",
        "message": "object not found",
        "retryable": True,
    }


async def test_unmatched_key_is_acknowledged_without_status():
    c, pub = ctx()
    await handle_uploaded(c, event("originals/x.pdf"), deliveries=1, traceparent=None)
    assert pub.sent == []


async def test_unreadable_event_goes_to_dead_letter():
    c, _ = ctx()
    with pytest.raises(DeadLetter):
        await handle_uploaded(c, b"nope", deliveries=1, traceparent=None)


def command(**over):
    body = {
        "job_id": "44444444-4444-4444-8444-444444444444",
        "job_type": "document_processing",
        "tender_id": T,
        "target": {"type": "document_version", "id": V},
        "requested_by": None,
        "attempt": 1,
        "created_at": datetime.now(UTC).isoformat(),
        "input": {"document_version_id": V},
    }
    body.update(over)
    return json.dumps(body).encode()


async def test_command_gets_status_named_after_job_id():
    c, pub = ctx()
    await handle_command(c, command(), "tender.analysis.requested/1", 1, None)
    assert pub.sent[0][3] == "44444444-4444-4444-8444-444444444444:s:1"
    assert pub.sent[0][1]["job_id"] == "44444444-4444-4444-8444-444444444444"


@pytest.mark.parametrize(
    "body,schema",
    [
        (b"{}", "tender.analysis.requested/1"),
        (command(job_type="evaluation"), "tender.analysis.requested/1"),
        (command(), "tender.analysis.requested/2"),
        (command(input=None), "tender.analysis.requested/1"),
    ],
)
async def test_bad_commands_are_dead_lettered(body, schema):
    c, pub = ctx()
    with pytest.raises(DeadLetter):
        await handle_command(c, body, schema, 1, None)
    assert pub.sent == []
