"""PDF parsing (AI 3.1, 3.2).

pdfplumber reads words and rectangles page by page, pypdf the encryption flag and the outline.
Nothing here is summarised or rewritten: a passage's text is what the page says, with line breaks
turned into spaces and end-of-line hyphens repaired.
"""

from __future__ import annotations

import hashlib
import io
import re
import uuid
from collections import Counter

import pdfplumber
import pypdf

from tender_worker.ingest.model import (
    MAX_TEXTLESS_SHARE,
    PAGE_MIN_CHARS,
    PASSAGE_MAX_CHARS,
    PASSAGE_MIN_CHARS,
    REPEAT_SHARE,
    IngestFailure,
    Line,
    PageResult,
    ParseResult,
    PassageResult,
    SectionResult,
    Warning_,
    passage_id,
    section_id,
)

_LIST_ITEM = re.compile(r"^\s*(?:\d+(?:\.\d+)*[.)]|[-•*–]|\([a-zA-Z0-9]{1,3}\)|[a-zA-Z][.)])\s+\S")
_NUMBERED_HEADING = re.compile(r"^\s*\d+(?:\.\d+){0,3}[.)]?\s+[A-Z]")
_SENTENCE_END = re.compile(r"(?<=[.!?])\s+(?=[A-Z0-9(\"'])")
_WS = re.compile(r"\s+")


def parse_pdf(
    data: bytes,
    version_id: str,
    *,
    page_min_chars: int = PAGE_MIN_CHARS,
    max_textless_share: float = MAX_TEXTLESS_SHARE,
) -> ParseResult:
    """Parse a PDF into pages, sections and passages. Raises IngestFailure for file causes."""
    vid = uuid.UUID(version_id)
    try:
        reader = pypdf.PdfReader(io.BytesIO(data))
        if reader.is_encrypted:
            raise IngestFailure("password_protected", "The file is password protected")
        page_count = len(reader.pages)
    except IngestFailure:
        raise
    except Exception as exc:  # noqa: BLE001 - any parser error means the file is not readable
        raise IngestFailure("unsupported_format", "The file could not be read as a PDF") from exc
    if page_count == 0:
        raise IngestFailure("unsupported_format", "The file has no pages")

    outline = _outline(reader)

    pages: list[PageResult] = []
    try:
        with pdfplumber.open(io.BytesIO(data)) as pdf:
            for number, page in enumerate(pdf.pages, start=1):
                pages.append(_read_page(page, number))
    except IngestFailure:
        raise
    except Exception as exc:  # noqa: BLE001
        raise IngestFailure("unsupported_format", "The file could not be read as a PDF") from exc

    textless = [p for p in pages if len(p.text.strip()) < page_min_chars]
    for p in textless:
        p.textless = True
    if len(textless) / len(pages) > max_textless_share:
        raise IngestFailure("no_selectable_text", "The file has no selectable text")

    suppressed = _repeated_lines(pages)
    sections = _build_sections(vid, outline, pages, suppressed)
    passages = _build_passages(vid, pages, sections, suppressed)

    warnings: list[Warning_] = []
    if textless:
        warnings.append(
            Warning_(
                "textless_pages",
                "Pages without selectable text: "
                + ", ".join(str(p.page_no) for p in textless[:20]),
                len(textless),
            )
        )
    if _not_english(pages):
        warnings.append(Warning_("not_english", "The text does not look English", 1))
    return ParseResult(
        pages=pages,
        sections=sections,
        passages=passages,
        warnings=warnings,
        parser_versions={"pdfplumber": pdfplumber.__version__, "pypdf": pypdf.__version__},
    )


# ---- page reading -----------------------------------------------------------------------------


def _read_page(page, number: int) -> PageResult:
    width, height = float(page.width), float(page.height)
    tables = []
    try:
        tables = page.find_tables()
    except Exception:  # noqa: BLE001 - a page whose tables cannot be found is read as text
        tables = []
    table_boxes = [t.bbox for t in tables]

    words = page.extract_words(
        keep_blank_chars=False, use_text_flow=False, extra_attrs=["size", "fontname"]
    )
    outside = [w for w in words if not any(_inside(w, b) for b in table_boxes)]
    lines = _words_to_lines(outside, width)

    # Table rows are lines of their own, marked so the passage builder keeps them whole.
    for table in tables:
        try:
            extracted = table.extract()
        except Exception:  # noqa: BLE001
            continue
        rows = getattr(table, "rows", [])
        for index, cells in enumerate(extracted):
            texts = [(c or "").strip().replace("\n", " ") for c in cells]
            if not any(texts):
                continue
            box = rows[index].bbox if index < len(rows) else table.bbox
            lines.append(
                Line(
                    text=" | ".join(t for t in texts if t),
                    x0=box[0],
                    top=box[1],
                    x1=box[2],
                    bottom=box[3],
                    size=0.0,
                    in_table=True,
                )
            )
    lines.sort(key=lambda ln: (ln.col, round(ln.top, 1), ln.x0))
    text = "\n".join(ln.text for ln in lines)
    return PageResult(
        page_no=number, width=width, height=height, text=text, textless=False, lines=lines
    )


