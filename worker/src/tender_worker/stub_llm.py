"""A local OpenAI-compatible chat endpoint for tests, CI and development (AGENTS rule 15).

It is a rule-based extractor, not a model: it reads the passages in the prompt and proposes every
sentence that carries an obligation word, quoting the sentence verbatim. It answers pair
comparisons by word overlap. A control endpoint switches in faults so the repair, retry and
breaker paths can be driven: `POST /control {"mode": "ok" | "bad_quote" | "fail", "status": 503}`.
Run with `python -m tender_worker.stub_llm`.
"""

from __future__ import annotations

import asyncio
import json
import os
import re
import time
import uuid

from aiohttp import web

_PASSAGE = re.compile(r'<passage id="([^"]+)"[^>]*>\n?(.*?)\n?</passage>', re.S)
_CATEGORY = re.compile(r"^([0-9a-f-]{36}): (.+)$", re.M)
_SENTENCE = re.compile(r"(?<=[.!?])\s+")
_OBLIGATION = re.compile(r"\b(shall|must|is required to|are required to|should|may)\b", re.I)
_MANDATORY = re.compile(r"\b(shall|must|required)\b", re.I)
_WORD = re.compile(r"[a-z0-9]+")


def _words(text: str) -> set[str]:
    return set(_WORD.findall(text.lower()))


def extract(user: str, bad_quote: bool = False) -> dict:
    categories = [(m.group(1), m.group(2).lower()) for m in _CATEGORY.finditer(user)]
    items: list[dict] = []
    seen: set[str] = set()
    for pid, text in _PASSAGE.findall(user):
        for sentence in _SENTENCE.split(" ".join(text.split())):
            sentence = sentence.strip()
            if len(sentence) < 15 or not _OBLIGATION.search(sentence) or sentence in seen:
                continue
            seen.add(sentence)
            category = None
            low = sentence.lower()
            for cid, name in categories:
                if any(w[:6] in low for w in _words(name) if len(w) > 3):
                    category = cid
                    break
            quote = sentence if len(sentence) <= 160 else sentence[:160]
            if bad_quote:
                quote = "this sentence does not appear anywhere in the document"
            items.append(
                {
                    "text": sentence,
                    "importance": "mandatory" if _MANDATORY.search(sentence) else "optional",
                    "acceptance_criteria": [],
                    "category_id": category,
                    "sources": [{"passage_id": pid, "quote": quote}],
                    "several_obligations": len(re.findall(r"\band\b", sentence)) >= 2
                    and len(_OBLIGATION.findall(sentence)) >= 2,
                    "certainty": "high" if _MANDATORY.search(sentence) else "medium",
                }
            )
    return {"items": items}


def judge(user: str) -> dict:
    a = re.search(r"Requirement A:\n(.*?)\n\nRequirement B:\n(.*)", user, re.S)
    if not a:
        return {"verdict": "unrelated", "reason": ""}
    wa, wb = _words(a.group(1)), _words(a.group(2))
    overlap = len(wa & wb) / max(1, len(wa | wb))
    nums_a = set(re.findall(r"\d+(?:\.\d+)?", a.group(1)))
    nums_b = set(re.findall(r"\d+(?:\.\d+)?", a.group(2)))
    if overlap >= 0.7:
        if nums_a != nums_b:
            return {"verdict": "conflicts", "reason": "Same subject with different numbers."}
        return {"verdict": "same", "reason": "The wording is almost identical."}
    return {"verdict": "unrelated", "reason": ""}


_EXPECT = re.compile(
    r"(?:proposal|response|bid|tender)s?\s+(?:must|shall)\s+(?:include|contain|comprise)"
    r"[^:]{0,60}:\s*(.+)",
    re.I | re.S,
)


