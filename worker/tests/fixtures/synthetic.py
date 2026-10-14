"""Synthetic documents for tests and drills (AGENTS rule 15: no real proposal enters the repo).

Everything is generated from fixed text with a fixed seed, so a run is repeatable.
"""

from __future__ import annotations

import io
import random

from pypdf import PdfReader, PdfWriter
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import (
    PageBreak,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)

_WORDS = (
    "the supplier shall provide a managed service with documented support hours incident "
    "response and monthly reporting; data is stored in the agreed region and encrypted at rest "
    "using approved algorithms; the platform supports single sign-on, role based access and "
    "audit logging for every administrative action; availability is measured per calendar month"
).split()


def _sentence(rng: random.Random, n: int = 18) -> str:
    words = [rng.choice(_WORDS) for _ in range(n)]
    return words[0].capitalize() + " " + " ".join(words[1:]) + "."


def _paragraph(rng: random.Random, sentences: int = 4) -> str:
    return " ".join(_sentence(rng) for _ in range(sentences))


class _Doc(SimpleDocTemplate):
    """Writes PDF bookmarks for every heading and a running header and footer."""

    def __init__(self, *args, header: str | None = "Cloud Platform Tender - Confidential", **kw):
        super().__init__(*args, **kw)
        self._header = header
        self._outline_keys = 0

    def afterFlowable(self, flowable):  # noqa: N802 - reportlab hook
        if isinstance(flowable, Paragraph) and flowable.style.name in ("H1", "H2"):
            level = 0 if flowable.style.name == "H1" else 1
            key = f"h{self._outline_keys}"
            self._outline_keys += 1
            self.canv.bookmarkPage(key)
            self.canv.addOutlineEntry(flowable.getPlainText(), key, level=level, closed=False)


def _decorate(header: str | None):
    def draw(canvas, doc):
        canvas.saveState()
        canvas.setFont("Helvetica", 8)
        if header:
            canvas.drawString(20 * mm, A4[1] - 10 * mm, header)
        canvas.drawString(20 * mm, 10 * mm, f"Page {doc.page}")
        canvas.restoreState()

    return draw


def make_rfp(pages: int = 12, *, seed: int = 7, with_table: bool = True) -> bytes:
    """A text PDF: numbered H1/H2 headings with bookmarks, paragraphs, lists, tables, header."""
    rng = random.Random(seed)
    styles = getSampleStyleSheet()
    h1 = ParagraphStyle("H1", parent=styles["Heading1"], fontSize=16)
    h2 = ParagraphStyle("H2", parent=styles["Heading2"], fontSize=13)
    body = ParagraphStyle("Body", parent=styles["BodyText"], fontSize=10, leading=13)
    out = io.BytesIO()
    doc = _Doc(out, pagesize=A4, leftMargin=20 * mm, rightMargin=20 * mm, topMargin=22 * mm)
    story: list = []
    chapter = 0
    for page in range(pages):
        if page % 4 == 0:
            chapter += 1
            story.append(Paragraph(f"{chapter}. Chapter {chapter} requirements", h1))
        story.append(Paragraph(f"{chapter}.{page % 4 + 1} Topic {page + 1}", h2))
        for _ in range(3):
            story.append(Paragraph(_paragraph(rng), body))
            story.append(Spacer(1, 4 * mm))
        story.append(
            Paragraph(f"{page + 1}. The supplier must support requirement R{page + 1:03d}.", body)
        )
        if with_table and page % 5 == 2:
            data = [["Id", "Requirement", "Priority"]] + [
                [f"T-{page}-{i}", _sentence(rng, 8), rng.choice(["Must", "Should"])]
                for i in range(1, 4)
            ]
            t = Table(data, colWidths=[25 * mm, 110 * mm, 25 * mm])
            t.setStyle(
                TableStyle(
                    [
                        ("GRID", (0, 0), (-1, -1), 0.5, colors.black),
                        ("BACKGROUND", (0, 0), (-1, 0), colors.lightgrey),
                    ]
                )
            )
            story.append(t)
        story.append(PageBreak())
    doc.build(story, onFirstPage=_decorate(doc._header), onLaterPages=_decorate(doc._header))
    return out.getvalue()


def make_two_column(*, seed: int = 3) -> bytes:
    """One page with two columns; the left column must be read first."""
    from reportlab.pdfgen import canvas

    rng = random.Random(seed)
    out = io.BytesIO()
    c = canvas.Canvas(out, pagesize=A4)
    width, height = A4
    c.setFont("Helvetica", 10)
    for col, label in enumerate(["LEFT", "RIGHT"]):
        x = 20 * mm + col * (width / 2)
        y = height - 30 * mm
        for line in range(30):
            c.drawString(x, y, f"{label} column line {line + 1} {_sentence(rng, 5)}"[:60])
            y -= 12
    c.showPage()
    c.save()
    return out.getvalue()


