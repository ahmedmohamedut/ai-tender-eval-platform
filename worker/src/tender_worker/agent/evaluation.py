"""The evaluation graph (AI 4.2, 4.5, 4.6, 5.1), a fixed LangGraph StateGraph.

retrieve (hybrid search, weighted rank fusion, neighbours) -> grade (several requirements of one
proposal in one call) -> check (every criterion in code) -> [repair each failed row alone] ->
[verify absence -> grade again] -> record. Plain code decides what is read, which tags are valid,
whether a quote is real and what the overall grade is; the model judges each acceptance criterion
against excerpts it was shown and quotes its evidence. A row that fails after its repair is
recorded as failed, never as a weaker grade (rule 6).
"""

from __future__ import annotations

import asyncio
import logging
import re
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any, TypedDict
from uuid import UUID

from langgraph.graph import END, START, StateGraph

from tender_worker import metrics, tracing
from tender_worker.agent.grounding import sanitise_passage, suspected_injection, verify_quote
from tender_worker.agent.llm import LlmUnavailable, StructuredLlm
from tender_worker.agent.retrieval import PassageRef
from tender_worker.agent.schemas import BatchGrade, QueryPlan, RequirementGrade
from tender_worker.agent.search import Hit, Searcher
from tender_worker.prompts import load

log = logging.getLogger(__name__)

ITEM_NAMESPACE = uuid.UUID("2f9d4b61-8a3e-4c57-b1d0-6e7a9c3f5b82")
CONFIDENCE = {"High": 0.9, "Medium": 0.7, "Low": 0.4}
RRF_K = 60
MAX_CITATIONS = 4
MAX_MISSING = 8
_PAGE = re.compile(r"\bpages?\s+(\d+)(?:\s*(?:-|to|and)\s*(\d+))?", re.I)
_BULLET = re.compile(r"^[\s•*\-]+|^\d+[.)]\s+")


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
    context_max_chars: int = 9000
    max_searches: int = 3
    max_repairs: int = 1
    batch_size: int = 4
    criteria_queries: int = 5


def parse_criteria(req: Requirement) -> list[str]:
    """The acceptance criteria as a list; the requirement itself when none are stated."""
    items = [_BULLET.sub("", line).strip() for line in req.acceptance_criteria.splitlines()]
    return [i for i in items if i] or [req.text.strip()]


@dataclass
class Ctx:
    """What one requirement is graded against in this run."""

    req: Requirement
    label: str
    criteria: list[str]
    shown: dict[str, PassageRef] = field(default_factory=dict)
    order: list[str] = field(default_factory=list)
    injection: bool = False

    @property
    def tags(self) -> dict[str, str]:
        """Excerpt tag to passage id. Numbered in document order, so a changed set renumbers."""
        return {f"P{n}": pid for n, pid in enumerate(self.order, 1)}

    def tag_of(self, pid: str) -> str:
        return f"P{self.order.index(pid) + 1}"

    def excerpts(self) -> str:
        blocks = []
        for pid in self.order:
            p = self.shown[pid]
            where = " > ".join(p.section_path) or "document"
            kind = (
                f", table row, columns: {p.table_header}"
                if p.table_header and p.table_header != p.text
                else ""
            )
            blocks.append(
                f"[{self.tag_of(pid)}] {where} (p.{p.page_no}{kind})\n{sanitise_passage(p.text)}"
            )
        return "\n\n".join(blocks) or "(no excerpt was found)"

    def block(self) -> str:
        return load("evaluation.requirement.txt").format(
            req_id=self.label,
            importance=self.req.importance,
            category=self.req.category or "none",
            text=self.req.text,
            criteria="\n".join(f"{n}. {c}" for n, c in enumerate(self.criteria, 1)),
            excerpts=self.excerpts(),
        )


@dataclass
class Verdict:
    results: list[str]
    citations: list[dict]
    grade: str
    reason: str | None
    missing: list[str]
    confidence: str
    justification: str


def rollup(results: list[str]) -> tuple[str, str | None]:
    """The grade from the criteria results, decided by code (the reference's roll-up)."""
    s = set(results)
    if s == {"Met"}:
        return "fully_compliant", None
    if s <= {"No Evidence"}:
        return "non_compliant", "not_addressed"
    if "Met" not in s and "Partially Met" not in s:
        return "non_compliant", "explicitly_excluded"
    return "partially_compliant", None


