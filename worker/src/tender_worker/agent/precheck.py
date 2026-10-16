"""The screening pre-check graph (AI 4.4): does the proposal follow the structure the RFP asks for?

find the format clauses (code only) -> list the expected sections (one call) -> match each one
against the proposal (one call per section, in parallel) -> report. The result is advisory: nothing
reads it to make a decision (UX-SCR-03). Plain code decides what is read and which ids are valid.
"""

from __future__ import annotations

import asyncio
import logging
import operator
import re
import uuid
from collections.abc import Awaitable, Callable
from typing import Annotated, Any, TypedDict
from uuid import UUID

from langgraph.graph import END, START, StateGraph
from langgraph.types import Send

from tender_worker import tracing
from tender_worker.agent.extraction import NoContentInScope, _passage_block
from tender_worker.agent.llm import StructuredLlm
from tender_worker.agent.retrieval import PassageRef, PassageSource, ScopeItem
from tender_worker.agent.schemas import ExpectedSections, SectionMatch
from tender_worker.prompts import load

log = logging.getLogger(__name__)

ITEM_NAMESPACE = uuid.UUID("7b1e9c42-5d3a-4e18-9a6f-2c8d0b4e7a31")
FORMAT_CLAUSE = re.compile(
    r"\b(proposal|response|tender|submission|bid)s?\b.{0,80}\b(must|shall|should|is to|are to)\b"
    r".{0,40}\b(include|contain|comprise|consist|be structured|be organi[sz]ed|follow)\b"
    r"|\b(structure|format|contents?|organi[sz]ation)\s+of\s+(the|your|a)\s+(proposal|response|bid)\b"
    r"|\b(required|mandatory)\s+(sections?|documents?|forms?|annex(?:es)?)\b",
    re.I,
)
HEADING_HINT = re.compile(
    r"\b(proposal (structure|format|content)|submission|instructions? to (vendors?|bidders?)|"
    r"format|contents? of the (proposal|response))\b",
    re.I,
)
MAX_FORMAT_PASSAGES = 12
MAX_CANDIDATES = 6
_WORD = re.compile(r"[a-z0-9]{4,}")


class State(TypedDict, total=False):
    rfp_scope: list[ScopeItem]
    proposal_scope: list[ScopeItem]
    rfp: list[PassageRef]
    proposal: list[PassageRef]
    format_passages: list[PassageRef]
    expected: list[dict]
    findings: Annotated[list[dict], operator.add]
    warnings: list[str]


def item_id(job_id: UUID, name: str) -> str:
    return str(uuid.uuid5(ITEM_NAMESPACE, f"{job_id}:{' '.join(name.lower().split())}"))


def _tokens(text: str) -> set[str]:
    return set(_WORD.findall(text.lower()))


def find_format_passages(rfp: list[PassageRef]) -> list[PassageRef]:
    """Passages that state how a proposal is organised: clauses first, then headings that say so."""
    scored: list[tuple[int, int, PassageRef]] = []
    for i, p in enumerate(rfp):
        score = 0
        if FORMAT_CLAUSE.search(p.text):
            score += 2
        if p.section_path and any(HEADING_HINT.search(t) for t in p.section_path):
            score += 1
        if score:
            scored.append((score, i, p))
    scored.sort(key=lambda t: (-t[0], t[1]))
    top = scored[:MAX_FORMAT_PASSAGES]
    top.sort(key=lambda t: t[1])  # back to document order
    return [p for _, _, p in top]


def candidates_for(expected: dict, proposal: list[PassageRef]) -> list[PassageRef]:
    """Proposal passages that share words with the expected section's name, by heading first."""
    want = _tokens(expected["name"] + " " + expected.get("description", ""))
    name_words = _tokens(expected["name"])
    scored: list[tuple[float, int, PassageRef]] = []
    for i, p in enumerate(proposal):
        head = _tokens(" ".join(p.section_path))
        body = _tokens(p.text[:400])
        score = 3.0 * len(name_words & head) + 1.0 * len(want & head) + 0.5 * len(want & body)
        if score > 0:
            scored.append((score, i, p))
    scored.sort(key=lambda t: (-t[0], t[1]))
    out: list[PassageRef] = []
    seen_sections: set[tuple[str, ...]] = set()
    for _, _, p in scored:
        key = p.section_path or (p.id,)
        if key in seen_sections and len(out) >= 2:
            continue
        seen_sections.add(key)
        out.append(p)
        if len(out) >= MAX_CANDIDATES:
            break
    out.sort(key=lambda p: (p.version_id, p.ordinal))
    return out


def _block_with_path(passages: list[PassageRef]) -> str:
    out = []
    for p in passages:
        path = " > ".join(p.section_path) or "(no heading)"
        body = " ".join(p.text.split())[:600]
        out.append(f'<passage id="{p.id}" page="{p.page_no}" section="{path}">\n{body}\n</passage>')
    return "\n".join(out)


def _pages(passages: list[PassageRef]) -> str:
    pages = sorted({p.page_no for p in passages})
    if not pages:
        return ""
    return f"page {pages[0]}" if pages[0] == pages[-1] else f"pages {pages[0]}-{pages[-1]}"


