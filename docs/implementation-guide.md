# Implementation guide

How to build the AI Tender Evaluation Platform from the design documents, for engineers and coding agents. It says where to start, what order to build in, which rules cannot be broken, and how to know a piece is done. It does not repeat the designs: each step names the section to read.

Companions: [`solution-architecture.md`](solution-architecture.md) (the parts), [`roadmap-plan.md`](roadmap-plan.md) (the phases), and the five design documents under `docs/design/`.

## 1. Ground rules

1. **The design documents and the contract files are the source of truth.** `openapi.yaml`, the Pydantic message models (and the JSON Schemas exported from them), `tender-eval-realm.json` and the design documents decide behaviour. If code and design disagree, fix one of them in the same pull request. Never leave them disagreeing.
2. **Contract first.** Change `openapi.yaml` or a message model before the code that uses it.
3. **Build the thinnest vertical slice first**, then widen it. A slice runs from a screen through the API, the database and, where relevant, NATS and a worker.
4. **Trace everything to a requirement.** Each operation carries `x-ux` (requirement and screen IDs). Each commit or pull request names the requirement IDs it serves.
5. **Defaults exist for every open question.** Build on the default (listed in the open-question tables), record the choice, and keep the code easy to change. Do not stop work to wait.
6. **When something is not decided anywhere, ask or record an assumption.** Do not invent a business rule and bury it in code.

## 2. Repository layout

A proposed layout. Adjust names, keep the separation.

```text
.
├── README.md
├── AGENTS.md
├── CLAUDE.md
├── docs/
│   ├── solution-architecture.md
│   ├── roadmap-plan.md
│   ├── implementation-guide.md
│   └── design/                    five design documents + README
├── api/
│   └── openapi.yaml               REST contract (OpenAPI 3.1)
├── contracts/
│   └── messages/                  JSON Schemas exported from the Pydantic models
├── infra/
│   ├── keycloak/tender-eval-realm.json
│   ├── nats/                      stream, consumer and KV definitions as code
│   ├── rustfs/                    bucket, lifecycle and notification setup
│   └── compose/                   local environment
├── backend/                       Spring Boot reactive application
├── worker/                        Python package tender_worker (one image, two roles)
└── frontend/                      React single-page application
```

## 3. Local environment

The local environment runs every dependency in containers so that a developer or agent can run the whole platform and its tests.

| Service | Local purpose | Provisioning |
| --- | --- | --- |
| PostgreSQL 15+ | Application database | Run migrations on start. Create the two application roles (see section 5.4). |
| Keycloak | Identity | Import `tender-eval-realm.json`. The bootstrap administrator comes from `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_TEMP_PASSWORD`. |
| OpenBao | Secrets | Dev mode is fine locally. Load the paths in PI 7.2. A `.env` file is allowed for local development only. |
| RustFS | Object storage | Create the four buckets, lifecycle rules, and the bucket notification to NATS (PI 4.1, 4.3). |
| NATS JetStream | Messaging | Create the four streams, the seven durable consumers and `JOB_CONTROL` (PI 5.1, 5.3). One replica locally. |
| Qdrant | Vector index | The ingest role creates the collection and payload indexes at start (AI 3.4). |
| Arize Phoenix | Traces | Optional locally. |
| Mail catcher | Invitation and reminder emails | Any SMTP sink. |
| Model endpoint | Chat and embeddings | A real approved endpoint, or a local stub that returns fixed answers for tests. Never send real proposals to an unapproved endpoint. |

Provision streams, consumers, buckets and the collection **from code, idempotently, at start**, so every environment (local, CI, staging, production) is built the same way and a missing piece is created or reported, never silently skipped.

A new contributor should be able to go from a clean checkout to a signed-in browser and a running test suite with one documented command. Keep that command in `README.md` accurate.

## 4. Contracts

### 4.1 REST

- `api/openapi.yaml` has 135 operations in 17 groups under `/api/v1`. Lint it in CI. Generate server interfaces for the backend and a typed client for the front end from it, so neither side writes a request or response type by hand.
- Conventions every operation follows are in PI 2.1: problem+json errors with a stable `code` and `trace_id`; `ETag` equals `row_version` and `PATCH`/`PUT` need `If-Match` (428 if missing, 412 if stale); lists use `limit`, opaque `cursor`, `sort`, `q` and return `{items, next_cursor, total}`; long-running work answers 202 with `Location`; job and export starts need `Idempotency-Key`; notes shorter than `min_note_length` give 422 `note_too_short`; resources carry an `actions` map (`allowed` and `reason`).
- A breaking change to an operation is a version bump of the path or an additive change. Never change the meaning of an existing field.

