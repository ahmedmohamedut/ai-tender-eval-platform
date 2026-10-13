"""AI 2.3, 3.3: the pipeline end to end with in-memory storage and publisher."""

from __future__ import annotations

import json
import multiprocessing
import uuid
from concurrent.futures import ProcessPoolExecutor, ThreadPoolExecutor

import pytest
from fixtures import synthetic

from tender_worker.ingest.model import IngestFailure
from tender_worker.ingest.pipeline import DocumentRef, IngestPipeline, NoIndex, with_retries
from tender_worker.messages import SCHEMA_RESULT, SCHEMA_STATUS, ResultBatch, Status, WorkerInfo

TENDER = uuid.UUID("0193a7a0-0000-7000-8000-000000000002")
VERSION = uuid.UUID("0193a7a0-0000-7000-8000-000000000003")
JOB = uuid.UUID("0193a7a0-0000-7000-8000-000000000001")


class Blobs:
    def __init__(self) -> None:
        self.objects: dict[tuple[str, str], bytes] = {}
        self.puts: list[str] = []
        self.gets: list[str] = []
        self.fail_gets = 0

    async def get(self, bucket: str, key: str) -> bytes:
        self.gets.append(key)
        if self.fail_gets:
            self.fail_gets -= 1
            raise ConnectionError("storage down")
        return self.objects[(bucket, key)]

    async def put(self, bucket: str, key: str, data: bytes, content_type: str) -> None:
        self.puts.append(key)
        self.objects[(bucket, key)] = data

    async def exists(self, bucket: str, key: str) -> bool:
        return (bucket, key) in self.objects


class Bus:
    def __init__(self) -> None:
        self.sent: list[tuple[str, bytes, dict[str, str]]] = []

    async def publish(self, subject: str, payload: bytes, headers: dict[str, str], msg_id: str):
        self.sent.append((subject, payload, headers))

    def statuses(self) -> list[Status]:
        return [Status.model_validate_json(p) for s, p, _ in self.sent if "status" in s]

    def results(self) -> list[ResultBatch]:
        return [ResultBatch.model_validate_json(p) for s, p, _ in self.sent if "result" in s]


async def no_sleep(_: float) -> None:
    return None


def build(blobs: Blobs, bus: Bus, *, cancelled=None, executor=None) -> IngestPipeline:
    async def never(_: uuid.UUID) -> bool:
        return False

    return IngestPipeline(
        blobs=blobs,
        publisher=bus,
        worker=WorkerInfo(id="ingest-test", version="0.0.0"),
        bucket_derived="tender-derived",
        executor=executor or ThreadPoolExecutor(1),
        indexer=NoIndex(),
        cancelled=cancelled or never,
        sleep=no_sleep,
    )


def ref(job: uuid.UUID | None = JOB, fmt: str = "pdf") -> DocumentRef:
    return DocumentRef(
        tender_id=TENDER,
        version_id=VERSION,
        job_id=job,
        file_format=fmt,
        source_bucket="tender-documents",
        source_key=f"originals/{TENDER}/{uuid.uuid4()}/{VERSION}.{fmt}",
    )


def put_original(blobs: Blobs, r: DocumentRef, data: bytes) -> None:
    blobs.objects[(r.source_bucket, r.source_key)] = data


@pytest.fixture(scope="module")
def rfp() -> bytes:
    return synthetic.make_rfp(8)


