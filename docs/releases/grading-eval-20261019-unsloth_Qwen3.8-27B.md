# Grading evaluation

- Run: 2026-10-19 05:54 UTC
- Model: `unsloth/Qwen3.8-27B` at `http://localhost:8888/v1`
- Prompt: `evaluation-2.0.0` (bundle `6ca7c90bbee9`)
- Cases: 360 synthetic (evalset, made by `tender_worker.evalgen`)
- Settings: top_k 8, context 12000 characters

| Measure | Value |
| --- | --- |
| Rows graded with evidence | 360 of 360 |
| Rows stored as failed | 0 |
| Exact agreement | 65 % |
| Weighted kappa | 0.54 |
| Wrong-way errors (AI fully compliant, panel non-compliant) | 0 |
| False not-addressed (evidence existed) | 31 |
| Citation validity (quote found in its passage) | 100 % |
| Citation relevance (the proving passage is cited) | 85 % |

Agreement by kind of case:

| Kind | Correct | Cases |
| --- | --- | --- |
| excluded | 100 % | 60 |
| full | 90 % | 60 |
| not_addressed | 97 % | 60 |
| partial | 17 % | 60 |
| table | 3 % | 60 |
| vague | 82 % | 60 |

Calibration (agreement by confidence band):

| Band | Agreement | Rows |
| --- | --- | --- |
| high | 76 % | 301 |
| medium | 8 % | 50 |
| low | 0 % | 9 |

A wrong-way error rise or a citation validity below 100 % blocks a release (AI 5.6). The
cases are synthetic: a regression and pipeline check, not a claim about real proposals.
