@AGENTS.md

# CLAUDE.md

Claude Code specifics. Everything in `AGENTS.md` (imported above) applies. This file only adds how to work efficiently in this repository.

## Keep context lean

The design documents are large (about 40 to 75 KB each). Do not read them whole.

- Locate first, then read the section. Headings are numbered in `04-...` and `05-...`: `grep -n "^### 5\." docs/design/04-platform-integration-design.md`.
- Search by ID: requirements `UX-xxx-nn`, screens `PG-nn`, config keys in backticks, failure codes, subjects such as `documents.analysis.requested`.
- Cross-references use `PI x.y` (Platform Integration Design) and `AI x.y` (AI Pipeline Design). Resolve them by section number.
- For wide questions ("where is X used?") use a read-only exploration subagent and ask for conclusions with file and section references, not file dumps.
- Do not read `api/openapi.yaml` whole. Search for the `operationId` or path, and read that operation and the schemas it references.

## Plan before you build

Use plan mode first when a change:

- crosses components (for example a new job type touching backend, worker, contracts and a screen),
- changes a row-level security policy, a status-change trigger or a migration,
- changes a prompt, a model or a retrieval setting,
- adds a NATS subject, consumer or bucket.

The plan names the requirement IDs served, the contract files that change first, the tests that prove it, and the design document sections to update.

## Order of work for a change

1. Find the requirement and screen IDs and read the relevant section.
2. Change the contract first (`api/openapi.yaml` or the message model), then regenerate.
3. Write the failing test, then the code.
4. Run the narrowest test suite that covers the change, then the wider one. Report which you ran and what passed. If a command from the table in `AGENTS.md` does not exist yet, say so rather than guessing one.
5. Update the design document section if behaviour changed.
6. Commit with the requirement IDs in the message.

## Checks to run mentally before finishing

- Did I put a decision in the Chair, the token or the front end that belongs in the database?
- Did any worker code gain a database or Keycloak connection?
- Is every NATS publish after a commit, with a `Nats-Msg-Id`, and idempotent on the receiving side?
- Does every new write also write an audit event?
- Could a log line, object key or trace now contain a name or document text?
- Did I change a prompt or retrieval setting without an evaluation run?

## Working with subagents

- Use read-only exploration agents for searches across the design documents and the code.
- Use a separate reviewing agent, which has not seen the work being produced, for changes to row-level security, the grounding checks, the message loop or anything in the list of rules that must never be broken in `AGENTS.md`.
- Give each subagent the exact section numbers to read and the question to answer, so it does not re-derive context.

## Suggested project automation

Not set up yet. Add them when the repository has code, and list them here:

- A pre-commit hook for formatting and secret scanning.
- A check that fails when `api/openapi.yaml` changes without regenerated clients.
- A check that fails when a prompt file changes without a version bump (the build hashes every prompt file, AI 4.7).
- A skill or command that, given a requirement ID, prints the requirement, its screens and the tables it touches (the traceability tables in `03-...` and `01-...` already hold this).
