# Progress

The one place to see what is done, what is left, what went wrong and what is still open. Update it in the same commit as the work, as the last step of each milestone.

- **Decisions and assumptions** are numbered in `docs/decisions.md` (D = tooling and environment, P = platform decisions). This file refers to them by number and does not repeat them.
- **Plan** (phases, sizes, exit criteria) is `docs/roadmap-plan.md`. This file holds the status of each of its work items.
- **Quality results** of the evaluation sets go in `docs/releases/`.

Status words: **done** (built, tested, committed), **partial** (built with a stated gap), **not started**, **open** (needs a decision or something outside the repository).
"Verified by" names the command or test that proves it, as it was run. A commit that names a milestone (for example `P3-4`) is the work for it.

## Summary

| Phase | Status | Gate |
| --- | --- | --- |
| 0 Foundations | done, with two items not done (below) | Clean-clone run green |
| 1 Platform spine | done | Browser journey, policy and audit-coverage tests |
| 2 Documents and ingestion | done | Clean-clone gate, drills 8 of 8, 300-page run |
| 3 Requirements | done, with gaps (below) | Gate run on the running stack: lint, api, contracts, unit, integration, e2e (3 journeys), verify-env, smoke-ingest, eval, extraction-smoke; extraction drills 6 of 6 |
| 4 Screening and evaluation | in progress (plan approved 2026-10-14; milestones P4-0 to P4-8, see below) | |
| 5 Consolidation and reporting | built; gate passed 2026-10-15 (open: G15, G16, G17, G18) | |
| 6, 7 | not started, see roadmap | |

## Task status

### Phase 0: Foundations (commits M1 to M8, `f6fd937`)

| Work item | Status | Commit | Verified by | Notes |
| --- | --- | --- | --- | --- |
| Repository, CI workflow | done | M1, M7 | CI file lints; **never run on GitHub** (nothing pushed) | D18 |
| Local environment in containers, provisioning as code | done | M2 | `make up`, `make verify-env` | D1 to D12 |
| Contract pipeline (lint, generated interfaces and client, JSON Schemas) | done | M4 | `make api`, `make contracts` | D19 |
| Backend, worker, frontend skeletons | done | M3 | unit tests | D14 |
| Integration tests (transaction-local identity, RLS not disableable, NATS, RustFS) | done | M5 | `make test-integration` | |
| Walking slice through the gateway | done | M6 | `make test-slice` | D13, D16 |
| Shared development and staging skeleton | **not done** | | | Local only; D8 lists the gap |
| AI feasibility spike on a real RFP and two proposals | **not done** | | | No real documents; replaced by synthetic documents and the extraction evaluation set (P66) |
| Decisions table with owners and dates | partial | M1 | `docs/decisions.md` | Defaults taken; owners and dates are not recorded |

### Phase 1: Platform spine (commits P1-1 to P1-8)

| Work item | Status | Commit | Verified by | Notes |
| --- | --- | --- | --- | --- |
| Keycloak realm, clients, roles, lockout, bootstrap administrator | done | M2, P1-5 | `infra/scripts/signin_check.py`, `make verify-env` | D3, D4 |
| Migrations V2 to V8 (identity, tenders, documents, vendors, jobs, audit), status triggers | done | P1-1 | schema tests | P1 to P5, P9 |
| Row-level security and policy tests | done | P1-2 | 27 policy tests | P6 to P10 |
| Backend spine (user resolution, `/me`, problems, ETag, cursors, idempotency, rate limits) | done | P1-3 | integration tests | P11, P12 |
| Audit service and write-coverage contract test | done | P1-4 | `AuditWriteCoverageIT` | |
| Users, tenders and settings API, x-roles guard | done | P1-5 | `OperationRolesIT` | |
| OpenBao for the backend (own AppRole) | done | P1-6 | `make verify-env` | D7: workers still read secrets from the environment |
| SPA shell and Phase 1 screens | done | P1-7 | vitest, Playwright | |
| Browser journey for the exit criterion | done | P1-8 | `make e2e` | |

### Phase 2: Documents and ingestion (commits P2-1 to P2-9)

