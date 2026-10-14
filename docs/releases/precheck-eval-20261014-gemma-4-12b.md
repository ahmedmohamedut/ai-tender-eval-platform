# Pre-check evaluation

- Run: 2026-10-14 09:50 UTC
- Model: `gemma-4-12b` at `http://127.0.0.1:8888/v1`
- Prompt: `precheck-1.0.0` (bundle `f5882a581b11`)
- Cases: 24 synthetic (evalset, made by `tender_worker.evalgen`)

| Measure | Value |
| --- | --- |
| Expected sections listed from the RFP (recall) | 96 % |
| Listed sections that the RFP asks for (precision) | 100 % |
| Found or missing decided correctly | 98 % |
| RFPs with no structure clause recognised as such | 96 % |

The pre-check only suggests; nobody decides from it (UX-SCR-03).
