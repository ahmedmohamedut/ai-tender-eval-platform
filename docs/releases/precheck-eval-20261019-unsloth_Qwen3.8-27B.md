# Pre-check evaluation

- Run: 2026-10-19 05:58 UTC
- Model: `unsloth/Qwen3.8-27B` at `http://localhost:8888/v1`
- Prompt: `precheck-1.0.0` (bundle `6ca7c90bbee9`)
- Cases: 24 synthetic (evalset, made by `tender_worker.evalgen`)

| Measure | Value |
| --- | --- |
| Expected sections listed from the RFP (recall) | 92 % |
| Listed sections that the RFP asks for (precision) | 100 % |
| Found or missing decided correctly | 95 % |
| RFPs with no structure clause recognised as such | 92 % |

The pre-check only suggests; nobody decides from it (UX-SCR-03).