def _inside(word: dict, box: tuple[float, float, float, float]) -> bool:
    cx = (word["x0"] + word["x1"]) / 2
    cy = (word["top"] + word["bottom"]) / 2
    return box[0] <= cx <= box[2] and box[1] <= cy <= box[3]


def _words_to_lines(words: list[dict], page_width: float) -> list[Line]:
    if not words:
        return []
    split = _column_split(words, page_width)
    groups = (
        [words]
        if split is None
        else [
            [w for w in words if (w["x0"] + w["x1"]) / 2 < split],
            [w for w in words if (w["x0"] + w["x1"]) / 2 >= split],
        ]
    )
    out: list[Line] = []
    for column, group in enumerate(groups):
        for line in _group_lines(group):
            line.col = column
            out.append(line)
    return out


def _group_lines(words: list[dict]) -> list[Line]:
    ordered = sorted(words, key=lambda w: (round(w["top"], 1), w["x0"]))
    lines: list[list[dict]] = []
    for w in ordered:
        if lines and abs(w["top"] - lines[-1][0]["top"]) < max(2.0, 0.5 * (w["bottom"] - w["top"])):
            lines[-1].append(w)
        else:
            lines.append([w])
    result = []
    for ws in lines:
        ws.sort(key=lambda w: w["x0"])
        sizes = [w.get("size", 0.0) for w in ws]
        fonts = [str(w.get("fontname", "")) for w in ws]
        result.append(
            Line(
                text=" ".join(w["text"] for w in ws),
                x0=min(w["x0"] for w in ws),
                top=min(w["top"] for w in ws),
                x1=max(w["x1"] for w in ws),
                bottom=max(w["bottom"] for w in ws),
                size=max(sizes) if sizes else 0.0,
                bold=any("bold" in f.lower() for f in fonts),
            )
        )
    return result


def _column_split(words: list[dict], page_width: float) -> float | None:
    """A gutter exists when no word crosses a vertical line near the middle (AI 3.1)."""
    if len(words) < 40:
        return None
    lo, hi = page_width * 0.35, page_width * 0.65
    best = None
    x = lo
    while x <= hi:
        crossing = sum(1 for w in words if w["x0"] < x < w["x1"])
        left = sum(1 for w in words if w["x1"] <= x)
        right = sum(1 for w in words if w["x0"] >= x)
        if crossing == 0 and left > 0.25 * len(words) and right > 0.25 * len(words):
            best = x
            break
        x += page_width * 0.01
    return best


# ---- running headers and footers -----------------------------------------------------------------


def _signature(line: Line, page_height: float) -> tuple[str, int]:
    text = re.sub(r"\d+", "#", _WS.sub(" ", line.text.strip().lower()))
    return text, round(line.top / max(page_height, 1) * 50)


def _repeated_lines(pages: list[PageResult]) -> set[tuple[int, int]]:
    """(page_no, index of line) of lines that repeat at one position on most pages."""
    if len(pages) < 3:
        return set()
    counts: Counter = Counter()
    for p in pages:
        seen = {_signature(ln, p.height) for ln in p.lines if not ln.in_table}
        counts.update(seen)
    threshold = REPEAT_SHARE * len(pages)
    repeated = {sig for sig, n in counts.items() if n >= threshold and sig[0]}
    out: set[tuple[int, int]] = set()
    for p in pages:
        for i, ln in enumerate(p.lines):
            if not ln.in_table and _signature(ln, p.height) in repeated:
                out.add((p.page_no, i))
    return out


# ---- outline and sections ------------------------------------------------------------------------