| Work item | Status | Commit | Verified by | Notes |
| --- | --- | --- | --- | --- |
| Upload streamed to object storage | done | P2-1 | integration tests, `make smoke-ingest` | |
| Job model, timers, handlers, jobs API | done | P2-2 | integration tests, drills | |
| `worker-ingest` pipeline (PDF, DOCX, passages, manifest) | done | P2-3 | worker tests, 300-page run | |
| Qdrant index, embedding client, re-index | done | P2-4 | `-m integration` tests | Dev embedding is a hash stub |
| Viewer API (outline, pages, passages, search, render) | done | P2-5 | integration tests | `render` buffers the whole PDF (gap) |
| Server-sent events | done | P2-6 | integration tests | |
| Vendors and proposal versions | done | P2-7a | integration tests | |
| Screens PG-04, PG-05, PG-17 and the viewer | done | P2-7 | vitest, `make e2e` | |
| Failure drills and the 300-page run | done | P2-8 | `make drills` (8 of 8) | |
| Worker metrics, lag script | done | P2-9 | `infra/scripts/consumer-lag.sh` | |
| UI changes asked for after Phase 2 (back links, 3-second dismissable toast, rebuild targets) | done | `2910747`, `df38f79`, `f1bc222` | vitest | |

### Phase 3: Requirements (commits P3-1 to P3-8)

| Work item | Status | Commit | Verified by | Notes |
| --- | --- | --- | --- | --- |
| Schema and RLS for requirements, baselines, scoring, AI runs | done | P3-1 (`2ce5775`) | `RequirementsSchemaIT` (9) | P55, P56; reviewed by the user before merge |
| Requirements API (list, edit with 412, delete, restore, bulk, merge, split, history, categories) | done | P3-2 | integration tests | P57, P58 |
| Requirements export (UX-REQ-15) | **not started** | | | Answers 501 until the generic export machinery exists (Phase 5) |
| Baseline and scoring (pre-check, confirm, reopen, snapshot, locked model) | done | P3-3 | integration tests | P59, P60; V18 DELETE grant flagged for review |
| Extraction job, backend side | done | P3-4 | `ExtractionApiIT`, `ExtractionResultIT` | P61, P62 |
| Agent runtime and extraction graph | done | P3-5 | worker tests | P63, P64; Phoenix tracing hook **not added** (P65) |
| Stub LLM and extraction smoke | done | P3-6a | `infra/scripts/extraction-smoke.sh` (15 of 15) | |
| Extraction evaluation set and first release record | done | P3-6 | `make eval` | P66, P67; `docs/releases/extraction-eval-20261013-gemma-4-26B-A4B.md` |
| Screens PG-10, PG-11, PG-12 | done | P3-7 | vitest (62), `e2e/phase3.spec.ts` | P68; text-selection scope not in the screens |
| Failure drills for extraction, extraction retry, gate | done | P3-8 | `DRILL_SET=extraction make drills` (6 of 6), gate run | P69 |

### Phase 4: Screening and evaluation (plan: P4-0 to P4-8)

Decisions for this phase: notification centre and email move to Phase 5; Chair reads peers like a member (Q3 default); other open data questions take the documented defaults (P70 and the Phase 4 entries that follow).