def expected_sections(user: str) -> dict:
    items: list[dict] = []
    seen: set[str] = set()
    for pid, text in _PASSAGE.findall(user):
        m = _EXPECT.search(" ".join(text.split()))
        if not m:
            continue
        for part in re.split(r",|;|\band\b", m.group(1).rstrip(".")):
            name = part.strip(" .")
            if len(name) < 3 or name.lower() in seen:
                continue
            seen.add(name.lower())
            items.append({"name": name, "description": "", "source_passage_id": pid})
    return {"items": items}


def section_match(user: str) -> dict:
    name = re.search(r"Expected section: (.+)", user)
    wanted = _words(name.group(1)) if name else set()
    wanted = {w for w in wanted if len(w) > 3}
    best: tuple[int, str, str] | None = None
    for m in re.finditer(r'<passage id="([^"]+)"[^>]*section="([^"]*)"[^>]*>', user):
        pid, path = m.group(1), m.group(2)
        score = len(wanted & _words(path))
        if score and (best is None or score > best[0]):
            best = (score, pid, path.split(" > ")[-1])
    if best is None:
        return {"found": False, "matched_title": None, "passage_ids": []}
    return {"found": True, "matched_title": best[2], "passage_ids": [best[1]]}


_REQ = re.compile(r"Requirement [^\n]*:\n(.*?)\n\nAcceptance criteria:", re.S)
_EXCLUDE = re.compile(
    r"\b(not (?:offer|provide|support)|do(?:es)? not|cannot|will not|excluded)\b", re.I
)


def grade_draft(user: str) -> dict:
    """A rule-based grade: word overlap between the requirement and the best passage sentence."""
    m = _REQ.search(user)
    req = m.group(1).strip() if m else ""
    filler = {"shall", "must", "should", "will", "supplier", "vendor", "provide", "platform"}
    wanted = {w for w in _words(req) if len(w) > 3 and w not in filler}
    best: tuple[float, str, str] | None = None
    for pid, text in _PASSAGE.findall(user):
        for sentence in _SENTENCE.split(" ".join(text.split())):
            sentence = sentence.strip()
            if len(sentence) < 15:
                continue
            overlap = len(wanted & _words(sentence)) / max(1, len(wanted))
            if best is None or overlap > best[0]:
                best = (overlap, pid, sentence)
    if best is None or best[0] < 0.34:
        return {
            "grade": "non_compliant",
            "non_compliance_reason": "not_addressed",
            "rationale_summary": "The proposal does not address this requirement.",
            "rationale": "No passage of the proposal covers the requirement.",
            "missing_elements": [],
            "citations": [],
            "certainty": "medium",
        }
    overlap, pid, sentence = best
    quote = sentence if len(sentence) <= 160 else sentence[:160]
    if _EXCLUDE.search(sentence):
        return {
            "grade": "non_compliant",
            "non_compliance_reason": "explicitly_excluded",
            "rationale_summary": "The proposal states it does not meet this requirement.",
            "rationale": "A passage says the vendor does not provide it.",
            "missing_elements": [],
            "citations": [{"passage_id": pid, "quote": quote}],
            "certainty": "high",
        }
    if overlap >= 0.67:
        return {
            "grade": "fully_compliant",
            "non_compliance_reason": None,
            "rationale_summary": "The proposal describes how the requirement is met.",
            "rationale": "A passage covers the requirement in detail.",
            "missing_elements": [],
            "citations": [{"passage_id": pid, "quote": quote}],
            "certainty": "high" if overlap >= 0.8 else "medium",
        }
    return {
        "grade": "partially_compliant",
        "non_compliance_reason": None,
        "rationale_summary": "The proposal covers part of the requirement.",
        "rationale": "A passage covers some of the requirement but not all of it.",
        "missing_elements": ["Details of the remaining part of the requirement"],
        "citations": [{"passage_id": pid, "quote": quote}],
        "certainty": "medium",
    }