### 4.2 Messages

- One Pydantic model per message in PI section 5 (command, status, result batch, push event). Export JSON Schemas into `contracts/messages/` and run the backend's tests against those files, so Java and Python cannot drift.
- Every published message carries `Nats-Msg-Id`, `traceparent` and `Tender-Schema` (name and major version, for example `tender.analysis.requested/1`). A consumer that meets an unknown major version terminates the message and copies it to the DLQ with `Dlq-Reason`.
- Messages change only by adding fields within a major version. A new major version is deployed to the consumer first.
- Command body: `job_id`, `job_type`, `tender_id`, `target {type, id}`, `requested_by` (null when the system started the job), `attempt`, `created_at`, and `input` (inline, under 64 KB) or `input_ref {bucket, key, sha256}`.

### 4.3 Identity

- `tender-eval-realm.json` defines the realm, clients (`tender-spa` public with PKCE S256, `tender-backend-admin` confidential with client credentials), roles (`admin`, `member`, `observer`), lockout and the password policy. Keep the file and PI section 3 in step.
- The access token is for audience `tender-api`. The Chair is **never** in the token.

## 5. Database

### 5.1 Build order

1. Conventions and enumerations (DM, Conventions and Enumerations).
2. Identity, tender and committee tables, then documents, vendors, requirements, baseline, jobs, evaluation, report, notifications, audit.
3. Constraints and indexes (DM, Integrity rules and Key indexes).
4. Triggers for allowed status changes (DM, Allowed status changes).
5. Row-level security policies and their tests (section 5.3).

Use a migration tool (for example Flyway or Liquibase). A migration is never edited after it has run anywhere. A new enumeration value is a migration, never an `UPDATE` of data.

### 5.2 Conventions to follow in every table

- Plural snake_case names. UUIDv7 keys. `created_at`, `updated_at` (not on append-only tables), `row_version` on editable tables, `deleted_at` and `deleted_by` for soft deletes.
- Enumerations are lowercase snake_case `text` with a check constraint. Times are `timestamptz` in UTC.
- Nothing is deleted for good through the application. Rows are soft-deleted, old versions are marked superseded, finished tenders are archived.
- Business rules that can be constraints are constraints: one active Chair per tender (partial unique index), a note of at least 10 characters for every override (check), valid status transitions (trigger).

### 5.3 Row-level security (independence)

The database, not the interface, hides a member's grades and notes. Each request runs in one transaction that first sets the signed-in user and role for that transaction only; the policies read them together with `tender_members`, `conflict_declarations` and `member_evaluations`.

```sql
-- At the start of every request transaction (transaction-local, so a pooled connection never leaks it)
SELECT set_config('app.user_id', :user_id, true);
SELECT set_config('app.role',    :role,    true);

-- Illustrative policy shape: a member reads a tender only when they are an active member
CREATE POLICY tenders_read ON tenders FOR SELECT USING (
    current_setting('app.role', true) IN ('admin', 'observer')
    OR EXISTS (
        SELECT 1 FROM tender_members m
        WHERE m.tender_id = tenders.id
          AND m.user_id   = current_setting('app.user_id', true)::uuid
          AND m.status    = 'active'
    )
);
```

The read and write rules are tables in DM, Independence and access. Implement each row of those tables as a policy or trigger, and write a test for each:

- A member cannot read another tender, or a vendor's proposal before declaring no conflict.
- A member's `member_evaluation_rows` are unreadable to others until the release rules apply (the reader's own `first_submitted_at` is set and the owner submitted; the admin only when every member of that vendor submitted or was excluded).
- Only the tender's active Chair can confirm or reopen a baseline, confirm a screening decision, record a final grade, finalize or reopen a report, or reopen a member's evaluation. A trigger checks it.
- The application connects with a role that **does not own the tables**, so it cannot disable policies.
- `audit_events` and `document_access_events` allow insert and select only.

### 5.4 Database roles

| Role | Used for | Can |
| --- | --- | --- |
| `tender_owner` | Migrations only (Flyway), never at runtime | Owns every table, so no runtime role does |
| `tender_app` | The request path | Read and write application data with policies applied |
| `tender_ai_ingest` | The result handler only | Insert AI tables, passages and AI-origin requirements. Cannot read `member_evaluation_rows`, so AI output never depends on a human decision. |

