"""AI 2.3 to 2.5, PI 5.4 to 5.9: one extraction job through the runtime."""

from __future__ import annotations

import json
import uuid
from datetime import UTC, datetime

import pytest
from test_agent_extraction import CATEGORIES, Llm, Source, cand, embed, passage

from tender_worker.agent.extraction import ExtractionSettings
from tender_worker.agent.llm import LlmUnavailable
from tender_worker.agent.runtime import AgentDeps, BadInput, run_extraction
from tender_worker.messages import Command, ResultBatch, Status, WorkerInfo

TENDER = uuid.UUID("0193a7a0-0000-7000-8000-000000000002")
JOB = uuid.UUID("0193a7a0-0000-7000-8000-000000000001")
VERSION = "0193a7a0-0000-7000-8000-000000000003"
TP = "00-" + "a" * 32 + "-" + "b" * 16 + "-01"


class Bus:
    def __init__(self) -> None:
        self.sent: list[tuple[str, bytes, dict]] = []

    async def publish(self, subject, payload, headers, msg_id):
        self.sent.append((subject, payload, headers))

    def statuses(self) -> list[Status]:
        return [Status.model_validate_json(p) for s, p, _ in self.sent if "status" in s]

    def results(self) -> list[ResultBatch]:
        return [ResultBatch.model_validate_json(p) for s, p, _ in self.sent if "result" in s]


class Kv:
    def __init__(self) -> None:
        self.data: dict[str, bytes] = {}
        self.order: list[str] = []

    async def get(self, key):
        return self.data.get(key)

    async def put(self, key, value):
        self.data[key] = value
        self.order.append(key)


class Blobs:
    def __init__(self, objects=None) -> None:
        self.objects = objects or {}

    async def get(self, bucket, key):
        return self.objects[(bucket, key)]


class FailingLlm:
    calls = 0

    async def generate(self, *a, **k):
        FailingLlm.calls += 1
        raise LlmUnavailable("down")


def command(**over) -> Command:
    base = {
        "job_id": JOB,
        "job_type": "requirement_extraction",
        "tender_id": TENDER,
        "target": {"type": "tender", "id": TENDER},
        "created_at": datetime.now(UTC),
        "input": {
            "scope": [{"document_version_id": VERSION}],
            "categories": CATEGORIES,
            "existing_requirements": [],
        },
    }
    base.update(over)
    return Command.model_validate(base)


def deps(llm, passages, bus, kv, blobs=None, batch=50) -> AgentDeps:
    return AgentDeps(
        publisher=bus,
        kv=kv,
        blobs=blobs or Blobs(),
        source=Source(passages),
        llm=llm,
        embed=embed,
        worker=WorkerInfo(id="agent-test", version="0.0.0"),
        bucket_jobs="tender-jobs",
        model_name="gemma-test",
        model_version="1",
        settings=ExtractionSettings(window_chars=300, overlap_passages=0),
        batch_max_items=batch,
    )


async def never(_):
    return False


def many(n: int):
    ps = [
        passage(i, f"The supplier shall provide capability number {i} with full support.")
        for i in range(1, n + 1)
    ]
    answers = {
        p.id: [
            cand(p.id, f"capability number {i}", text=f"The supplier shall provide capability {i}.")
        ]
        for i, p in enumerate(ps, start=1)
    }
    return ps, answers


async def test_a_run_announces_the_run_seals_every_part_then_publishes_and_completes():
    ps, answers = many(5)
    bus, kv = Bus(), Kv()
    await run_extraction(deps(Llm(answers), ps, bus, kv, batch=2), command(), 1, TP, never)

    statuses = bus.statuses()
    first = statuses[0]
    assert first.state == "running" and first.steps and first.run
    assert first.run.model_name == "gemma-test" and first.run.prompt_version == "extraction-1.0.0"
    assert [s.seq for s in statuses] == list(range(1, len(statuses) + 1))
    last = statuses[-1]
    assert last.state == "completed" and last.result_parts == 3
    assert last.counts == {"requirements": 5}

    results = bus.results()
    assert [r.part_no for r in results] == [1, 2, 3]
    assert [r.final for r in results] == [False, False, True]
    assert all(len(r.items) <= 2 for r in results) and sum(len(r.items) for r in results) == 5
    assert all(r.kind == "requirements" for r in results)
    # sealed first: every part and the marker are in the KV before the first publication
    assert kv.order == [f"part.{JOB}.1", f"part.{JOB}.2", f"part.{JOB}.3", f"part.{JOB}.final"]
    # parts go out before the terminal status
    subjects = [s for s, _, _ in bus.sent]
    assert subjects.index(next(s for s in subjects if "result" in s)) < len(subjects) - 1
    assert "result" not in subjects[-1]