async def test_a_pdf_is_parsed_written_manifest_last_and_reported(rfp):
    blobs, bus = Blobs(), Bus()
    r = ref()
    put_original(blobs, r, rfp)
    await build(blobs, bus).run(
        r, deliveries=1, traceparent="00-" + "a" * 32 + "-" + "b" * 16 + "-01"
    )

    # files in tender-derived, manifest after every other file
    written = [k for k in blobs.puts]
    assert written[-1].endswith("manifest.json")
    assert {k.rsplit("/", 1)[-1] for k in written} == {
        "document.pdf",
        "pages.jsonl",
        "sections.jsonl",
        "passages.jsonl",
        "manifest.json",
    }
    manifest = json.loads(blobs.objects[("tender-derived", r.manifest_key)])
    assert manifest["page_count"] == 8 and manifest["passage_count"] > 8
    assert len(manifest["source_sha256"]) == 64

    statuses = bus.statuses()
    assert [s.state for s in statuses][0] == "running"
    assert statuses[0].steps == ["fetch", "parse", "write", "index"]
    assert [s.seq for s in statuses] == list(range(1, len(statuses) + 1))
    final = statuses[-1]
    assert final.state in ("completed", "completed_with_warnings")
    assert final.result_parts == 1
    assert final.counts == {
        "pages": 8,
        "sections": manifest["section_count"],
        "passages": manifest["passage_count"],
    }
    results = bus.results()
    assert len(results) == 1 and results[0].kind == "parsed" and results[0].final
    assert results[0].parsed.counts == final.counts
    # the result is sent before the completion that depends on it
    order = [("result" in s) for s, _, _ in bus.sent]
    assert order.index(True) < len(bus.sent) - 1
    # headers carry a schema and the producer-chosen message id
    headers = bus.sent[0][2]
    assert headers["Tender-Schema"] == SCHEMA_STATUS and headers["Nats-Msg-Id"].startswith(str(JOB))
    result_headers = [h for s, _, h in bus.sent if "result" in s][0]
    assert result_headers["Tender-Schema"] == SCHEMA_RESULT


async def test_a_second_run_finds_the_manifest_and_does_not_parse_again(rfp):
    blobs, bus = Blobs(), Bus()
    r = ref()
    put_original(blobs, r, rfp)
    pipeline = build(blobs, bus)
    await pipeline.run(r, 1, "00-" + "a" * 32 + "-" + "b" * 16 + "-01")
    parsed_files = {k: v for k, v in blobs.objects.items() if k[1].startswith("parsed/")}
    puts_before = len(blobs.puts)
    blobs.gets.clear()

    bus2 = Bus()
    pipeline.publisher = bus2
    await pipeline.run(r, 2, "00-" + "a" * 32 + "-" + "b" * 16 + "-01")
    assert len(blobs.puts) == puts_before  # nothing rewritten
    assert r.source_key not in blobs.gets  # the original was not read again
    assert {k: v for k, v in blobs.objects.items() if k[1].startswith("parsed/")} == parsed_files
    # the redelivery numbers its statuses from 1001 and still reports the result
    assert bus2.statuses()[0].seq == 1001
    assert len(bus2.results()) == 1 and bus2.statuses()[-1].state.startswith("completed")


async def test_an_upload_event_run_has_no_job_id_and_names_the_version(rfp):
    blobs, bus = Blobs(), Bus()
    r = ref(job=None)
    put_original(blobs, r, rfp)
    await build(blobs, bus).run(r, 1, "00-" + "a" * 32 + "-" + "b" * 16 + "-01")
    assert all(s.job_id is None for s in bus.statuses())
    assert bus.results()[0].job_id is None
    assert bus.sent[0][2]["Nats-Msg-Id"].startswith(f"dv:{VERSION}:s:")


@pytest.mark.parametrize(
    ("data", "code"),
    [
        (synthetic.make_protected(), "password_protected"),
        (synthetic.make_textless(), "no_selectable_text"),
        (synthetic.make_corrupt(), "unsupported_format"),
    ],
)
async def test_a_file_cause_ends_in_a_failed_status_and_writes_nothing(data, code):
    blobs, bus = Blobs(), Bus()
    r = ref()
    put_original(blobs, r, data)
    await build(blobs, bus).run(r, 1, "00-" + "a" * 32 + "-" + "b" * 16 + "-01")
    final = bus.statuses()[-1]
    assert final.state == "failed" and final.failure.code == code
    assert final.failure.retryable is False
    assert blobs.puts == []
    assert bus.results() == []


