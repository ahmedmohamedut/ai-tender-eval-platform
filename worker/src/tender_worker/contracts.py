"""Export the JSON Schemas of the message models (implementation guide 4.2).

    python -m tender_worker.contracts export ../contracts/messages
    python -m tender_worker.contracts check  ../contracts/messages

`check` fails when the committed files differ from a fresh export, which is what
CI runs: a model change without a re-export cannot be merged.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from tender_worker.messages import (
    MESSAGE_MODELS,
    SCHEMA_COMMAND,
    SCHEMA_PUSH,
    SCHEMA_RESULT,
    SCHEMA_STATUS,
)

SCHEMA_DRAFT = "https://json-schema.org/draft/2020-12/schema"

# Value of the Tender-Schema header each message carries (name and major version).
HEADER_VALUE = {
    "command": SCHEMA_COMMAND,
    "status": SCHEMA_STATUS,
    "result_batch": SCHEMA_RESULT,
    "push_event": SCHEMA_PUSH,
}


def render() -> dict[str, str]:
    files: dict[str, str] = {}
    for name, model in MESSAGE_MODELS.items():
        schema = {"$schema": SCHEMA_DRAFT, **model.model_json_schema()}
        schema["x-tender-schema"] = HEADER_VALUE[name]
        files[f"{name}.schema.json"] = json.dumps(schema, indent=2, sort_keys=True) + "\n"
    return files


def export(target: Path) -> None:
    target.mkdir(parents=True, exist_ok=True)
    for filename, text in render().items():
        (target / filename).write_text(text, encoding="utf-8")
        print(f"wrote {target / filename}")


def check(target: Path) -> int:
    expected = render()
    problems = []
    for filename, text in expected.items():
        path = target / filename
        if not path.exists():
            problems.append(f"missing {path}")
        elif path.read_text(encoding="utf-8") != text:
            problems.append(f"out of date: {path}")
    existing = {p.name for p in target.glob("*.schema.json")}
    problems += [f"unexpected file: {target / n}" for n in sorted(existing - set(expected))]
    for problem in problems:
        print(problem, file=sys.stderr)
    if not problems:
        print(f"{len(expected)} schemas match the models")
    return 1 if problems else 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="tender_worker.contracts")
    parser.add_argument("command", choices=["export", "check"])
    parser.add_argument("directory", type=Path)
    args = parser.parse_args(argv)
    if args.command == "export":
        export(args.directory)
        return 0
    return check(args.directory)


if __name__ == "__main__":
    raise SystemExit(main())