| Work item | Status | Commit | Verified by | Notes |
| --- | --- | --- | --- | --- |
| P4-0 Embeddings and models (Nemotron prefixes, eval tooling, release record) | done, defaults not switched | P4-0 | worker unit tests (118), `docs/releases/extraction-eval-20261014-gemma-4-12b-nemotron-embed-8b.md` | P70; containers cannot reach the gateway (I12) |
| P4-1 Schema and RLS (ten tables, policies, triggers) | done (approved) | P4-1 | `ScreeningEvaluationSchemaIT` (29), backend 264 passing; independent review found 11 issues, fixed | P71; V20 |
| P4-2 Conflict declaration and proposal access (PG-13) | done | P4-2 | `ConflictDeclarationApiIT` (3), audit scenario, vitest (65) | P72; closes G8 |
| P4-3a Screening API (checklist, checks, recommendations, decision, reinstate) | done | P4-3a | `ScreeningApiIT` (5), 5 audit scenarios | P73 |
| P4-3b Pre-check job (backend, worker graph, stub) | done | P4-3b | worker 124, backend 275, `infra/scripts/screening-smoke.sh` 16/16 on the stack | P74 |
| P4-3c Screen PG-14 | done | P4-3c | vitest 68 (3 new, axe clean); journey in P4-7 | P75 |
| P4-4 Evaluation backend (job, grades handler, member rows, outdated) | done | P4-4 | `GradeResultIT` (3), `EvaluationApiIT` (3), audit scenario; backend 281 | P76; the worker side is P4-5 |
| P4-5 Evaluation graph (retrieval, grade, verify absence, repair, resume) | done | P4-5 | worker 133, `infra/scripts/evaluation-smoke.sh` 19/19 on the stack (real Qdrant hybrid search, stub model) | P77 |
| P4-6 Member evaluation API | done | P4-6 | `MemberEvaluationApiIT` (6), 6 audit scenarios, `ScreeningEvaluationSchemaIT` audit-hold test; backend 288 | P78 (audit read policy change approved) |
| P4-7 Screens PG-15, PG-16 (+ evaluation overview) | done | P4-7 | vitest 74 (7 new, axe clean), `e2e/phase4.spec.ts` (two members, independence) | P79; Chair reopen/exclude controls are API only (gap G14) |
| P4-8 Evaluation sets, security tests, drills, gate | not started | | | |

### Phase 5: Consolidation and reporting (plan approved 2026-10-15: P5-1 to P5-7)

Decisions for this phase: P83 (scoring formula), P84 (exports generated by the API), P85 (visibility, reminders, notify). The roadmap names UX-RPT-01 to 16; the design has 01 to 13 only.

