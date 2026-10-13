"""Process lifecycle (AI 2.7) and the message loop (AI 2.3) for both roles."""

from __future__ import annotations

import asyncio
import contextlib
import logging
import multiprocessing
import signal
from concurrent.futures import ProcessPoolExecutor
from dataclasses import dataclass

import aioboto3
import aiohttp
import nats
from botocore.config import Config
from botocore.exceptions import ClientError
from nats.aio.msg import Msg
from nats.errors import TimeoutError as NatsTimeout
from nats.js.errors import APIError, KeyNotFoundError

from tender_worker.consumers import (
    INGEST_COMMANDS,
    INGEST_UPLOADED,
    ROLE_CONSUMERS,
    ConsumerSpec,
    ensure_consumers,
)
from tender_worker.handlers import (
    Context,
    DeadLetter,
    handle_command,
    handle_uploaded,
)
from tender_worker.health import HealthServer
from tender_worker.ingest.pipeline import IngestPipeline, NoIndex
from tender_worker.messages import WorkerInfo
from tender_worker.settings import Settings

log = logging.getLogger(__name__)


class S3Store:
    """HEAD requests against RustFS. The worker never lists or deletes."""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._session = aioboto3.Session()

    def _client(self):
        s = self._settings
        return self._session.client(
            "s3",
            endpoint_url=s.S3_ENDPOINT,
            region_name=s.S3_REGION,
            aws_access_key_id=s.S3_ACCESS_KEY.get_secret_value(),
            aws_secret_access_key=s.S3_SECRET_KEY.get_secret_value(),
            config=Config(
                s3={"addressing_style": "path" if s.S3_PATH_STYLE else "auto"},
                connect_timeout=s.DEP_TIMEOUT_SECONDS,
                read_timeout=s.DEP_TIMEOUT_SECONDS,
                retries={"max_attempts": 3},
            ),
        )

    async def head(self, bucket: str, key: str) -> tuple[int, str] | None:
        async with self._client() as client:
            try:
                resp = await client.head_object(Bucket=bucket, Key=key)
            except ClientError as exc:
                if exc.response.get("Error", {}).get("Code") in {"404", "NoSuchKey", "NotFound"}:
                    return None
                raise
            return int(resp["ContentLength"]), resp["ETag"].strip('"')

    async def get(self, bucket: str, key: str) -> bytes:
        async with self._client() as client:
            resp = await client.get_object(Bucket=bucket, Key=key)
            return await resp["Body"].read()

    async def put(self, bucket: str, key: str, data: bytes, content_type: str) -> None:
        async with self._client() as client:
            await client.put_object(Bucket=bucket, Key=key, Body=data, ContentType=content_type)

    async def exists(self, bucket: str, key: str) -> bool:
        return await self.head(bucket, key) is not None

    async def bucket_reachable(self, bucket: str) -> bool:
        async with self._client() as client:
            await client.head_bucket(Bucket=bucket)
        return True


class JetStreamPublisher:
    def __init__(self, js) -> None:
        self._js = js

    async def publish(
        self, subject: str, payload: bytes, headers: dict[str, str], msg_id: str
    ) -> None:
        # Awaiting the ack means JetStream accepted it (or dropped a duplicate).
        await self._js.publish(subject, payload, headers=headers)


