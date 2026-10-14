# Grading evaluation

- Run: 2026-10-14 09:47 UTC
- Model: `gemma-4-12b` at `http://127.0.0.1:8888/v1`
- Prompt: `evaluation-1.0.0` (bundle `f5882a581b11`)
- Cases: 360 synthetic (evalset, made by `tender_worker.evalgen`)
- Settings: top_k 8, context 5000 characters

| Measure | Value |
| --- | --- |
| Rows graded with evidence | 357 of 360 |
| Rows stored as failed | 3 |
| Exact agreement | 78 % |
| Weighted kappa | 0.75 |
| Wrong-way errors (AI fully compliant, panel non-compliant) | 0 |
| False not-addressed (evidence existed) | 10 |
| Citation validity (quote found in its passage) | 100 % |
| Citation relevance (the proving passage is cited) | 95 % |

Agreement by kind of case:

| Kind | Correct | Cases |
| --- | --- | --- |
| excluded | 88 % | 60 |
| full | 92 % | 60 |
| not_addressed | 100 % | 60 |
| partial | 72 % | 60 |
| table | 8 % | 60 |
| vague | 95 % | 60 |

Calibration (agreement by confidence band):

| Band | Agreement | Rows |
| --- | --- | --- |
| high | 94 % | 138 |
| medium | 68 % | 219 |
| low | n/a | 0 |

A wrong-way error rise or a citation validity below 100 % blocks a release (AI 5.6). The
cases are synthetic: a regression and pipeline check, not a claim about real proposals.