| Work item | Status | Commit | Verified by | Notes |
| --- | --- | --- | --- | --- |
| P5-1 Schema and RLS (V24: report versions, grades, copies, vendor results, exports, notifications, preferences) | done (independent review: 8 findings, 7 fixed in V24 before merge, 1 confirmed intended) | P5-1 | `ReportSchemaIT` (28), backend integration suite green | P83, P85; `tender_stage_dates` and the exclusion columns already existed |
| P5-2 Report API (progress, overview, matrix, cell, disagreements, appendix, versions, screening outcomes, final grades, finalize, reopen; member reopen, exclude and clarifications already built in P4) | done; independent review: 7 findings, 1, 2, 5, 6, 7 fixed (V26 for 1), 3 and 4 open as gaps below | UX-RPT-01..08, 10, 11, 13 | `ReportApiIT` (6), `AuditWriteCoverageIT` +3 | V25 read functions for the Observer (P86); P87, P88 |
| P5-3 Scoring (`ScoringService`, P83) | done | UX-SCO-03, UX-RPT-03, UX-RPT-07 | `ScoringServiceTest` (8) | computed at finalization into `report_vendor_results` |
| P5-4 Exports (`ExportService`, report PDF and Excel, requirements, clarifications, audit CSV, status and download) | done; export failure drill in P5-7 | UX-RPT-09, UX-RPT-12, UX-REQ-15, UX-EVA-06, UX-AUD-03 | `ExportApiIT` (3), `AuditWriteCoverageIT` +4, backend suites green | P89; closes G7 |
| P5-5 Notifications, reminders, email (list, read, preferences, unread count, producers, `sendReportReminders`, due-date timer, SMTP sender, per-user push) | done; email delivery against Mailpit not run (no SMTP in the tests) | UX-NOT-01..03, UX-RPT-02 | `NotificationApiIT` (4), `UiEventHubTest` (2), `AuditWriteCoverageIT` +1, backend suites green | P90; V27 |
| P5-6 Screens PG-19, PG-20, Chair panel, notification centre | done: 6a (backend `listAuditEvents`, progress cells carry the evaluation id, notification bell and PG-18, Chair panel, export button); 6b (PG-19 `Report`: status, versions, ranking, matrix with filters, expanded cell, Chair's final grade, finalize with missing list, reopen, export, print layout); 6c (PG-20 `Audit`: changes and proposal access tabs, tender/action/date filters, paging, CSV export; backend `listDocumentAccessEvents`; Audit nav entry for admin and Observer; `e2e/phase5.spec.ts`) | UX-NOT-02, UX-RPT-02, UX-EVA-06, UX-EVA-13, UX-AUD-01, UX-AUD-03, UX-NFR-07 | `phase5.test.tsx` (9), `AuditSearchApiIT` (2), `ReportApiIT` (finalize action state), vitest 89, `e2e/phase5.spec.ts` green on the running stack | closes G14; no operation answers 501 any more (the 501 example test in `MeApiIT` is gone) |
| P5-7 Gate | done 2026-10-15: `make lint api contracts` green; `make test` green (backend unit and integration 293, worker, frontend 89); `make test-slice` green; `make e2e` 5 of 5 on the running stack; export drills as integration tests (`ExportDrillIT`: writer killed, file gone, rebuilt by a new request); email delivered to Mailpit (compose now passes `SMTP_*` to the backend); G19 closed | UX-RPT-01..13, UX-SCO-03, UX-NOT-01..03, UX-AUD-01..03 | see left | Exit criteria met: finalized reports never change (V24 triggers, `ReportSchemaIT`), reopen creates a version and keeps the old readable (`ReportApiIT`, `e2e/phase5.spec.ts`), progress-only until all submitted and members and Observer read after finalization, exports in the background with 90-day expiry and rebuild. Open before release: G15, G16, G17, G18 and P4-8 |
| P5-8 Final-grade history and provenance (G15, G16) | done 2026-10-15; independent review of V28 and the service found one gap (a decision turned into a derived grade left no history), two hardenings (derived rows carry no decider; a carried copy must be in the version's baseline), all fixed and tested | UX-RPT-06, UX-RPT-08 | `ReportSchemaIT` +2, `ReportApiIT` +2, `phase5.test.tsx` +1; backend 296, frontend 90, `make e2e` 5 of 5 | P91 |

## Issues and fixes

Notable problems found while building, how each was found and what guards against it now. Add a row when a defect, a wrong assumption or an environment problem costs real time.

| # | Phase | Issue | Cause | Fix | Guarded by |
| --- | --- | --- | --- | --- | --- |
| I1 | 0 | Clean-clone build failed in the frontend | The typed client was not generated before the frontend build | Makefile generates the client first (`f6fd937`) | Clean-clone run |
| I2 | 1 | Keycloak sign-in failed in the browser journey | `redirect_uri` was not encoded (P1-8) | Encoded in the SPA | `make e2e` |
| I3 | 1 | Lint failed on a clean clone | Vitest reporter output was tracked; vitest collected the Playwright journeys (`7c876ea`, `873b4c0`) | Ignored the output; excluded `e2e/` | `make lint` |
| I4 | 1 | `verify-env` failed on the 100 MB check | The check asked for more than the gateway promises (`9f0bb27`) | It now asks only that the gateway does not refuse | `make verify-env` |
| I5 | 1 | Admin sign-in locked out repeatedly in local runs | Keycloak brute-force lockout (5 failures in 15 minutes) and password history of 5 | Password reset, value kept in `.env` for development | Documented here |
| I6 | 3 | Extraction returned items without sources | `sources` had a default, so it was optional in the JSON schema and the model left it out | Made required (P67) | Extraction evaluation set |
| I7 | 3 | A window's answer was cut off | 1,500 output tokens was too few | `LLM_MAX_OUTPUT_TOKENS` default 4096; a length-limited answer does not open the breaker (P67) | Evaluation set, unit tests |
| I8 | 3 | Playwright journey hung or failed in several places | Heading order (axe), exact text match on chips with an icon, 120 s default timeout, checkbox count read before the table loaded, navigation before the bulk save finished | Fixed in `e2e/phase3.spec.ts` (`7517706`) | `make e2e` |
| I9 | 3 | A failed extraction could not be retried | `retryJob` answered 501 for AI jobs; found by the outage drill | Retry creates a new job over the same scope with `retry_of_job_id` (P69) | `ExtractionApiIT`, `AuditWriteCoverageIT`, drill 8 |
| I10 | 3 | Extraction drills could not start a job | The drill uploaded the RFP as type `other`, so the tender would not open | Upload with `doc_type=rfp` | `DRILL_SET=extraction make drills` |
| I11 | 3 | Kill drill killed nothing | One window, so the stub answered before the kill | Stub delay control, 20 s delay | Drill 7 |
| I13 | 4 | After the Docker host restarted, the backend would not start (`OpenBao refused AppRole login 403`, then `UI event hub: stream not found`) | OpenBao runs in dev mode and loses its AppRoles on restart, and the NATS streams were not there | Re-ran provisioning (`make up` is idempotent and does all of it) | `make verify-env` |
| I12 | 4 | Real model unreachable from containers; real extraction failed with `LlmUnavailable` | The gateway listens on `127.0.0.1` (refused from `host.docker.internal`); `gemma-4-12b` has a 4096-token context, so a 4096-token output limit overflowed it and 1,800 to 2,300 token outputs were cut or overflowed with 2,000 to 4,000 character windows | Eval run from the host with 1,200-character windows and 1,800 output tokens; settings recorded in P70 | `make eval` with `--window-chars`/`--max-output-tokens` |
| I14 | 4 | A member could not reach the declaration dialog: the proposal file list was empty before the first declaration, so no Open button | The file list joined `proposal_version_files` to `document_versions`, which row-level security hides from a member until they declare no conflict; the dialog opens only from a file's Open button | `ProposalService.withFiles` uses a LEFT JOIN: a closed file is listed with its id and role, the name "Main file" or "Annex" and a null `status`; `ProposalFile.status` is nullable in `openapi.yaml`; Vendors shows Open for `ready` or null | `ConflictDeclarationApiIT.theFileRowsAreListedBeforeTheDeclarationWithoutTheirNamesOrStatus`, `phase2.test.tsx` (PG-13 Open) |
| I15 | 4 | Extraction left every requirement without a category and the drop-down was empty, so the baseline could not be confirmed | `requirement_categories` was never filled: no seed, and the UI only read the list; the endpoints to manage it had no screen | V23 seeds seven starter categories when the table is empty; admin screen PG-21 `/categories` (add, rename, reorder, deactivate); `updateCategory` refuses a blank name | `CategoriesApiIT`, `categories.test.tsx`, `pages.test.tsx` (nav) |
| I16 | 5 | The report's Finalize button was enabled while a disagreement had no final grade; the unit test only used a mocked header | The backend set the `finalize` action by role and stage and left the open disagreements to the 422 check | `finalize.allowed` is also false with the reason "N disagreements still need a final grade"; the page shows that reason once | `ReportApiIT` (action state), `e2e/phase5.spec.ts` |
| I17 | 5 | Axe in the browser: matrix cell buttons carried an `aria-label` that did not contain their visible text (WCAG 2.5.3) | jsdom axe does not run the label-in-name rule | The name is built from hidden text plus the visible grade | `e2e/phase5.spec.ts` (axe on the report) |

## Open gaps

| # | Gap | Where it bites | To close |
| --- | --- | --- | --- |
| G1 | Nothing has been pushed, so CI has never run on GitHub | Every gate was run locally | Push to a remote and read the first run |
| G2 | No staging environment; NATS and Phoenix run without authentication or TLS locally (D8) | Shared environments | Phase 0 staging skeleton |
| G3 | Workers read secrets from the environment, not OpenBao (D7) | Security review | Closed: `tender_worker/secrets.py` and `load_settings()` read the worker's own OpenBao paths over AppRole at start (P92); compose no longer passes those secrets; `test_secrets.py` (7), `make verify-env` check |
| G4 | Recall and precision on real RFPs are unmeasured; the only figures are on synthetic RFPs with the stub or the local model | Release gate | An approved endpoint and real or anonymised documents (rule 15) |
| G5 | Duplicate detection is not exercised well: the development embedding is a hash (duplicates flagged 0 of 2 in the release record) | Extraction quality | A real embedding model, then re-run `make eval` |
| G6 | Phoenix tracing for the agent: extraction is traced (P80); evaluation and pre-check were not | Observability | Closed: job, step, row and llm spans for both jobs (P93); `tests/test_tracing_jobs.py` (3); export to Phoenix checked on the stack (probe span reached project `tender-eval`) |
| G7 | Requirements export answered 501 | UX-REQ-15 | Closed in P5-4 (`exportRequirements`) |
| G8 | Proposal files are visible to members only after Phase 4 conflict declarations | Phase 4 | Closed in P4-2: declaration API and dialog; files open only with a no-conflict declaration |
| G9 | `render` buffers the whole PDF in memory | Large documents | Stream with Range |
| G10 | Parts are published together after the graph finishes, so a cancel keeps nothing or whole parts (P69) | Long extractions | Design change if incremental results are wanted |
| G11 | A gate from a wiped clone was not repeated for Phase 3 (`make clean` deletes local data) | Phase 3 sign-off | `git clone`, `make up`, then the gate |
| G13 | The containers cannot use the real model gateway (loopback only) and the chat model has a 4096-token context | Real end-to-end runs and grading quality | Bind the gateway to the Docker bridge or forward it; serve a model with a larger context |
| G14 | The Chair's reopen and exclude controls, and the clarification export, had no screen | UX-EVA-13, UX-EVA-06 | Closed in P5-6a (`ChairPanel` on the progress board) |
| G12 | Decision owners and dates (Phase 0 table) are not recorded | Phase 0 exit criterion | Name an owner for each row |
| G15 | A Chair's final grade was overwritten on re-recording without history, and no If-Match guarded two tabs | UX-RPT-06 | Closed in P5-8: `report_grade_history` (V28), `If-Match` and `row_version` on the cell, history list in the cell dialog (P91); `ReportSchemaIT`, `ReportApiIT`, `phase5.test.tsx` |
| G16 | Reopening a report copied the earlier decisions under the reopening Chair's name and time | UX-RPT-08 provenance | Closed in P5-8: carried decisions keep their decider and time; the guard accepts them only as exact repeats (P91); `ReportSchemaIT`, `ReportApiIT` |
| G17 | Notification types with no producer yet: added_to_tender, tender_opened, job_completed, job_failed, proposal_screened, evaluation_ready, new_proposal_version, conflict_declared, resubmission_requested, document_added | UX-NOT-01 | Add each at its event; job events need a system-context path (the worker status handler runs without a person) |
| G18 | With several backend instances the email batch could be sent twice (`app_notifications_email_next` does not lock) | UX-NOT-03 | Claim rows with `FOR UPDATE SKIP LOCKED` in a transaction that spans the send |
| G19 | The report published no push event, so an open report page refreshed only on tender or evaluation events and on reload | UX-GEN-06 | Closed in P5-7: `report.changed` on `ui.push.tender.{id}` after a final grade, finalize and reopen (after the commit; ids and a reason only); `ReportApiIT` |
| G20 | Workers read OpenBao once at start: no token renewal and no re-read after an authentication failure (PI 7.2) | Rotation of S3, Qdrant, embedding or LLM keys | Restart the worker today; later re-read on a 401/403 and rebuild the clients |
| G21 | The vendor registry has no "tenders this vendor takes part in" list (no endpoint), and the vendor list filters status and industry in the browser on the first 100 vendors | UX-VND-03 | Add a `tenders` view to `getVendor` and `status`/`industry` filters to `listVendors` |
| G22 | The revamp's dark mode and right-to-left layout are checked by the contrast test and the unit tests only, not by the browser journeys or by axe in a real browser | UX-NFR accessibility | Run axe with contrast in both modes on the main pages in `make e2e` |
| G23 | The first screen loads about 480 kB gzip of JavaScript (antd and the shell); only the viewer and the report are split off | Load time | Split more routes, and import antd icons per name (already so) |
