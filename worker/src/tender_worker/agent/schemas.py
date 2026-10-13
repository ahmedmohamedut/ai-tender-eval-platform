"""Structured outputs of the extraction graph (AI 5.1)."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field


class SourceRef(BaseModel):
    passage_id: str
    quote: str


class Candidate(BaseModel):
    text: str = Field(min_length=1, max_length=2000)
    importance: Literal["mandatory", "optional"] = "mandatory"
    acceptance_criteria: list[str] = Field(default_factory=list, max_length=20)
    category_id: str | None = None
    # Required in the schema: without it a model may leave the key out, and a requirement
    # without a source is never shown (rule 6).
    sources: list[SourceRef] = Field(max_length=10)
    several_obligations: bool = False
    certainty: Literal["high", "medium", "low"] = "medium"


class Candidates(BaseModel):
    items: list[Candidate] = Field(default_factory=list, max_length=60)


class PairJudgement(BaseModel):
    verdict: Literal["same", "conflicts", "unrelated"]
    reason: str = Field(default="", max_length=300)
