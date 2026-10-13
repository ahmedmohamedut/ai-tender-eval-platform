"""AI 3.1, 3.2: parsing synthetic PDFs into pages, sections and passages."""

from __future__ import annotations

import re
import uuid

import pytest
from fixtures import synthetic

from tender_worker.ingest.model import PASSAGE_MAX_CHARS, IngestFailure, passage_id
from tender_worker.ingest.pdf import parse_pdf

VERSION = "0193a7a0-0000-7000-8000-000000000003"


@pytest.fixture(scope="module")
def rfp() -> bytes:
    return synthetic.make_rfp(12)


@pytest.fixture(scope="module")
def parsed(rfp):
    return parse_pdf(rfp, VERSION)


def test_every_page_is_read_with_its_size(parsed):
    assert [p.page_no for p in parsed.pages] == list(range(1, 13))
    assert all(p.width > 500 and p.height > 800 for p in parsed.pages)
    assert not any(p.textless for p in parsed.pages)


def test_passages_follow_the_rules_of_ai_3_2(parsed):
    assert len(parsed.passages) > 12
    ids = [p.passage_id for p in parsed.passages]
    assert len(set(ids)) == len(ids)
    # ids are uuid5 of the version and the ordinal, in reading order across the document
    assert ids == [passage_id(uuid.UUID(VERSION), i) for i in range(len(ids))]
    for p in parsed.passages:
        assert p.text and len(p.text) <= PASSAGE_MAX_CHARS
        assert "\n" not in p.text
        assert re.fullmatch(r"[0-9a-f]{64}", p.content_hash)
        assert p.char_end >= p.char_start
        assert p.bbox and all(len(b) == 4 and b[2] > b[0] and b[3] > b[1] for b in p.bbox)
    # passage numbers restart on each page and a passage never crosses a page
    by_page: dict[int, list[int]] = {}
    for p in parsed.passages:
        by_page.setdefault(p.page_no, []).append(p.passage_no)
    assert all(nums == list(range(1, len(nums) + 1)) for nums in by_page.values())


def test_a_passage_is_found_in_its_page_text(parsed):
    for p in parsed.passages[:40]:
        page = parsed.pages[p.page_no - 1].text
        words = p.text.split()[:4]
        assert " ".join(words) in re.sub(r"\s+", " ", page)


def test_running_header_and_footer_are_left_out_of_passages(parsed):
    assert not any("Confidential" in p.text for p in parsed.passages)
    assert any("Confidential" in page.text for page in parsed.pages)  # kept in the page text
    assert not any(re.fullmatch(r"Page \d+", p.text) for p in parsed.passages)


def test_bookmarks_become_sections_with_parents_and_ranges(parsed):
    titles = [s.title for s in parsed.sections]
    assert any(t.startswith("1. Chapter 1") for t in titles)
    top = [s for s in parsed.sections if s.level == 1]
    assert len(top) == 3
    assert all(s.parent_section_id is None for s in top)
    child = next(s for s in parsed.sections if s.level == 2)
    assert child.parent_section_id in {s.section_id for s in top}
    assert all(1 <= s.start_page <= s.end_page <= 12 for s in parsed.sections)
    assert [s.sort_order for s in parsed.sections] == list(range(len(parsed.sections)))
    # every passage points at a section that exists
    ids = {s.section_id for s in parsed.sections}
    assert all(p.section_id in ids for p in parsed.passages)


def test_table_rows_are_passages_with_their_header(parsed):
    rows = [p for p in parsed.passages if p.table_header]
    assert rows, "the generated tables must produce row passages"
    assert all(p.table_header.startswith("Id") for p in rows)
    assert all("|" in p.text for p in rows)


def test_parsing_twice_gives_the_same_ids_and_text(rfp, parsed):
    again = parse_pdf(rfp, VERSION)
    assert [(p.passage_id, p.text, p.bbox) for p in again.passages] == [
        (p.passage_id, p.text, p.bbox) for p in parsed.passages
    ]


def test_two_columns_are_read_one_after_the_other():
    result = parse_pdf(synthetic.make_two_column(), VERSION)
    text = result.pages[0].text
    last_left = text.rindex("LEFT column line")
    first_right = text.index("RIGHT column line")
    assert last_left < first_right


def test_a_password_protected_file_fails_with_its_own_code():
    with pytest.raises(IngestFailure) as err:
        parse_pdf(synthetic.make_protected(), VERSION)
    assert err.value.code == "password_protected"


def test_a_file_without_text_fails_with_its_own_code():
    with pytest.raises(IngestFailure) as err:
        parse_pdf(synthetic.make_textless(), VERSION)
    assert err.value.code == "no_selectable_text"


def test_a_corrupt_file_is_unsupported_not_a_crash():
    with pytest.raises(IngestFailure) as err:
        parse_pdf(synthetic.make_corrupt(), VERSION)
    assert err.value.code == "unsupported_format"


def test_a_few_empty_pages_are_accepted_with_a_warning():
    import io

    from pypdf import PdfReader, PdfWriter

    text = PdfReader(io.BytesIO(synthetic.make_rfp(6, with_table=False)))
    blank = PdfReader(io.BytesIO(synthetic.make_textless(1)))
    w = PdfWriter()
    for p in text.pages:
        w.add_page(p)
    w.add_page(blank.pages[0])
    out = io.BytesIO()
    w.write(out)
    result = parse_pdf(out.getvalue(), VERSION)
    codes = {x.code: x for x in result.warnings}
    assert "textless_pages" in codes and codes["textless_pages"].count == 1
    assert result.pages[-1].textless
