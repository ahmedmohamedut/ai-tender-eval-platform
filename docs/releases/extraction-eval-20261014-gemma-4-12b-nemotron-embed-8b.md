# Extraction evaluation

- Run: 2026-10-14 07:18 UTC
- Model: `gemma-4-12b` at `http://127.0.0.1:8888/v1`
- Embedding: nemotron-embed-8b (4096)
- Window: 1200 characters, output limit 1800 tokens
- Prompt: `extraction-1.0.0` (bundle `125e72336106`)
- Documents: synthetic (evalset/extraction)

| Case | Expected | Produced | Recall | Precision | Verbatim sources | Importance | Duplicates handled | Conflicts flagged | Dropped |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| cloud-platform | 16 | 15 | 88 % | 100 % | 100 % | 100 % | 1/1 | 0/1 | 0 |
| registry-modernisation | 11 | 10 | 91 % | 100 % | 100 % | 90 % | 0/1 | 0/0 | 0 |
| **All** | 27 | 25 | 89 % | 100 % | 100 % | 96 % | 1/2 | 0/1 | 0 |

Recall is expected requirements found; precision is produced items that cite an expected passage. Verbatim sources must be 100 %: a candidate with an unmatched quote is never shown (AGENTS rule 6).
