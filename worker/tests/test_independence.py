"""AI 5.2 and 5.3: what reaches the model carries no member data and no vendor identity."""

from __future__ import annotations

import dataclasses
import string

from tender_worker.agent.evaluation import Requirement
from tender_worker.prompts import load

ALLOWED = {
    "req_id",
    "importance",
    "category",
    "text",
    "criteria",
    "excerpts",
    "count",
    "batch",
    "requirement",
    "problems",
    "previous",
}
FILES = [
    "evaluation.system.txt",
    "evaluation.user.txt",
    "evaluation.requirement.txt",
    "evaluation.repair.txt",
    "evaluation.absence.system.txt",
    "evaluation.absence.user.txt",
]


def test_the_grading_prompts_take_only_the_requirement_and_the_passages():
    for name in FILES:
        fields = {f for _, f, _, _ in string.Formatter().parse(load(name)) if f}
        assert fields <= ALLOWED, f"{name} takes {fields - ALLOWED}"


def test_the_prompts_never_name_a_vendor_a_member_or_a_score():
    for name in FILES:
        text = load(name).lower()
        for word in ("member", "reviewer", "committee", "chair", "score", "rank", "other vendor"):
            assert word not in text, f"{name} mentions {word}"


def test_a_requirement_carries_nothing_about_a_vendor_or_a_person():
    names = {f.name for f in dataclasses.fields(Requirement)}
    assert names == {
        "id",
        "number",
        "text",
        "acceptance_criteria",
        "importance",
        "category",
        "requirement_hash",
    }
