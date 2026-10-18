"""A Word document as markdown blocks (AI 3.1, 3.3).

The model reads the true heading hierarchy of a DOCX, not a flat page text. Each block is a
heading, a paragraph, a list item or one table row, in document order, with its full heading path.
Table rows carry their column labels (`Header: value | Header: value`) so a chunk can never
separate a row from its header; merged cells appear once. Content controls (`w:sdt`), hyperlinks
and tracked insertions are read, tracked deletions are not.

The blocks are an addition to the passages, never a replacement (rule 13). Passages come from the
converted PDF and are what a citation points at; `align_blocks` records which passages hold each
block's text, by exact containment after normalisation. A block with no such passage is kept for
context but cannot be cited.
"""

from __future__ import annotations

import io
import json
import re
from collections.abc import Iterator
from dataclasses import asdict, dataclass, field

from tender_worker.agent.grounding import normalise

_HEADING_STYLE = re.compile(r"^heading\s*(\d+)", re.I)
_RUN_XPATH = "./w:r | ./w:hyperlink/w:r | ./w:ins/w:r | ./w:sdt/w:sdtContent/w:r"
TABLE_ROW = "[Table row] "
# A passage inside a longer block must be this long, or a stray "1." would match anywhere.
MIN_PASSAGE_IN_BLOCK = 10


@dataclass
class Block:
    block_id: str
    heading_path: str
    kind: str  # heading | paragraph | list_item | table_row
    text: str  # plain text, as a passage would hold it (table cells joined with " | ")
    label: str = ""  # table rows only: the same cells with their column labels
    level: int = 0  # headings only
    passage_ids: list[str] = field(default_factory=list)

    @property
    def shown(self) -> str:
        """What the model reads for this block."""
        if self.kind == "table_row":
            return TABLE_ROW + (self.label or self.text)
        if self.kind == "list_item":
            return "- " + self.text
        return self.text


def _iter_blocks(doc) -> Iterator:
    """Body paragraphs and tables in document order, including those in content controls."""
    from docx.oxml.ns import qn
    from docx.table import Table
    from docx.text.paragraph import Paragraph

    def walk(container):
        for child in container.iterchildren():
            if child.tag == qn("w:p"):
                yield Paragraph(child, doc)
            elif child.tag == qn("w:tbl"):
                yield Table(child, doc)
            elif child.tag == qn("w:sdt"):
                content = child.find(qn("w:sdtContent"))
                if content is not None:
                    yield from walk(content)

    yield from walk(doc.element.body)


def _para_text(p) -> str:
    from docx.oxml.ns import qn

    parts: list[str] = []
    for run in p._p.xpath(_RUN_XPATH):
        for node in run:
            if node.tag == qn("w:t"):
                parts.append(node.text or "")
            elif node.tag in (qn("w:tab"), qn("w:br"), qn("w:cr")):
                parts.append(" ")
    return re.sub(r"\s+", " ", "".join(parts)).strip()


def _cell_text(cell) -> str:
    return " ".join(t for t in (_para_text(p) for p in cell.paragraphs) if t)


def _header_like(row, cells: list[str]) -> bool:
    if row._tr.xpath("./w:trPr/w:tblHeader"):
        return True
    texts = [c for c in cells if c]
    if not texts or any(len(t) > 60 for t in texts):
        return False
    runs = [r for c in row.cells for p in c.paragraphs for r in p.runs if r.text.strip()]
    return bool(runs) and all(r.bold for r in runs)


def _row_cells(row) -> list[tuple[int, int, str]]:
    """(first grid column, column span, text) per physical cell; merged cells appear once."""
    out, seen = [], set()
    grid = row.cells
    for i, c in enumerate(grid):
        if c._tc in seen:
            continue
        seen.add(c._tc)
        span = sum(1 for x in grid if x._tc is c._tc)
        out.append((i, span, _cell_text(c)))
    return out