@dataclass
class Runner:
    settings: Settings

    async def run(
        self, stop: asyncio.Event | None = None, ready: asyncio.Event | None = None
    ) -> None:
        """Run until SIGTERM/SIGINT, or until `stop` is set when one is supplied (tests)."""
        s = self.settings
        if stop is None:
            stop = asyncio.Event()
            loop = asyncio.get_running_loop()
            for sig in (signal.SIGTERM, signal.SIGINT):
                loop.add_signal_handler(sig, stop.set)

        store = S3Store(s)
        bucket = s.S3_BUCKET_DOCUMENTS if s.WORKER_ROLE == "ingest" else s.S3_BUCKET_JOBS
        nc = await nats.connect(s.NATS_URL, name=f"{s.WORKER_ID}", connect_timeout=10)
        js = nc.jetstream()

        async def nats_ok() -> bool:
            return nc.is_connected

        async def s3_ok() -> bool:
            return await store.bucket_reachable(bucket)

        async def qdrant_ok() -> bool:
            async with aiohttp.ClientSession() as http:
                async with http.get(
                    f"{s.QDRANT_URL.rstrip('/')}/readyz",
                    headers={"api-key": s.QDRANT_API_KEY.get_secret_value()},
                    timeout=aiohttp.ClientTimeout(total=5),
                ) as resp:
                    return resp.status == 200

        health = HealthServer(
            s.HEALTH_PORT, {"nats": nats_ok, "rustfs": s3_ok, "qdrant": qdrant_ok}
        )
        await ensure_consumers(js, s.WORKER_ROLE)
        await health.start()
        log.info("started", extra={"health_port": health.port, "version": s.service_version})

        worker = WorkerInfo(id=s.WORKER_ID, version=s.service_version)
        publisher = JetStreamPublisher(js)
        pool = None
        pipeline = None
        if s.WORKER_ROLE == "ingest":
            # CPU-bound parsing runs in spawned processes, never on the event loop (AI 2.7).
            def make_pool() -> ProcessPoolExecutor:
                return ProcessPoolExecutor(
                    max_workers=s.INGEST_CONCURRENCY,
                    mp_context=multiprocessing.get_context("spawn"),
                )

            pool = make_pool()
            control = await js.key_value("JOB_CONTROL")

            async def cancelled(job_id) -> bool:
                try:
                    await control.get(f"cancel.{job_id}")
                    return True
                except KeyNotFoundError:
                    return False

            pipeline = IngestPipeline(
                blobs=store,
                publisher=publisher,
                worker=worker,
                bucket_derived=s.S3_BUCKET_DERIVED,
                executor=pool,
                new_executor=make_pool,
                indexer=NoIndex(),
                cancelled=cancelled,
                parse_timeout=s.INGEST_PARSE_TIMEOUT_SECONDS,
                convert_timeout=s.INGEST_CONVERT_TIMEOUT_SECONDS,
                page_min_chars=s.INGEST_PAGE_MIN_CHARS,
                max_textless_share=s.INGEST_MAX_TEXTLESS_SHARE,
            )
        ctx = Context(
            role=s.WORKER_ROLE,
            worker=worker,
            publisher=publisher,
            store=store,
            bucket_documents=s.S3_BUCKET_DOCUMENTS,
            pipeline=pipeline,
        )
        slots = asyncio.Semaphore(self._concurrency())
        tasks = [
            asyncio.create_task(self._consume(js, spec, ctx, slots, stop))
            for spec in ROLE_CONSUMERS[s.WORKER_ROLE]
        ]
        if ready is not None:
            ready.set()
        await stop.wait()
        health.accepting = False
        log.info("stopping: no longer pulling")
        done, pending = await asyncio.wait(tasks, timeout=s.SHUTDOWN_GRACE_SECONDS)
        for task in pending:
            task.cancel()
        await health.stop()
        if pool is not None:
            pool.shutdown(wait=False, cancel_futures=True)
        await nc.drain()

    def _concurrency(self) -> int:
        s = self.settings
        if s.WORKER_ROLE == "ingest":
            return s.INGEST_CONCURRENCY
        return (
            s.AGENT_CONCURRENCY_EVALUATION
            + s.AGENT_CONCURRENCY_EXTRACTION
            + s.AGENT_CONCURRENCY_PRECHECK
        )

    async def _consume(
        self, js, spec: ConsumerSpec, ctx: Context, slots: asyncio.Semaphore, stop: asyncio.Event
    ) -> None:
        psub = await js.pull_subscribe(
            spec.filter_subject, durable=spec.durable, stream=spec.stream
        )
        inflight: set[asyncio.Task] = set()
        while not stop.is_set():
            await slots.acquire()
            try:
                msgs = await psub.fetch(1, timeout=2)
            except NatsTimeout:
                slots.release()
                continue
            except Exception:  # noqa: BLE001 - reconnect handled by the client
                slots.release()
                log.warning("fetch failed", extra={"durable": spec.durable}, exc_info=True)
                await asyncio.sleep(1)
                continue
            task = asyncio.create_task(self._process(msgs[0], spec, ctx, slots))
            inflight.add(task)
            task.add_done_callback(inflight.discard)
        if inflight:
            await asyncio.gather(*inflight, return_exceptions=True)

    async def _process(
        self, msg: Msg, spec: ConsumerSpec, ctx: Context, slots: asyncio.Semaphore
    ) -> None:
        """AI 2.3: validate, run, report, then acknowledge."""
        heartbeat = asyncio.create_task(self._heartbeat(msg, spec.ack_wait_seconds / 3))
        try:
            headers = msg.headers or {}
            deliveries = msg.metadata.num_delivered
            traceparent = headers.get("traceparent")
            if spec is INGEST_UPLOADED:
                await handle_uploaded(ctx, msg.data, deliveries, traceparent)
            elif spec is INGEST_COMMANDS:
                await handle_command(
                    ctx, msg.data, headers.get("Tender-Schema"), deliveries, traceparent
                )
            else:
                # The agent graphs do not exist yet; commands for them are not consumed.
                log.info("agent command seen", extra={"durable": spec.durable})
            await msg.ack()
        except DeadLetter as exc:
            await self._dead_letter(ctx, msg, exc.reason)
        except Exception:  # noqa: BLE001
            log.exception("handler failed", extra={"durable": spec.durable})
            with contextlib.suppress(Exception):
                await msg.nak(delay=30)
        finally:
            heartbeat.cancel()
            slots.release()

    @staticmethod
    async def _heartbeat(msg: Msg, every: float) -> None:
        while True:
            await asyncio.sleep(every)
            with contextlib.suppress(Exception):
                await msg.in_progress()

    @staticmethod
    async def _dead_letter(ctx: Context, msg: Msg, reason: str) -> None:
        deliveries = msg.metadata.num_delivered
        log.warning("dead letter", extra={"subject": msg.subject, "reason": reason})
        try:
            await ctx.publisher.publish(
                f"dlq.{msg.subject}",
                msg.data,
                {
                    "Dlq-Reason": reason,
                    "Dlq-Original-Subject": msg.subject,
                    "Dlq-Deliveries": str(deliveries),
                },
                msg_id=f"dlq:{msg.metadata.sequence.stream}",
            )
            await msg.term()
        except APIError:
            await msg.nak(delay=30)