class PrecheckGraph:
    def __init__(
        self,
        *,
        llm: StructuredLlm,
        source: PassageSource,
        tender_id: UUID,
        job_id: UUID,
        model: str | None = None,
        concurrency: int = 4,
        on_progress: Callable[[str, int], Awaitable[None]] | None = None,
        cancelled: Callable[[], Awaitable[bool]] | None = None,
    ) -> None:
        self._llm = llm
        self._source = source
        self._tender = tender_id
        self._job = job_id
        self._model = model
        self._progress = on_progress
        self._cancelled = cancelled
        self._gate = asyncio.Semaphore(concurrency)
        self._done = 0
        g = StateGraph(State)
        g.add_node("load", self._load)
        g.add_node("find_format", self._find_format)
        g.add_node("list_expected", self._list_expected)
        g.add_node("match", self._match)
        g.add_edge(START, "load")
        g.add_edge("load", "find_format")
        g.add_edge("find_format", "list_expected")
        g.add_conditional_edges("list_expected", self._fan_out, ["match", END])
        g.add_edge("match", END)
        self._graph = g.compile()

    async def run(
        self, rfp_scope: list[ScopeItem], proposal_scope: list[ScopeItem]
    ) -> tuple[list[dict], list[str]]:
        state = await self._graph.ainvoke(
            {
                "rfp_scope": rfp_scope,
                "proposal_scope": proposal_scope,
                "findings": [],
                "warnings": [],
            }
        )
        findings = state.get("findings", [])
        # the order of the RFP's own list, whatever order the parallel branches finished in
        order = {e["name"]: i for i, e in enumerate(state.get("expected", []))}
        findings.sort(key=lambda f: order.get(f["expected_section"], 0))
        return findings, state.get("warnings", [])

    async def _tick(self, step: str, percent: int) -> None:
        if self._progress:
            await self._progress(step, percent)

    @tracing.step("load")
    async def _load(self, state: State) -> dict[str, Any]:
        await self._tick("load_scope", 5)
        rfp = await self._source.load(self._tender, state["rfp_scope"])
        proposal = await self._source.load(self._tender, state["proposal_scope"])
        if not rfp or not proposal:
            raise NoContentInScope()
        return {"rfp": rfp, "proposal": proposal}

    @tracing.step("find_format")
    async def _find_format(self, state: State) -> dict[str, Any]:
        await self._tick("find_format", 15)
        return {"format_passages": find_format_passages(state["rfp"])}

    @tracing.step("list_expected")
    async def _list_expected(self, state: State) -> dict[str, Any]:
        await self._tick("list_expected", 25)
        shown = {p.id: p for p in state["format_passages"]}
        if not shown:
            return {"expected": [], "warnings": ["no_format_found"]}
        with tracing.purpose("llm.expected"):
            answer = await self._llm.generate(
                load("precheck.expected.system.txt"),
                load("precheck.expected.user.txt").format(
                    passages=_passage_block(list(shown.values()))
                ),
                ExpectedSections,
                model=self._model,
            )
        expected: list[dict] = []
        seen: set[str] = set()
        for item in answer.items:
            key = " ".join(item.name.lower().split())
            # an expected section must come from a passage the model was shown (rule 6)
            if item.source_passage_id not in shown or key in seen:
                continue
            seen.add(key)
            expected.append({"name": item.name.strip(), "description": item.description.strip()})
        if not expected:
            return {"expected": [], "warnings": ["no_format_found"]}
        return {"expected": expected}

    def _fan_out(self, state: State):
        expected = state.get("expected", [])
        if not expected:
            return END
        return [
            Send(
                "match",
                {"expected": e, "proposal": state["proposal"], "total": len(expected)},
            )
            for e in expected
        ]

    async def _match(self, state: dict) -> dict[str, Any]:
        # the expected section's name comes from the RFP, so it is content: left out (rule 10)
        with tracing.span("row", "CHAIN", {"tender.step": "match"}):
            out = await self._match_one(state)
            findings = out.get("findings") or []
            if findings:
                tracing.set_attributes(
                    {"tender.found": findings[0]["found"], "tender.row_outcome": "done"}
                )
            return out

    async def _match_one(self, state: dict) -> dict[str, Any]:
        expected: dict = state["expected"]
        cands = candidates_for(expected, state["proposal"])
        shown = {p.id: p for p in cands}
        found = False
        note = "No section of the proposal covers it."
        if cands:
            async with self._gate:
                if self._cancelled and await self._cancelled():
                    return {"findings": []}
                with tracing.purpose("llm.match"):
                    answer = await self._llm.generate(
                        load("precheck.match.system.txt"),
                        load("precheck.match.user.txt").format(
                            name=expected["name"],
                            description=expected.get("description") or expected["name"],
                            passages=_block_with_path(cands),
                        ),
                        SectionMatch,
                        model=self._model,
                    )
            cited = [shown[i] for i in answer.passage_ids if i in shown]
            if answer.found and cited:
                found = True
                title = (
                    answer.matched_title or " > ".join(cited[0].section_path) or "Section"
                ).strip()
                note = f"{title} ({_pages(cited)})"
        self._done += 1
        await self._tick(
            "match", min(90, 30 + int(60 * self._done / max(1, state.get("total", 1))))
        )
        return {
            "findings": [
                {
                    "item_id": item_id(self._job, expected["name"]),
                    "expected_section": expected["name"],
                    "found": found,
                    "note": note,
                }
            ]
        }