def query_plan(user: str) -> dict:
    m = re.search(r"Requirement:\n(.*?)\n\nAcceptance criteria:", user, re.S)
    words = [w for w in _WORD.findall((m.group(1) if m else "").lower()) if len(w) > 4]
    return {"queries": [" ".join(words[i : i + 3]) for i in range(0, min(len(words), 6), 3)]}


class Control:
    def __init__(self) -> None:
        self.mode = os.environ.get("STUB_LLM_MODE", "ok")
        self.status = int(os.environ.get("STUB_LLM_FAIL_STATUS", "503"))
        self.delay_ms = int(os.environ.get("STUB_LLM_DELAY_MS", "0"))
        self.calls = 0


def app() -> web.Application:
    control = Control()
    a = web.Application()

    async def chat(request: web.Request) -> web.Response:
        control.calls += 1
        body = await request.json()
        if control.delay_ms:
            await asyncio.sleep(control.delay_ms / 1000)
        if control.mode == "fail":
            return web.json_response({"error": {"message": "stub outage"}}, status=control.status)
        messages = body.get("messages", [])
        user = next((m["content"] for m in reversed(messages) if m["role"] == "user"), "")
        schema_name = (
            body.get("response_format", {}).get("json_schema", {}).get("name", "")
            if isinstance(body.get("response_format"), dict)
            else ""
        )
        tools = body.get("tools") or []
        tool_names = [t.get("function", {}).get("name") for t in (body.get("tools") or [])]
        wants = {schema_name, *tool_names}
        wants_pair = (
            "Requirement A:" in user
            or schema_name == "PairJudgement"
            or any(t.get("function", {}).get("name") == "PairJudgement" for t in tools)
        )
        if "GradeDraft" in wants:
            payload = grade_draft(user)
        elif "QueryPlan" in wants:
            payload = query_plan(user)
        elif "ExpectedSections" in wants:
            payload = expected_sections(user)
        elif "SectionMatch" in wants:
            payload = section_match(user)
        elif wants_pair:
            payload = judge(user)
        else:
            payload = extract(user, control.mode == "bad_quote")
        content = json.dumps(payload)
        message: dict = {"role": "assistant", "content": content}
        if tools:
            name = tools[0]["function"]["name"]
            message = {
                "role": "assistant",
                "content": None,
                "tool_calls": [
                    {
                        "id": "call_" + uuid.uuid4().hex[:8],
                        "type": "function",
                        "function": {"name": name, "arguments": content},
                    }
                ],
            }
        return web.json_response(
            {
                "id": "chatcmpl-" + uuid.uuid4().hex[:12],
                "object": "chat.completion",
                "created": int(time.time()),
                "model": body.get("model", "stub-llm"),
                "choices": [
                    {
                        "index": 0,
                        "message": message,
                        "finish_reason": "tool_calls" if tools else "stop",
                    }
                ],
                "usage": {
                    "prompt_tokens": len(user) // 4,
                    "completion_tokens": len(content) // 4,
                    "total_tokens": (len(user) + len(content)) // 4,
                },
            }
        )

    async def set_control(request: web.Request) -> web.Response:
        data = await request.json()
        control.mode = data.get("mode", control.mode)
        control.status = int(data.get("status", control.status))
        control.delay_ms = int(data.get("delay_ms", control.delay_ms))
        return web.json_response(
            {"mode": control.mode, "status": control.status, "delay_ms": control.delay_ms}
        )

    async def models(_: web.Request) -> web.Response:
        return web.json_response(
            {"object": "list", "data": [{"id": "stub-llm", "object": "model"}]}
        )

    async def health(_: web.Request) -> web.Response:
        return web.json_response({"status": "ok", "mode": control.mode, "calls": control.calls})

    a.router.add_post("/v1/chat/completions", chat)
    a.router.add_get("/v1/models", models)
    a.router.add_post("/control", set_control)
    a.router.add_get("/health", health)
    return a


if __name__ == "__main__":
    web.run_app(app(), port=int(os.environ.get("PORT", "8000")), print=None)
