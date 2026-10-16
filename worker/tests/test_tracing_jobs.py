"""AI 6: evaluation and pre-check spans (G6). Same rules as extraction: ids, hashes and lengths."""

from __future__ import annotations

import pytest
import test_agent_evaluation as ev
import test_agent_precheck as pre
from aiohttp.test_utils import TestServer
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from test_agent_runtime import TP, Bus, Kv

from tender_worker import stub_llm, tracing
from tender_worker.agent.llm import OpenAiCompatible
from tender_worker.agent.runtime import run_evaluation, run_precheck

TRACE_ID = "a" * 32
PROPOSAL_TEXT = "Zebrafish-Marker-Alpha"


@pytest.fixture
def exporter():
    exp = InMemorySpanExporter()
    yield exp
    tracing.configure(
        endpoint=None,
        project="t",
        api_key=None,
        capture_content=False,
        service_version="0",
        worker_id="w",
    )


def configure(exp, *, capture: bool) -> None:
    tracing.configure(
        endpoint=None,
        project="tender-eval",
        api_key=None,
        capture_content=capture,
        service_version="0.1.0",
        worker_id="w1",
        exporter_factory=lambda: exp,
    )


@pytest.fixture
async def llm():
    server = TestServer(stub_llm.app())
    await server.start_server()
    client = OpenAiCompatible(
        base_url=str(server.make_url("/v1")).rstrip("/"),
        api_key="sk-secret-key-value",
        model="stub",
        structured_output="json_schema",
        timeout=30,
        retries=1,
    )
    yield client
    await server.close()


def flush(exp) -> list:
    tracing._provider.force_flush()
    return list(exp.get_finished_spans())


def blob(spans) -> str:
    return " ".join(str(v) for s in spans for v in s.attributes.values())


def ancestors(span, spans) -> list[str]:
    by_id = {s.context.span_id: s for s in spans}
    out, cur = [], span
    while cur.parent is not None and cur.parent.span_id in by_id:
        cur = by_id[cur.parent.span_id]
        out.append(cur.name)
    return out


async def run_eval(llm) -> None:
    await run_evaluation(
        ev.deps(llm, ev.Search(ev.PASSAGES), Bus(), Kv()), ev.command(4), 1, TP, ev.never
    )


async def run_pre(llm) -> None:
    source = pre.Source({pre.RFP: pre.RFP_PASSAGES, pre.PROPOSAL: pre.PROPOSAL_PASSAGES})
    await run_precheck(pre.deps(llm, source, Bus(), Kv()), pre.command(), 1, TP, pre.never)


async def test_an_evaluation_job_is_traced_row_by_row_without_text(exporter, llm):
    configure(exporter, capture=False)
    await run_eval(llm)
    spans = flush(exporter)
    names = {s.name for s in spans}
    assert {
        "job.evaluation",
        "row",
        "step.retrieve",
        "step.grade",
        "step.validate",
        "step.record",
        "llm.grade",
    } <= names
    # the ISO requirement is not addressed: it gets the one second look
    assert {"step.absence", "llm.verify"} <= names
    job = next(s for s in spans if s.name == "job.evaluation")
    assert f"{job.context.trace_id:032x}" == TRACE_ID
    assert job.attributes["tender.job_id"] == str(ev.JOB)
    assert job.attributes["tender.prompt_version"]
    rows = [s for s in spans if s.name == "row"]
    assert len(rows) == 4
    assert {r.attributes["tender.row_outcome"] for r in rows} <= {"graded", "failed"}
    assert all("tender.requirement_id" in r.attributes for r in rows)
    grade = next(s for s in spans if s.name == "llm.grade")
    assert ancestors(grade, spans)[:2] == ["step.grade", "row"]
    assert ancestors(grade, spans)[-1] == "job.evaluation"
    assert {s.context.trace_id for s in spans} == {job.context.trace_id}
    text = blob(spans)
    for secret in ("AES-256", "encrypt all customer data", "ISO 27001", "sk-secret-key-value"):
        assert secret not in text


async def test_evaluation_content_is_recorded_only_when_the_switch_is_on(exporter, llm):
    configure(exporter, capture=True)
    await run_eval(llm)
    text = blob(flush(exporter))
    assert "encrypt all customer data" in text
    assert "sk-secret-key-value" not in text


async def test_a_precheck_job_is_traced_without_text(exporter, llm):
    configure(exporter, capture=False)
    await run_pre(llm)
    spans = flush(exporter)
    names = {s.name for s in spans}
    assert {
        "job.screening_precheck",
        "step.load",
        "step.find_format",
        "step.list_expected",
        "row",
        "llm.expected",
        "llm.match",
    } <= names
    job = next(s for s in spans if s.name == "job.screening_precheck")
    assert f"{job.context.trace_id:032x}" == TRACE_ID
    match = next(s for s in spans if s.name == "llm.match")
    assert ancestors(match, spans)[0] == "row"
    rows = [s for s in spans if s.name == "row"]
    assert len(rows) == 4
    assert {r.attributes["tender.found"] for r in rows} == {True, False}
    text = blob(spans)
    assert "Phase one starts in January" not in text
    assert "three availability zones" not in text
    assert "Executive summary" not in text  # section names come from the RFP: content
    assert "sk-secret-key-value" not in text