The database role names use underscores as in PI 7.5; the OpenBao role names below keep their hyphens (PI 7.2). Credentials come from OpenBao (`database/creds/tender-app`, `database/creds/tender-ai-ingest`) where the database engine is available, otherwise static secrets under `kv/tender-eval/backend/`. Dynamic users live 24 hours and are renewed at two thirds of their life; pools drain and reconnect on renewal.

## 6. Backend

### 6.1 Request pipeline

Every request passes the same stages. Build them once as shared infrastructure, then write handlers on top.

1. **Authenticate**: validate signature, issuer (`KEYCLOAK_ISSUER_URI`), audience `tender-api` and expiry.
2. **Authorize by role**: check the roles in the operation's `x-roles`.
3. **Resolve the Chair and committee membership** from `tender_members`, never from the token.
4. **Open a transaction** and set `app.user_id` and `app.role` (section 5.3).
5. **Handle**, with `If-Match`, idempotency key and note-length checks applied by shared filters.
6. **Write an audit event in the same transaction** with the values before and after.
7. **Commit**, then publish side effects (commands, push hints).
8. **Answer** with problem+json on failure, always including `trace_id`.

### 6.2 Uploads

Follow PI 4.4 exactly:

1. Check the role and the tender state, then `Content-Length` against `upload_max_bytes` (413) and the first bytes for a PDF or ZIP signature (415).
2. In one transaction insert the `document_versions` row (`uploading`) and the `document_processing` job (`queued`).
3. Stream the body to `tender-documents` as an S3 multipart upload in 8 MiB parts, hashing as it goes. If the body outgrows the limit mid-stream, abort the multipart upload and soft-delete the row.
4. On completion store size, SHA-256 and `storage_key`, set the status to `processing`, answer 201 with the version and its `job_id`.
5. RustFS emits the bucket event to `documents.uploaded`. The backend does nothing more for this job except wait for results.

Keys are `originals/{tender_id}/{document_id}/{document_version_id}.{ext}`: lowercase UUIDs, `{ext}` from the checked signature, never from the client's file name, and no vendor or person name in any key.

### 6.3 Starting background jobs

For extraction, pre-check, evaluation re-runs and retries:

1. Check for an overlapping queued or running job (409 `job_overlap`, unless `acknowledge_overlap` is true). Honour `Idempotency-Key` for 24 hours.
2. In one transaction insert the job row (and, for evaluation, write `jobs/{job_id}/input.json` to `tender-jobs`).
3. **Commit, then publish** the command to `documents.analysis.requested.{job_type}` with `Nats-Msg-Id = job_id`. Never publish before the commit.
4. Answer 202 with the job and a `Location` header. If publish is refused (stream full), mark the job failed with code `other` at once.

Three backend timers close the gaps (PI 5.8): a job queued longer than `watchdog.queued_timeout` (2 minutes) has its command published again; a running job silent for `watchdog.silence_timeout` (5 minutes) fails with `timeout`; a job past `JOB_MAX_DURATION_MINUTES_*` fails with `timeout` and gets a `cancel.{job_id}` key.

### 6.4 Result and status handlers

- Consumers `backend-status` and `backend-results` run on every backend instance. Delivery is at least once and not ordered, so **never rely on arrival order**.
- Insert items with the producer's id and `ON CONFLICT DO NOTHING`. Persist through the `tender_ai_ingest` role.
- Process a `completed` status only when the stored result batches match `result_parts` and `counts`; otherwise return the message with a 5-second delay.
- For a document, set `ready` when the job is `completed` and the rows are stored. Read the parsed files named in the `parsed` result and insert `document_pages`, `document_sections`, `passages` in batches, then check counts.
- Drop and log messages for jobs already in a terminal state.
- On failure to store: return with backoff (1 s, 5 s, 30 s, 2 min, 10 min); after 10 tries copy to the DLQ and mark the job failed.
- After storing anything a user can see, publish a `PushEvent` to `ui.push.tender.{tender_id}` (or `ui.push.user.{user_id}` for notifications).

### 6.5 Push (SSE)

`GET /events` is one stream per tab, with a heartbeat every 15 seconds. The SSE `id` is the stream sequence. A reconnecting browser sends `Last-Event-ID`; inside the 15-minute window the server replays, outside it sends a `resync` event and the browser refetches what is on screen. The connection closes when the user's access changes. Events are hints: the client refetches the object they name.

