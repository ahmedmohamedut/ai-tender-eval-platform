# Extraction evaluation

- Run: 2026-10-19 05:23 UTC
- Model: `unsloth/Qwen3.8-27B` at `http://localhost:8888/v1`
- Embedding: hash stub (256)
- Chunk: 3000 characters, output limit 8192 tokens
- Prompt: `extraction-2.0.0` (bundle `6ca7c90bbee9`)
- Documents: synthetic (evalset/extraction)

| Case | Expected | Produced | Recall | Precision | Verbatim sources | Importance | Duplicates handled | Conflicts flagged | Dropped |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| cloud-platform | 16 | 15 | 94 % | 100 % | 100 % | 100 % | 0/1 | 1/1 | 0 |
| registry-modernisation | 11 | 10 | 91 % | 100 % | 100 % | 100 % | 0/1 | 0/0 | 0 |
| **All** | 27 | 25 | 93 % | 100 % | 100 % | 100 % | 0/2 | 1/1 | 0 |

Recall is expected requirements found; precision is produced items that cite an expected passage. Verbatim sources must be 100 %: a candidate with an unmatched quote is never shown (AGENTS rule 6).
