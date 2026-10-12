import json
from pathlib import Path

from tender_worker.contracts import check, export, render

ROOT = Path(__file__).resolve().parents[2] / "contracts" / "messages"


def test_one_schema_per_message_with_header_value():
    files = render()
    assert sorted(files) == [
        "command.schema.json",
        "push_event.schema.json",
        "result_batch.schema.json",
        "status.schema.json",
    ]
    status = json.loads(files["status.schema.json"])
    assert status["x-tender-schema"] == "tender.analysis.status/1"
    assert status["$schema"].endswith("2020-12/schema")


def test_export_then_check_round_trips(tmp_path):
    export(tmp_path)
    assert check(tmp_path) == 0
    (tmp_path / "status.schema.json").write_text("{}\n")
    assert check(tmp_path) == 1


def test_committed_schemas_are_current():
    assert check(ROOT) == 0


SAMPLES = ROOT / "samples"
MODEL_FOR_PREFIX = {
    "command": "command",
    "status": "status",
    "result_batch": "result_batch",
    "push_event": "push_event",
}


def _cases(kind: str):
    return sorted((SAMPLES / kind).glob("*.json"))


def _model(path: Path):
    from tender_worker.messages import MESSAGE_MODELS

    return MESSAGE_MODELS[MODEL_FOR_PREFIX[path.name.split(".")[0]]]


def test_valid_samples_are_accepted_by_the_models():
    cases = _cases("valid")
    assert len(cases) >= 8
    for path in cases:
        _model(path).model_validate_json(path.read_text())


def test_invalid_samples_are_refused_by_the_models():
    import pytest
    from pydantic import ValidationError

    cases = _cases("invalid")
    assert len(cases) >= 8
    for path in cases:
        with pytest.raises(ValidationError):
            _model(path).model_validate_json(path.read_text())
