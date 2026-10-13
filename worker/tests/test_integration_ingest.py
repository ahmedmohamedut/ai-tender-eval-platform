"""The ingest role against real NATS and RustFS containers.

RustFS publishes the bucket event to NATS itself, so the whole path is real:
upload -> RustFS event -> documents.uploaded -> ingest consumer -> HEAD on RustFS
-> status in ANALYSIS_EVENTS. Needs Docker; run with `pytest -m integration`.
"""

from __future__ import annotations

import asyncio
import json
import time
import urllib.request
import uuid
from pathlib import Path

import aioboto3
import nats
import pytest
from aiohttp.test_utils import TestServer
from botocore.config import Config
from fixtures import synthetic
from nats.js.api import KeyValueConfig, StreamConfig
from test_ingest_index import FakeQdrant
from testcontainers.core.container import DockerContainer
from testcontainers.core.network import Network

from tender_worker import stub_embedding
from tender_worker.consumers import ROLE_CONSUMERS
from tender_worker.runner import Runner
from tender_worker.settings import Settings

pytestmark = pytest.mark.integration


INFRA_STREAMS = Path(__file__).resolve().parents[2] / "infra" / "nats" / "streams"
ACCESS, SECRET = "testaccesskey", "testsecretkey-0123456789"


def _wait_http(url: str, seconds: int = 60) -> None:
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=2) as r:  # noqa: S310
                if r.status == 200:
                    return
        except Exception:  # noqa: BLE001
            time.sleep(0.5)
    raise TimeoutError(url)


@pytest.fixture(scope="module")
def stack():
    with Network() as net:
        nats_c = (
            DockerContainer("nats:2.15.0-alpine")
            .with_command("-js -m 8222")
            .with_exposed_ports(4222, 8222)
            .with_network(net)
            .with_network_aliases("nats")
        )
        rustfs = (
            DockerContainer("rustfs/rustfs:1.0.1")
            .with_env("RUSTFS_ACCESS_KEY", ACCESS)
            .with_env("RUSTFS_SECRET_KEY", SECRET)
            .with_env("RUSTFS_VOLUMES", "/data")
            .with_env("RUSTFS_ADDRESS", ":9000")
            .with_env("RUSTFS_NOTIFY_ENABLE", "true")
            .with_env("RUSTFS_NOTIFY_NATS_ENABLE_PRIMARY", "on")
            .with_env("RUSTFS_NOTIFY_NATS_ADDRESS_PRIMARY", "nats:4222")
            .with_env("RUSTFS_NOTIFY_NATS_SUBJECT_PRIMARY", "documents.uploaded")
            .with_exposed_ports(9000)
            .with_network(net)
            .with_network_aliases("rustfs")
        )
        with nats_c:
            _wait_http(f"http://localhost:{nats_c.get_exposed_port(8222)}/healthz")
            with rustfs:
                s3 = f"http://localhost:{rustfs.get_exposed_port(9000)}"
                _wait_http(f"{s3}/health")
                yield {
                    "nats": f"nats://localhost:{nats_c.get_exposed_port(4222)}",
                    "s3": s3,
                }


def _s3_client(endpoint: str):
    return aioboto3.Session().client(
        "s3",
        endpoint_url=endpoint,
        region_name="us-east-1",
        aws_access_key_id=ACCESS,
        aws_secret_access_key=SECRET,
        config=Config(s3={"addressing_style": "path"}),
    )


def _stream_config(path: Path) -> StreamConfig:
    d = json.loads(path.read_text())
    ns = 1_000_000_000
    return StreamConfig(
        name=d["name"],
        subjects=d["subjects"],
        retention=d["retention"],
        storage=d["storage"],
        discard=d["discard"],
        max_age=d["max_age"] / ns,
        duplicate_window=d["duplicate_window"] / ns,
        num_replicas=1,
    )


async def _statuses(js, count: int, timeout: float = 20.0) -> list[tuple[str, dict]]:
    """Read ANALYSIS_EVENTS status messages: (Nats-Msg-Id, body)."""
    sub = await js.pull_subscribe("documents.analysis.status.>", stream="ANALYSIS_EVENTS")
    out: list[tuple[str, dict]] = []
    deadline = time.time() + timeout
    while len(out) < count and time.time() < deadline:
        try:
            for m in await sub.fetch(10, timeout=1):
                out.append((m.headers.get("Nats-Msg-Id", ""), json.loads(m.data)))
                await m.ack()
        except Exception:  # noqa: BLE001
            await asyncio.sleep(0.2)
    return out


