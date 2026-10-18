"""AI 4.1, 4.3: the scope as tagged markdown blocks, chunks and quote location."""

from __future__ import annotations

import uuid

from tender_worker.agent.markdown import (
    Chunk,
    MarkdownSource,
    MdBlock,
    candidate_obligations,
    chunk_blocks,
    locate_quote,
)
from tender_worker.agent.retrieval import PassageRef, ScopeItem
from tender_worker.ingest.docx_markdown import Block, blocks_to_jsonl

TENDER = uuid.UUID("0193a7a0-0000-7000-8000-000000000002")
VERSION = "0193a7a0-0000-7000-8000-000000000003"


def passage(i: int, text: str, *, header: str | None = None, path=("Security",)) -> PassageRef:
    return PassageRef(f"p{i}", VERSION, i, 1, text, (), header, path)


class Passages:
    def __init__(self, items: list[PassageRef]) -> None:
        self.items = items

    async def load(self, tender_id, scope):
        return self.items


def source(items: list[PassageRef], stored: bytes | None = None) -> MarkdownSource:
    async def read(_tender, _version):
        return stored

    return MarkdownSource(Passages(items), read)


SCOPE = [ScopeItem(VERSION)]


async def test_without_stored_blocks_the_passages_are_rendered():
    items = [
        passage(1, "The vendor shall encrypt data at rest and in transit."),
        passage(2, "Ref | Control", header="Ref | Control"),
        passage(3, "C-1 | Encrypt backups", header="Ref | Control"),
    ]
    blocks = await source(items).load(TENDER, SCOPE)
    assert [b.tag for b in blocks] == ["B1", "B2", "B3"]
    assert blocks[0].heading_path == "Security" and blocks[0].kind == "paragraph"
    assert blocks[2].kind == "table_row"
    assert blocks[2].shown == "[Table row] Ref: C-1 | Control: Encrypt backups"
    assert all(b.citable for b in blocks)


async def test_stored_docx_blocks_are_used_and_limited_to_the_scope():
    items = [passage(1, "The vendor shall support single sign on for users.")]
    stored = blocks_to_jsonl(
        [
            Block(
                "B1",
                "Other",
                "paragraph",
                "Text from another section entirely.",
                passage_ids=["p9"],
            ),
            Block("B2", "Security", "heading", "Security", level=1, passage_ids=[]),
            Block(
                "B3",
                "Security",
                "paragraph",
                "The vendor shall support single sign on for users.",
                passage_ids=["p1"],
            ),
            Block(
                "B4",
                "Security",
                "paragraph",
                "A block that did not align to any page.",
                passage_ids=[],
            ),
            Block("B5", "Security", "paragraph", "Out of scope again.", passage_ids=["p8"]),
        ]
    )
    blocks = await source(items, stored).load(TENDER, SCOPE)
    # from the first to the last block in scope; the block aligned elsewhere is dropped
    assert [b.block_id for b in blocks] == ["B3"]
    items2 = items + [passage(2, "Closing statement of the section here.")]
    stored2 = blocks_to_jsonl(
        [
            Block(
                "B1",
                "S",
                "paragraph",
                "The vendor shall support single sign on for users.",
                passage_ids=["p1"],
            ),
            Block("B2", "S", "paragraph", "Unaligned context between two blocks.", passage_ids=[]),
            Block(
                "B3", "S", "paragraph", "Closing statement of the section here.", passage_ids=["p2"]
            ),
        ]
    )
    both = await source(items2, stored2).load(TENDER, SCOPE)
    assert [(b.block_id, b.citable) for b in both] == [("B1", True), ("B2", False), ("B3", True)]


async def test_unreadable_or_unrelated_stored_blocks_fall_back_to_passages():
    items = [passage(1, "The vendor shall back up data every night.")]
    for stored in (
        b"not json at all\n",
        blocks_to_jsonl([Block("B1", "S", "paragraph", "x", passage_ids=["zz"])]),
    ):
        blocks = await source(items, stored).load(TENDER, SCOPE)
        assert [b.block_id for b in blocks] == ["p1"]


def md(tag: str, text: str, path: str = "A", kind: str = "paragraph", shown: str | None = None):
    p = PassageRef(f"p-{tag}", VERSION, 1, 1, text, (), None, ())
    return MdBlock(tag, VERSION, tag, path, kind, text, shown or text, (p,))


def test_chunks_split_on_block_boundaries_and_carry_the_lead_in():
    lead = md("B1", "The vendor shall support the following:")
    items = [md(f"B{i}", f"Capability number {i} is required of the vendor.") for i in range(2, 8)]
    chunks = chunk_blocks([lead, *items], max_chars=130, max_lines=25)
    assert len(chunks) > 1 and all(c.n_parts == len(chunks) for c in chunks)
    assert chunks[0].lead_in is None and chunks[1].lead_in is lead
    assert chunks[1].render().startswith("(Section intro) [B1] The vendor shall support")
    seen = [b.tag for c in chunks for b in c.blocks]
    assert seen == [b.tag for b in [lead, *items]]  # nothing lost, nothing repeated
    assert chunks[1].label.endswith(f"(part 2/{len(chunks)})")


def test_a_new_heading_path_starts_a_new_chunk_and_headings_are_not_text():
    blocks = [
        md("B1", "Security", "Security", "heading"),
        md("B2", "The vendor shall encrypt everything.", "Security"),
        md("B3", "The vendor shall log every action.", "Security > Logging"),
    ]
    chunks = chunk_blocks(blocks)
    assert [c.heading_path for c in chunks] == ["Security", "Security > Logging"]
    assert [b.tag for b in chunks[0].blocks] == ["B2"]


def test_a_table_row_is_never_split_and_a_long_block_is_kept_whole():
    big = md("B1", "word " * 2000)
    row = md(
        "B2", "C-1 | Encrypt", kind="table_row", shown="[Table row] Ref: C-1 | Control: Encrypt"
    )
    chunks = chunk_blocks([big, row], max_chars=500)
    assert [[b.tag for b in c.blocks] for c in chunks] == [["B1"], ["B2"]]


def test_candidate_obligations_find_rows_and_modal_sentences():
    chunk = Chunk(
        "A",
        [
            md("B1", "Background only. The vendor shall encrypt data. It is fine."),
            md("B2", "C-1 | Encrypt", kind="table_row"),
        ],
    )
    assert candidate_obligations(chunk) == ["The vendor shall encrypt data.", "C-1 | Encrypt"]


def test_a_quote_is_located_in_the_passage_that_holds_it():
    first = PassageRef(
        "p1", VERSION, 1, 1, "Alpha beta gamma delta epsilon zeta eta theta.", (), None, ()
    )
    second = PassageRef(
        "p2", VERSION, 2, 2, "Iota kappa lambda mu nu xi omicron pi rho.", (), None, ()
    )
    block = MdBlock(
        "B1",
        VERSION,
        "b",
        "A",
        "paragraph",
        f"{first.text} {second.text}",
        f"{first.text} {second.text}",
        (first, second),
    )
    got = locate_quote(block, "Iota kappa lambda mu nu xi")
    assert (
        got is not None and got.passage.id == "p2" and got.excerpt == "Iota kappa lambda mu nu xi"
    )
    assert locate_quote(block, "theta. Iota kappa lambda") is None  # crosses two passages
    assert locate_quote(block, "words that are nowhere in it") is None
    unciteable = MdBlock("B2", VERSION, "b", "A", "paragraph", "A sentence with no page.", "x", ())
    assert locate_quote(unciteable, "A sentence with no page.") is None
