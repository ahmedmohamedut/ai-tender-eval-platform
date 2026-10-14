"""AI 5.6: the generated sets are deterministic and the runner scores them."""

from __future__ import annotations

import json
from pathlib import Path

from tender_worker import evalgen
from tender_worker.evalquality import GradeOutcome, score_grading, weighted_kappa

SET = Path(__file__).resolve().parents[1] / "evalset"


def test_the_committed_sets_are_exactly_what_the_generator_makes():
    grading = evalgen.grading_set()
    assert len(grading) >= 300
    assert json.loads((SET / "grading" / "grading.json").read_text()) == json.loads(
        json.dumps(grading, sort_keys=True)
    )
    assert json.loads((SET / "injection" / "injection.json").read_text()) == json.loads(
        json.dumps(evalgen.injection_set(grading), sort_keys=True)
    )
    assert json.loads((SET / "precheck" / "precheck.json").read_text()) == json.loads(
        json.dumps(evalgen.precheck_set(), sort_keys=True)
    )


def test_every_case_has_the_evidence_its_label_needs():
    kinds = set()
    for c in evalgen.grading_set():
        kinds.add(c["kind"])
        exp = c["expected"]
        ids = {p["id"] for p in c["passages"]}
        assert len(ids) == len(c["passages"])
        if c["kind"] in ("full", "partial", "excluded", "table"):
            assert exp["proof_passage_id"] in ids
        else:
            assert exp["proof_passage_id"] is None
        if exp["grade"] == "non_compliant":
            assert exp["non_compliance_reason"] in ("explicitly_excluded", "not_addressed")
    assert kinds == {"full", "partial", "excluded", "not_addressed", "vague", "table"}


def test_kappa_is_one_for_agreement_and_zero_or_less_for_chance():
    same = [("fully_compliant", "fully_compliant"), ("non_compliant", "non_compliant")]
    assert weighted_kappa(same) == 1.0
    swapped = [("fully_compliant", "non_compliant"), ("non_compliant", "fully_compliant")]
    assert weighted_kappa(swapped) < 0


def test_scoring_counts_wrong_way_errors_and_invalid_citations():
    case = {
        "kind": "excluded",
        "passages": [{"id": "p1", "text": "We do not encrypt customer data at rest."}],
        "expected": {
            "grade": "non_compliant",
            "non_compliance_reason": "explicitly_excluded",
            "proof_passage_id": "p1",
        },
    }
    wrong = GradeOutcome(
        case,
        {
            "outcome": "graded",
            "grade": "fully_compliant",
            "confidence": 0.9,
            "citations": [{"passage_id": "p1", "excerpt": "we invented this text entirely"}],
        },
    )
    m, _ = score_grading([wrong])
    assert m.wrong_way == 1 and m.exact == 0
    assert m.citations == 1 and m.citations_valid == 0 and m.citations_relevant == 1
