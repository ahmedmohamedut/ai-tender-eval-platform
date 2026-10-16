"""The evaluation graph for one requirement (AI 4.2, 4.5, 4.6, 5.1), a fixed LangGraph StateGraph.

retrieve (hybrid search, rank fusion, neighbours) -> grade -> [verify absence -> grade] ->
validate (+ one repair) -> record. Plain code decides what is read, which ids are valid and whether
a row is accepted; the model grades one requirement at a time against passages it was shown. A row
that fails after its repair is recorded as failed, never as a weaker grade (rule 6).
"""

from __future__ import annotations

import logging
import re
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any, TypedDict
from uuid import UUID

from langgraph.graph import END, START, StateGraph

from tender_worker import metrics, tracing
from tender_worker.agent.extraction import _passage_block
from tender_worker.agent.grounding import suspected_injection, verify_quote
from tender_worker.agent.llm import LlmUnavailable, StructuredLlm
from tender_worker.agent.retrieval import PassageRef
from tender_worker.agent.schemas import GradeDraft, QueryPlan
from tender_worker.agent.search import Hit, Searcher
from tender_worker.prompts import load

log = logging.getLogger(__name__)

ITEM_NAMESPACE = uuid.UUID("2f9d4b61-8a3e-4c57-b1d0-6e7a9c3f5b82")
CERTAINTY = {"high": 0.9, "medium": 0.7, "low": 0.4}
RRF_K = 60
_PAGE = re.compile(r"\bpages?\s+(\d+)(?:\s*(?:-|to|and)\s*(\d+))?", re.I)


@dataclass(frozen=True)
class Requirement:
    id: str
    number: str
    text: str
    acceptance_criteria: str
    importance: str
    category: str | None
    requirement_hash: str


@dataclass(frozen=True)
class EvaluationSettings:
    top_k: int = 8
    context_neighbors: int = 1
    context_max_chars: int = 12000
    max_searches: int = 3
    max_repairs: int = 1


class RowState(TypedDict, total=False):
    req: Requirement
    shown: dict[str, PassageRef]
    order: list[str]
    draft: GradeDraft | None
    problems: list[str]
    repaired: bool
    absence_done: bool
    injection: bool
    failure: str | None
    item: dict[str, Any]


@dataclass
class RowResult:
    item: dict[str, Any]
    injection: bool = False
    repaired: bool = False
    searches: int = 0


def item_id(job_id: UUID, requirement_id: str) -> str:
    return str(uuid.uuid5(ITEM_NAMESPACE, f"{job_id}:{requirement_id}"))


def _rrf(lists: list[list[Hit]]) -> list[Hit]:
    """Reciprocal rank fusion of several ranked lists (the queries of one requirement)."""
    score: dict[str, float] = {}
    keep: dict[str, PassageRef] = {}
    for hits in lists:
        for rank, h in enumerate(hits):
            score[h.passage.id] = score.get(h.passage.id, 0.0) + 1.0 / (RRF_K + rank + 1)
            keep.setdefault(h.passage.id, h.passage)
    return [Hit(keep[i], s) for i, s in sorted(score.items(), key=lambda t: -t[1])]


