"""AI 4.4: the pre-check graph and its job, with the stub model's rules and in-memory passages."""

from __future__ import annotations

import json
import uuid
from datetime import UTC, datetime

import pytest
from aiohttp.test_utils import TestServer
from test_agent_runtime import TENDER, TP, Blobs, Bus, Kv

from tender_worker import stub_llm
from tender_worker.agent.extraction import ExtractionSettings, NoContentInScope
from tender_worker.agent.llm import OpenAiCompatible
from tender_worker.agent.precheck import PrecheckGraph, find_format_passages
from tender_worker.agent.retrieval import PassageRef, ScopeItem
from tender_worker.agent.runtime import AgentDeps, run_precheck
from tender_worker.messages import Command, WorkerInfo

JOB = uuid.UUID("0193a7a0-0000-7000-8000-0000000000b1")
RFP = "0193a7a0-0000-7000-8000-0000000000c1"
PROPOSAL = "0193a7a0-0000-7000-8000-0000000000c2"
PV = uuid.UUID("0193a7a0-0000-7000-8000-0000000000d1")


def p(n: int, version: str, text: str, path: tuple[str, ...] = (), page: int = 1) -> PassageRef:
    return PassageRef(
        id=str(uuid.UUID(int=n)),
        version_id=version,
        ordinal=n,
        page_no=page,
        text=text,
        section_path_ids=(),
        section_path=path,
    )


RFP_PASSAGES = [
    p(
        1,
        RFP,
        "Background of the ministry and the purpose of this procurement.",
        ("1. Background",),
    ),
    p(
        2,
        RFP,
        "The proposal must include the following sections: Executive summary, Technical solution,"
        " Implementation plan and Pricing.",
        ("2. Instructions to vendors",),
        page=4,
    ),
    p(3, RFP, "Evaluation will use the weights in annex B.", ("3. Evaluation",), page=5),
]
PROPOSAL_PASSAGES = [
    p(11, PROPOSAL, "We summarise our offer for the ministry.", ("Executive summary",), page=2),
    p(
        12,
        PROPOSAL,
        "Our platform runs on three availability zones.",
        ("Technical solution",),
        page=5,
    ),
    p(13, PROPOSAL, "Phase one starts in January.", ("Technical solution", "Delivery"), page=9),
    p(14, PROPOSAL, "Prices are fixed for 24 months.", ("Annex: pricing sheet",), page=14),
]


class Source:
    def __init__(self, by_version: dict[str, list[PassageRef]]) -> None:
        self.by_version = by_version

    async def load(self, tender_id, scope):
        return [x for s in scope for x in self.by_version.get(s.version_id, [])]


@pytest.fixture
async def llm():
    server = TestServer(stub_llm.app())
    await server.start_server()
    client = OpenAiCompatible(
        base_url=str(server.make_url("/v1")).rstrip("/"),
        api_key="k",
        model="stub",
        structured_output="json_schema",
        timeout=30,
        retries=1,
    )
    yield client
    await server.close()


def graph(llm, source, **kw):
    return PrecheckGraph(llm=llm, source=source, tender_id=TENDER, job_id=JOB, **kw)


def test_format_passages_are_found_by_code_and_kept_in_order():
    found = find_format_passages(RFP_PASSAGES)
    assert [x.id for x in found] == [RFP_PASSAGES[1].id]


async def test_expected_sections_are_matched_with_a_code_computed_page_range(llm):
    source = Source({RFP: RFP_PASSAGES, PROPOSAL: PROPOSAL_PASSAGES})
    findings, warnings = await graph(llm, source).run(
        [ScopeItem(version_id=RFP)], [ScopeItem(version_id=PROPOSAL)]
    )
    assert warnings == []
    by_name = {f["expected_section"]: f for f in findings}
    assert list(by_name) == [
        "Executive summary",
        "Technical solution",
        "Implementation plan",
        "Pricing",
    ]
    assert by_name["Executive summary"]["found"] is True
    assert "page 2" in by_name["Executive summary"]["note"]
    assert by_name["Technical solution"]["found"] is True
    assert by_name["Implementation plan"]["found"] is False
    assert by_name["Implementation plan"]["note"] == "No section of the proposal covers it."
    # ids are the producer's and stable for a job and a name
    again, _ = await graph(llm, source).run(
        [ScopeItem(version_id=RFP)], [ScopeItem(version_id=PROPOSAL)]
    )
    assert [f["item_id"] for f in again] == [f["item_id"] for f in findings]


