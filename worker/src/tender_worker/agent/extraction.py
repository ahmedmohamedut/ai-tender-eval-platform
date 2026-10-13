"""The requirement extraction graph (AI 4.3), a fixed LangGraph StateGraph.

load scope -> plan windows -> extract (one call per window, in parallel, each grounded and
repaired once) -> merge -> compare -> flag. Plain code decides what is read, whether an answer is
accepted and when a loop ends; the model answers one narrow question at a time.
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import math
import operator
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Annotated, Any, TypedDict
from uuid import UUID

from langgraph.graph import END, START, StateGraph
from langgraph.types import Send

from tender_worker import metrics
from tender_worker.agent.grounding import (
    sanitise_passage,
    suspected_injection,
    verify_quote,
)
from tender_worker.agent.llm import StructuredLlm
from tender_worker.agent.retrieval import PassageRef, PassageSource, ScopeItem
from tender_worker.agent.schemas import Candidate, Candidates, PairJudgement
from tender_worker.prompts import load

log = logging.getLogger(__name__)

ITEM_NAMESPACE = uuid.UUID("c47f2a1e-3b9d-4f0a-8d6e-5a1b7c9e2f40")
CERTAINTY = {"high": 0.9, "medium": 0.7, "low": 0.4}
MAX_JUDGEMENTS = 200


class NoContentInScope(Exception):
    """The chosen sections hold no text (failure code no_content_in_scope)."""


@dataclass(frozen=True)
class ExtractionSettings:
    window_chars: int = 8000
    overlap_passages: int = 2
    dedup_threshold: float = 0.85
    max_repairs: int = 1
    window_concurrency: int = 4


@dataclass
class Grounded:
    text: str
    importance: str
    acceptance_criteria: list[str]
    category_id: str | None
    sources: list[dict]  # document_version_id, passage_id, page_no, quote
    several_obligations: bool
    confidence: float
    window: int
    repaired: bool = False
    injection: bool = False
    flags: list[dict] = field(default_factory=list)
    item_id: str = ""


@dataclass
class Window:
    index: int
    passages: list[PassageRef]


class State(TypedDict, total=False):
    scope: list[ScopeItem]
    categories: list[dict]
    existing: list[dict]
    passages: list[PassageRef]
    windows: list[Window]
    candidates: Annotated[list[Grounded], operator.add]
    counters: Annotated[list[tuple[str, int]], operator.add]
    merged: list[Grounded]
    result: list[Grounded]


def plan_windows(passages: list[PassageRef], window_chars: int, overlap: int) -> list[Window]:
    """Consecutive passages of one document grouped to about `window_chars`, with overlap."""
    windows: list[Window] = []
    current: list[PassageRef] = []
    size = 0
    for p in passages:
        new_doc = bool(current) and current[-1].version_id != p.version_id
        if current and (size + len(p.text) > window_chars or new_doc):
            windows.append(Window(len(windows), current))
            tail = [] if new_doc else current[-overlap:] if overlap > 0 else []
            current = list(tail)
            size = sum(len(x.text) for x in current)
        current.append(p)
        size += len(p.text)
    if current:
        windows.append(Window(len(windows), current))
    return windows


def _passage_block(passages: list[PassageRef]) -> str:
    out = []
    for p in passages:
        header = f" [table row; header: {p.table_header}]" if p.table_header else ""
        body = sanitise_passage(p.text)
        out.append(f'<passage id="{p.id}" page="{p.page_no}"{header}>\n{body}\n</passage>')
    return "\n".join(out)


def _cosine(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b, strict=False))
    na = math.sqrt(sum(x * x for x in a)) or 1.0
    nb = math.sqrt(sum(x * x for x in b)) or 1.0
    return dot / (na * nb)


def item_id(job_id: UUID, text: str, passage_ids: list[str]) -> str:
    key = hashlib.sha256(
        (" ".join(text.lower().split()) + "|" + ",".join(sorted(passage_ids))).encode()
    ).hexdigest()
    return str(uuid.uuid5(ITEM_NAMESPACE, f"{job_id}:{key}"))


class ExtractionGraph:
    def __init__(
        self,
        *,
        llm: StructuredLlm,
        passages: PassageSource,
        embed: Callable[[list[str]], Awaitable[list[list[float]]]],
        tender_id: UUID,
        job_id: UUID,
        settings: ExtractionSettings | None = None,
        on_progress: Callable[[str, int], Awaitable[None]] | None = None,
        cancelled: Callable[[], Awaitable[bool]] | None = None,
    ) -> None:
        self._llm = llm
        self._source = passages
        self._embed = embed
        self._tender = tender_id
        self._job = job_id
        self._cfg = settings or ExtractionSettings()
        self._progress = on_progress
        self._cancelled = cancelled
        self._gate = asyncio.Semaphore(self._cfg.window_concurrency)
        self._windows_done = 0
        self.graph = self._build()

    # ---- graph ---------------------------------------------------------------------------------

    def _build(self):
        g = StateGraph(State)
        g.add_node("load_scope", self._load_scope)
        g.add_node("plan_windows", self._plan)
        g.add_node("extract", self._extract)
        g.add_node("merge", self._merge)
        g.add_node("compare", self._compare)
        g.add_node("flag", self._flag)
        g.add_edge(START, "load_scope")
        g.add_edge("load_scope", "plan_windows")
        g.add_conditional_edges("plan_windows", self._fan_out, ["extract", "merge"])
        g.add_edge("extract", "merge")
        g.add_edge("merge", "compare")
        g.add_edge("compare", "flag")
        g.add_edge("flag", END)
        return g.compile()

    async def run(self, scope: list[ScopeItem], categories: list[dict], existing: list[dict]):
        state = await self.graph.ainvoke(
            {
                "scope": scope,
                "categories": categories,
                "existing": existing,
                "candidates": [],
                "counters": [],
            }
        )
        counters: dict[str, int] = {}
        for name, n in state.get("counters", []):
            counters[name] = counters.get(name, 0) + n
        return state.get("result", []), counters

    async def _tick(self, step: str, percent: int) -> None:
        if self._progress:
            await self._progress(step, percent)

    async def _load_scope(self, state: State) -> dict[str, Any]:
        await self._tick("load_scope", 5)
        passages = await self._source.load(self._tender, state["scope"])
        if not passages:
            raise NoContentInScope()
        return {"passages": passages}

    async def _plan(self, state: State) -> dict[str, Any]:
        await self._tick("plan_windows", 10)
        return {
            "windows": plan_windows(
                state["passages"], self._cfg.window_chars, self._cfg.overlap_passages
            )
        }

    def _fan_out(self, state: State):
        windows = state.get("windows", [])
        if not windows:
            return "merge"
        return [
            Send(
                "extract",
                {
                    "window": w,
                    "categories": state.get("categories", []),
                    "scope": state["scope"],
                    "total": len(windows),
                },
            )
            for w in windows
        ]

    async def _extract(self, state: dict) -> dict[str, Any]:
        window: Window = state["window"]
        categories = state.get("categories", [])
        valid_categories = {c["id"] for c in categories}
        cats = "\n".join(f"{c['id']}: {c['name']}" for c in categories) or "(none)"
        shown = {p.id: p for p in window.passages}
        system = load("extraction.system.txt")
        user = load("extraction.user.txt").format(
            categories=cats, passages=_passage_block(window.passages)
        )
        counters: list[tuple[str, int]] = []
        async with self._gate:
            if self._cancelled and await self._cancelled():
                return {"candidates": [], "counters": []}
            answer = await self._llm.generate(system, user, Candidates)
            problems: list[str] = []
            accepted: list[Grounded] = []
            for cand in answer.items:
                ok, issues = self._ground(cand, shown, valid_categories, window.index)
                if ok is not None:
                    accepted.append(ok)
                else:
                    problems.extend(issues)
            attempts = 0
            while problems and attempts < self._cfg.max_repairs:
                attempts += 1
                repair = load("extraction.repair.txt").format(
                    problems="\n".join(f"- {p}" for p in problems[:20]),
                    passages=_passage_block(window.passages),
                    previous=answer.model_dump_json(),
                )
                fixed = await self._llm.generate(system, repair, Candidates)
                problems = []
                again: list[Grounded] = []
                for cand in fixed.items:
                    ok, issues = self._ground(cand, shown, valid_categories, window.index)
                    if ok is not None:
                        ok.repaired = True
                        ok.confidence = max(0.0, ok.confidence - 0.1)
                        again.append(ok)
                    else:
                        problems.extend(issues)
                # what the repair gives replaces the rejected candidates; what was accepted stays
                known = {a.text for a in accepted}
                accepted.extend(a for a in again if a.text not in known)
            if problems:
                counters.append(("ungrounded_candidate", max(1, len(problems))))
        counters.append(("windows_done", 1))
        self._windows_done += 1
        percent = 10 + int(60 * self._windows_done / max(1, state.get("total", 1)))
        await self._tick("extract", min(70, percent))
        return {"candidates": accepted, "counters": counters}

    def _ground(
        self,
        cand: Candidate,
        shown: dict[str, PassageRef],
        categories: set[str],
        window: int,
    ) -> tuple[Grounded | None, list[str]]:
        issues: list[str] = []
        sources: list[dict] = []
        injected = False
        if not cand.sources:
            issues.append(f'"{cand.text[:60]}": needs at least one source')
        for s in cand.sources:
            passage = shown.get(s.passage_id)
            if passage is None:
                issues.append(f'"{cand.text[:60]}": passage {s.passage_id} was not shown')
                continue
            verified = verify_quote(s.quote, passage.text)
            if verified is None:
                issues.append(
                    f'"{cand.text[:60]}": the quote is not in passage {s.passage_id}'
                    " (copy at least ten characters word for word)"
                )
                continue
            injected = injected or suspected_injection(passage.text)
            sources.append(
                {
                    "document_version_id": passage.version_id,
                    "passage_id": passage.id,
                    "page_no": passage.page_no,
                    "quote": verified.excerpt,  # the stored excerpt is the passage's own text
                }
            )
        if not sources:
            return None, issues or [f'"{cand.text[:60]}": no verified source']
        confidence = CERTAINTY[cand.certainty]
        if injected:
            confidence = min(confidence, 0.4)
        return (
            Grounded(
                text=" ".join(cand.text.split()),
                importance=cand.importance,
                acceptance_criteria=[c.strip() for c in cand.acceptance_criteria if c.strip()],
                category_id=cand.category_id if cand.category_id in categories else None,
                sources=sources,
                several_obligations=cand.several_obligations,
                confidence=confidence,
                window=window,
                injection=injected,
            ),
            [],
        )

    async def _merge(self, state: State) -> dict[str, Any]:
        await self._tick("merge", 75)
        cands = sorted(state.get("candidates", []), key=lambda c: (c.window, c.text))
        if len(cands) < 2:
            return {"merged": cands}
        vectors = await self._embed([c.text for c in cands])
        keep: list[int] = []
        merged_into: dict[int, int] = {}
        for i, c in enumerate(cands):
            target = None
            for j in keep:
                same_passage = {s["passage_id"] for s in c.sources} & {
                    s["passage_id"] for s in cands[j].sources
                }
                if same_passage and _cosine(vectors[i], vectors[j]) >= self._cfg.dedup_threshold:
                    target = j
                    break
            if target is None:
                keep.append(i)
            else:
                merged_into[i] = target
                base = cands[target]
                seen = {s["passage_id"] + s["quote"] for s in base.sources}
                base.sources.extend(
                    s for s in c.sources if s["passage_id"] + s["quote"] not in seen
                )
                base.confidence = max(base.confidence, c.confidence)
        return {"merged": [cands[i] for i in keep]}

    async def _compare(self, state: State) -> dict[str, Any]:
        await self._tick("compare", 82)
        merged = state.get("merged", [])
        for c in merged:
            c.item_id = item_id(self._job, c.text, [s["passage_id"] for s in c.sources])
        existing = state.get("existing", [])
        if not merged:
            return {"result": []}
        texts = [c.text for c in merged] + [e["text"] for e in existing]
        vectors = await self._embed(texts)
        cand_vec = vectors[: len(merged)]
        ex_vec = vectors[len(merged) :]
        pairs: list[tuple[int, str, int]] = []  # candidate index, kind, other index
        for i in range(len(merged)):
            for j in range(i + 1, len(merged)):
                if _cosine(cand_vec[i], cand_vec[j]) >= self._cfg.dedup_threshold:
                    pairs.append((i, "candidate", j))
            for k in range(len(existing)):
                if _cosine(cand_vec[i], ex_vec[k]) >= self._cfg.dedup_threshold:
                    pairs.append((i, "existing", k))
        system = load("compare.system.txt")
        for i, kind, other in pairs[:MAX_JUDGEMENTS]:
            a = merged[i].text
            b = merged[other].text if kind == "candidate" else existing[other]["text"]
            verdict = await self._llm.generate(
                system, f"Requirement A:\n{a}\n\nRequirement B:\n{b}", PairJudgement
            )
            if verdict.verdict == "unrelated":
                continue
            flag_type = "possible_duplicate" if verdict.verdict == "same" else "conflict"
            flag: dict[str, Any] = {"flag_type": flag_type, "detail": verdict.reason or None}
            if kind == "candidate":
                flag["related_item_id"] = merged[other].item_id
            else:
                flag["related_requirement_id"] = existing[other]["id"]
            merged[i].flags.append(flag)
        metrics.inc("extraction_pairs_judged_total", float(min(len(pairs), MAX_JUDGEMENTS)))
        return {"merged": merged}

    async def _flag(self, state: State) -> dict[str, Any]:
        await self._tick("flag", 90)
        out = state.get("merged", [])
        for c in out:
            if c.several_obligations:
                c.flags.append(
                    {"flag_type": "compound", "detail": "States more than one obligation"}
                )
        return {"result": out}


def to_item(c: Grounded) -> dict[str, Any]:
    """One result item (PI 5.6)."""
    return {
        "item_id": c.item_id,
        "text": c.text,
        "category_id": c.category_id,
        "importance": c.importance,
        "acceptance_criteria": c.acceptance_criteria,
        "sources": c.sources,
        "confidence": round(c.confidence, 2),
        "flags": c.flags,
    }
