# Extraction evaluation

- Run: 2026-10-13 11:17 UTC
- Model: `gemma-4-26B-A4B` at `http://127.0.0.1:8888/v1`
- Prompt: `extraction-1.0.0` (bundle `125e72336106`)
- Documents: synthetic (evalset/extraction)

| Case | Expected | Produced | Recall | Precision | Verbatim sources | Importance | Duplicates flagged | Conflicts flagged | Dropped |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| cloud-platform | 16 | 16 | 100 % | 100 % | 100 % | 88 % | 0/1 | 1/1 | 0 |
| registry-modernisation | 11 | 10 | 91 % | 100 % | 100 % | 90 % | 0/1 | 0/0 | 0 |
| **All** | 27 | 26 | 96 % | 100 % | 100 % | 88 % | 0/2 | 1/1 | 0 |

Recall is expected requirements found; precision is produced items that cite an expected passage. Verbatim sources must be 100 %: a candidate with an unmatched quote is never shown (AGENTS rule 6).