async def test_an_rfp_without_format_clauses_gives_no_findings_and_a_warning(llm):
    source = Source({RFP: [RFP_PASSAGES[0], RFP_PASSAGES[2]], PROPOSAL: PROPOSAL_PASSAGES})
    findings, warnings = await graph(llm, source).run(
        [ScopeItem(version_id=RFP)], [ScopeItem(version_id=PROPOSAL)]
    )
    assert findings == []
    assert warnings == ["no_format_found"]


async def test_documents_without_text_are_refused(llm):
    source = Source({RFP: [], PROPOSAL: PROPOSAL_PASSAGES})
    with pytest.raises(NoContentInScope):
        await graph(llm, source).run([ScopeItem(version_id=RFP)], [ScopeItem(version_id=PROPOSAL)])


def deps(llm, source, bus, kv) -> AgentDeps:
    async def embed(texts):
        return [[0.0] for _ in texts]

    return AgentDeps(
        publisher=bus,
        kv=kv,
        blobs=Blobs(),
        source=source,
        llm=llm,
        embed=embed,
        worker=WorkerInfo(id="w1", version="t"),
        bucket_jobs="tender-jobs",
        model_name="stub",
        model_version="1",
        settings=ExtractionSettings(),
    )


def command() -> Command:
    return Command(
        job_id=JOB,
        job_type="screening_precheck",
        tender_id=TENDER,
        target={"type": "proposal_version", "id": PV},
        created_at=datetime.now(UTC),
        input={
            "proposal_version_id": str(PV),
            "rfp_document_version_ids": [RFP],
            "proposal_document_version_ids": [PROPOSAL],
        },
    )


async def never(_job) -> bool:
    return False


async def test_the_job_seals_publishes_prechecks_and_ends_with_the_counts(llm):
    bus, kv = Bus(), Kv()
    source = Source({RFP: RFP_PASSAGES, PROPOSAL: PROPOSAL_PASSAGES})
    await run_precheck(deps(llm, source, bus, kv), command(), 1, TP, never)

    results = bus.results()
    assert len(results) == 1 and results[0].kind == "prechecks" and results[0].final
    assert results[0].target.type == "proposal_version"
    assert len(results[0].items) == 4
    assert {i["found"] for i in results[0].items} == {True, False}
    last = bus.statuses()[-1]
    assert last.state == "completed"
    assert last.counts == {"prechecks": 4}
    subjects = {s for s, _, _ in bus.sent}
    assert "documents.analysis.result.screening_precheck" in subjects
    assert "documents.analysis.status.screening_precheck" in subjects
    # parts are sealed before the final marker, and the marker before anything is published
    assert kv.order == [f"part.{JOB}.1", f"part.{JOB}.final"]
    # a restart republishes what was sealed without calling the model again
    bus2 = Bus()

    class NoModel:
        async def generate(self, *a, **k):
            raise AssertionError("the model must not be called again")

    await run_precheck(deps(NoModel(), source, bus2, kv), command(), 2, TP, never)
    assert [r.items for r in bus2.results()] == [r.items for r in results]
    assert json.loads(kv.data[f"part.{JOB}.final"])["kind"] == "prechecks"


async def test_a_cancel_before_the_start_stops_the_job(llm):
    bus, kv = Bus(), Kv()

    async def yes(_job) -> bool:
        return True

    await run_precheck(deps(llm, Source({}), bus, kv), command(), 1, TP, yes)
    assert [s.state for s in bus.statuses()] == ["cancelled"]
    assert kv.order == []
