"""Structured outputs of the extraction graph (AI 5.1)."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field


class RequirementCandidate(BaseModel):
    """One requirement the model proposes (AI 4.6). The quote comes first: it anchors the rest of
    the answer to the text, and code checks it against the block the tag names (rule 6)."""

    source_quote: str = Field(max_length=600)
    tag: str = Field(max_length=16)
    title: str = Field(default="", max_length=200)
    description: str = Field(min_length=1, max_length=2000)
    category_id: str | None = None
    is_mandatory: bool = True
    acceptance_criteria: list[str] = Field(default_factory=list, max_length=8)


class RequirementExtraction(BaseModel):
    requirements: list[RequirementCandidate] = Field(default_factory=list, max_length=60)


class PairJudgement(BaseModel):
    verdict: Literal["same", "conflicts", "unrelated"]
    reason: str = Field(default="", max_length=300)


class ExpectedSection(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    description: str = Field(default="", max_length=400)
    source_passage_id: str


class ExpectedSections(BaseModel):
    items: list[ExpectedSection] = Field(default_factory=list, max_length=40)


class SectionMatch(BaseModel):
    found: bool
    matched_title: str | None = Field(default=None, max_length=300)
    passage_ids: list[str] = Field(default_factory=list, max_length=6)


CriterionResult = Literal["Met", "Partially Met", "Not Met", "No Evidence"]


class CriterionCheck(BaseModel):
    """The model's verdict on one acceptance criterion, with the evidence it relied on."""

    criterion_no: int = Field(ge=1, le=50)
    result: CriterionResult
    # "N/A" for No Evidence; otherwise copied word for word from the excerpt the tag names.
    evidence_quote: str = Field(max_length=400)
    tag: str = Field(default="", max_length=16)


class RequirementGrade(BaseModel):
    req_id: str = Field(max_length=64)
    criteria_checks: list[CriterionCheck] = Field(max_length=50)
    confidence: Literal["High", "Medium", "Low"]
    justification: str = Field(max_length=900)


class BatchGrade(BaseModel):
    """Several requirements of one proposal graded in one call (AI 4.2)."""

    evaluations: list[RequirementGrade] = Field(max_length=16)


class QueryPlan(BaseModel):
    queries: list[str] = Field(default_factory=list, max_length=3)