### 6.6 Viewing documents

`GET /document-versions/{id}/render` streams `document.pdf` from `tender-derived` with `Range` support and `Cache-Control: no-store`, after checking access, and writes a `document_access_events` row. There is no endpoint that downloads an original, and no signed storage URL is ever issued (UX-NFR-07).

### 6.7 Other duties

- **Exports** (report, requirements list, clarification list, audit log): generate in the background, write to `tender-exports`, return 202 with an export, serve with `GET /exports/{id}/file`.
- **Notifications**: the in-app item is created in the same transaction as its cause; email is optional per user.
- **Settings**: business settings (note length, upload limit, low-confidence threshold) live in the `settings` table and are returned in `client_config`.
- **Rate limits**: 429 with `Retry-After` on uploads, search, extraction, re-runs and exports (`RATE_LIMIT_*` keys).
- **Configuration**: validate at start and refuse to run on a missing required key. Keys are listed in PI 7.3.

## 7. Python worker

One package, `tender_worker`, one image; `WORKER_ROLE` is `ingest` or `agent`. Python 3.12, one asyncio loop per process.

### 7.1 Building blocks

Settings (pydantic-settings, fail on missing keys); Secrets (OpenBao AppRole, own paths only); Messaging (nats-py durable pull consumers, explicit ack); Object storage (async S3 client, path style, multipart over 8 MiB); Process pool (`spawn`, size `INGEST_CONCURRENCY`) for CPU-bound parsing; Job runner (the loop below); Contracts (Pydantic models for every message); Telemetry (OpenTelemetry, JSON logs without content); Health (`/healthz`, `/readyz`). See AI 2.1.

### 7.2 The message loop

Follow AI 2.3 in this order and do not reorder:

1. **Fetch** only when a slot is free; ask for as many messages as there are free slots.
2. **Validate** with the Pydantic model. An unreadable message is terminated and copied to `dlq.{subject}` with `Dlq-Reason`. No status is sent, because it names no job.
3. **Look before working**: the agent reads `cancel.{job_id}` in `JOB_CONTROL`; the ingest role reads the manifest of the document version. A complete manifest and index means only re-announce completion.
4. **Start the heartbeat**: call `in_progress` every third of the ack wait (20 s ingest, 40 s agent) and send a `running` status if none went out in that time.
5. **Run** the job with a context holding ids, status counter, cancellation token, trace span and clients.
6. **Report**: result parts first, then exactly one terminal status (`completed`, `completed_with_warnings`, `failed`, `cancelled`).
7. **Acknowledge** only after JetStream accepted the terminal status.

Status `seq` rises by 1 within one delivery. After a redelivery it restarts at `deliveries_so_far * 1000 + 1`, so `Nats-Msg-Id` never repeats.

### 7.3 Failure handling

| Class | Do | Code |
| --- | --- | --- |
| File problem (encrypted, no text, unsupported, corrupt) | Publish `failed`, acknowledge. No retry. | `password_protected`, `no_selectable_text`, `unsupported_format`, `other` |
| Nothing to read | Publish `failed`, acknowledge | `no_content_in_scope` |
| Dependency down (RustFS, Qdrant, embeddings, model) | Retry in place: 5 tries, backoff 1 s doubling to 30 s with jitter, honour `Retry-After`. Then `failed` as retryable. | `source_unavailable`, `index_unavailable`, `llm_unavailable` |
| Cancelled | Stop at the next check, publish what is finished, then `cancelled` | none |
| Unexpected exception | `nak` with a 30 s delay. On the last delivery publish `failed`, copy to the DLQ, terminate. | `other` |
| Native crash in a parser or LibreOffice | Rebuild the pool and try once in a fresh process; a second crash is a file problem | `other` |

### 7.4 Ingest role: building passages

Read AI section 3 completely before writing the parser. Key rules:

- PDF and DOCX only. DOCX is converted once to PDF with LibreOffice (outline export on, one conversion at a time, fresh profile, macros off, no network, killed after `INGEST_CONVERT_TIMEOUT_SECONDS`). The converted PDF is the document; its pages are the ones citations use.
- Words with rectangles by page via pdfplumber; page size, rotation and encryption flag via pypdf. Tables via pdfplumber; each row is one passage and the header row travels with it as embedding context.
- Passage: a paragraph, list item, heading or table row; short consecutive paragraphs of one section and page are joined up to `PASSAGE_MIN_CHARS` (200); a paragraph over `PASSAGE_MAX_CHARS` (1,200) is split at sentence ends with no overlap; a passage never crosses a page.
- Ids are deterministic:

