# AGENTS.md

Instructions for AI coding agents and human contributors working in this repository. Keep this file accurate: when you change how the project is built, tested or organised, change this file in the same commit.

## Project in brief

AI Tender Evaluation Platform. A procurement committee uploads an RFP and vendor proposals. AI turns documents into searchable passages, proposes requirements, and grades each proposal against each requirement with cited evidence. Members review independently, the Chair settles disagreements, and a consolidated report is finalized. AI proposes, people decide.

Components: React SPA, Spring Boot reactive REST API (the only writer of business data), PostgreSQL with row-level security, Keycloak, OpenBao, RustFS (S3), NATS JetStream, one Python worker image in two roles (`ingest`, `agent`), Qdrant, Arize Phoenix.

Status: design complete, code not started. Check the repository before assuming a file exists.

## Read before you change anything

Do not load every document. Read this file, then only what your task needs.

| Task touches | Read |
| --- | --- |
| Anything | `docs/solution-architecture.md` (sections 2 and 10), `docs/implementation-guide.md` (sections 1 and 12) |
| What a screen or feature must do | `docs/design/01-ux-requirements.md` (find the `UX-xxx-nn` ID), `docs/design/02-ux-design.md` (find the `PG-nn` screen) |
| Tables, enums, statuses, who may read what | `docs/design/03-data-model-design.md` (Entity catalogue, Allowed status changes, Independence and access) |
| REST rules, auth, buckets, NATS, config keys, flows | `docs/design/04-platform-integration-design.md` (cited as `PI x.y`) and `api/openapi.yaml` |
| Parsing, passages, Qdrant, agent graphs, grounding, quality | `docs/design/05-ai-pipeline-design.md` (cited as `AI x.y`) |
| Order of work, what is in scope for a phase | `docs/roadmap-plan.md` |

Find a section by its number or heading, for example `grep -n "^### 5.3" docs/design/04-platform-integration-design.md`. Find a requirement by its ID, for example `grep -rn "UX-EVA-12" docs/design`.

## Source of truth and precedence

1. `api/openapi.yaml`, the Pydantic message models (and `contracts/messages/*.json` exported from them) and `infra/keycloak/tender-eval-realm.json`.
2. The five design documents in `docs/design/`.
3. `docs/solution-architecture.md`, `docs/implementation-guide.md`, `docs/roadmap-plan.md`.
4. Everything else.

If two sources disagree, do not choose silently. Follow the higher one, fix the lower one in the same change, and say so in the commit message. If the disagreement is a real design question, stop and ask.

## Rules that must never be broken

1. **Independence is enforced in the database.** A member's grades and notes are hidden from everyone else by row-level security until the release rules apply. The API role does not own tables and cannot disable policies. Never move this check into the front end only.
2. **The Chair is read from `tender_members`, never from the token.**
3. **Workers have no database access.** `worker-ingest` and `worker-agent` never open a PostgreSQL or Keycloak connection. The backend stores what they publish, through the separate AI-ingest role.
4. **Publish after commit.** Create the job row, commit, then publish the NATS command. Never publish first.
5. **Delivery is at least once and unordered.** Use producer-chosen ids, `ON CONFLICT DO NOTHING`, and terminal-state checks. Never rely on arrival order.
6. **The model proposes and code verifies.** A grade or requirement is accepted only when every citation names a passage the model was shown, the passage is in scope, and the quote occurs in its text after normalisation. A row that fails after one repair is stored as failed. Never store a weaker grade in its place.
7. **Code sets every retrieval filter.** No tool or prompt lets the model choose a tender, vendor or document version.
8. **The agent is read-only.** It has no tool that writes, sends, deletes or reaches the network. Passage text is untrusted data and instructions inside it are never followed.
9. **No downloads of originals and no signed storage URLs.** The backend streams views with `Cache-Control: no-store` and writes an access-log row.
10. **No names in keys, logs or traces.** Object keys are lowercase UUIDs. Logs and traces carry ids, hashes and lengths, not document text, unless `TRACE_CAPTURE_CONTENT` is on in a non-production project.
11. **Snapshots are immutable and history is appended.** Confirmed baselines and finalized reports are replaced by a new version, never edited. Nothing is deleted for good through the application.
12. **No secrets in code, images, logs, traces or the repository.** Secrets live in OpenBao. A `.env` file is for local development only and is never committed.
13. **Do not re-chunk an existing document version.** Passage ids and the citations that point at them must stay valid. A different chunking means a new version.
14. **Prompts, models and retrieval settings change only after the evaluation set runs** (`docs/design/05-ai-pipeline-design.md`, 5.6). Results go with the release record.
15. **Never send real proposals or RFPs to an unapproved model endpoint.** Tests and examples use synthetic or anonymised data and a stub endpoint.

## Repository layout