async def test_a_restart_after_sealing_republishes_the_same_parts_and_never_calls_the_model():
    ps, answers = many(3)
    kv = Kv()
    llm = Llm(answers)
    await run_extraction(deps(llm, ps, Bus(), kv, batch=2), command(), 1, TP, never)
    calls = llm.calls

    bus2 = Bus()
    await run_extraction(deps(llm, ps, bus2, kv, batch=2), command(), 2, TP, never)
    assert llm.calls == calls  # no model call after the restart
    assert [r.part_no for r in bus2.results()] == [1, 2]
    again = [i["item_id"] for r in bus2.results() for i in r.items]
    assert len(again) == 3 and len(set(again)) == 3
    assert bus2.statuses()[0].seq == 1001 and bus2.statuses()[-1].state == "completed"


async def test_a_cancel_key_stops_before_any_work_and_after_the_graph():
    ps, answers = many(1)
    bus = Bus()

    async def yes(_):
        return True

    await run_extraction(deps(Llm(answers), ps, bus, Kv()), command(), 1, TP, yes)
    assert [s.state for s in bus.statuses()] == ["cancelled"]


async def test_nothing_in_scope_fails_with_its_own_code_and_no_retry():
    bus = Bus()
    await run_extraction(deps(Llm({}), [], bus, Kv()), command(), 1, TP, never)
    last = bus.statuses()[-1]
    assert last.state == "failed" and last.failure.code == "no_content_in_scope"
    assert last.failure.retryable is False and bus.results() == []


async def test_a_model_outage_fails_retryable_without_storing_anything():
    ps, _ = many(2)
    bus, kv = Bus(), Kv()
    await run_extraction(deps(FailingLlm(), ps, bus, kv), command(), 1, TP, never)
    last = bus.statuses()[-1]
    assert last.state == "failed" and last.failure.code == "llm_unavailable"
    assert last.failure.retryable is True and kv.data == {} and bus.results() == []


async def test_a_dropped_candidate_completes_with_warnings():
    p = passage(1, "The supplier shall encrypt all data at rest.")
    llm = Llm(
        {
            p.id: [cand(p.id, "the supplier must store data in Iceland")],
            "repair": [cand(p.id, "the supplier must store data in Iceland")],
        }
    )
    bus = Bus()
    await run_extraction(deps(llm, [p], bus, Kv()), command(), 1, TP, never)
    last = bus.statuses()[-1]
    assert last.state == "completed_with_warnings"
    assert last.warnings[0].code == "ungrounded_candidate" and last.counts == {"requirements": 0}
    # an empty extraction still reports one final (empty) part, so the backend can complete
    assert [r.final for r in bus.results()] == [True] and bus.results()[0].items == []


async def test_a_large_input_comes_from_the_claim_check_and_is_verified():
    ps, answers = many(1)
    data = json.dumps(
        {
            "scope": [{"document_version_id": VERSION}],
            "categories": CATEGORIES,
            "existing_requirements": [],
        }
    ).encode()
    import hashlib

    sha = hashlib.sha256(data).hexdigest()
    ref = {"bucket": "tender-jobs", "key": f"inputs/{TENDER}/{JOB}.json", "sha256": sha}
    cmd = command(input=None, input_ref=ref)
    blobs = Blobs({("tender-jobs", ref["key"]): data})
    bus = Bus()
    await run_extraction(deps(Llm(answers), ps, bus, Kv(), blobs), cmd, 1, TP, never)
    assert bus.statuses()[-1].state == "completed"

    tampered = Blobs({("tender-jobs", ref["key"]): data + b" "})
    with pytest.raises(BadInput):
        await run_extraction(deps(Llm(answers), ps, Bus(), Kv(), tampered), cmd, 1, TP, never)
