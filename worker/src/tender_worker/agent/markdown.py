"""The text the model reads, as tagged markdown blocks (AI 4.1, 4.3).

The scope of a job (a version, optionally sections or a page range) is still decided by code from
the stored passages (rule 7). What changes is the form the model sees: blocks in document order
under their heading path, table rows with their column labels, list items as bullets. A DOCX
version has the blocks written at ingest (`blocks.jsonl`, aligned to the passages of the converted
PDF); every other version, and every version ingested before the blocks existed, is rendered from
its passages.

A block carries a short tag (`B12`), numbered per run. The model answers with a tag and a quote;
code checks the quote against the block's text and then against the aligned passage, which is
what a citation points at (rule 6). A block with no passage can be read but never cited.
"""

from __future__ import annotations

import logging
import re
from collections import defaultdict
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from uuid import UUID

from tender_worker.agent.grounding import sanitise_passage, verify_quote
from tender_worker.agent.retrieval import PassageRef, PassageSource, ScopeItem
from tender_worker.ingest.docx_markdown import TABLE_ROW, blocks_from_jsonl

log = logging.getLogger(__name__)

CHUNK_CHARS = 3000
CHUNK_LINES = 25
LEAD_IN_MAX = 300

MODAL = re.compile(
    r"\b(shall|must|should|required|requires?|mandatory|need(?:s)? to|expected to|has to|"
    r"have to|responsible for|will (?:provide|support|ensure|deliver|perform|comply|"
    r"be responsible))\b",
    re.I,
)


@dataclass(frozen=True)
class MdBlock:
    tag: str
    version_id: str
    block_id: str
    heading_path: str
    kind: str  # heading | paragraph | list_item | table_row
    text: str  # what a quote is checked against
    shown: str  # the line the model reads
    passages: tuple[PassageRef, ...] = ()

    @property
    def citable(self) -> bool:
        return bool(self.passages)


@dataclass
class Chunk:
    heading_path: str
    blocks: list[MdBlock]
    part: int = 1
    n_parts: int = 1
    lead_in: MdBlock | None = None
    index: int = 0

    @property
    def label(self) -> str:
        if self.n_parts == 1:
            return self.heading_path
        return f"{self.heading_path} (part {self.part}/{self.n_parts})"

    def render(self) -> str:
        lines: list[str] = []
        if self.lead_in is not None:
            lines.append(f"(Section intro) [{self.lead_in.tag}] {_clean(self.lead_in.shown)}")
        lines.extend(f"[{b.tag}] {_clean(b.shown)}" for b in self.blocks)
        return "\n".join(lines)

    @property
    def size(self) -> int:
        return sum(len(b.shown) for b in self.blocks)


def _clean(text: str) -> str:
    return sanitise_passage(text).replace("\n", " ")


def candidate_obligations(chunk: Chunk) -> list[str]:
    """A deterministic recall proxy: table rows and sentences with obligation wording."""
    out: list[str] = []
    for b in chunk.blocks:
        if b.kind == "table_row":
            out.append(b.text)
        elif b.kind != "heading":
            out.extend(s.strip() for s in re.split(r"(?<=[.!?])\s+", b.text) if MODAL.search(s))
    return out


# ---- loading -----------------------------------------------------------------------------------


class BlobReader:
    async def get(self, bucket: str, key: str) -> bytes: ...


def blocks_key(tender_id: UUID, version_id: str) -> str:
    return f"parsed/{tender_id}/{version_id}/blocks.jsonl"


def _label_row(header: str | None, row: str) -> str:
    """`Header: value | Header: value` when the row has as many cells as the header."""
    if not header or header == row:
        return row
    heads, cells = header.split(" | "), row.split(" | ")
    if len(heads) != len(cells):
        return row
    return " | ".join(f"{h}: {c}" if h else c for h, c in zip(heads, cells, strict=True))


def blocks_from_passages(passages: list[PassageRef]) -> list[tuple[str, str, str, str, PassageRef]]:
    """(kind, heading path, text, shown, passage) per passage, in document order."""
    out = []
    for p in passages:
        path = " > ".join(p.section_path) or "General"
        if p.table_header and p.table_header != p.text:
            out.append(
                ("table_row", path, p.text, TABLE_ROW + _label_row(p.table_header, p.text), p)
            )
        else:
            out.append(("paragraph", path, p.text, p.text, p))
    return out


