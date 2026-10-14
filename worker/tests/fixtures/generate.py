"""Write the synthetic documents of the drills to a directory (AGENTS rule 15).

uv run python tests/fixtures/generate.py OUTDIR [PAGES]
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fixtures import synthetic  # noqa: E402


def main() -> None:
    out = Path(sys.argv[1])
    pages = int(sys.argv[2]) if len(sys.argv) > 2 else 30
    out.mkdir(parents=True, exist_ok=True)
    (out / "rfp.pdf").write_bytes(synthetic.make_rfp(pages))
    (out / "sow.docx").write_bytes(synthetic.make_docx(40))
    (out / "tracked.docx").write_bytes(synthetic.make_docx(10, tracked_change=True))
    (out / "protected.pdf").write_bytes(synthetic.make_protected())
    (out / "textless.pdf").write_bytes(synthetic.make_textless())
    (out / "corrupt.pdf").write_bytes(synthetic.make_corrupt())
    (out / "notes.txt").write_bytes(b"plain text, not a document")
    expected = ["Executive summary", "Technical solution", "Implementation plan", "Pricing"]
    (out / "rfp-structured.pdf").write_bytes(synthetic.make_structured_rfp(expected))
    (out / "proposal-structured.pdf").write_bytes(
        synthetic.make_structured_proposal(["Executive summary", "Technical solution", "Pricing"])
    )
    print(f"wrote {len(list(out.iterdir()))} files to {out}")


if __name__ == "__main__":
    main()
