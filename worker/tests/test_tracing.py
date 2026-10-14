"""AI 6: extraction spans. Default traces hold ids, hashes and lengths, never text (rule 10)."""

from __future__ import annotations

import uuid

import pytest
from aiohttp.test_utils import TestServer
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from pydantic import BaseModel

from tender_worker import stub_embedding, stub_llm, tracing
from tender_worker.agent.extraction import ExtractionGraph, ExtractionSettings
from tender_worker.agent.llm import OpenAiCompatible
from tender_worker.agent.retrieval import PassageRef, ScopeItem

TENDER = uuid.UUID("0193a7a0-0000-7000-8000-000000000002")
JOB = uuid.UUID("0193a7a0-0000-7000-8000-000000000001")
VERSION = "0193a7a0-0000-7000-8000-000000000003"
PHRASE = "shall encrypt all data at rest using AES-256"
PASSAGE_TEXT = f"The supplier {PHRASE}. Zebrafish-Marker-Alpha."
TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"


class Source:
    def __init__(self, passages: list[PassageRef]) -> None:
        self.passages = passages

    async def load(self, tender_id, scope):
        return self.passages


async def embed(texts: list[str]) -> list[list[float]]:
    return [stub_embedding.embed(t) for t in texts]


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


async def run_extraction(llm) -> None:
    p = PassageRef(
        id=str(uuid.UUID(int=1)),
        version_id=VERSION,
        ordinal=1,
        page_no=1,
        text=PASSAGE_TEXT,
        section_path_ids=(),
    )
    graph = ExtractionGraph(
        llm=llm,
        passages=Source([p]),
        embed=embed,
        tender_id=TENDER,
        job_id=JOB,
        settings=ExtractionSettings(window_chars=400, overlap_passages=1),
    )
    with tracing.span(
        "job.requirement_extraction",
        "CHAIN",
        {"tender.job_id": str(JOB)},
        traceparent=TRACEPARENT,
    ):
        await graph.run([ScopeItem(version_id=VERSION)], [], [])


def flush(exp) -> list:
    tracing._provider.force_flush()
    return list(exp.get_finished_spans())


def all_values(spans) -> list[str]:
    return [str(v) for s in spans for v in s.attributes.values()]


async def test_default_trace_holds_hashes_and_lengths_but_no_text(exporter, llm):
    configure(exporter, capture=False)
    await run_extraction(llm)
    spans = flush(exporter)
    names = {s.name for s in spans}
    assert {"job.requirement_extraction", "step.load_scope", "row", "llm.extract"} <= names
    blob = " ".join(all_values(spans))
    assert "Zebrafish-Marker-Alpha" not in blob and PHRASE not in blob
    assert "sk-secret-key-value" not in blob
    llm_span = next(s for s in spans if s.name == "llm.extract")
    assert llm_span.attributes["openinference.span.kind"] == "LLM"
    assert llm_span.attributes["llm.model_name"] == "stub"
    assert len(llm_span.attributes["tender.prompt.sha256"]) == 64
    assert llm_span.attributes["tender.prompt.length"] > len(PASSAGE_TEXT)
    assert "input.value" not in llm_span.attributes
    assert "tender.prompt.value" not in llm_span.attributes


async def test_content_is_recorded_only_when_the_switch_is_on(exporter, llm):
    configure(exporter, capture=True)
    await run_extraction(llm)
    blob = " ".join(all_values(flush(exporter)))
    assert "Zebrafish-Marker-Alpha" in blob
    assert "sk-secret-key-value" not in blob


async def test_spans_nest_under_the_incoming_trace(exporter, llm):
    configure(exporter, capture=False)
    await run_extraction(llm)
    spans = flush(exporter)
    job = next(s for s in spans if s.name == "job.requirement_extraction")
    assert f"{job.context.trace_id:032x}" == "4bf92f3577b34da6a3ce929d0e0e4736"
    assert f"{job.parent.span_id:016x}" == "00f067aa0ba902b7"
    by_id = {s.context.span_id: s for s in spans}
    llm_span = next(s for s in spans if s.name == "llm.extract")
    chain = []
    cur = llm_span
    while cur.parent is not None and cur.parent.span_id in by_id:
        cur = by_id[cur.parent.span_id]
        chain.append(cur.name)
    assert chain[0] == "row" and chain[-1] == "job.requirement_extraction"
    assert {s.context.trace_id for s in spans} == {job.context.trace_id}


async def test_a_failing_model_call_records_the_class_not_the_message(exporter):
    configure(exporter, capture=False)

    def boom(model: str):
        class Chat:
            def bind(self, **_):
                return self

            async def ainvoke(self, _):
                raise RuntimeError("echoed Zebrafish-Marker-Alpha text")

        return Chat()

    client = OpenAiCompatible(
        base_url="http://unused", api_key="k", model="m", retries=1, chat_factory=boom
    )
    with pytest.raises(Exception):  # noqa: B017 - LlmUnavailable
        await client.generate("sys", "user text", _Model)
    spans = flush(exporter)
    failed = next(s for s in spans if s.name == "llm.call")
    assert failed.attributes["error.type"] == "RuntimeError"
    assert "Zebrafish" not in " ".join(all_values(spans))
    assert failed.events == ()


async def test_a_dead_exporter_never_fails_the_work(llm):
    class Dead:
        def export(self, spans):
            raise ConnectionError("phoenix is down")

        def shutdown(self):
            pass

        def force_flush(self, timeout_millis=0):
            return True

    tracing.configure(
        endpoint=None,
        project="p",
        api_key=None,
        capture_content=False,
        service_version="0",
        worker_id="w",
        exporter_factory=Dead,
    )
    await run_extraction(llm)  # must not raise
    tracing.shutdown()


async def test_tracing_is_a_no_op_without_an_endpoint(llm):
    tracing.configure(
        endpoint=None,
        project="p",
        api_key=None,
        capture_content=False,
        service_version="0",
        worker_id="w",
    )
    assert tracing._provider is None
    await run_extraction(llm)


class _Model(BaseModel):
    x: int = 0
