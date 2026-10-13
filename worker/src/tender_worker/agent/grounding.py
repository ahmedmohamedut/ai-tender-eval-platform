"""Grounding checks (AI 4.6, 5.1, rule 6): the model proposes, code verifies.

A quote is accepted only when it occurs in the passage it cites after normalisation. The excerpt
that is stored is the matching slice of the passage, never the model's own string.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

MIN_QUOTE_CHARS = 10

_DASHES = dict.fromkeys(map(ord, "‐‑‒–—―−"), "-")
_QUOTES = {
    ord("‘"): "'",
    ord("’"): "'",
    ord("‚"): "'",
    ord("‛"): "'",
    ord("“"): '"',
    ord("”"): '"',
    ord("„"): '"',
}
_INVISIBLE = {0x00AD, 0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF}
_CONTROL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")


def _norm_char(ch: str) -> str:
    """One character to its normalised form: NFKC, plain quotes and dashes, spaces."""
    if ord(ch) in _INVISIBLE:
        return ""
    ch = unicodedata.normalize("NFKC", ch)
    ch = ch.translate(_DASHES).translate(_QUOTES)
    return " " if ch.isspace() else ch


def normalise_with_map(text: str) -> tuple[str, list[int]]:
    """Normalised text and, for each normalised character, its index in the original."""
    out: list[str] = []
    index: list[int] = []
    last_space = True  # leading whitespace is dropped
    for i, ch in enumerate(text):
        n = _norm_char(ch)
        for c in n:
            if c == " ":
                if last_space:
                    continue
                last_space = True
            else:
                last_space = False
            out.append(c)
            index.append(i)
    while out and out[-1] == " ":
        out.pop()
        index.pop()
    return "".join(out), index


def normalise(text: str) -> str:
    return normalise_with_map(text)[0]


@dataclass(frozen=True)
class Verified:
    excerpt: str
    start: int
    end: int


def verify_quote(quote: str, passage_text: str) -> Verified | None:
    """The slice of `passage_text` the quote matches, or None (also for a too-short quote)."""
    q = normalise(quote)
    if len(q) < MIN_QUOTE_CHARS:
        return None
    haystack, index = normalise_with_map(passage_text)
    at = haystack.find(q)
    if at < 0:
        return None
    start = index[at]
    end = index[at + len(q) - 1] + 1
    return Verified(passage_text[start:end], start, end)


def sanitise_passage(text: str) -> str:
    """Control and zero-width characters removed before a passage reaches a prompt (AI 4.6)."""
    text = "".join(ch for ch in text if ord(ch) not in _INVISIBLE)
    return _CONTROL.sub(" ", text)


_TRIPWIRES = (
    re.compile(r"ignore (all |any )?(the )?(previous|prior|above) (instructions|prompts?)", re.I),
    re.compile(r"disregard (all |any )?(the )?(previous|prior|above)", re.I),
    re.compile(r"(reveal|print|show) (your |the )?(system|hidden) prompt", re.I),
    re.compile(r"you are now (a|an|the) ", re.I),
)


def suspected_injection(text: str) -> bool:
    return any(p.search(text) for p in _TRIPWIRES)
