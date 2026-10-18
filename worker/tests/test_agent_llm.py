"""AI 2.4, 7.2: retries, the breaker's recovery, and an unusable answer versus an outage."""

from __future__ import annotations

import pytest
from pydantic import BaseModel

from tender_worker.agent.llm import Breaker, LlmOutputUnusable, LlmUnavailable, OpenAiCompatible


class Answer(BaseModel):
    ok: bool


class LengthFinishReasonError(Exception):
    """Same name as openai's: the endpoint answered, the answer was cut at the token limit."""


class Reply:
    def __init__(self, content: str) -> None:
        self.content = content


class Chat:
    """Plays a script: an exception is raised, a string is returned as the reply content."""

    def __init__(self, script: list) -> None:
        self.script = script
        self.calls = 0

    def bind(self, **_):
        return self

    async def ainvoke(self, _messages):
        self.calls += 1
        step = self.script[min(self.calls, len(self.script)) - 1]
        if isinstance(step, BaseException):
            raise step
        return Reply(step)


class Clock:
    def __init__(self) -> None:
        self.now = 0.0

    def __call__(self) -> float:
        return self.now


async def no_sleep(_):
    return None


def ticking(clock: Clock):
    async def sleep(seconds: float) -> None:
        clock.now += seconds

    return sleep


def client(chat: Chat, breaker: Breaker, retries: int = 2, sleep=no_sleep) -> OpenAiCompatible:
    return OpenAiCompatible(
        base_url="http://model",
        api_key="k",
        model="m",
        retries=retries,
        breaker=breaker,
        sleep=sleep,
        chat_factory=lambda _model: chat,
    )


def given_up_breaker(clock: Clock) -> Breaker:
    breaker = Breaker(threshold=1, probe_seconds=30, give_up_seconds=600, clock=clock)
    breaker.failure()
    clock.now = 700.0
    assert breaker.gave_up()
    return breaker


async def test_a_breaker_that_gave_up_probes_again_and_closes_on_an_answer():
    clock = Clock()
    breaker = given_up_breaker(clock)
    chat = Chat(['{"ok": true}'])

    result = await client(chat, breaker).generate("s", "u", Answer)

    assert result.ok is True and chat.calls == 1
    assert breaker.open is False and breaker.gave_up() is False


async def test_a_retry_just_after_a_failed_probe_waits_for_the_next_slot_and_recovers():
    clock = Clock()
    breaker = given_up_breaker(clock)
    breaker.allow()  # a probe has just been spent and failed
    breaker.failure()
    chat = Chat(['{"ok": true}'])

    result = await client(chat, breaker, sleep=ticking(clock)).generate("s", "u", Answer)

    assert result.ok is True and chat.calls == 1
    assert clock.now >= 700.0 + 30  # it waited for the probe slot, not refused
    assert breaker.open is False


async def test_a_breaker_that_gave_up_ends_a_call_on_its_first_failed_probe():
    clock = Clock()
    breaker = given_up_breaker(clock)
    chat = Chat([ConnectionError("refused")])

    with pytest.raises(LlmUnavailable) as caught:
        await client(chat, breaker, retries=5, sleep=ticking(clock)).generate("s", "u", Answer)

    assert not isinstance(caught.value, LlmOutputUnusable)
    # one probe, not five: many calls share one probe slot per interval, so retrying here would
    # make a job with many calls take minutes to fail
    assert chat.calls == 1
    assert breaker.gave_up()


async def test_a_breaker_that_is_not_yet_given_up_still_retries_with_backoff():
    clock = Clock()
    breaker = Breaker(threshold=10, probe_seconds=30, give_up_seconds=600, clock=clock)
    chat = Chat([ConnectionError("refused")])

    with pytest.raises(LlmUnavailable):
        await client(chat, breaker, retries=3, sleep=ticking(clock)).generate("s", "u", Answer)

    assert chat.calls == 3 and not breaker.gave_up()


async def test_a_cut_answer_is_unusable_not_an_outage_and_leaves_the_breaker_closed():
    breaker = Breaker(threshold=2)
    chat = Chat([LengthFinishReasonError("length")])

    with pytest.raises(LlmOutputUnusable):
        await client(chat, breaker, retries=3).generate("s", "u", Answer)

    assert chat.calls == 3
    assert breaker.failures == 0 and breaker.open is False


async def test_a_cut_answer_closes_a_breaker_that_was_open():
    clock = Clock()
    breaker = given_up_breaker(clock)
    chat = Chat([LengthFinishReasonError("length")])

    with pytest.raises(LlmOutputUnusable):
        await client(chat, breaker, retries=1).generate("s", "u", Answer)

    assert breaker.open is False


async def test_unreadable_json_is_unusable_after_the_retries():
    breaker = Breaker()
    chat = Chat(["not json"])

    with pytest.raises(LlmOutputUnusable):
        await client(chat, breaker, retries=2).generate("s", "u", Answer)

    assert chat.calls == 2 and breaker.failures == 0


async def test_connection_errors_stay_an_outage_and_count_against_the_breaker():
    breaker = Breaker(threshold=5)
    chat = Chat([ConnectionError("refused")])

    with pytest.raises(LlmUnavailable) as caught:
        await client(chat, breaker, retries=3).generate("s", "u", Answer)

    assert not isinstance(caught.value, LlmOutputUnusable)
    assert chat.calls == 3 and breaker.failures == 3
