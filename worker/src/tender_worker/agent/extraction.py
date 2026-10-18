"""The requirement extraction graph (AI 4.3), a fixed LangGraph StateGraph.

load scope -> plan windows -> extract (one call per chunk of the section text, in parallel, each
grounded and repaired once) -> merge -> compare -> flag. Plain code decides what is read, whether
an answer is accepted and when a loop ends; the model answers one narrow question at a time.

The model reads the user's scope as tagged markdown blocks (`agent/markdown.py`): the heading
path, table rows with their column labels, list items as bullets. It answers quote first, then
the block's tag. Code checks the quote against that block and against the passage the block was
aligned to, which is what the stored citation points at (rule 6).
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import math
import operator
import re
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Annotated, Any, TypedDict
from uuid import UUID

from langgraph.graph import END, START, StateGraph
from langgraph.types import Send

from tender_worker import metrics, tracing
from tender_worker.agent.grounding import sanitise_passage, suspected_injection
from tender_worker.agent.llm import StructuredLlm
from tender_worker.agent.markdown import (
    Chunk,
    MarkdownSource,
    MdBlock,
    candidate_obligations,
    chunk_blocks,
    locate_quote,
)
from tender_worker.agent.retrieval import PassageRef, ScopeItem
from tender_worker.agent.schemas import (
    PairJudgement,
    RequirementCandidate,
    RequirementExtraction,
)
from tender_worker.prompts import load

log = logging.getLogger(__name__)

ITEM_NAMESPACE = uuid.UUID("c47f2a1e-3b9d-4f0a-8d6e-5a1b7c9e2f40")
# A quote checked against its block and passage is as sure as the checks make it: the model no
# longer states its own certainty. A repaired answer is trusted a little less; a suspected
# injection is never trusted (AI 4.6).
CONFIDENCE = 0.85
REPAIRED_CONFIDENCE = 0.65
INJECTION_CONFIDENCE = 0.4
UNDER_EXTRACTION_SHARE = 0.8
MAX_JUDGEMENTS = 200


class NoContentInScope(Exception):
    """The chosen sections hold no text (failure code no_content_in_scope)."""


@dataclass(frozen=True)
class ExtractionSettings:
    chunk_chars: int = 3000
    chunk_lines: int = 25
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
    confidence: float
    window: int
    repaired: bool = False
    injection: bool = False
    flags: list[dict] = field(default_factory=list)
    item_id: str = ""


class State(TypedDict, total=False):
    scope: list[ScopeItem]
    categories: list[dict]
    existing: list[dict]
    blocks: list[MdBlock]
    chunks: list[Chunk]
    candidates: Annotated[list[Grounded], operator.add]
    counters: Annotated[list[tuple[str, int]], operator.add]
    merged: list[Grounded]
    result: list[Grounded]


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


_step = tracing.step


_NUMBER = re.compile(r"\d+(?:[.,]\d+)*")
_NEGATION = re.compile(r"\b(not|no|never|without|cannot|prohibited|excluding|except)\b|n't", re.I)


def _same_facts(a: str, b: str) -> bool:
    """Two statements may be merged only if they hold the same numbers and the same polarity:
    "99.9% availability" and "99.5% availability", or "shall" and "shall not", stay apart."""
    nums = lambda t: {n.replace(",", "") for n in _NUMBER.findall(t)}  # noqa: E731
    return nums(a) == nums(b) and bool(_NEGATION.search(a)) == bool(_NEGATION.search(b))


def _tag(raw: str) -> str:
    return raw.strip().strip("[]() ").upper()


class ExtractionGraph:
    def __init__(
        self,
        *,
        llm: StructuredLlm,
        source: MarkdownSource,
        embed: Callable[[list[str]], Awaitable[list[list[float]]]],
        tender_id: UUID,
        job_id: UUID,
        settings: ExtractionSettings | None = None,
        on_progress: Callable[[str, int], Awaitable[None]] | None = None,
        cancelled: Callable[[], Awaitable[bool]] | None = None,
    ) -> None:
        self._llm = llm
        self._source = source
        self._embed = embed
        self._tender = tender_id
        self._job = job_id
        self._cfg = settings or ExtractionSettings()
        self._progress = on_progress
        self._cancelled = cancelled
        self._gate = asyncio.Semaphore(self._cfg.window_concurrency)
        self._chunks_done = 0
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

    @_step("load_scope")
    async def _load_scope(self, state: State) -> dict[str, Any]:
        await self._tick("load_scope", 5)
        blocks = await self._source.load(self._tender, state["scope"])
        if not any(b.kind != "heading" and b.text.strip() for b in blocks):
            raise NoContentInScope()
        return {"blocks": blocks}

    @_step("plan_windows")
    async def _plan(self, state: State) -> dict[str, Any]:
        await self._tick("plan_windows", 10)
        return {
            "chunks": chunk_blocks(state["blocks"], self._cfg.chunk_chars, self._cfg.chunk_lines)
        }

    def _fan_out(self, state: State):
        chunks = state.get("chunks", [])
        if not chunks:
            return "merge"
        return [
            Send(
                "extract",
                {"chunk": c, "categories": state.get("categories", []), "total": len(chunks)},
            )
            for c in chunks
        ]

    async def _extract(self, state: dict) -> dict[str, Any]:
        chunk: Chunk = state["chunk"]
        with tracing.span(
            "row",
            "CHAIN",
            {
                "tender.step": "extract",
                "tender.window": chunk.index,
                "tender.passage_count": len(chunk.blocks),
            },
        ):
            return await self._extract_chunk(state, chunk)

    async def _extract_chunk(self, state: dict, chunk: Chunk) -> dict[str, Any]:
        categories = state.get("categories", [])
        valid_categories = {c["id"] for c in categories}
        cats = "\n".join(f"{c['id']}: {c['name']}" for c in categories) or "(none)"
        shown = {b.tag: b for b in chunk.blocks}
        if chunk.lead_in is not None:
            shown.setdefault(chunk.lead_in.tag, chunk.lead_in)
        system = load("extraction.system.txt")
        text = chunk.render()
        user = load("extraction.user.txt").format(categories=cats, heading=chunk.label, text=text)
        counters: list[tuple[str, int]] = []
        async with self._gate:
            if self._cancelled and await self._cancelled():
                return {"candidates": [], "counters": []}
            with tracing.purpose("llm.extract"):
                answer = await self._llm.generate(system, user, RequirementExtraction)
            problems: list[str] = []
            accepted: list[Grounded] = []
            recovered = 0
            for cand in answer.requirements:
                ok, issues, by_search = self._ground(cand, shown, valid_categories, chunk.index)
                recovered += by_search
                if ok is not None:
                    accepted.append(ok)
                else:
                    problems.extend(issues)
            rejected = len(problems)
            repaired_ok = 0
            attempts = 0
            while problems and attempts < self._cfg.max_repairs:
                attempts += 1
                repair = load("extraction.repair.txt").format(
                    problems="\n".join(f"- {p}" for p in problems[:20]),
                    heading=chunk.label,
                    text=text,
                    previous=answer.model_dump_json(),
                )
                with tracing.purpose("llm.repair"):
                    fixed = await self._llm.generate(system, repair, RequirementExtraction)
                problems = []
                again: list[Grounded] = []
                for cand in fixed.requirements:
                    ok, issues, by_search = self._ground(cand, shown, valid_categories, chunk.index)
                    recovered += by_search
                    if ok is not None:
                        ok.repaired = True
                        ok.confidence = min(ok.confidence, REPAIRED_CONFIDENCE)
                        again.append(ok)
                    else:
                        problems.extend(issues)
                # what the repair gives replaces the rejected candidates; what was accepted stays
                known = {a.text for a in accepted}
                fresh = [a for a in again if a.text not in known]
                accepted.extend(fresh)
                repaired_ok += len(fresh)
            # a candidate the repair did not bring back is dropped, and counted either way
            if rejected > repaired_ok:
                counters.append(("ungrounded_candidate", rejected - repaired_ok))
            if recovered:
                counters.append(("tag_recovered", recovered))
            # the reference's recall proxy: obligation wording the model left without a requirement
            wanted = len(candidate_obligations(chunk))
            if wanted and len(accepted) < UNDER_EXTRACTION_SHARE * wanted:
                counters.append(("under_extraction", 1))
            # counts only: the problem texts quote the candidate (rule 10)
            tracing.set_attributes(
                {
                    "tender.accepted": len(accepted),
                    "tender.rejected": len(problems),
                    "tender.candidate_obligations": wanted,
                    "tender.repair_attempts": attempts,
                    "tender.row_outcome": "failed" if problems and not accepted else "done",
                }
            )
        counters.append(("windows_done", 1))
        self._chunks_done += 1
        percent = 10 + int(60 * self._chunks_done / max(1, state.get("total", 1)))
        await self._tick("extract", min(70, percent))
        return {"candidates": accepted, "counters": counters}

    def _ground(
        self,
        cand: RequirementCandidate,
        shown: dict[str, MdBlock],
        categories: set[str],
        chunk: int,
    ) -> tuple[Grounded | None, list[str], int]:
        """The candidate with its checked source, or the problems; 1 if the tag was recovered."""
        head = f'"{cand.description[:60]}"'
        block = shown.get(_tag(cand.tag))
        located = locate_quote(block, cand.source_quote) if block is not None else None
        recovered = 0
        if located is None:
            # A wrong or missing tag is not a wrong quote: the quote may occur in exactly one
            # block of what was shown. The checks are the same; only the tag is looked up.
            hits = [
                found
                for b in shown.values()
                if (found := locate_quote(b, cand.source_quote)) is not None
            ]
            if len(hits) == 1:
                located, recovered = hits[0], 1
        if located is None:
            if block is None:
                return None, [f"{head}: tag {cand.tag.strip()[:16]} was not shown"], 0
            if not block.citable:
                return None, [f"{head}: the text of {block.tag} cannot be cited; pick another"], 0
            return (
                None,
                [
                    f"{head}: the quote is not in {block.tag} (copy at least ten characters word"
                    " for word from that line, without the tag or a column label)"
                ],
                0,
            )
        injected = suspected_injection(located.passage.text)
        confidence = INJECTION_CONFIDENCE if injected else CONFIDENCE
        return (
            Grounded(
                text=" ".join(cand.description.split()),
                importance="mandatory" if cand.is_mandatory else "optional",
                acceptance_criteria=[c.strip() for c in cand.acceptance_criteria if c.strip()],
                category_id=cand.category_id if cand.category_id in categories else None,
                sources=[
                    {
                        "document_version_id": located.passage.version_id,
                        "passage_id": located.passage.id,
                        "page_no": located.passage.page_no,
                        "quote": located.excerpt,  # the passage's own text
                    }
                ],
                confidence=confidence,
                window=chunk,
                injection=injected,
            ),
            [],
            recovered,
        )

    @_step("merge")
    async def _merge(self, state: State) -> dict[str, Any]:
        await self._tick("merge", 75)
        cands = sorted(state.get("candidates", []), key=lambda c: (c.window, c.text))
        if len(cands) < 2:
            return {"merged": cands}
        vectors = await self._embed([c.text for c in cands])
        keep: list[int] = []
        for i, c in enumerate(cands):
            target = None
            for j in keep:
                same_passage = {s["passage_id"] for s in c.sources} & {
                    s["passage_id"] for s in cands[j].sources
                }
                if (
                    same_passage
                    and _cosine(vectors[i], vectors[j]) >= self._cfg.dedup_threshold
                    and _same_facts(c.text, cands[j].text)
                ):
                    target = j
                    break
            if target is None:
                keep.append(i)
            else:
                base = cands[target]
                seen = {s["passage_id"] + s["quote"] for s in base.sources}
                base.sources.extend(
                    s for s in c.sources if s["passage_id"] + s["quote"] not in seen
                )
                known = {x.casefold() for x in base.acceptance_criteria}
                base.acceptance_criteria.extend(
                    x for x in c.acceptance_criteria if x.casefold() not in known
                )
                if c.importance == "mandatory":
                    base.importance = "mandatory"
                base.confidence = max(base.confidence, c.confidence)
        return {"merged": [cands[i] for i in keep]}

    @_step("compare")
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
            with tracing.purpose("llm.compare"):
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

    @_step("flag")
    async def _flag(self, state: State) -> dict[str, Any]:
        await self._tick("flag", 90)
        return {"result": state.get("merged", [])}


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
