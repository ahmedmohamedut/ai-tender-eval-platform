"""DOCX to markdown blocks and their alignment with passages (AI 3.1, 3.3)."""

from __future__ import annotations

import io

import docx
from docx.oxml import OxmlElement

from tender_worker.ingest.docx_markdown import (
    TABLE_ROW,
    Block,
    align_blocks,
    blocks_from_jsonl,
    blocks_to_jsonl,
    parse_docx_blocks,
    render_markdown,
)


def _bold(cell, text: str) -> None:
    cell.text = ""
    cell.paragraphs[0].add_run(text).bold = True


def _build() -> bytes:
    d = docx.Document()
    d.add_heading("Security", level=1)
    d.add_paragraph("The vendor shall encrypt all data at rest and in transit.")
    d.add_heading("Access control", level=2)
    d.add_paragraph("The vendor shall support the following:")
    d.add_paragraph("Multi-factor authentication for every user", style="List Bullet")
    d.add_paragraph("Role based access control", style="List Bullet")
    d.add_heading("Controls", level=1)
    t = d.add_table(rows=3, cols=3)
    for i, h in enumerate(("Ref", "Control", "Mandatory")):
        _bold(t.rows[0].cells[i], h)
    for r, (ref, ctl, m) in enumerate(
        (("C-1", "Encrypt backups with AES-256", "Yes"), ("C-2", "Log every admin action", "No")),
        start=1,
    ):
        for i, v in enumerate((ref, ctl, m)):
            t.rows[r].cells[i].text = v
    return _save(d)


def _save(d) -> bytes:
    out = io.BytesIO()
    d.save(out)
    return out.getvalue()


def test_headings_lists_and_paths():
    blocks = parse_docx_blocks(_build())
    assert [b.block_id for b in blocks][:3] == ["B1", "B2", "B3"]
    by_text = {b.text: b for b in blocks}
    assert by_text["Security"].kind == "heading" and by_text["Security"].heading_path == "Security"
    para = by_text["The vendor shall encrypt all data at rest and in transit."]
    assert para.kind == "paragraph" and para.heading_path == "Security"
    item = by_text["Role based access control"]
    assert item.kind == "list_item"
    assert item.heading_path == "Security > Access control"
    assert by_text["Controls"].heading_path == "Controls"  # a level 1 heading closes level 2


def test_table_rows_carry_their_column_labels():
    rows = [b for b in parse_docx_blocks(_build()) if b.kind == "table_row"]
    assert [r.text for r in rows] == [
        "C-1 | Encrypt backups with AES-256 | Yes",
        "C-2 | Log every admin action | No",
    ]
    assert rows[0].label == "Ref: C-1 | Control: Encrypt backups with AES-256 | Mandatory: Yes"
    assert rows[0].shown.startswith(TABLE_ROW)
    assert rows[0].heading_path == "Controls"


def test_merged_cells_appear_once():
    d = docx.Document()
    d.add_heading("Plan", level=1)
    t = d.add_table(rows=2, cols=2)
    _bold(t.rows[0].cells[0], "Phase")
    _bold(t.rows[0].cells[1], "Output")
    merged = t.rows[1].cells[0].merge(t.rows[1].cells[1])
    merged.text = "Both phases deliver the migration plan"
    rows = [b for b in parse_docx_blocks(_save(d)) if b.kind == "table_row"]
    assert [r.text for r in rows] == ["Both phases deliver the migration plan"]
    assert rows[0].label == "Phase / Output: Both phases deliver the migration plan"


def test_content_controls_are_read():
    d = docx.Document()
    d.add_heading("Scope", level=1)
    sdt = OxmlElement("w:sdt")
    content = OxmlElement("w:sdtContent")
    p = OxmlElement("w:p")
    r = OxmlElement("w:r")
    t = OxmlElement("w:t")
    t.text = "Answer inside a content control"
    r.append(t)
    p.append(r)
    content.append(p)
    sdt.append(content)
    d.element.body.insert(len(d.element.body) - 1, sdt)
    texts = [b.text for b in parse_docx_blocks(_save(d))]
    assert "Answer inside a content control" in texts


def test_tracked_deletions_are_not_read_insertions_are():
    d = docx.Document()
    d.add_heading("Terms", level=1)
    para = d.add_paragraph("")
    ins = OxmlElement("w:ins")
    run = OxmlElement("w:r")
    text = OxmlElement("w:t")
    text.text = "inserted words stay"
    run.append(text)
    ins.append(run)
    para._p.append(ins)
    dele = OxmlElement("w:del")
    run2 = OxmlElement("w:r")
    gone = OxmlElement("w:delText")
    gone.text = "deleted words go"
    run2.append(gone)
    dele.append(run2)
    para._p.append(dele)
    joined = " ".join(b.text for b in parse_docx_blocks(_save(d)))
    assert "inserted words stay" in joined and "deleted words go" not in joined


def test_markdown_and_jsonl_round_trip():
    blocks = parse_docx_blocks(_build())
    md = render_markdown(blocks)
    assert md.startswith("# Security\n\n")
    assert "## Access control" in md
    assert "- Multi-factor authentication for every user" in md
    assert f"- {TABLE_ROW}Ref: C-1" in md or f"{TABLE_ROW}Ref: C-1" in md
    again = blocks_from_jsonl(blocks_to_jsonl(blocks))
    assert [b.__dict__ for b in again] == [b.__dict__ for b in blocks]


def _passages(*texts: str) -> list[dict]:
    return [{"passage_id": f"p{i}", "text": t} for i, t in enumerate(texts)]


def test_blocks_align_with_passages_by_exact_containment():
    blocks = [
        Block("B1", "A", "paragraph", "The vendor shall encrypt all data at rest."),
        Block("B2", "A", "table_row", "C-1 | Encrypt backups | Yes", "Ref: C-1 | Control: x"),
        Block("B3", "A", "paragraph", "Text that appears in no passage at all."),
    ]
    passages = _passages(
        "Intro. The vendor shall encrypt all data at rest.  Another sentence follows.",
        "C-1 | Encrypt backups | Yes",
    )
    assert align_blocks(blocks, passages) == 1
    assert blocks[0].passage_ids == ["p0"]
    assert blocks[1].passage_ids == ["p1"]
    assert blocks[2].passage_ids == []


def test_a_long_block_split_over_passages_lists_them_all():
    first = "Alpha beta gamma delta epsilon zeta eta theta."
    second = "Iota kappa lambda mu nu xi omicron pi rho sigma."
    block = Block("B1", "A", "paragraph", f"{first} {second}")
    assert align_blocks([block], _passages("Other text here that differs", first, second)) == 0
    assert block.passage_ids == ["p1", "p2"]


def test_a_tiny_passage_does_not_match_a_long_block():
    block = Block("B1", "A", "paragraph", "Section 1. Scope of the whole engagement is large.")
    assert align_blocks([block], _passages("1.")) == 1
