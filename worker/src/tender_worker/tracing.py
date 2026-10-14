"""Tracing for the agent role (AI 6): OpenTelemetry spans with OpenInference attributes, to Phoenix.

Spans are made by hand so that we decide exactly what is recorded (rule 10). By default a span
holds ids, hashes of texts, lengths, counts and durations. Prompt, passage text and model output
are added only when `TRACE_CAPTURE_CONTENT` is on. Export is asynchronous and bounded; a failure
to export is dropped and never fails or delays a job (AI 7.1).

Without `PHOENIX_COLLECTOR_ENDPOINT` every call here is a cheap no-op.
"""

from __future__ import annotations

import contextlib
import hashlib
import logging
from collections.abc import Callable, Iterator
from contextvars import ContextVar
from typing import Any

from opentelemetry import context as otel_context
from opentelemetry import trace
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator

log = logging.getLogger(__name__)

# OpenInference attribute names (only the ones used).
SPAN_KIND = "openinference.span.kind"
INPUT_VALUE = "input.value"
OUTPUT_VALUE = "output.value"
LLM_MODEL_NAME = "llm.model_name"
LLM_TOKENS_PROMPT = "llm.token_count.prompt"
LLM_TOKENS_COMPLETION = "llm.token_count.completion"
LLM_TOKENS_TOTAL = "llm.token_count.total"
PROJECT_NAME = "openinference.project.name"

_TRACER_NAME = "tender_worker"
_capture_content = False
_provider: Any = None
_purpose: ContextVar[str] = ContextVar("llm_purpose", default="llm.call")


def configure(
    *,
    endpoint: str | None,
    project: str,
    api_key: str | None,
    capture_content: bool,
    service_version: str,
    worker_id: str,
    exporter_factory: Callable[[], Any] | None = None,
) -> None:
    """Install the provider once at start. A missing endpoint leaves tracing off."""
    global _capture_content, _provider
    _capture_content = capture_content
    _provider = None
    if exporter_factory is None and not endpoint:
        return
    from opentelemetry.sdk.resources import Resource
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import BatchSpanProcessor

    exporter = exporter_factory() if exporter_factory else _otlp_exporter(endpoint or "", api_key)
    provider = TracerProvider(
        resource=Resource.create(
            {
                "service.name": "tender-worker-agent",
                "service.version": service_version,
                "service.instance.id": worker_id,
                PROJECT_NAME: project,
            }
        )
    )
    # A bounded queue and a short export timeout: a dead Phoenix drops spans, it never blocks.
    provider.add_span_processor(
        BatchSpanProcessor(
            exporter,
            max_queue_size=2048,
            schedule_delay_millis=2000,
            export_timeout_millis=5000,
        )
    )
    _provider = provider
    log.info("tracing on", extra={"capture_content": capture_content})


def _otlp_exporter(endpoint: str, api_key: str | None):
    from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter

    url = endpoint.rstrip("/")
    if not url.endswith("/v1/traces"):
        url += "/v1/traces"
    headers = {"authorization": f"Bearer {api_key}"} if api_key else None
    return OTLPSpanExporter(endpoint=url, headers=headers, timeout=5)


def shutdown() -> None:
    """Flush what is queued, within a short time. Never raises."""
    if _provider is not None:
        with contextlib.suppress(Exception):
            _provider.force_flush(timeout_millis=3000)
            _provider.shutdown()


def capture_content() -> bool:
    return _capture_content


def digest(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()


def text_attrs(prefix: str, text: str) -> dict[str, Any]:
    """Hash and length of a text, plus the text itself only when content capture is on."""
    attrs: dict[str, Any] = {f"{prefix}.sha256": digest(text), f"{prefix}.length": len(text)}
    if _capture_content:
        attrs[f"{prefix}.value"] = text
    return attrs


def _context_from(traceparent: str | None):
    if not traceparent:
        return None
    ctx = TraceContextTextMapPropagator().extract({"traceparent": traceparent})
    return ctx if trace.get_current_span(ctx).get_span_context().is_valid else None


@contextlib.contextmanager
def span(
    name: str,
    kind: str = "CHAIN",
    attrs: dict[str, Any] | None = None,
    *,
    traceparent: str | None = None,
) -> Iterator[Any]:
    """A span that is the current one inside the block. Errors are recorded by class only."""
    tracer = (_provider or trace.NoOpTracerProvider()).get_tracer(_TRACER_NAME)
    parent = _context_from(traceparent)
    token = otel_context.attach(parent) if parent is not None else None
    try:
        with tracer.start_as_current_span(
            name,
            attributes={SPAN_KIND: kind, **(attrs or {})},
            record_exception=False,  # an exception message can echo document text
            set_status_on_exception=False,
        ) as sp:
            try:
                yield sp
            except BaseException as exc:
                sp.set_attribute("error.type", type(exc).__name__)
                sp.set_status(trace.Status(trace.StatusCode.ERROR))
                raise
    finally:
        if token is not None:
            otel_context.detach(token)


def set_attributes(attrs: dict[str, Any]) -> None:
    """Add attributes to the current span (a no-op when there is none)."""
    sp = trace.get_current_span()
    if sp.get_span_context().is_valid:
        for k, v in attrs.items():
            sp.set_attribute(k, v)


@contextlib.contextmanager
def purpose(name: str) -> Iterator[None]:
    """Name the model calls made inside the block, for example `llm.extract`."""
    token = _purpose.set(name)
    try:
        yield
    finally:
        _purpose.reset(token)


def current_purpose() -> str:
    return _purpose.get()