def _outline(reader: pypdf.PdfReader) -> list[tuple[str, int, int]]:
    """Flatten bookmarks to (title, level, 0-based page index), in document order."""
    out: list[tuple[str, int, int]] = []

    def walk(items, level: int) -> None:
        for item in items:
            if isinstance(item, list):
                walk(item, level + 1)
                continue
            try:
                page = reader.get_destination_page_number(item)
                title = str(item.title).strip()
            except Exception:  # noqa: BLE001
                continue
            if title and page is not None and page >= 0:
                out.append((title, level, page))

    try:
        walk(reader.outline, 1)
    except Exception:  # noqa: BLE001 - a broken outline is the same as none
        return []
    return out


def _build_sections(
    vid: uuid.UUID,
    outline: list[tuple[str, int, int]],
    pages: list[PageResult],
    suppressed: set[tuple[int, int]],
) -> list[SectionResult]:
    entries = [(t, lv, p + 1) for t, lv, p in outline if p + 1 <= len(pages)]
    if not entries:
        entries = _inferred_headings(pages, suppressed)
    if not entries:
        return []
    last_page = len(pages)
    sections: list[SectionResult] = []
    stack: list[tuple[int, str]] = []  # (level, section_id)
    for index, (title, level, start) in enumerate(entries):
        while stack and stack[-1][0] >= level:
            stack.pop()
        parent = stack[-1][1] if stack else None
        sid = section_id(vid, index)
        # Ends the page before the next section of the same or a higher level starts.
        end = last_page
        for _, nlevel, nstart in entries[index + 1 :]:
            if nlevel <= level:
                end = max(start, nstart - 1 if nstart > start else start)
                break
        sections.append(
            SectionResult(
                section_id=sid,
                parent_section_id=parent,
                title=title[:500],
                level=level,
                sort_order=index,
                start_page=start,
                end_page=max(start, end),
            )
        )
        stack.append((level, sid))
    return sections


def _inferred_headings(
    pages: list[PageResult], suppressed: set[tuple[int, int]]
) -> list[tuple[str, int, int]]:
    """Numbered, larger or bold lines; accepted only when at least three agree (AI 3.1)."""
    sizes = Counter(round(ln.size) for p in pages for ln in p.lines if ln.size)
    if not sizes:
        return []
    body = sizes.most_common(1)[0][0]
    found: list[tuple[str, int, int]] = []
    for p in pages:
        for i, ln in enumerate(p.lines):
            if (p.page_no, i) in suppressed or ln.in_table or len(ln.text) > 120:
                continue
            numbered = bool(_NUMBERED_HEADING.match(ln.text))
            emphasised = ln.size > body + 1 or ln.bold
            if numbered and emphasised:
                depth = ln.text.strip().split(" ")[0].rstrip(".)").count(".") + 1
                found.append((ln.text.strip(), min(depth, 4), p.page_no))
    return found if len(found) >= 3 else []


# ---- passages ---------------------------------------------------------------------------


def _clean(lines: list[str]) -> str:
    """Line breaks become spaces and a hyphen at a line end is repaired."""
    out = ""
    for part in lines:
        part = part.strip()
        if not part:
            continue
        if out.endswith("-") and part[:1].islower() and len(out) > 1 and out[-2].isalpha():
            out = out[:-1] + part
        elif out:
            out += " " + part
        else:
            out = part
    return _WS.sub(" ", out).strip()


def _split_long(text: str) -> list[str]:
    if len(text) <= PASSAGE_MAX_CHARS:
        return [text]
    parts = _SENTENCE_END.split(text)
    out: list[str] = []
    current = ""
    for sentence in parts:
        if len(sentence) > PASSAGE_MAX_CHARS:  # no sentence end to use: cut at a space
            if current:
                out.append(current)
                current = ""
            rest = sentence
            while len(rest) > PASSAGE_MAX_CHARS:
                cut = rest.rfind(" ", 0, PASSAGE_MAX_CHARS)
                cut = cut if cut > 0 else PASSAGE_MAX_CHARS
                out.append(rest[:cut].strip())
                rest = rest[cut:].strip()
            current = rest
            continue
        if current and len(current) + 1 + len(sentence) > PASSAGE_MAX_CHARS:
            out.append(current)
            current = sentence
        else:
            current = f"{current} {sentence}".strip()
    if current:
        out.append(current)
    return out