class MarkdownSource:
    """Scope in, tagged blocks out."""

    def __init__(
        self,
        passages: PassageSource,
        read_blocks: Callable[[UUID, str], Awaitable[bytes | None]],
    ) -> None:
        self._passages = passages
        self._read = read_blocks

    async def load(self, tender_id: UUID, scope: list[ScopeItem]) -> list[MdBlock]:
        out: list[MdBlock] = []
        for item in scope:
            in_scope = await self._passages.load(tender_id, [item])
            if not in_scope:
                continue
            blocks = await self._from_docx_blocks(tender_id, item.version_id, in_scope, len(out))
            if blocks is None:
                blocks = self._from_passages(item.version_id, in_scope, len(out))
            out.extend(blocks)
        return out

    async def _from_docx_blocks(
        self, tender_id: UUID, version_id: str, in_scope: list[PassageRef], offset: int
    ) -> list[MdBlock] | None:
        raw = await self._read(tender_id, version_id)
        if raw is None:
            return None
        try:
            stored = blocks_from_jsonl(raw)
        except (ValueError, TypeError):
            return None
        by_id = {p.id: p for p in in_scope}
        hit = [i for i, b in enumerate(stored) if any(pid in by_id for pid in b.passage_ids)]
        if not hit:
            return None
        out: list[MdBlock] = []
        for i in range(hit[0], hit[-1] + 1):
            b = stored[i]
            if b.passage_ids and not any(pid in by_id for pid in b.passage_ids):
                continue  # aligned only to text outside the scope
            out.append(
                MdBlock(
                    tag=f"B{offset + len(out) + 1}",
                    version_id=version_id,
                    block_id=b.block_id,
                    heading_path=b.heading_path,
                    kind=b.kind,
                    text=b.text,
                    shown=b.shown,
                    passages=tuple(by_id[pid] for pid in b.passage_ids if pid in by_id),
                )
            )
        return out

    @staticmethod
    def _from_passages(version_id: str, in_scope: list[PassageRef], offset: int) -> list[MdBlock]:
        return [
            MdBlock(
                tag=f"B{offset + n}",
                version_id=version_id,
                block_id=p.id,
                heading_path=path,
                kind=kind,
                text=text,
                shown=shown,
                passages=(p,),
            )
            for n, (kind, path, text, shown, p) in enumerate(blocks_from_passages(in_scope), 1)
        ]


# ---- chunking ----------------------------------------------------------------------------------


def _split_long(line: str, max_chars: int) -> list[str]:
    if len(line) <= max_chars:
        return [line]
    out: list[str] = []
    cur = ""
    for s in re.split(r"(?<=[.!?;])\s+", line):
        if cur and len(cur) + len(s) + 1 > max_chars:
            out.append(cur)
            cur = s
        else:
            cur = f"{cur} {s}".strip()
    if cur:
        out.append(cur)
    final: list[str] = []
    for piece in out:
        while len(piece) > max_chars:
            final.append(piece[:max_chars])
            piece = piece[max_chars:]
        final.append(piece)
    return final


def chunk_blocks(
    blocks: list[MdBlock], max_chars: int = CHUNK_CHARS, max_lines: int = CHUNK_LINES
) -> list[Chunk]:
    """Blocks of one heading path, cut on block boundaries (never inside a table row).

    Later parts of a section carry its opening line as context, so a lead-in such as "The vendor
    shall support the following:" is not lost. A block longer than `max_chars` is read whole: the
    model needs the sentence, and the quote is checked against the whole block anyway.
    """
    runs: list[tuple[str, list[MdBlock]]] = []
    for b in blocks:
        if b.kind == "heading":
            continue  # the heading is the path of the blocks under it
        if runs and runs[-1][0] == b.heading_path and runs[-1][1][-1].version_id == b.version_id:
            runs[-1][1].append(b)
        else:
            runs.append((b.heading_path, [b]))
    chunks: list[Chunk] = []
    for path, group in runs:
        pieces: list[list[MdBlock]] = []
        cur: list[MdBlock] = []
        size = 0
        for b in group:
            if cur and (size + len(b.shown) > max_chars or len(cur) >= max_lines):
                pieces.append(cur)
                cur, size = [], 0
            cur.append(b)
            size += len(b.shown) + 1
        if cur:
            pieces.append(cur)
        first = group[0]
        lead = first if len(first.shown) < LEAD_IN_MAX and first.kind != "table_row" else None
        for n, piece in enumerate(pieces, 1):
            chunks.append(
                Chunk(
                    path,
                    piece,
                    part=n,
                    n_parts=len(pieces),
                    lead_in=lead if n > 1 and lead is not None and piece[0] is not lead else None,
                )
            )
    for i, c in enumerate(chunks):
        c.index = i
    return chunks


# ---- checking a tag and a quote ----------------------------------------------------------------


@dataclass(frozen=True)
class Located:
    block: MdBlock
    passage: PassageRef
    excerpt: str


def locate_quote(block: MdBlock, quote: str) -> Located | None:
    """The passage of `block` that holds `quote`, with the passage's own slice as the excerpt.

    The quote must occur in the block's text and in one of its aligned passages (rule 6). A quote
    that crosses two passages is refused: a citation points at one.
    """
    if verify_quote(quote, block.text) is None:
        return None
    for p in block.passages:
        v = verify_quote(quote, p.text)
        if v is not None:
            return Located(block, p, v.excerpt)
    return None


def index_by_tag(blocks: list[MdBlock]) -> dict[str, MdBlock]:
    return {b.tag: b for b in blocks}


def group_by_version(blocks: list[MdBlock]) -> dict[str, list[MdBlock]]:
    out: dict[str, list[MdBlock]] = defaultdict(list)
    for b in blocks:
        out[b.version_id].append(b)
    return out