```python
passage_id = uuid5(PASSAGE_NAMESPACE, f"{document_version_id}:{ordinal}")   # ordinal: reading order from 0
section_id = uuid5(SECTION_NAMESPACE, f"{document_version_id}:{outline_index}")
```

  The namespaces are constants in the code and never change.
- `text` is the extracted text exactly. Never summarise or rewrite it.
- A page with fewer than `INGEST_PAGE_MIN_CHARS` (20) characters is empty. More than `INGEST_MAX_TEXTLESS_SHARE` (0.5) empty pages fails with `no_selectable_text`; a few empty pages end `completed_with_warnings` with warning `textless_pages`.
- Write to `tender-derived`: the rendered PDF, `pages.jsonl`, `sections.jsonl`, `passages.jsonl`, and **`manifest.json` last**. A retry that finds a manifest never parses again.
- Embed in batches of 64, upsert 128 points at a time, point id = passage id, wait until the write is applied. A version is indexed when the number of points carrying its id equals `passage_count`. Only then publish `completed`.

Qdrant collection (AI 3.4): dense vector `dense` (cosine, `EMBEDDING_DIMENSIONS`) and sparse `bm25` with the IDF modifier; payload includes `tender_id`, `document_version_id`, `page_no`, `ordinal`, `section_path_ids`, `text` and more; payload indexes on `tender_id` (tenant), `document_version_id`, `section_path_ids`, `page_no`, `ordinal`. Search is the Query API with dense and sparse prefetch fused by reciprocal rank fusion. Points are never deleted.

## 8. LangChain agent

Three LangGraph graphs, one per job type. Each is a fixed set of steps with bounded loops; it is **not** a free-running tool-calling loop.

### 8.1 Rules that must hold in every graph

1. **The model proposes and code verifies.** Run the grounding checks of AI 5.1 on every item: the cited passage was shown to the model, is in scope, and the quote occurs in it after normalisation (NFKC, collapsed whitespace, soft hyphens removed, curly quotes made plain, case kept; a quote under 10 characters is refused). The stored excerpt is the matching slice of the passage, never the model's string.
2. **A row that still fails after one repair is stored as failed**, with `ungrounded`, `invalid_output` or `model_error`. Never store a weaker grade in its place.
3. **Code sets every retrieval filter.** No tool takes a tender, vendor or document version from the model.
4. **The agent is read-only.** Only `search_passages` and `get_passage_context`, and only in the Verify absence node, within `VERIFY_ABSENCE_MAX_SEARCHES` (3).
5. **Passage text is untrusted.** It sits in delimited blocks, control and zero-width characters are removed, delimiter look-alikes are neutralised, and instructions inside it are never followed.
6. **The agent receives no member's grade, note, override or identity**, and no vendor name or other vendor's material.
7. **Doubt pushes down.** If the passages could support more than one grade choose the lower one and mark certainty low.

### 8.2 Evaluation graph

Nodes: Load and plan, Retrieve, Grade, Verify absence, Validate, Repair, Record the row, Seal a part and report (AI 4.2).

- Load and plan reads `input.json`, checks its SHA-256 against `input_ref` (fail with `source_unavailable` on mismatch), sends the first status with step names and the run record (`model_name`, `model_version`, `prompt_version`, `input_scope`), and removes requirements already in sealed parts from the work list.
- Retrieve: two queries per requirement (its text and its acceptance criteria), hybrid search over the proposal's document versions only, `RETRIEVAL_TOP_K` (8) hits each, merged by rank fusion, one neighbouring passage on each side of the three best hits, context cut to `GRADE_CONTEXT_MAX_CHARS` (12,000).
- Grade: one call returning `GradeDraft`. Verify absence runs only if the draft says `not_addressed` or cites nothing, and grading then runs one more time and never a second time. Repair is one more Grade call that lists the exact violations.
- Rows run in parallel up to `LLM_MAX_CONCURRENCY`; a part is sealed at 50 rows, at the end, or on cancel.
- **Sealing**: write `part.{job_id}.{part_no}` to `JOB_CONTROL`, then publish the result batch with `Nats-Msg-Id = {job_id}:r:{part_no}`, then count it as sent. After a crash, republish sealed parts unchanged and resume with the requirements no part contains. Item ids derive from job id and requirement id.
- The job ends `completed` when every row is graded, `completed_with_warnings` (warning `row_failed` with count) when some rows failed, and `failed` with `llm_unavailable` when the endpoint stays down past `LLM_OUTAGE_GIVE_UP_SECONDS` (600). Sealed parts are kept in every case.

