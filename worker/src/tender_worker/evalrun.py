"""The extraction evaluation set and its runner (AI 5.6; rules 6 and 14).

    python -m tender_worker.evalrun extraction [--base-url URL --model NAME --api-key KEY]
                                               [--mode json_schema|function_calling] [--write]

Without --base-url the rule-based stub runs in-process, which measures the harness, not a model.
With one, the same graph code that runs in production runs against that endpoint. Documents are
synthetic (evalset/extraction/*.json). Results go into docs/releases/ with the release record.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

from aiohttp.test_utils import TestServer

from tender_worker import stub_embedding, stub_llm
from tender_worker.agent.extraction import ExtractionGraph, ExtractionSettings
from tender_worker.agent.grounding import verify_quote
from tender_worker.agent.llm import OpenAiCompatible
from tender_worker.agent.retrieval import PassageRef, ScopeItem
from tender_worker.prompts import EXTRACTION_VERSION, bundle_hash

ROOT = Path(__file__).resolve().parents[2]
SET_DIR = ROOT / "evalset" / "extraction"
TENDER = uuid.UUID("0193a7a0-0000-7000-8000-0000000000e1")
JOB = uuid.UUID("0193a7a0-0000-7000-8000-0000000000e2")
VERSION = "0193a7a0-0000-7000-8000-0000000000e3"


@dataclass
class CaseResult:
    name: str
    expected: int
    produced: int
    matched_expected: int
    matched_produced: int
    sources: int
    verbatim_sources: int
    importance_right: int
    duplicates_expected: int
    duplicates_flagged: int
    conflicts_expected: int
    conflicts_flagged: int
    background_items: int
    dropped: int


class _Source:
    def __init__(self, passages: list[PassageRef]) -> None:
        self._p = passages

    async def load(self, tender_id, scope):
        return self._p


async def _embed(texts: list[str]) -> list[list[float]]:
    return [stub_embedding.embed(t) for t in texts]


async def run_case(data: dict, llm) -> CaseResult:
    passages = [
        PassageRef(
            id=p["id"],
            version_id=VERSION,
            ordinal=p["ordinal"],
            page_no=p["page_no"],
            text=p["text"],
            section_path_ids=(),
        )
        for p in data["passages"]
    ]
    by_id = {p.id: p for p in passages}
    graph = ExtractionGraph(
        llm=llm,
        passages=_Source(passages),
        embed=_embed,
        tender_id=TENDER,
        job_id=JOB,
        settings=ExtractionSettings(window_chars=4000, overlap_passages=1),
    )
    items, counters = await graph.run([ScopeItem(version_id=VERSION)], data["categories"], [])
    expected = data["expected"]
    exp_by_passage = {e["passage_id"]: e for e in expected}
    matched_exp: set[str] = set()
    matched_prod = 0
    importance_right = 0
    background = 0
    produced_for: dict[str, object] = {}
    sources = verbatim = 0
    for item in items:
        cited = {s["passage_id"] for s in item.sources}
        for s in item.sources:
            sources += 1
            if verify_quote(s["quote"], by_id[s["passage_id"]].text):
                verbatim += 1
        hit = next((p for p in cited if p in exp_by_passage), None)
        if hit is None:
            background += 1
            continue
        matched_prod += 1
        matched_exp.add(hit)
        produced_for[hit] = item
        if item.importance == exp_by_passage[hit]["importance"]:
            importance_right += 1
    dups = [e for e in expected if e["duplicate_of"]]
    confs = [e for e in expected if e["conflicts_with"]]

    def flagged(e, kind: str, other_key: str) -> bool:
        item = produced_for.get(e["passage_id"])
        other = produced_for.get(e[other_key])
        if item is None or other is None:
            return False
        return any(
            f["flag_type"] == kind and f.get("related_item_id") == other.item_id for f in item.flags
        ) or any(
            f["flag_type"] == kind and f.get("related_item_id") == item.item_id for f in other.flags
        )

    return CaseResult(
        name=data["name"],
        expected=len(expected),
        produced=len(items),
        matched_expected=len(matched_exp),
        matched_produced=matched_prod,
        sources=sources,
        verbatim_sources=verbatim,
        importance_right=importance_right,
        duplicates_expected=len(dups),
        duplicates_flagged=sum(1 for e in dups if flagged(e, "possible_duplicate", "duplicate_of")),
        conflicts_expected=len(confs),
        conflicts_flagged=sum(1 for e in confs if flagged(e, "conflict", "conflicts_with")),
        background_items=background,
        dropped=counters.get("ungrounded_candidate", 0),
    )


def _pct(a: int, b: int) -> str:
    return "n/a" if b == 0 else f"{100 * a / b:.0f} %"


def render(results: list[CaseResult], model: str, endpoint: str) -> str:
    tot = lambda f: sum(getattr(r, f) for r in results)  # noqa: E731
    lines = [
        "# Extraction evaluation",
        "",
        f"- Run: {datetime.now(UTC):%Y-%m-%d %H:%M} UTC",
        f"- Model: `{model}` at `{endpoint}`",
        f"- Prompt: `{EXTRACTION_VERSION}` (bundle `{bundle_hash()[:12]}`)",
        "- Documents: synthetic (evalset/extraction)",
        "",
        "| Case | Expected | Produced | Recall | Precision | Verbatim sources | Importance | "
        "Duplicates flagged | Conflicts flagged | Dropped |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    rows = [*results]
    for r in rows:
        lines.append(_row(r.name, r))
    all_ = CaseResult(
        "**All**",
        tot("expected"),
        tot("produced"),
        tot("matched_expected"),
        tot("matched_produced"),
        tot("sources"),
        tot("verbatim_sources"),
        tot("importance_right"),
        tot("duplicates_expected"),
        tot("duplicates_flagged"),
        tot("conflicts_expected"),
        tot("conflicts_flagged"),
        tot("background_items"),
        tot("dropped"),
    )
    lines.append(_row(all_.name, all_))
    lines += [
        "",
        "Recall is expected requirements found; precision is produced items that cite an expected "
        "passage. Verbatim sources must be 100 %: a candidate with an unmatched quote is never "
        "shown (AGENTS rule 6).",
    ]
    return "\n".join(lines) + "\n"


def _row(name: str, r: CaseResult) -> str:
    return (
        f"| {name} | {r.expected} | {r.produced} | {_pct(r.matched_expected, r.expected)} | "
        f"{_pct(r.matched_produced, r.produced)} | {_pct(r.verbatim_sources, r.sources)} | "
        f"{_pct(r.importance_right, r.matched_produced)} | "
        f"{r.duplicates_flagged}/{r.duplicates_expected} | "
        f"{r.conflicts_flagged}/{r.conflicts_expected} | {r.dropped} |"
    )


async def main_async(args: argparse.Namespace) -> int:
    server = None
    base_url, api_key, model = args.base_url, args.api_key, args.model
    if not base_url:
        server = TestServer(stub_llm.app())
        await server.start_server()
        base_url = str(server.make_url("/v1")).rstrip("/")
        model = model or "stub-llm"
    llm = OpenAiCompatible(
        base_url=base_url,
        api_key=api_key or "none",
        model=model or "unknown",
        structured_output=args.mode,
        timeout=args.timeout,
        retries=3,
    )
    results = []
    try:
        for path in sorted(SET_DIR.glob("*.json")):
            results.append(await run_case(json.loads(path.read_text()), llm))
    finally:
        if server is not None:
            await server.close()
    text = render(results, model or "unknown", args.base_url or "in-process stub")
    sys.stdout.write(text)
    if args.write:
        out = ROOT.parent / "docs" / "releases"
        out.mkdir(parents=True, exist_ok=True)
        slug = (model or "stub").replace("/", "_")
        target = out / f"extraction-eval-{datetime.now(UTC):%Y%m%d}-{slug}.md"
        target.write_text(text)
        sys.stderr.write(f"wrote {target}\n")
    verbatim = sum(r.verbatim_sources for r in results)
    sources = sum(r.sources for r in results)
    return 0 if verbatim == sources else 1


def main() -> None:
    p = argparse.ArgumentParser(prog="tender_worker.evalrun")
    p.add_argument("set", choices=["extraction"])
    p.add_argument("--base-url")
    p.add_argument("--model")
    p.add_argument("--api-key")
    p.add_argument("--mode", default="json_schema", choices=["json_schema", "function_calling"])
    p.add_argument("--timeout", type=int, default=180)
    p.add_argument("--write", action="store_true")
    args = p.parse_args()
    raise SystemExit(asyncio.run(main_async(args)))


if __name__ == "__main__":
    main()
