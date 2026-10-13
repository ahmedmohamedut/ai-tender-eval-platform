"""The stub model: deterministic, verbatim quotes, switchable faults."""

import pytest
from aiohttp.test_utils import TestClient, TestServer

from tender_worker import stub_llm
from tender_worker.agent.grounding import verify_quote

PASSAGES = (
    "Categories (id: name):\n0193a7a0-0000-7000-8000-0000000000aa: Encryption\n\nPassages:\n"
    '<passage id="p1" page="1">\nThe supplier shall encrypt all data at rest. '
    "Background text that carries no obligation at all here.\n</passage>\n"
    '<passage id="p2" page="2">\nSupport hours should cover the working week.\n</passage>'
)


def test_extract_proposes_obligation_sentences_with_verbatim_quotes():
    out = stub_llm.extract(PASSAGES)
    texts = [i["text"] for i in out["items"]]
    assert texts == [
        "The supplier shall encrypt all data at rest.",
        "Support hours should cover the working week.",
    ]
    first, second = out["items"]
    assert first["importance"] == "mandatory" and second["importance"] == "optional"
    assert first["category_id"] == "0193a7a0-0000-7000-8000-0000000000aa"
    assert verify_quote(
        first["sources"][0]["quote"], "The supplier shall encrypt all data at rest."
    )


def test_judge_by_overlap_and_numbers():
    same = (
        "Requirement A:\nThe supplier shall encrypt data at rest.\n\n"
        "Requirement B:\nThe supplier shall encrypt data at rest always."
    )
    assert stub_llm.judge(same)["verdict"] == "same"
    conflict = (
        "Requirement A:\nAvailability shall be 99.9 percent monthly.\n\n"
        "Requirement B:\nAvailability shall be 99.5 percent monthly."
    )
    assert stub_llm.judge(conflict)["verdict"] == "conflicts"
    other = "Requirement A:\nProvide training.\n\nRequirement B:\nStore data in the EU."
    assert stub_llm.judge(other)["verdict"] == "unrelated"


@pytest.fixture
async def client():
    server = TestServer(stub_llm.app())
    c = TestClient(server)
    await c.start_server()
    yield c
    await c.close()


async def test_chat_modes_and_faults(client):
    body = {"model": "m", "messages": [{"role": "user", "content": PASSAGES}]}
    ok = await (await client.post("/v1/chat/completions", json=body)).json()
    assert "items" in ok["choices"][0]["message"]["content"]

    await client.post("/control", json={"mode": "bad_quote"})
    bad = await (await client.post("/v1/chat/completions", json=body)).json()
    assert "does not appear anywhere" in bad["choices"][0]["message"]["content"]

    await client.post("/control", json={"mode": "fail", "status": 429})
    r = await client.post("/v1/chat/completions", json=body)
    assert r.status == 429

    await client.post("/control", json={"mode": "ok"})
    tools = dict(body, tools=[{"type": "function", "function": {"name": "Candidates"}}])
    t = await (await client.post("/v1/chat/completions", json=tools)).json()
    assert t["choices"][0]["message"]["tool_calls"][0]["function"]["name"] == "Candidates"