class BatchState(TypedDict, total=False):
    ctxs: dict[str, Ctx]
    drafts: dict[str, RequirementGrade | None]
    verdicts: dict[str, Verdict | None]
    problems: dict[str, list[str]]
    failure: dict[str, str | None]
    repaired: set[str]
    absence_done: set[str]
    items: dict[str, dict[str, Any]]


@dataclass
class RowResult:
    item: dict[str, Any]
    injection: bool = False
    repaired: bool = False
    searches: int = 0


def item_id(job_id: UUID, requirement_id: str) -> str:
    return str(uuid.uuid5(ITEM_NAMESPACE, f"{job_id}:{requirement_id}"))


def _rrf(lists: list[list[Hit]], weights: list[float] | None = None) -> list[Hit]:
    """Weighted reciprocal rank fusion of the ranked lists of one requirement's queries."""
    weights = weights or [1.0] * len(lists)
    score: dict[str, float] = {}
    keep: dict[str, PassageRef] = {}
    for hits, w in zip(lists, weights, strict=False):
        for rank, h in enumerate(hits):
            score[h.passage.id] = score.get(h.passage.id, 0.0) + w / (RRF_K + rank + 1)
            keep.setdefault(h.passage.id, h.passage)
    return [Hit(keep[i], s) for i, s in sorted(score.items(), key=lambda t: -t[1])]