def _paragraphs(page: PageResult, suppressed: set[tuple[int, int]]) -> list[dict]:
    """Group lines into paragraphs, headings and list items, keeping each line's index."""
    result: list[dict] = []
    current: dict | None = None
    prev: Line | None = None
    body_sizes = [ln.size for ln in page.lines if ln.size]
    body = sorted(body_sizes)[len(body_sizes) // 2] if body_sizes else 0.0

    def flush() -> None:
        nonlocal current
        if current:
            result.append(current)
        current = None

    for index, line in enumerate(page.lines):
        if (page.page_no, index) in suppressed:
            flush()
            prev = None
            continue
        if line.in_table:
            flush()
            result.append({"kind": "row", "lines": [index]})
            prev = None
            continue
        heading = bool(line.size and body and line.size > body + 1 and len(line.text) < 150)
        starts_item = bool(_LIST_ITEM.match(line.text))
        gap = (line.top - prev.bottom) if prev else 0.0
        height = max(line.bottom - line.top, 1.0)
        new_block = (
            current is None
            or heading
            or current["kind"] == "heading"
            or starts_item
            or gap > 0.9 * height
            or (prev is not None and abs(line.x0 - prev.x0) > 60 and line.x0 < prev.x0 - 20)
        )
        if new_block:
            flush()
            current = {
                "kind": "heading" if heading else ("item" if starts_item else "paragraph"),
                "lines": [index],
            }
        else:
            current["lines"].append(index)
        prev = line
    flush()
    return result


def _section_for(sections: list[SectionResult], page_no: int) -> str | None:
    chosen = None
    for s in sections:
        if s.start_page <= page_no <= max(s.end_page, s.start_page):
            chosen = s.section_id
        elif s.start_page > page_no:
            break
    if chosen is None:
        # a page before the first heading, or after the last section's end: the latest started
        started = [s for s in sections if s.start_page <= page_no]
        chosen = started[-1].section_id if started else None
    return chosen


def _build_passages(
    vid: uuid.UUID,
    pages: list[PageResult],
    sections: list[SectionResult],
    suppressed: set[tuple[int, int]],
) -> list[PassageResult]:
    out: list[PassageResult] = []
    for page in pages:
        out.extend(_page_passages(vid, page, sections, suppressed, len(out)))
    return out


def _page_passages(
    vid: uuid.UUID,
    page: PageResult,
    sections: list[SectionResult],
    suppressed: set[tuple[int, int]],
    first_ordinal: int,
) -> list[PassageResult]:
    """Passages of one page; `first_ordinal` is the document-wide position in reading order."""
    out: list[PassageResult] = []
    starts: list[int] = []  # char offset of each line inside the page text
    pos = 0
    for line in page.lines:
        starts.append(pos)
        pos += len(line.text) + 1
    section = _section_for(sections, page.page_no)

    def emit(text: str, indexes: list[int], header: str | None = None) -> None:
        first, last = indexes[0], indexes[-1]
        for piece in _split_long(text):
            if not piece:
                continue
            out.append(
                PassageResult(
                    passage_id=passage_id(vid, first_ordinal + len(out)),
                    section_id=section,
                    page_no=page.page_no,
                    passage_no=len(out) + 1,
                    text=piece,
                    bbox=[
                        [
                            round(page.lines[i].x0, 1),
                            round(page.lines[i].top, 1),
                            round(page.lines[i].x1, 1),
                            round(page.lines[i].bottom, 1),
                        ]
                        for i in indexes
                    ],
                    char_start=starts[first],
                    char_end=starts[last] + len(page.lines[last].text),
                    content_hash=hashlib.sha256(_WS.sub(" ", piece).strip().encode()).hexdigest(),
                    table_header=header,
                )
            )

    table_header: str | None = None
    pending: list[int] = []  # short paragraphs being joined

    def flush() -> None:
        if pending:
            emit(_clean([page.lines[i].text for i in pending]), list(pending))
            pending.clear()

    for block in _paragraphs(page, suppressed):
        idx = block["lines"]
        if block["kind"] == "row":
            flush()
            row_text = page.lines[idx[0]].text
            if table_header is None:
                table_header = row_text  # the first row of a table is its header
                emit(row_text, idx, None)
            else:
                emit(row_text, idx, table_header)
            continue
        table_header = None
        text = _clean([page.lines[i].text for i in idx])
        if block["kind"] in ("heading", "item") or len(text) >= PASSAGE_MIN_CHARS:
            flush()
            emit(text, idx)
            continue
        pending.extend(idx)  # a short paragraph: joined with the next until the minimum is met
        if len(_clean([page.lines[i].text for i in pending])) >= PASSAGE_MIN_CHARS:
            flush()
    flush()
    return out


def _not_english(pages: list[PageResult]) -> bool:
    letters = [c for p in pages[:20] for c in p.text if c.isalpha()]
    if len(letters) < 200:
        return False
    non_ascii = sum(1 for c in letters if ord(c) > 127)
    return non_ascii / len(letters) > 0.3