```text
docs/        architecture, roadmap, implementation guide, design/ (five design documents)
api/         openapi.yaml
contracts/   JSON Schemas for NATS messages
infra/       keycloak realm, NATS and RustFS provisioning as code, local environment
backend/     Spring Boot reactive application
worker/      Python package tender_worker (one image, two roles)
frontend/    React application
```

## Commands

No commands exist yet. Phase 0 creates a root entry point (a `Makefile` or a task runner) with these names. Replace this section with the real commands as they appear, and keep them exact.

| Intent | Name to provide |
| --- | --- |
| Start the whole local environment with realm imported, buckets, streams, collection and migrations ready | `make up` |
| Stop it | `make down` |
| Run every test suite | `make test` |
| Lint and format check everything | `make lint` |
| Lint the OpenAPI file and regenerate the server interfaces and the typed client | `make api` |
| Export JSON Schemas from the Pydantic message models | `make contracts` |
| Run the AI evaluation set | `make eval` |

## Conventions

**General.** English only. Timestamps are RFC 3339 UTC. Identifiers are lowercase UUIDs (UUIDv7 for database keys). Names in the database are plural snake_case; enumerations are lowercase snake_case text with a check constraint. REST errors are `application/problem+json` with a stable `code` and a `trace_id`.

**Backend (Spring Boot, reactive).** Never block the event loop. Every request runs in one transaction that sets `app.user_id` and `app.role` for that transaction only. Every write also writes an audit event in the same transaction. Editable resources use `ETag` and `If-Match`. Job and export starts require `Idempotency-Key`.

**Worker (Python 3.12).** One asyncio loop per process; CPU-bound parsing runs in a `spawn` process pool. pydantic for every message and for settings; fail at start on a missing required key. Dependencies pinned by hash. Follow the message loop in `docs/implementation-guide.md` section 7.2 in order.

**Front end (React).** Use the generated typed client. Render controls from each resource's `actions` map. SSE through a fetch-based client with `Last-Event-ID`. WCAG 2.2 AA. Use the fixed vocabulary in `docs/design/02-ux-design.md` ("Content design").

**Configuration.** Environment variables for configuration, OpenBao for secrets, the `settings` table for business settings an administrator may change. Every key is listed in `docs/design/04-platform-integration-design.md` section 7. Add a key there when you add one in code.

## Testing expectations

- A bug fix starts with a failing test.
- Every row-level security rule has a positive and a negative test. Every allowed status change is tested, and so is every refused one.
- Message models and the exported JSON Schemas are tested from both the Python and the Java side.
- Failure drills for the worker (killed mid-parse, lost event, duplicate delivery, model outage) run in staging before a release.
- Never depend on a real model or real documents in an automated test.

## Working method

- Work in small steps. Build the thinnest vertical slice first, then widen it.
- Name the requirement IDs (`UX-REQ-12`) or screens (`PG-11`) a change serves in the commit message or pull request description.
- Contract first: change `openapi.yaml` or a message model before the code that uses it. Update the design document section in the same change if behaviour changes.
- If a decision is open, build on the documented default, record the assumption, and keep it easy to change. The open questions with defaults are in `docs/design/03-data-model-design.md` (Open data questions), `04-...` and `05-...` (section 8) and `02-...` (Open design questions).
- **Stop and ask** before: changing a row-level security policy or a status-change trigger; adding a table that holds member decisions; changing a prompt, model or retrieval setting without an evaluation run; anything that deletes or overwrites stored evidence; adding a network path from a worker to the database; weakening a rule in the list above.

## Glossary

| Term | Meaning |
| --- | --- |
| Baseline | The confirmed, immutable snapshot of requirements and scoring for a tender. Reopening creates the next version. |
| Chair | A member designated per tender, who confirms the baseline and screening decisions, records final grades and finalizes the report. Not a role in Keycloak. |
| Passage | The unit of text the platform depends on: a paragraph, list item, heading or table row, with page, section and rectangles. The viewer highlights it, a citation points at it, Qdrant indexes it. |
| Citation | A reference from a requirement or a grade to a passage, with a verified quote. |
| Grade | `fully_compliant`, `partially_compliant` or `non_compliant`. An AI grade is a suggestion; a member agrees or overrides. |
| Outdated | A member's reviewed row whose requirement or proposal has changed since. |
| Job | A unit of background work: `document_processing`, `requirement_extraction`, `screening_precheck` or `evaluation`. |
| Part | A batch of at most 50 result items, sealed in `JOB_CONTROL` before it is published, so a restart republishes it unchanged. |
| Claim check | A NATS message carries a small body and an object key; the data is a file in RustFS. |
| Verify absence | The one bounded step where the agent searches again with other wording before accepting "not addressed". |
| Ingest, agent | The two roles of the Python worker image, chosen by `WORKER_ROLE`. |