class EvaluationGraph:
    def __init__(
        self,
        *,
        llm: StructuredLlm,
        search: Searcher,
        embed_query: Callable[[list[str]], Awaitable[list[list[float]]]],
        tender_id: UUID,
        job_id: UUID,
        version_ids: list[str],
        settings: EvaluationSettings,
        model: str | None = None,
    ) -> None:
        self._llm = llm
        self._search = search
        self._embed = embed_query
        self._tender = tender_id
        self._job = job_id
        self._versions = version_ids
        self._cfg = settings
        self._model = model
        g = StateGraph(RowState)
        g.add_node("retrieve", self._retrieve)
        g.add_node("grade", self._grade)
        g.add_node("absence", self._absence)
        g.add_node("validate", self._validate)
        g.add_node("repair", self._repair)
        g.add_node("record", self._record)
        g.add_edge(START, "retrieve")
        g.add_edge("retrieve", "grade")
        g.add_conditional_edges("grade", self._after_grade, ["absence", "validate"])
        g.add_edge("absence", "grade")
        g.add_conditional_edges("validate", self._after_validate, ["repair", "record"])
        g.add_edge("repair", "validate")
        g.add_edge("record", END)
        self._graph = g.compile()

    async def grade_requirement(self, req: Requirement) -> RowResult:
        # ids and outcomes only: the requirement's words and the passages stay out (rule 10)
        with tracing.span(
            "row", "CHAIN", {"tender.step": "grade_requirement", "tender.requirement_id": req.id}
        ):
            row = await self._grade_requirement(req)
            item = row.item
            tracing.set_attributes(
                {
                    "tender.row_outcome": item.get("outcome", ""),
                    "tender.failure_code": item.get("failure_code", ""),
                    "tender.repaired": row.repaired,
                    "tender.injection_suspected": row.injection,
                }
            )
            return row

    async def _grade_requirement(self, req: Requirement) -> RowResult:
        state = await self._graph.ainvoke(
            {
                "req": req,
                "shown": {},
                "order": [],
                "draft": None,
                "problems": [],
                "repaired": False,
                "absence_done": False,
                "injection": False,
                "failure": None,
            }
        )
        return RowResult(
            item=state["item"],
            injection=state.get("injection", False),
            repaired=state.get("repaired", False),
        )

    # ---- nodes ---------------------------------------------------------------------------------

    async def _hits(self, text: str) -> list[Hit]:
        vectors = await self._embed([text])
        return await self._search.hybrid(
            self._tender, self._versions, vectors[0], text, self._cfg.top_k
        )

    @tracing.step("retrieve")
    async def _retrieve(self, state: RowState) -> dict[str, Any]:
        req = state["req"]
        queries = [req.text]
        if req.acceptance_criteria.strip():
            queries.append(req.acceptance_criteria.strip())
        fused = _rrf([await self._hits(q) for q in queries])
        shown: dict[str, PassageRef] = {}
        ranked: list[PassageRef] = []
        for h in fused:
            ranked.append(h.passage)
        # the passages around the best hits, one on each side by default
        for hit in ranked[:3]:
            if self._cfg.context_neighbors > 0:
                for p in await self._search.around(
                    self._tender,
                    self._versions,
                    hit,
                    self._cfg.context_neighbors,
                    self._cfg.context_neighbors,
                ):
                    shown.setdefault(p.id, p)
        for p in ranked:
            shown.setdefault(p.id, p)
        # the lowest-ranked passages are dropped first when the context is too long
        rank = {p.id: i for i, p in enumerate(ranked)}
        total = 0
        kept: dict[str, PassageRef] = {}
        for p in sorted(shown.values(), key=lambda x: rank.get(x.id, len(rank) + x.ordinal)):
            if total + len(p.text) > self._cfg.context_max_chars and kept:
                continue
            kept[p.id] = p
            total += len(p.text)
        order = sorted(kept, key=lambda i: (kept[i].version_id, kept[i].ordinal))
        injection = any(suspected_injection(p.text) for p in kept.values())
        tracing.set_attributes({"tender.hit_count": len(ranked), "tender.shown_count": len(kept)})
        return {"shown": kept, "order": order, "injection": injection}

    def _prompt(self, state: RowState, template: str = "evaluation.user.txt") -> str:
        req = state["req"]
        passages = [state["shown"][i] for i in state["order"]]
        return load(template).format(
            number=req.number,
            importance=req.importance,
            category=req.category or "none",
            text=req.text,
            criteria=req.acceptance_criteria.strip() or "(none stated)",
            passages=_passage_block(passages) if passages else "(no passage was found)",
        )

    @tracing.step("grade")
    async def _grade(self, state: RowState) -> dict[str, Any]:
        try:
            with tracing.purpose("llm.grade"):
                draft = await self._llm.generate(
                    load("evaluation.system.txt"),
                    self._prompt(state),
                    GradeDraft,
                    model=self._model,
                )
        except LlmUnavailable:
            breaker = getattr(self._llm, "breaker", None)
            if breaker is not None and breaker.gave_up():
                raise
            return {"draft": None, "failure": "model_error"}
        return {"draft": draft, "failure": None}

    def _after_grade(self, state: RowState):
        draft = state.get("draft")
        if (
            draft is not None
            and not state.get("absence_done")
            and self._cfg.max_searches > 0
            and (
                draft.grade == "non_compliant"
                and draft.non_compliance_reason != "explicitly_excluded"
                or not draft.citations
            )
            and draft.grade != "fully_compliant"
        ):
            return "absence"
        return "validate"

    @tracing.step("absence")
    async def _absence(self, state: RowState) -> dict[str, Any]:
        """One bounded second look: other wording, searched by code, graded once more."""
        req = state["req"]
        try:
            with tracing.purpose("llm.verify"):
                plan = await self._llm.generate(
                    load("evaluation.absence.system.txt"),
                    load("evaluation.absence.user.txt").format(
                        text=req.text,
                        criteria=req.acceptance_criteria.strip() or "(none stated)",
                    ),
                    QueryPlan,
                    model=self._model,
                )
        except LlmUnavailable:
            breaker = getattr(self._llm, "breaker", None)
            if breaker is not None and breaker.gave_up():
                raise
            # the first draft stands (AI 4.2)
            return {"absence_done": True, "draft": state.get("draft")}
        shown = dict(state["shown"])
        for q in [q.strip() for q in plan.queries if q.strip()][: self._cfg.max_searches]:
            for h in await self._hits(q):
                shown.setdefault(h.passage.id, h.passage)
        total = 0
        kept: dict[str, PassageRef] = {}
        old = set(state["shown"])
        # what was shown stays; new passages are added while the context has room
        for pid in list(state["shown"]) + [i for i in shown if i not in old]:
            p = shown[pid]
            if total + len(p.text) > self._cfg.context_max_chars and kept:
                continue
            kept[pid] = p
            total += len(p.text)
        order = sorted(kept, key=lambda i: (kept[i].version_id, kept[i].ordinal))
        injection = state.get("injection", False) or any(
            suspected_injection(p.text) for p in kept.values()
        )
        return {"shown": kept, "order": order, "absence_done": True, "injection": injection}

    def _check(
        self, draft: GradeDraft, shown: dict[str, PassageRef]
    ) -> tuple[list[dict], list[str], str]:
        """Citations that hold, the problems, and the failure code if it stays this way."""
        problems: list[str] = []
        code = "invalid_output"
        citations: list[dict] = []
        for c in draft.citations:
            p = shown.get(c.passage_id)
            if p is None:
                problems.append(f"passage {c.passage_id} was not shown to you")
                code = "ungrounded"
                continue
            v = verify_quote(c.quote, p.text)
            if v is None:
                problems.append(
                    f"the quote is not in passage {c.passage_id} (copy at least ten characters"
                    " word for word)"
                )
                code = "ungrounded"
                continue
            citations.append(
                {
                    "document_version_id": p.version_id,
                    "passage_id": p.id,
                    "page_no": p.page_no,
                    "excerpt": v.excerpt,
                }
            )
        # completeness (AI 5.1 check 4)
        reason = draft.non_compliance_reason
        if draft.grade == "non_compliant" and reason is None:
            problems.append("a non_compliant grade needs non_compliance_reason")
        if draft.grade != "non_compliant" and reason is not None:
            problems.append("non_compliance_reason is only for non_compliant")
        needs_evidence = draft.grade in ("fully_compliant", "partially_compliant") or (
            draft.grade == "non_compliant" and reason == "explicitly_excluded"
        )
        if needs_evidence and not citations:
            problems.append(f"{draft.grade} needs at least one citation")
            if draft.citations:
                code = "ungrounded"
        if draft.grade == "partially_compliant" and not [
            m for m in draft.missing_elements if m.strip()
        ]:
            problems.append("partially_compliant needs missing_elements")
        # page numbers named in the rationale are pages of passages that are cited (check 5)
        cited_pages = {c["page_no"] for c in citations}
        for m in _PAGE.finditer(draft.rationale + " " + draft.rationale_summary):
            named = {int(m.group(1))} | ({int(m.group(2))} if m.group(2) else set())
            if (
                cited_pages
                and not named & cited_pages
                and not named <= {p.page_no for p in shown.values()}
            ):
                problems.append(f"page {m.group(1)} is not a page of a passage you cited")
        if draft.grade == "not_addressed":
            pass
        return citations, problems, code

    @tracing.step("validate")
    async def _validate(self, state: RowState) -> dict[str, Any]:
        draft = state.get("draft")
        if draft is None:
            return {"problems": ["no answer"], "failure": state.get("failure") or "model_error"}
        _, problems, code = self._check(draft, state["shown"])
        if problems:
            return {"problems": problems, "failure": code}
        return {"problems": [], "failure": None}

    def _after_validate(self, state: RowState):
        if (
            state.get("problems")
            and state.get("draft") is not None
            and not state.get("repaired")
            and self._cfg.max_repairs > 0
        ):
            return "repair"
        return "record"

    @tracing.step("repair")
    async def _repair(self, state: RowState) -> dict[str, Any]:
        req = state["req"]
        passages = [state["shown"][i] for i in state["order"]]
        prompt = load("evaluation.repair.txt").format(
            number=req.number,
            importance=req.importance,
            category=req.category or "none",
            text=req.text,
            criteria=req.acceptance_criteria.strip() or "(none stated)",
            passages=_passage_block(passages) if passages else "(no passage was found)",
            problems="\n".join(f"- {p}" for p in state.get("problems", [])[:12]),
            previous=state["draft"].model_dump_json() if state.get("draft") else "{}",
        )
        try:
            with tracing.purpose("llm.repair"):
                fixed = await self._llm.generate(
                    load("evaluation.system.txt"), prompt, GradeDraft, model=self._model
                )
        except LlmUnavailable:
            breaker = getattr(self._llm, "breaker", None)
            if breaker is not None and breaker.gave_up():
                raise
            return {"repaired": True, "draft": None, "failure": "model_error"}
        return {"repaired": True, "draft": fixed}

    @tracing.step("record")
    async def _record(self, state: RowState) -> dict[str, Any]:
        req = state["req"]
        base = {
            "item_id": item_id(self._job, req.id),
            "requirement_id": req.id,
            "requirement_hash": req.requirement_hash,
        }
        draft = state.get("draft")
        if state.get("problems") or draft is None:
            metrics.inc("evaluation_rows_total", outcome="failed")
            return {
                "item": {
                    **base,
                    "outcome": "failed",
                    "failure_code": state.get("failure") or "invalid_output",
                }
            }
        citations, _, _ = self._check(draft, state["shown"])
        confidence = CERTAINTY[draft.certainty]
        if state.get("repaired"):
            confidence = max(0.0, confidence - 0.1)
        if state.get("injection"):
            confidence = min(confidence, 0.4)
        reason = draft.non_compliance_reason if draft.grade == "non_compliant" else None
        if draft.grade == "non_compliant" and reason == "not_addressed":
            citations = []
        metrics.inc("evaluation_rows_total", outcome="graded")
        return {
            "item": {
                **base,
                "outcome": "graded",
                "grade": draft.grade,
                "non_compliance_reason": reason,
                "rationale_summary": draft.rationale_summary.strip(),
                "rationale": draft.rationale.strip(),
                "missing_elements": [m.strip() for m in draft.missing_elements if m.strip()],
                "confidence": round(confidence, 2),
                "citations": [{"citation_no": i + 1, **c} for i, c in enumerate(citations)],
            }
        }
