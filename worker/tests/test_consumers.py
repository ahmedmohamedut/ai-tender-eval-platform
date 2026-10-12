"""The worker's consumer definitions must equal the provisioning files (PI 5.3)."""

import json
from pathlib import Path

import pytest

from tender_worker.consumers import ROLE_CONSUMERS

INFRA = Path(__file__).resolve().parents[2] / "infra" / "nats" / "consumers"
ALL = [spec for specs in ROLE_CONSUMERS.values() for spec in specs]


def test_roles_hold_the_five_worker_consumers():
    assert [s.durable for s in ROLE_CONSUMERS["ingest"]] == ["ingest-uploaded", "ingest-commands"]
    assert [s.durable for s in ROLE_CONSUMERS["agent"]] == [
        "agent-extraction",
        "agent-precheck",
        "agent-evaluation",
    ]


@pytest.mark.parametrize("spec", ALL, ids=lambda s: s.durable)
def test_matches_infra_definition(spec):
    data = json.loads((INFRA / f"{spec.stream}.{spec.durable}.json").read_text())
    assert data["durable_name"] == spec.durable
    assert data["filter_subject"] == spec.filter_subject
    assert data["ack_wait"] == spec.ack_wait_seconds * 1_000_000_000
    assert data["max_ack_pending"] == spec.max_ack_pending
    assert data["max_deliver"] == spec.max_deliver
    assert data["ack_policy"] == "explicit"


def test_filters_do_not_overlap_in_the_work_queue_stream():
    subjects = [s.filter_subject for s in ALL if s.stream == "DOCUMENTS"]
    assert len(subjects) == len(set(subjects)) == 5
