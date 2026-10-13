import json
from datetime import UTC, datetime

import pytest
from pydantic import ValidationError

from tender_worker.messages import MESSAGE_MODELS, PushEvent, ResultBatch, Status

ID = "11111111-1111-4111-8111-111111111111"


def status(**over):
    base = {
        "job_type": "document_processing",
        "tender_id": ID,
        "target": {"type": "document_version", "id": ID},
        "seq": 1,
        "state": "running",
        "emitted_at": datetime.now(UTC).isoformat(),
        "worker": {"id": "w", "version": "1"},
    }
    base.update(over)
    return base


def test_unknown_fields_are_ignored_for_additive_changes():
    assert Status.model_validate(status(added_in_minor="x")).seq == 1


def test_failed_needs_failure_and_only_then():
    with pytest.raises(ValidationError):
        Status.model_validate(status(state="failed"))
    with pytest.raises(ValidationError):
        Status.model_validate(status(failure={"code": "other", "message": "m", "retryable": False}))
    Status.model_validate(
        status(state="failed", failure={"code": "timeout", "message": "m", "retryable": True})
    )


def test_result_batch_holds_at_most_fifty_items():
    base = {
        "job_id": ID,
        "job_type": "evaluation",
        "tender_id": ID,
        "target": {"type": "proposal_version", "id": ID},
        "kind": "grades",
        "part_no": 1,
        "final": True,
        "items": [{}] * 50,
        "emitted_at": datetime.now(UTC).isoformat(),
        "worker": {"id": "w", "version": "1"},
    }
    ResultBatch.model_validate(base)
    base["items"] = [{}] * 51
    with pytest.raises(ValidationError):
        ResultBatch.model_validate(base)


def test_push_event_matches_api_enum():
    assert PushEvent.model_validate({"type": "resync"}).data == {}
    with pytest.raises(ValidationError):
        PushEvent.model_validate({"type": "something.else"})


def test_four_models_are_registered():
    assert sorted(MESSAGE_MODELS) == ["command", "push_event", "result_batch", "status"]
    for model in MESSAGE_MODELS.values():
        json.dumps(model.model_json_schema())


def test_a_parsed_result_must_name_its_files_and_nothing_else_may():
    import pytest
    from pydantic import ValidationError

    from tender_worker.messages import ResultBatch

    base = {
        "job_type": "document_processing",
        "tender_id": "0193a7a0-0000-7000-8000-000000000002",
        "target": {"type": "document_version", "id": "0193a7a0-0000-7000-8000-000000000003"},
        "part_no": 1,
        "final": True,
        "emitted_at": "2026-10-13T09:00:00Z",
        "worker": {"id": "w", "version": "0"},
    }
    with pytest.raises(ValidationError):
        ResultBatch.model_validate({**base, "kind": "parsed"})
    files = {
        "pages": "p",
        "sections": "s",
        "passages": "q",
        "rendered_pdf": "r",
        "manifest": "m",
        "page_count": 1,
        "counts": {"pages": 1},
        "source_sha256": "a" * 64,
    }
    assert ResultBatch.model_validate({**base, "kind": "parsed", "parsed": files}).job_id is None
    with pytest.raises(ValidationError):
        ResultBatch.model_validate({**base, "kind": "grades", "parsed": files})