### 8.3 Extraction and pre-check graphs

Extraction: Load scope, Plan windows (about `EXTRACTION_WINDOW_CHARS` 8,000 with the last 2 passages overlapping), Extract (one call per window, in parallel, verbatim quote for every source), Ground, Merge (cosine at or above `DEDUP_SIMILARITY_THRESHOLD` 0.85), Compare, Flag. A flagged candidate is still emitted, never dropped. Extraction and pre-check seal **all** parts first, then write `part.{job_id}.final` with the part count; a restart without that key starts again. See AI 4.3 and 4.4.

### 8.4 Prompts

Prompts are templates in the repository, one folder per job type, loaded at start. Document content enters only through marked slots. A bundle has a version (for example `evaluation-1.0.0`); the build hashes every prompt file and fails if a file changed without a new version. A change to a prompt, a model or a retrieval setting runs the evaluation set first. Rows stored by an earlier version keep their version and are never rewritten. The Grade prompt content is specified in AI 4.7.

### 8.5 Evaluation set and release gate

Run the sets as Phoenix experiments over datasets in a non-production project, through the same code path. Metrics and rules are in AI 5.6. Two are absolute: any rise in wrong-way errors (AI says fully compliant, panel says non-compliant) blocks a release, and any injected passage that changes a grade blocks a release. Citation validity is 100 percent by construction; a drop means the checker is broken.

## 9. Front end

- **Structure by screen** (`PG-01` to `PG-20`), with the shared components from UX Requirements ("Shared components") and UX Design ("Component specifications").
- **Token handling**: authorization code flow with PKCE against `tender-spa`; no secret in the app; the idle warning follows `client_config.session_idle_timeout_seconds` and `session_warning_seconds`.
- **Push**: a fetch-based SSE client (native `EventSource` cannot send a bearer token), reconnect with `Last-Event-ID`, handle `resync` by refetching what is on screen. Treat events as hints and refetch the named object.
- **Data**: use the generated typed client. Send `If-Match` on edits; on 412 show what changed and who changed it. Send `Idempotency-Key` on job and export starts.
- **Actions**: render controls from each resource's `actions` map. Hide an action that is never allowed; show a state-dependent one disabled with its one-line reason (UX-ROL-03).
- **Document viewer**: render the PDF pages from the `render` endpoint with `Range`; highlight passages by their rectangles; no download or print controls for proposals.
- **Large tables**: the evaluation sheet must scroll 500 rows without lag, filters respond within 1 second, a cited page opens within 2 seconds and a 300-page document within 3 seconds (UX-NFR-04). Virtualise rows.
- **Content**: use the fixed vocabulary and message patterns in UX Design ("Content design"); the same status shows the same label on every screen (UX-GEN-10). English only, left to right.
- **Accessibility**: WCAG 2.2 AA. Keyboard review of the evaluation sheet, visible focus, status never conveyed by colour alone. Contrast ratios are recomputed if a brand replaces the neutral tokens.
- **Wireframe values** (142 requirements, 78 percent) are illustrative and carry no meaning.

## 10. Testing

| Level | What | Where |
| --- | --- | --- |
| Unit | Business rules, parsers, passage builder, grounding checks, quote normalisation, id derivation | Each component |
| Contract | OpenAPI lint; generated client compiles; backend handlers match the spec; message models and exported JSON Schemas agree with the backend's tests | CI |
| Database | Migrations apply to an empty database; every status transition allowed and every other refused; every row-level security rule has a positive and a negative test; the AI-ingest role cannot read member rows | Backend, against a real PostgreSQL in a container |
| Integration | Backend with NATS, RustFS, Keycloak; worker with NATS, RustFS, Qdrant and a stub model endpoint | Containers in CI |
| End to end | Upload to ready; extraction to confirmed baseline; evaluation to finalized report, through the browser | Staging |
| Failure drills | Killed worker mid-parse; lost bucket event; DLQ message; model outage; Qdrant down; duplicate delivery; redelivery after crash (parts republished unchanged) | Staging, repeated before release |
| AI quality | Evaluation set and release gate (section 8.5) | Phoenix experiments |
| Security | Independence tests, injection set, dependency and image scans, secret scanning, penetration test, egress rules | CI and Phase 6 |
| Accessibility | Automated checks in CI, manual keyboard and screen-reader review, usability tests | UI work |
| Performance | The sizing assumptions: 500 requirements, 8 vendors, 300-page RFP, 150-page proposals | Staging |

