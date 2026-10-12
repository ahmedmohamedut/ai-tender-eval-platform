# Decisions and assumptions

Phase 0 tooling choices, assumptions and known gaps. Newest decisions are added at the end of each section. Design questions that are open in the design documents keep their documented default here and are named by their question number (PI 8, AI 8).

## Tooling

| # | Decision | Reason |
| --- | --- | --- |
| T1 | Java 25 (current LTS) through a Gradle toolchain; Gradle 9.x with Kotlin DSL | Implementation guide 6 fixes no version; solution architecture 3 asks for a current LTS |
| T2 | Spring Boot 4.1.x, WebFlux, R2DBC, Flyway | Reactive backend (PI 1) |
| T3 | Python 3.12 with uv; `uv.lock` and a hash-pinned export for the image | AI 2.6 requires hash pinning |
| T4 | React, TypeScript 5.x, Vite, vitest | `openapi-typescript` needs TypeScript 5 |
| T5 | Root `Makefile` is the single entry point | Command names reserved in AGENTS.md |
| T6 | GitHub Actions for CI | Repository is on GitHub |
| T7 | Caddy as gateway | Native body limit, timeouts and unbuffered streaming |
| T8 | PostgreSQL 18 | `uuidv7()` is built in; meets "15 or later" |

## Assumptions and gaps

| # | Item | Status |
| --- | --- | --- |
| D1 | Host ports are non-standard (`.env.example`) because 5432, 4222 and 9000 are commonly taken. `make up` checks them first. | Decision |
| D2 | Database roles are `tender_owner` (migrations only), `tender_app`, `tender_ai_ingest`, named as in PI 7.5. Implementation guide 5.4 writes `tender-app` and `tender-ai-ingest` and lists two roles; PI wins (higher source) and the guide is corrected. The application roles own nothing, have no DELETE by default and are NOSUPERUSER NOBYPASSRLS. | Decision, verified |
| D3 | Realm roles are `procurement_admin`, `committee_member`, `observer` (realm file, PI 3.2). The backend maps them to admin, member, observer. Implementation guide 4.3 and AGENTS.md use the short names for the platform roles. | Decision |
| D4 | The realm file imports cleanly on Keycloak 26.8.0 (design tested 26.4.2). The bootstrap administrator signs in with code + PKCE, is forced to change the temporary password, and the token has `iss`, `aud` `tender-api`, `azp` `tender-spa` and `roles` `["procurement_admin"]`. | Verified by `infra/scripts/signin_check.py` |
| D5 | PI Q2 (bucket events to NATS) is resolved by experiment on RustFS 1.0.1. The docs name only `RUSTFS_NOTIFY_NATS_ADDRESS`. `RUSTFS_NOTIFY_NATS_ENABLE_<NAME>=on`, `RUSTFS_NOTIFY_NATS_ADDRESS_<NAME>` and `RUSTFS_NOTIFY_NATS_SUBJECT_<NAME>` work, with a bucket queue configuration whose `QueueArn` is `arn:rustfs:sqs:<region>:<name>:nats`. The event carries `EventName`, a top-level `Key` (`bucket/key`), and `Records[0].s3.object.key` URL-encoded, with `size` and `eTag` present. The extra keys are undocumented, so a RustFS upgrade must re-run `make verify-env`. **The record is nested differently from PI 4.3**: 1.0.1 sends `Records[i].data.s3.{bucket.name, object.key}` and repeats the names as `Records[i].bucket_name` and `Records[i].object_name`, not `Records[i].s3`. The worker accepts all three shapes (`keys.py`) and PI 4.3 is corrected. A HEAD request remains the source of size and ETag (PI 4.3). | Verified, undocumented upstream |
| D6 | RustFS: delete-deny bucket policy is enforced (even for the root key) and lifecycle rules are accepted and read back; actual expiry after 90 days was not observed. Versioning and Object Lock are not attempted (need erasure coding on 4 or more drives, PI Q3). Per-component access keys (PI 4.5) are not created locally; every component would use the root key. | Gap |
| D7 | OpenBao runs in dev mode and holds the PI 7.2 paths, but components read their secrets from environment variables locally (`.env` is allowed for local development). The AppRole client in the worker (AI 2.1, PI Q4) and in the backend lands with the shared development environment. | Gap |
| D8 | Local transport is plain HTTP, NATS has no authentication or TLS, Phoenix authentication is off. PI 5.11 and PI 7.5 require NKey users, TLS and Phoenix authentication in shared environments. | Gap |
| D9 | The gateway body limit is 120 MiB (`request_body max_size 120MiB`) and every proxy timeout is 15 minutes. A request whose `Content-Length` exceeds 120 MiB is refused with 413 before it is proxied (a CEL matcher), so the limit holds even when the backend would answer first with 401 or 404; a chunked body without a length is stopped by `request_body` as it is read. The gateway's 413 body has a stable `code` but no `trace_id`. | Decision, verified |
| D10 | `make up` waits with `infra/scripts/wait-healthy.sh`, not `docker compose --wait`, because compose treats a one-shot that exited 0 as a failure under `--wait`. | Decision |
| D11 | Qdrant collection creation (AI 2.7 step 3, AI 3.4) is not in Phase 0. `/readyz` of the worker only checks that Qdrant answers. | Deferred to Phase 2 |
| D12 | Phoenix runs from the `version-20.21` tag; the Qdrant image has no curl, so its health check uses bash and `/dev/tcp`. | Decision |
| D13 | The worker handler for `documents.uploaded` and for the `document_processing` command is a scaffold: it validates the input, does a HEAD on the object, publishes exactly one terminal status (`completed`, step `walking_skeleton`, or `failed` with `source_unavailable` when the object is missing) and acknowledges after JetStream accepted it. It parses nothing and writes no file. M3 asked for "acknowledges and logs, nothing more"; a status is needed by M5 and M6. The backend must not treat this status as a finished parse in Phase 2. | Decision |
| D14 | Worker settings declare only the keys the code uses. The remaining PI 7.4 keys (embedding, LLM, retrieval, batch sizes, Phoenix) are added with the code that reads them. | Decision |
| D15 | The `worker` field of a status is an object `{id, version}` ("worker id and version", PI 5.5). The shape is not spelled out in the design; the JSON Schema fixes it. The Pydantic model cannot express "exactly one of `input` and `input_ref`" or "`failure` only when `state` is `failed`" in JSON Schema, so those two rules are enforced by the Python model only. | Assumption |
| D16 | The user id set in `app.user_id` is the token `sub` for now. Mapping `sub` to `users.id` (PI 3.4) needs the users table and arrives in Phase 1. | Gap |
| D17 | Backend integration tests build tokens with a throwaway key and the production validators; they do not call Keycloak. Sign-in against the real realm is verified by `infra/scripts/signin_check.py`. | Decision |
| D18 | CI (`.github/workflows/ci.yml`) runs lint, contracts (generation and schema drift), unit tests, integration tests, image builds, the walking slice and a secret scan. Actions are pinned to a major version tag, not a commit SHA; pin by SHA before the first release. The workflow has been checked with actionlint and its commands run locally, but has **not** run on GitHub. Dependency updates come from Dependabot; a dedicated dependency vulnerability scan (roadmap Phase 0 lists one) is not set up. | Gap |
| D19 | "Generated code is not committed" and "CI fails on generated-client drift" cannot both hold, so drift is detected by regeneration: `make api` fails when the spec no longer lints, the server interfaces no longer compile against the code, or the TypeScript client no longer typechecks against the code. Message schemas under `contracts/messages` are committed and CI fails on any difference from a fresh export. | Decision |