async def test_a_storage_outage_is_retried_in_place_then_reported_as_source_unavailable():
    blobs, bus = Blobs(), Bus()
    r = ref()
    put_original(blobs, r, synthetic.make_rfp(2))
    blobs.fail_gets = 99
    await build(blobs, bus).run(r, 1, "00-" + "a" * 32 + "-" + "b" * 16 + "-01")
    final = bus.statuses()[-1]
    assert final.state == "failed" and final.failure.code == "source_unavailable"
    assert final.failure.retryable is True
    assert blobs.gets.count(r.source_key) == 5  # five tries


async def test_a_brief_outage_is_survived():
    blobs, bus = Blobs(), Bus()
    r = ref()
    put_original(blobs, r, synthetic.make_rfp(2, with_table=False))
    blobs.fail_gets = 2
    await build(blobs, bus).run(r, 1, "00-" + "a" * 32 + "-" + "b" * 16 + "-01")
    assert bus.statuses()[-1].state.startswith("completed")


async def test_a_cancel_key_stops_the_job_before_any_work():
    blobs, bus = Blobs(), Bus()
    r = ref()
    put_original(blobs, r, synthetic.make_rfp(2))

    async def cancelled(_: uuid.UUID) -> bool:
        return True

    await build(blobs, bus, cancelled=cancelled).run(
        r, 1, "00-" + "a" * 32 + "-" + "b" * 16 + "-01"
    )
    assert [s.state for s in bus.statuses()] == ["cancelled"]
    assert blobs.puts == [] and r.source_key not in blobs.gets


async def test_parsing_runs_in_a_spawned_process(rfp):
    blobs, bus = Blobs(), Bus()
    r = ref()
    put_original(blobs, r, rfp)
    with ProcessPoolExecutor(1, mp_context=multiprocessing.get_context("spawn")) as pool:
        await build(blobs, bus, executor=pool).run(r, 1, "00-" + "a" * 32 + "-" + "b" * 16 + "-01")
    assert bus.statuses()[-1].state.startswith("completed")


async def test_with_retries_backs_off_and_gives_up():
    calls = 0
    delays: list[float] = []

    async def fail():
        nonlocal calls
        calls += 1
        raise ConnectionError

    async def sleep(d: float) -> None:
        delays.append(d)

    with pytest.raises(IngestFailure) as err:
        await with_retries(fail, sleep=sleep)
    assert calls == 5 and len(delays) == 4
    assert delays == sorted(delays) and delays[-1] <= 30 * 1.2
    assert err.value.code == "source_unavailable" and err.value.retryable


async def test_a_broken_pool_is_replaced_for_the_next_document(rfp):
    from concurrent.futures.process import BrokenProcessPool

    class Dead:
        def submit(self, *a, **k):
            raise BrokenProcessPool("a process died")

    blobs, bus = Blobs(), Bus()
    r = ref()
    put_original(blobs, r, rfp)
    fresh = ThreadPoolExecutor(1)
    pipeline = build(blobs, bus, executor=Dead())
    pipeline.new_executor = lambda: fresh
    with pytest.raises(BrokenProcessPool):
        await pipeline.run(r, 1, "00-" + "a" * 32 + "-" + "b" * 16 + "-01")
    assert pipeline.executor is fresh
    await pipeline.run(r, 2, "00-" + "a" * 32 + "-" + "b" * 16 + "-01")
    assert bus.statuses()[-1].state.startswith("completed")


async def test_a_file_cause_raised_in_a_pool_process_keeps_its_code():
    blobs, bus = Blobs(), Bus()
    r = ref()
    put_original(blobs, r, synthetic.make_protected())
    with ProcessPoolExecutor(1, mp_context=multiprocessing.get_context("spawn")) as pool:
        await build(blobs, bus, executor=pool).run(r, 1, "00-" + "a" * 32 + "-" + "b" * 16 + "-01")
    assert bus.statuses()[-1].failure.code == "password_protected"
