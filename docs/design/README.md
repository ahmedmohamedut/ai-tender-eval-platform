# AI Tender Evaluation Platform: design documents

Five design documents, plus this index. Every diagram in them is a Mermaid code block (` ```mermaid `), so it can be read as text and rendered by GitHub, GitLab, VS Code and most Markdown viewers.

## Files, in reading order

| # | File | Original title | What it decides |
| --- | --- | --- | --- |
| 1 | `01-ux-requirements.md` | UX Requirements | Roles, tender lifecycle, numbered requirements (`UX-...`) and non-functional rules |
| 2 | `02-ux-design.md` | UX Design | Sitemap, user journey, the 20 screens (`PG-nn`) with wireframes, states and messages |
| 3 | `03-data-model-design.md` | Data Model Design | PostgreSQL tables, constraints, row-level security, state machines |
| 4 | `04-platform-integration-design.md` | Platform Integration Design | REST API rules, RustFS buckets and keys, NATS streams and subjects, configuration keys. Referenced elsewhere as "PI x.y" (section x.y of this file) |
| 5 | `05-ai-pipeline-design.md` | AI Pipeline Design | Python worker (ingest role) and LangChain agent (extraction, pre-check, evaluation) |

The documents refer to each other by their original titles. Use the table above to find the file.

## Reading the diagrams

- The text and tables around a diagram are the specification. The diagram is a picture of the same facts.
- Step numbers in the flows match the numbered tables directly below them (for example PI 6.1 and 6.2, AI Pipeline section 3).
- Wireframes in `02-ux-design.md` are layout sketches. Numbers in parentheses match the callout table under each wireframe.
- In flowcharts, a solid line is the normal path and a dotted line is an exception, a reopen path or a loose reference.
- In the data model diagrams, `erDiagram` shows the evaluation tables only. The full table list is in the document's tables.

## Facts that hold across all five documents

- The browser talks only to the backend API. The backend streams uploads to RustFS and persists every result.
- The worker and the agent have no database connection. They read from NATS, RustFS and Qdrant, and publish to NATS and RustFS.
- NATS streams: `DOCUMENTS`, `ANALYSIS_EVENTS`, `UI_EVENTS`, `DLQ`. Key-value bucket: `JOB_CONTROL`.
- Job failure codes: `password_protected`, `no_selectable_text`, `unsupported_format`, `source_unavailable`, `index_unavailable`, `llm_unavailable`, `model_output_unusable`, `no_content_in_scope`, `timeout`, `other`. Row-level codes: `ungrounded`, `invalid_output`, `model_error`.
- Keycloak realm name: `tender-eval`.
- Language: English only. Documents: PDF and DOCX only, no OCR.