## Phase 1 and 2

| # | Item | Status |
| --- | --- | --- |
| P1 | Schema (V2 to V8): tables of identity, tenders, documents, vendors, proposals, jobs, audit; text enums with check constraints; `row_version` and `updated_at` by one trigger; status moves held in `status_transitions` and enforced by one trigger function. `SchemaIT` compares that table with the DM "Allowed status changes" document and tries every move between every pair of states. | Verified |
| P2 | Added beyond the data model, with the DM document updated in the same commit: `idempotency_keys` table; six operational columns on `jobs`; ten audit actions (`user_updated`, `user_reactivated`, `user_invitation_resent`, `setting_changed`, `tender_duplicated`, `tender_vendor_added`, `document_impact_decided`, `document_retry_requested`, `job_cancelled`, `job_retried`); the move `jobs: queued -> failed`. | Design gap, closed |
| P3 | The data model has no `table_header` column on `passages` although AI 3.2 produces one. It is kept in `passages.jsonl` and the Qdrant payload only. | Assumption |
| P4 | Tender names are unique ignoring case and outer spaces (`lower(btrim(name))`); DM says "unique". | Assumption |
| P5 | `audit_events` and `document_access_events` are partitioned by month with 24 months created at migration time plus a default partition. `ensure_monthly_partitions()` is a SECURITY DEFINER function the application role may call; a scheduled task in the backend must run it before the window ends (P2-2 adds the task). Data landing in the default partition blocks creating that month later, so the task must stay ahead. | Gap until the task exists |
| P6 | RLS (V9, V10): every policy is scoped `TO` its role. Helper functions (`app_is_member_of`, `app_is_chair_of`, `app_can_read_document_version`, ...) are SECURITY DEFINER with a fixed search path; the Chair is read from `tender_members`. Observer reads the tender list, committee rows and vendor counts only (PI Q7 default). The application role has no privilege on audit partitions, because a partition addressed by name would bypass the parent's policy (found in review, test `auditEventsAreAppendOnly...`). | Verified (`RlsIT`, 27 tests) |
| P7 | A user may update their own `time_zone` and `last_sign_in_at`, and move `invited -> active` on first sign-in; role, email, name and any other status change are the administrator's (trigger `guard_user_self_update`). The role mirror from the token on first request (PI 3.4) is not implemented: the role changes only through the admin API, which changes Keycloak and the row together. | Assumption |
| P8 | The AI-ingest role has column-level UPDATE on `jobs`, `job_steps` and `document_versions`, INSERT on pages, sections, passages, steps and non-person audit events, and no access to users, committees, declarations or drafts. Its policies are `USING (true)` and apply only to that role. | Decision (you chose it) |
| P9 | V2 to V8 were corrected before any shared environment ran them (trigger functions made SECURITY DEFINER; partition privileges). Migrations are immutable from the first deployment on. | Note |
