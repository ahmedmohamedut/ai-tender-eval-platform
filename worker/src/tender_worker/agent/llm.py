"""The language-model client (AI 2.4, 4.1): one place for retries, the shared limit and the breaker.

It talks to an OpenAI-compatible endpoint through langchain-openai. A call is bounded by a timeout,
retried with backoff on 429, 5xx, timeouts and unreadable output, limited by one semaphore for the
whole process, and stopped by a circuit breaker after consecutive failures (the breaker closes
again on one successful probe). Prompts and answers are never logged (rule 10).
"""

from __future__ import annotations

import asyncio
import json
import logging
import random
import time
from collections.abc import Awaitable, Callable
from typing import Any, Protocol, TypeVar

from pydantic import BaseModel, ValidationError

log = logging.getLogger(__name__)
T = TypeVar("T", bound=BaseModel)


class LlmUnavailable(Exception):
    """The endpoint did not answer after retries, or the breaker has been open too long."""


class StructuredLlm(Protocol):
    async def generate(
        self, system: str, user: str, schema: type[T], *, model: str | None = None
    ) -> T:
        """One structured answer, parsed into `schema`; raises LlmUnavailable on an outage."""


class Breaker:
    """Opens after `threshold` consecutive failures; one probe per `probe_seconds` while open."""

    def __init__(
        self,
        threshold: int = 5,
        probe_seconds: float = 30.0,
        give_up_seconds: float = 600.0,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.threshold = threshold
        self.probe_seconds = probe_seconds
        self.give_up_seconds = give_up_seconds
        self._clock = clock
        self.failures = 0
        self.opened_at: float | None = None
        self._last_probe = 0.0

    @property
    def open(self) -> bool:
        return self.opened_at is not None

    def open_seconds(self) -> float:
        return 0.0 if self.opened_at is None else self._clock() - self.opened_at

    def allow(self) -> bool:
        """May a call start now? While open, only one probe per interval."""
        if self.opened_at is None:
            return True
        now = self._clock()
        if now - self._last_probe >= self.probe_seconds:
            self._last_probe = now
            return True
        return False

    def success(self) -> None:
        self.failures = 0
        self.opened_at = None

    def failure(self) -> None:
        self.failures += 1
        if self.failures >= self.threshold and self.opened_at is None:
            self.opened_at = self._clock()
            self._last_probe = self.opened_at

    def gave_up(self) -> bool:
        return self.open and self.open_seconds() >= self.give_up_seconds


class OpenAiCompatible:
    """langchain-openai behind the StructuredLlm protocol."""

    def __init__(
        self,
        *,
        base_url: str,
        api_key: str,
        model: str,
        temperature: float = 0.0,
        max_tokens: int = 4096,
        timeout: float = 120.0,
        max_concurrency: int = 8,
        structured_output: str = "json_schema",
        retries: int = 5,
        backoff_base: float = 1.0,
        backoff_cap: float = 30.0,
        breaker: Breaker | None = None,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
        chat_factory: Callable[[str], Any] | None = None,
    ) -> None:
        self.model = model
        self._mode = structured_output
        self._retries = retries
        self._base = backoff_base
        self._cap = backoff_cap
        self._limit = asyncio.Semaphore(max_concurrency)
        self.breaker = breaker or Breaker()
        self._sleep = sleep
        self._temperature = temperature
        self._max_tokens = max_tokens
        self._timeout = timeout
        self._base_url = base_url
        self._api_key = api_key
        self._chat_factory = chat_factory
        self._chats: dict[str, Any] = {}

    def _chat(self, model: str):
        if model not in self._chats:
            if self._chat_factory is not None:
                self._chats[model] = self._chat_factory(model)
            else:
                from langchain_openai import ChatOpenAI

                self._chats[model] = ChatOpenAI(
                    model=model,
                    base_url=self._base_url,
                    api_key=self._api_key,  # type: ignore[arg-type]
                    temperature=self._temperature,
                    max_tokens=self._max_tokens,  # type: ignore[call-arg]
                    timeout=self._timeout,
                    max_retries=0,  # retries are ours, so the breaker sees every failure
                )
        return self._chats[model]

    async def generate(
        self, system: str, user: str, schema: type[T], *, model: str | None = None
    ) -> T:
        last: BaseException | None = None
        for attempt in range(self._retries):
            if self.breaker.gave_up():
                raise LlmUnavailable("the model endpoint has been unavailable for too long")
            while not self.breaker.allow():
                if self.breaker.gave_up():
                    raise LlmUnavailable("the model endpoint has been unavailable for too long")
                await self._sleep(1.0)
            try:
                async with self._limit:
                    result = await self._call(system, user, schema, model or self.model)
                self.breaker.success()
                return result
            except (ValidationError, ValueError, json.JSONDecodeError) as exc:
                # unreadable output: counts against the call, not against the endpoint
                last = exc
            except Exception as exc:  # noqa: BLE001 - 429, 5xx, timeouts and connection errors
                last = exc
                if type(exc).__name__ in (
                    "LengthFinishReasonError",
                    "ContentFilterFinishReasonError",
                ):
                    pass  # the endpoint answered; the answer was cut or refused: not an outage
                else:
                    self.breaker.failure()
            if attempt + 1 < self._retries:
                delay = min(self._cap, self._base * (2**attempt)) * (0.8 + 0.4 * random.random())
                await self._sleep(delay)
        log.warning("model call gave up", extra={"error": type(last).__name__})
        raise LlmUnavailable("the model endpoint did not give a usable answer") from last

    async def _call(self, system: str, user: str, schema: type[T], model: str) -> T:
        from langchain_core.messages import HumanMessage, SystemMessage

        chat = self._chat(model)
        messages = [SystemMessage(content=system), HumanMessage(content=user)]
        if self._mode == "function_calling":
            structured = chat.with_structured_output(schema, method="function_calling")
            out = await structured.ainvoke(messages)
            return out if isinstance(out, schema) else schema.model_validate(out)
        bound = chat.bind(
            response_format={
                "type": "json_schema",
                "json_schema": {
                    "name": schema.__name__,
                    "schema": schema.model_json_schema(),
                    "strict": False,
                },
            }
        )
        reply = await bound.ainvoke(messages)
        content = reply.content if isinstance(reply.content, str) else json.dumps(reply.content)
        return schema.model_validate_json(_json_text(content))


def _json_text(content: str) -> str:
    """The JSON object in a reply, tolerating a code fence around it."""
    text = content.strip()
    if text.startswith("```"):
        text = text.split("\n", 1)[1] if "\n" in text else text
        text = text.rsplit("```", 1)[0]
    start, end = text.find("{"), text.rfind("}")
    return text[start : end + 1] if start >= 0 and end > start else text