def make_protected(plain: bytes | None = None) -> bytes:
    writer = PdfWriter(clone_from=PdfReader(io.BytesIO(plain or make_rfp(2))))
    writer.encrypt("user-secret", "owner-secret")
    out = io.BytesIO()
    writer.write(out)
    return out.getvalue()


def make_textless(pages: int = 3) -> bytes:
    """Pages that only hold shapes: what a scan without OCR looks like to a text parser."""
    from reportlab.pdfgen import canvas

    out = io.BytesIO()
    c = canvas.Canvas(out, pagesize=A4)
    for n in range(pages):
        c.rect(30 * mm, 40 * mm, 100 * mm, 150 * mm, fill=1)
        c.circle(100 * mm, 100 * mm, 20 * mm + n)
        c.showPage()
    c.save()
    return out.getvalue()


def make_corrupt() -> bytes:
    return b"%PDF-1.7\n" + bytes(random.Random(1).randrange(256) for _ in range(4000))


def make_docx(paragraphs: int = 30, *, seed: int = 5, tracked_change: bool = False) -> bytes:
    import docx

    rng = random.Random(seed)
    d = docx.Document()
    d.add_heading("Statement of work", level=1)
    for i in range(paragraphs):
        if i % 8 == 0:
            d.add_heading(f"Section {i // 8 + 1}", level=2)
        d.add_paragraph(_paragraph(rng, 3))
    table = d.add_table(rows=3, cols=2)
    table.style = "Table Grid"
    for r, row in enumerate(table.rows):
        row.cells[0].text = f"Item {r}"
        row.cells[1].text = _sentence(rng, 6)
    out = io.BytesIO()
    d.save(out)
    data = out.getvalue()
    if tracked_change:
        import zipfile

        src = zipfile.ZipFile(io.BytesIO(data))
        dst_io = io.BytesIO()
        with zipfile.ZipFile(dst_io, "w", zipfile.ZIP_DEFLATED) as dst:
            for item in src.infolist():
                content = src.read(item.filename)
                if item.filename == "word/document.xml":
                    content = content.replace(
                        b"<w:body>",
                        b'<w:body><w:p><w:ins w:id="1" w:author="a" w:date="2026-01-01T00:00:00Z">'
                        b"<w:r><w:t>added words</w:t></w:r></w:ins></w:p>",
                        1,
                    )
                dst.writestr(item, content)
        data = dst_io.getvalue()
    return data


def make_structured(
    title: str, sections: list[tuple[str, list[str]]], *, header: str | None = None
) -> bytes:
    """A text PDF with one H1 (and bookmark) per section and the given paragraphs under it."""
    styles = getSampleStyleSheet()
    h1 = ParagraphStyle("H1", parent=styles["Heading1"], fontSize=16)
    body = ParagraphStyle("Body", parent=styles["BodyText"], fontSize=10, leading=13)
    out = io.BytesIO()
    doc = _Doc(
        out, pagesize=A4, leftMargin=20 * mm, rightMargin=20 * mm, topMargin=22 * mm, header=header
    )
    story: list = [
        Paragraph(title, ParagraphStyle("Title", parent=styles["Title"])),
        Spacer(1, 6 * mm),
    ]
    for heading, paragraphs in sections:
        story.append(Paragraph(heading, h1))
        for text in paragraphs:
            story.append(Paragraph(text, body))
            story.append(Spacer(1, 3 * mm))
        story.append(PageBreak())
    deco = _decorate(header)
    doc.build(story, onFirstPage=deco, onLaterPages=deco)
    return out.getvalue()


def make_structured_rfp(expected: list[str]) -> bytes:
    """An RFP that says which sections a proposal must include (the pre-check's input)."""
    names = ", ".join(expected[:-1]) + " and " + expected[-1]
    return make_structured(
        "Request for proposal: managed platform",
        [
            ("1. Background", ["The ministry wants to move its registers to a managed platform."]),
            (
                "2. Instructions to vendors",
                [
                    "The proposal must include the following sections: " + names + ".",
                    "Proposals are submitted as one PDF file in English.",
                ],
            ),
            (
                "3. Requirements",
                [
                    "The supplier shall encrypt all customer data at rest.",
                    "The supplier must provide support during working hours.",
                ],
            ),
        ],
    )


def make_structured_proposal(present: list[str]) -> bytes:
    """A proposal with one section per heading in `present`."""
    return make_structured(
        "Proposal: managed platform",
        [
            (
                name,
                [f"This section describes our {name.lower()} in detail for the ministry."]
                + (
                    [
                        "We encrypt all customer data at rest using AES-256 in every region.",
                        "Support is provided during working hours by a named service team.",
                    ]
                    if name == "Technical solution"
                    else []
                ),
            )
            for name in present
        ],
    )
