"""Plain data the parser returns. Everything here is picklable (it crosses a process pool)."""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field

# Constants of the code: a passage or section id must be the same on every run (AI 3.2, rule 13).
PASSAGE_NAMESPACE = uuid.UUID("5b0c1d2e-7a41-4f9e-9a7e-0c3f6a2b1d10")
SECTION_NAMESPACE = uuid.UUID("a3e1f4c8-2d6b-4b07-8c55-7e9d41b0f2a6")

PASSAGE_MIN_CHARS = 200
PASSAGE_MAX_CHARS = 1200
PAGE_MIN_CHARS = 20
MAX_TEXTLESS_SHARE = 0.5
REPEAT_SHARE = 0.6


def passage_id(version_id: uuid.UUID, ordinal: int) -> str:
    return str(uuid.uuid5(PASSAGE_NAMESPACE, f"{version_id}:{ordinal}"))


def section_id(version_id: uuid.UUID, outline_index: int) -> str:
    return str(uuid.uuid5(SECTION_NAMESPACE, f"{version_id}:{outline_index}"))


class IngestFailure(Exception):
    """The document cannot be processed. `code` is a failure code of PI 5.5."""

    def __init__(
        self,
        code: str,
        message: str,
        retryable: bool = False,
        cause_code: str | None = None,
        params: dict[str, int] | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = retryable
        # `cause_code` and `params` go to the backend, which words them (see messages.Failure)
        self.cause_code = cause_code
        self.params = params or {}

    def __reduce__(self):
        # The parser raises this in a pool process: it must survive pickling with its fields,
        # or the pool reports a broken result instead of the failure code.
        return (
            type(self),
            (self.code, self.message, self.retryable, self.cause_code, self.params),
        )


@dataclass
class Line:
    text: str
    x0: float
    top: float
    x1: float
    bottom: float
    size: float
    bold: bool = False
    in_table: bool = False
    col: int = 0


@dataclass
class PageResult:
    page_no: int
    width: float
    height: float
    text: str
    textless: bool
    lines: list[Line] = field(default_factory=list)


@dataclass
class SectionResult:
    section_id: str
    parent_section_id: str | None
    title: str
    level: int
    sort_order: int
    start_page: int
    end_page: int


@dataclass
class PassageResult:
    passage_id: str
    section_id: str | None
    page_no: int
    passage_no: int
    text: str
    bbox: list[list[float]]
    char_start: int
    char_end: int
    content_hash: str
    table_header: str | None = None


@dataclass
class Warning_:
    code: str
    message: str
    count: int = 1


@dataclass
class ParseResult:
    pages: list[PageResult]
    sections: list[SectionResult]
    passages: list[PassageResult]
    warnings: list[Warning_]
    parser_versions: dict[str, str]