def _tag(raw: str) -> str:
    return raw.strip().strip("[]() ").upper()


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
        g = StateGraph(BatchState)
        g.add_node("retrieve", self._retrieve)
        g.add_node("grade", self._grade)
        g.add_node("check", self._check_all)
        g.add_node("repair", self._repair)
        g.add_node("absence", self._absence)
        g.add_node("record", self._record)
        g.add_edge(START, "retrieve")
        g.add_edge("retrieve", "grade")
        g.add_edge("grade", "check")
        g.add_conditional_edges("check", self._after_check, ["repair", "absence"])
        g.add_edge("repair", "absence")
        g.add_edge("absence", "record")
        g.add_edge("record", END)
        self._graph = g.compile()

    # ---- entry points --------------------------------------------------------------------------

    async def grade_requirement(self, req: Requirement) -> RowResult:
        return (await self.grade_batch([req]))[0]

    async def grade_batch(self, reqs: list[Requirement]) -> list[RowResult]:
        """Grade requirements of one proposal in one call; results come back in input order."""
        ctxs: dict[str, Ctx] = {}
        seen: set[str] = set()
        for r in reqs:
            label = r.number.strip() or r.id[:8]
            if label in seen:
                label = f"{label}-{r.id[:4]}"
            seen.add(label)
            ctxs[r.id] = Ctx(r, label, parse_criteria(r))
        # ids and outcomes only: the requirement's words and the passages stay out (rule 10)
        with tracing.span(
            "batch", "CHAIN", {"tender.step": "grade_batch", "tender.requirement_count": len(reqs)}
        ):
            state = await self._graph.ainvoke(
                {
                    "ctxs": ctxs,
                    "drafts": {},
                    "verdicts": {},
                    "problems": {},
                    "failure": {},
                    "repaired": set(),
                    "absence_done": set(),
                    "items": {},
                }
            )
            out = [
                RowResult(
                    item=state["items"][r.id],
                    injection=state["ctxs"][r.id].injection,
                    repaired=r.id in state["repaired"],
                )
                for r in reqs
            ]
            for r, row in zip(reqs, out, strict=True):
                # one span per requirement so a row can be found by its id in the trace
                with tracing.span(
                    "row", "CHAIN", {"tender.step": "record", "tender.requirement_id": r.id}
                ):
                    tracing.set_attributes(
                        {
                            "tender.row_outcome": row.item.get("outcome", ""),
                            "tender.failure_code": row.item.get("failure_code", ""),
                            "tender.repaired": row.repaired,
                            "tender.injection_suspected": row.injection,
                        }
                    )
            tracing.set_attributes(
                {
                    "tender.graded": sum(1 for o in out if o.item.get("outcome") == "graded"),
                    "tender.failed": sum(1 for o in out if o.item.get("outcome") == "failed"),
                    "tender.repaired": sum(1 for o in out if o.repaired),
                    "tender.injection_suspected": sum(1 for o in out if o.injection),
                }
            )
        return out

    # ---- retrieval -----------------------------------------------------------------------------

    async def _hits(self, text: str) -> list[Hit]:
        vectors = await self._embed([text])
        return await self._search.hybrid(
            self._tender, self._versions, vectors[0], text, self._cfg.top_k
        )

    def _fit(self, order_of_rank: list[PassageRef], extra: dict[str, PassageRef], ctx: Ctx) -> None:
        """Fill the context: lowest-ranked passages are dropped first when it is too long."""
        pool = dict(extra)
        for p in order_of_rank:
            pool.setdefault(p.id, p)
        rank = {p.id: i for i, p in enumerate(order_of_rank)}
        total = 0
        kept: dict[str, PassageRef] = {}
        for p in sorted(pool.values(), key=lambda x: rank.get(x.id, len(rank) + x.ordinal)):
            if total + len(p.text) > self._cfg.context_max_chars and kept:
                continue
            kept[p.id] = p
            total += len(p.text)
        ctx.shown = kept
        ctx.order = sorted(kept, key=lambda i: (kept[i].version_id, kept[i].ordinal))
        ctx.injection = ctx.injection or any(suspected_injection(p.text) for p in kept.values())

    async def _retrieve_one(self, ctx: Ctx) -> None:
        req = ctx.req
        queries = [req.text]
        weights = [2.0]
        if len(ctx.criteria) > 1:
            queries += ctx.criteria[: self._cfg.criteria_queries]
            weights += [1.0] * len(queries[1:])
        fused = _rrf([await self._hits(q) for q in queries], weights)
        ranked = [h.passage for h in fused]
        around: dict[str, PassageRef] = {}
        if self._cfg.context_neighbors > 0:
            for hit in ranked[:3]:
                for p in await self._search.around(
                    self._tender,
                    self._versions,
                    hit,
                    self._cfg.context_neighbors,
                    self._cfg.context_neighbors,
                ):
                    around.setdefault(p.id, p)
        self._fit(ranked, around, ctx)

    @tracing.step("retrieve")
    async def _retrieve(self, state: BatchState) -> dict[str, Any]:
        ctxs = state["ctxs"]
        await asyncio.gather(*(self._retrieve_one(c) for c in ctxs.values()))
        tracing.set_attributes({"tender.shown_count": sum(len(c.order) for c in ctxs.values())})
        return {"ctxs": ctxs}

    # ---- grading -------------------------------------------------------------------------------

    def _breaker_gave_up(self) -> bool:
        breaker = getattr(self._llm, "breaker", None)
        return breaker is not None and breaker.gave_up()

    async def _ask(
        self, ctxs: list[Ctx], purpose: str, template: str | None = None, **extra: str
    ) -> BatchGrade | None:
        """One call for these requirements; None when the model did not answer usably."""
        blocks = "\n\n---\n\n".join(c.block() for c in ctxs)
        if template is None:
            user = load("evaluation.user.txt").format(count=len(ctxs), batch=blocks)
        else:
            user = load(template).format(requirement=blocks, **extra)
        try:
            with tracing.purpose(purpose):
                return await self._llm.generate(
                    load("evaluation.system.txt"), user, BatchGrade, model=self._model
                )
        except LlmUnavailable:
            if self._breaker_gave_up():
                raise
            return None

    @staticmethod
    def _by_label(answer: BatchGrade | None, ctxs: list[Ctx]) -> dict[str, RequirementGrade]:
        if answer is None:
            return {}
        wanted = {c.label.casefold(): c.req.id for c in ctxs}
        out: dict[str, RequirementGrade] = {}
        for g in answer.evaluations:
            rid = wanted.get(g.req_id.strip().casefold())
            if rid is not None:
                out.setdefault(rid, g)
        if len(ctxs) == 1 and not out and len(answer.evaluations) == 1:
            out[ctxs[0].req.id] = answer.evaluations[0]  # one asked, one answered
        return out

    @tracing.step("grade")
    async def _grade(self, state: BatchState) -> dict[str, Any]:
        ctxs = list(state["ctxs"].values())
        answer = await self._ask(ctxs, "llm.grade")
        got = self._by_label(answer, ctxs)
        drafts = {c.req.id: got.get(c.req.id) for c in ctxs}
        failure = {c.req.id: None if drafts[c.req.id] else "model_error" for c in ctxs}
        return {"drafts": drafts, "failure": failure}

    # ---- checks --------------------------------------------------------------------------------

    def _assess(self, ctx: Ctx, grade: RequirementGrade) -> tuple[Verdict | None, list[str], str]:
        """The verdict if every criterion holds, else the problems and the failure code."""
        problems: list[str] = []
        code = "invalid_output"
        tags = ctx.tags
        n = len(ctx.criteria)
        checks = {c.criterion_no: c for c in grade.criteria_checks if 1 <= c.criterion_no <= n}
        results: list[str] = []
        cites: list[dict] = []
        for k in range(1, n + 1):
            c = checks.get(k)
            if c is None:
                problems.append(f"criterion {k} was not answered")
                continue
            results.append(c.result)
            if c.result == "No Evidence":
                continue
            quote = c.evidence_quote.strip()
            found: tuple[str, Any] | None = None
            pid = tags.get(_tag(c.tag))
            if pid is not None:
                v = verify_quote(quote, ctx.shown[pid].text)
                found = (pid, v) if v is not None else None
            if found is None:
                # a wrong tag is not a wrong quote: it may occur in exactly one excerpt
                hits = [
                    (p, v)
                    for p in ctx.order
                    if (v := verify_quote(quote, ctx.shown[p].text)) is not None
                ]
                if len(hits) == 1:
                    found = hits[0]
            if found is None:
                problems.append(
                    f"criterion {k}: the quote is not in excerpt {c.tag.strip()[:16] or '(no tag)'}"
                    " (copy at least ten characters word for word from its text, without the tag"
                    " or the header line)"
                )
                code = "ungrounded"
                continue
            pid, v = found
            p = ctx.shown[pid]
            cites.append(
                {
                    "document_version_id": p.version_id,
                    "passage_id": p.id,
                    "page_no": p.page_no,
                    "excerpt": v.excerpt,
                }
            )
        # a page named in the justification is a page of a passage that was shown (check 5)
        cited_pages = {c["page_no"] for c in cites}
        shown_pages = {p.page_no for p in ctx.shown.values()}
        for m in _PAGE.finditer(grade.justification):
            named = {int(m.group(1))} | ({int(m.group(2))} if m.group(2) else set())
            if cited_pages and not named & cited_pages and not named <= shown_pages:
                problems.append(f"page {m.group(1)} is not a page of an excerpt you cited")
        if problems:
            return None, problems, code
        status, reason = rollup(results)
        unique: list[dict] = []
        seen: set[tuple[str, str]] = set()
        for c in cites:
            key = (c["passage_id"], c["excerpt"])
            if key not in seen:
                seen.add(key)
                unique.append(c)
        if status == "non_compliant" and reason == "not_addressed":
            unique = []
        missing = [ctx.criteria[i][:300] for i, r in enumerate(results) if r != "Met"][:MAX_MISSING]
        return (
            Verdict(
                results,
                unique[:MAX_CITATIONS],
                status,
                reason,
                missing,
                grade.confidence,
                grade.justification.strip(),
            ),
            [],
            code,
        )

    def _recheck(self, state: BatchState, ids: list[str]) -> BatchState:
        verdicts = dict(state["verdicts"])
        problems = dict(state["problems"])
        failure = dict(state["failure"])
        for rid in ids:
            draft = state["drafts"].get(rid)
            if draft is None:
                verdicts[rid] = None
                problems[rid] = ["no answer"]
                failure[rid] = failure.get(rid) or "model_error"
                continue
            verdict, issues, code = self._assess(state["ctxs"][rid], draft)
            verdicts[rid] = verdict
            problems[rid] = issues
            failure[rid] = code if issues else None
        return {"verdicts": verdicts, "problems": problems, "failure": failure}  # type: ignore[typeddict-item]

    @tracing.step("check")
    async def _check_all(self, state: BatchState) -> dict[str, Any]:
        return dict(self._recheck(state, list(state["ctxs"])))

    def _after_check(self, state: BatchState):
        if self._cfg.max_repairs > 0 and any(
            state["problems"].get(rid) and rid not in state["repaired"] for rid in state["ctxs"]
        ):
            return "repair"
        return "absence"

    @tracing.step("repair")
    async def _repair(self, state: BatchState) -> dict[str, Any]:
        """Each failed row is asked again alone, once, with the problems named."""
        todo = [
            rid
            for rid in state["ctxs"]
            if state["problems"].get(rid) and rid not in state["repaired"]
        ]
        drafts = dict(state["drafts"])

        async def again(rid: str) -> None:
            ctx = state["ctxs"][rid]
            previous = state["drafts"].get(rid)
            answer = await self._ask(
                [ctx],
                "llm.repair",
                "evaluation.repair.txt",
                problems="\n".join(f"- {p}" for p in state["problems"][rid][:12]),
                previous=previous.model_dump_json() if previous else "{}",
            )
            got = self._by_label(answer, [ctx])
            drafts[rid] = got.get(rid)

        await asyncio.gather(*(again(rid) for rid in todo))
        merged: BatchState = {**state, "drafts": drafts, "repaired": state["repaired"] | set(todo)}
        return {"drafts": drafts, "repaired": merged["repaired"], **self._recheck(merged, todo)}

    # ---- verify absence ------------------------------------------------------------------------

    @tracing.step("absence")
    async def _absence(self, state: BatchState) -> dict[str, Any]:
        """One bounded second look at rows with no evidence: other wording, searched by code."""
        if self._cfg.max_searches <= 0:
            return {}
        todo = [
            rid
            for rid, v in state["verdicts"].items()
            if v is not None and v.reason == "not_addressed" and rid not in state["absence_done"]
        ]
        if not todo:
            return {}
        verdicts = dict(state["verdicts"])
        drafts = dict(state["drafts"])

        async def look(rid: str) -> None:
            ctx = state["ctxs"][rid]
            try:
                with tracing.purpose("llm.verify"):
                    plan = await self._llm.generate(
                        load("evaluation.absence.system.txt"),
                        load("evaluation.absence.user.txt").format(
                            text=ctx.req.text, criteria="\n".join(ctx.criteria)
                        ),
                        QueryPlan,
                        model=self._model,
                    )
            except LlmUnavailable:
                if self._breaker_gave_up():
                    raise
                return  # the first answer stands (AI 4.2)
            extra: dict[str, PassageRef] = {}
            for q in [q.strip() for q in plan.queries if q.strip()][: self._cfg.max_searches]:
                for h in await self._hits(q):
                    extra.setdefault(h.passage.id, h.passage)
            fresh = [p for pid, p in extra.items() if pid not in ctx.shown]
            if not fresh:
                return
            before = (ctx.shown, ctx.order, ctx.injection)
            # what was shown stays; new passages are added while the context has room
            self._fit(list(ctx.shown.values()) + fresh, {}, ctx)
            answer = await self._ask([ctx], "llm.grade")
            got = self._by_label(answer, [ctx]).get(rid)
            verdict, issues, _ = self._assess(ctx, got) if got is not None else (None, ["x"], "")
            if verdict is None or issues:
                ctx.shown, ctx.order, ctx.injection = before  # the first answer stands
                return
            drafts[rid], verdicts[rid] = got, verdict

        await asyncio.gather(*(look(rid) for rid in todo))
        return {
            "verdicts": verdicts,
            "drafts": drafts,
            "absence_done": state["absence_done"] | set(todo),
        }

    # ---- record --------------------------------------------------------------------------------

    @tracing.step("record")
    async def _record(self, state: BatchState) -> dict[str, Any]:
        items: dict[str, dict[str, Any]] = {}
        for rid, ctx in state["ctxs"].items():
            req = ctx.req
            base = {
                "item_id": item_id(self._job, req.id),
                "requirement_id": req.id,
                "requirement_hash": req.requirement_hash,
            }
            verdict = state["verdicts"].get(rid)
            if verdict is None or state["problems"].get(rid):
                metrics.inc("evaluation_rows_total", outcome="failed")
                items[rid] = {
                    **base,
                    "outcome": "failed",
                    "failure_code": state["failure"].get(rid) or "invalid_output",
                }
                continue
            confidence = CONFIDENCE[verdict.confidence]
            if rid in state["repaired"]:
                confidence = max(0.0, confidence - 0.1)
            if ctx.injection:
                confidence = min(confidence, 0.4)
            summary = " ".join(verdict.justification.split())
            first = re.split(r"(?<=[.!?])\s+", summary, maxsplit=1)[0] if summary else ""
            by_criterion = "; ".join(f"{n}: {r}" for n, r in enumerate(verdict.results, 1))
            metrics.inc("evaluation_rows_total", outcome="graded")
            items[rid] = {
                **base,
                "outcome": "graded",
                "grade": verdict.grade,
                "non_compliance_reason": verdict.reason,
                "rationale_summary": (first or "See the criteria results.")[:400],
                "rationale": f"{summary}\n\nCriteria: {by_criterion}".strip()[:2400],
                "missing_elements": verdict.missing if verdict.grade != "fully_compliant" else [],
                "confidence": round(confidence, 2),
                "citations": [{"citation_no": i + 1, **c} for i, c in enumerate(verdict.citations)],
            }
        return {"items": items}