Rules: a bug fix starts with a failing test; no test depends on a real model or on real proposals; fixtures are synthetic or anonymised.

## 11. Delivery

1. **Pipeline stages**: lint and format, unit tests, contract checks, build, integration tests, image build and scan, deploy to development, end-to-end tests, deploy to staging on tag.
2. **Images**: one backend image, one front-end bundle, one worker image used by two deployments (`worker-ingest`, `worker-agent`). The worker image carries LibreOffice and metric-compatible substitutes for the common Office fonts, so DOCX pagination does not change between releases. Dependencies are pinned by hash; images rebuild on every base-image security update. Containers run as non-root with a read-only root file system and a size-limited scratch volume.
3. **Release record**: each release stores the evaluation set results, the prompt bundle versions, the model and embedding versions and the migration list.
4. **Rollout**: new replicas start first and old ones drain. Message schemas change only additively within a major version; a new major version is deployed to the consumer first. Worker termination grace period is 60 seconds, above `SHUTDOWN_GRACE_SECONDS` (45).
5. **Rollback**: migrations are forward-only; ship a compensating migration. Prompt and model changes roll back by configuration.

## 12. Do and don't

| Don't | Do instead |
| --- | --- |
| Read the Chair from the token | Read it from `tender_members` |
| Publish a command before the job row commits | Commit, then publish; the watchdog covers a lost publish |
| Rely on message order | Use ids, `ON CONFLICT DO NOTHING` and terminal-state checks |
| Put a person or vendor name in an object key, log line or trace | UUIDs and hashes only |
| Issue a signed storage URL or add a download for originals | Stream through the backend with `no-store` and an access-log row |
| Let the worker or agent open a database connection | Publish results; the backend stores them |
| Let the model choose a tender, vendor, document version or filter | Set every filter in code from the job input |
| Trust a model's own claim that a quote is exact | Check the quote against the passage text in code |
| Store a weaker grade when grounding fails | Store a failed row (Not evaluated) |
| Delete rows or objects for good | Soft-delete, supersede, archive. Only the lifecycle rules of `tender-jobs` and `tender-exports` expire objects. |
| Edit a baseline or a finalized report | Create a new version; snapshots are immutable |
| Change a prompt, model or retrieval setting without running the evaluation set | Run it first and attach results to the release |
| Put secrets in an image, repository, log or trace | OpenBao and process memory only |
| Re-chunk an existing document version | A different chunking is a new document version, because citations must stay valid |
| Hide an unfinished decision in code | Record the assumption and link the open question |

## 13. Checklists

**New REST operation**
- [ ] Added to `openapi.yaml` with `x-roles`, `x-ux`, problem+json errors, `ETag` and `If-Match` where editable
- [ ] Role check in the API and a row-level security policy in the database
- [ ] Writes an audit event in the same transaction
- [ ] Idempotency key if it starts a job or export; 202 with `Location` if long-running
- [ ] Tests: success, forbidden, conflict, validation; a policy test if it touches independence

**New table or column**
- [ ] Migration, never an edit of an old one; enumerations as check constraints
- [ ] Standard columns, soft delete, `row_version` if editable
- [ ] Policy and tests; indexes from the expected volumes
- [ ] Updated DM section and, if exposed, `openapi.yaml`

**New message or job type**
- [ ] Pydantic model, exported JSON Schema, `Tender-Schema` version
- [ ] Subject, consumer, access control and `Nats-Msg-Id` rule in `infra/nats`
- [ ] Idempotent insert on the backend; failure code and what the interface offers
- [ ] Status steps for the job center; sealing and resume rules if it is an AI job

**Pull request**
- [ ] Names the requirement or screen IDs it serves
- [ ] Contract and design documents updated in the same pull request if behaviour changed
- [ ] Tests added or updated; no new lint or scanner findings
- [ ] Accessibility checked for UI work; telemetry added for new paths