async def test_ingest_consumes_upload_event_and_duplicates_have_no_second_effect(
    stack, monkeypatch
):
    tender, document, version = (str(uuid.uuid4()) for _ in range(3))
    key = f"originals/{tender}/{document}/{version}.pdf"

    fake = FakeQdrant()
    qdrant = TestServer(fake.app())
    embed = TestServer(stub_embedding.app())
    await qdrant.start_server()
    await embed.start_server()
    nc = await nats.connect(stack["nats"])
    js = nc.jetstream()
    for f in sorted(INFRA_STREAMS.glob("*.json")):
        await js.add_stream(_stream_config(f))

    await js.create_key_value(config=KeyValueConfig(bucket="JOB_CONTROL"))
    async with _s3_client(stack["s3"]) as s3:
        await s3.create_bucket(Bucket="tender-documents")
        await s3.create_bucket(Bucket="tender-derived")
        await s3.put_bucket_notification_configuration(
            Bucket="tender-documents",
            NotificationConfiguration={
                "QueueConfigurations": [
                    {
                        "QueueArn": "arn:rustfs:sqs:us-east-1:primary:nats",
                        "Events": ["s3:ObjectCreated:*"],
                        "Filter": {
                            "Key": {"FilterRules": [{"Name": "prefix", "Value": "originals/"}]}
                        },
                    }
                ]
            },
        )

        for k, v in {
            "WORKER_ROLE": "ingest",
            "NATS_URL": stack["nats"],
            "S3_ENDPOINT": stack["s3"],
            "S3_ACCESS_KEY": ACCESS,
            "S3_SECRET_KEY": SECRET,
            "QDRANT_URL": str(qdrant.make_url("")).rstrip("/"),
            "EMBEDDING_BASE_URL": str(embed.make_url("/v1")).rstrip("/"),
            "EMBEDDING_MODEL": "stub",
            "EMBEDDING_DIMENSIONS": "256",
            "QDRANT_API_KEY": "unused",
            "HEALTH_PORT": "0",
            "SHUTDOWN_GRACE_SECONDS": "5",
        }.items():
            monkeypatch.setenv(k, v)
        stop, ready = asyncio.Event(), asyncio.Event()
        task = asyncio.create_task(Runner(Settings()).run(stop=stop, ready=ready))  # type: ignore[call-arg]
        await asyncio.wait_for(ready.wait(), 30)

        # The worker created its own two durable consumers (AI 2.7 step 2).
        for spec in ROLE_CONSUMERS["ingest"]:
            info = await js.consumer_info(spec.stream, spec.durable)
            assert info.config.max_ack_pending == spec.max_ack_pending

        # 1. A real upload: RustFS publishes documents.uploaded, the worker HEADs the object
        #    and publishes one status with a deterministic Nats-Msg-Id.
        await s3.put_object(Bucket="tender-documents", Key=key, Body=synthetic.make_rfp(3))
        # fetch, parse, write, index (running) and the completion: five numbered statuses
        got = await _statuses(js, 5, timeout=60)
        assert [m for m, _ in got] == [f"dv:{version}:s:{n}" for n in range(1, 6)]
        body = got[-1][1]
        assert body["state"] in ("completed", "completed_with_warnings")
        assert body["target"] == {"type": "document_version", "id": version}
        assert body["tender_id"] == tender
        assert body["result_parts"] == 1 and body["counts"]["pages"] == 3
        assert "job_id" not in body
        # The files and, last, the manifest are in tender-derived.
        manifest = await s3.get_object(
            Bucket="tender-derived", Key=f"parsed/{tender}/{version}/manifest.json"
        )
        assert json.loads(await manifest["Body"].read())["page_count"] == 3
        # and every passage is a point in the collection (AI 3.5)
        assert len(fake.points) == body["counts"]["passages"]
        result = await js.get_last_msg(
            "ANALYSIS_EVENTS", "documents.analysis.result.document_processing"
        )
        assert json.loads(result.data)["parsed"]["counts"]["pages"] == 3

        # 2. The same event delivered again (RustFS may emit duplicates). It is handled again
        #    and publishes the same Nats-Msg-Id, which JetStream drops: still one status.
        event = json.dumps(
            {
                "EventName": "s3:ObjectCreated:Put",
                "Records": [
                    {
                        "s3": {
                            "bucket": {"name": "tender-documents"},
                            "object": {"key": key.replace("/", "%2F")},
                        }
                    }
                ],
            }
        ).encode()
        await js.publish("documents.uploaded", event)
        await js.publish("documents.uploaded", event)
        await asyncio.sleep(3)
        info = await js.stream_info("ANALYSIS_EVENTS")
        assert info.state.messages == 6, "a duplicate delivery produced a second status"

        # 3. The same Nats-Msg-Id published twice is stored once by JetStream itself.
        first = await js.publish("documents.uploaded", b"{}", headers={"Nats-Msg-Id": "dup-1"})
        second = await js.publish("documents.uploaded", b"{}", headers={"Nats-Msg-Id": "dup-1"})
        assert not first.duplicate and second.duplicate

        # 4. A key that is not an original (wrong pattern) is acknowledged and ignored.
        other = {
            "Records": [
                {
                    "s3": {
                        "bucket": {"name": "tender-documents"},
                        "object": {"key": "originals/not-a-uuid/x/y.pdf"},
                    }
                }
            ]
        }
        await js.publish("documents.uploaded", json.dumps(other).encode())
        await asyncio.sleep(2)
        assert (await js.stream_info("ANALYSIS_EVENTS")).state.messages == 6

        # 5. Unreadable body goes to the dead-letter stream with its reason (PI 5.8).
        await js.publish("documents.uploaded", b"not json")
        deadline = time.time() + 15
        while time.time() < deadline:
            if (await js.stream_info("DLQ")).state.messages >= 1:
                break
            await asyncio.sleep(0.3)
        assert (await js.stream_info("DLQ")).state.messages >= 1

        # Everything pulled was acknowledged: nothing is left pending.
        info = await js.consumer_info("DOCUMENTS", "ingest-uploaded")
        assert info.num_ack_pending == 0

        stop.set()
        await asyncio.wait_for(task, 20)
    await nc.close()
    await qdrant.close()
    await embed.close()