def _table_rows(table) -> list[tuple[str, str]]:
    """(plain, labelled) text per row. The header row is not a row of its own."""
    rows = [_row_cells(r) for r in table.rows]
    if not rows:
        return []
    has_header = len(rows) > 1 and _header_like(table.rows[0], [t for _, _, t in rows[0]])
    header_by_col: dict[int, str] = {}
    if has_header:
        for start, span, text in rows[0]:
            for col in range(start, start + span):
                header_by_col[col] = text
    out: list[tuple[str, str]] = []
    for cells in rows[1 if has_header else 0 :]:
        plain: list[str] = []
        labelled: list[str] = []
        for start, span, text in cells:
            if not text:
                continue
            labels = list(
                dict.fromkeys(header_by_col.get(c, "") for c in range(start, start + span))
            )
            label = " / ".join(x for x in labels if x)
            plain.append(text)
            labelled.append(f"{label}: {text}" if label else text)
        if plain:
            out.append((" | ".join(plain), " | ".join(labelled)))
    return out


def _is_bold(p) -> bool:
    runs = [r for r in p.runs if r.text.strip()]
    return bool(runs) and all(r.bold for r in runs)


def _heading_level(p, text: str, style: str) -> int | None:
    m = _HEADING_STYLE.match(style)
    if m:
        return int(m.group(1))
    if style.lower() in {"title", "subtitle"}:
        return 1
    # documents that fake headings with bold text
    if 0 < len(text) < 90 and not text.endswith((".", ";")) and _is_bold(p):
        num = re.match(r"^(\d+(?:\.\d+)*)[.)]?\s", text)
        return min(num.group(1).count(".") + 1, 6) if num else 7
    return None


def parse_docx_blocks(data: bytes) -> list[Block]:
    """The blocks of a DOCX in document order, each with its full heading path."""
    from docx import Document
    from docx.table import Table

    doc = Document(io.BytesIO(data))
    blocks: list[Block] = []
    stack: list[tuple[int, str]] = []

    def path() -> str:
        return " > ".join(t for _, t in stack) or "General"

    def add(kind: str, text: str, label: str = "", level: int = 0) -> None:
        blocks.append(
            Block(f"B{len(blocks) + 1}", path(), kind, text, label if label != text else "", level)
        )

    for item in _iter_blocks(doc):
        if isinstance(item, Table):
            for plain, labelled in _table_rows(item):
                add("table_row", plain, labelled)
            continue
        text = _para_text(item)
        if not text:
            continue
        style = item.style.name if item.style is not None else ""
        if style.upper().startswith("TOC"):
            continue
        level = _heading_level(item, text, style)
        if level:
            while stack and stack[-1][0] >= level:
                stack.pop()
            stack.append((level, text))
            add("heading", text, level=level)
        else:
            listed = bool(item._p.xpath("./w:pPr/w:numPr")) or style.lower().startswith("list")
            add("list_item" if listed else "paragraph", text)
    return blocks


def render_markdown(blocks: list[Block]) -> str:
    """The document as markdown: headings as `#`, lists as `-`, table rows labelled."""
    out: list[str] = []
    for b in blocks:
        if b.kind == "heading":
            out.append(f"{'#' * max(1, min(b.level, 6))} {b.text}")
        else:
            out.append(b.shown)
    return "\n\n".join(out) + "\n"


def align_blocks(blocks: list[Block], passages: list[dict]) -> int:
    """Set `passage_ids` on each block; return how many blocks have none.

    A passage belongs to a block when one normalised text contains the other, exactly. Both lists
    are in document order, so each search starts near the previous match and widens only on a miss.
    """
    norm_passages = [normalise(p["text"]) for p in passages]
    cursor = 0
    unaligned = 0
    for block in blocks:
        text = normalise(block.text)
        block.passage_ids = []
        if len(text) < 3:
            unaligned += 1
            continue
        found: list[int] = []
        for start, stop in (
            (max(0, cursor - 4), min(len(passages), cursor + 80)),
            (0, len(passages)),
        ):
            for i in range(start, stop):
                n = norm_passages[i]
                if n and (text in n or (len(n) >= MIN_PASSAGE_IN_BLOCK and n in text)):
                    found.append(i)
                elif found:
                    break  # the passages of one block are consecutive
            if found:
                break
        if found:
            block.passage_ids = [passages[i]["passage_id"] for i in found]
            cursor = found[-1]
        else:
            unaligned += 1
    return unaligned


def blocks_to_jsonl(blocks: list[Block]) -> bytes:
    return (
        "\n".join(json.dumps(asdict(b), ensure_ascii=False, separators=(",", ":")) for b in blocks)
        + "\n"
    ).encode()


def blocks_from_jsonl(raw: bytes) -> list[Block]:
    return [Block(**json.loads(line)) for line in raw.decode().splitlines() if line.strip()]
