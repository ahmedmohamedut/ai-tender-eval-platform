# Single entry point (AGENTS.md "Commands"). Targets are added milestone by milestone.
SHELL := /bin/bash
COMPOSE := docker compose --env-file .env -f infra/compose/compose.yaml --profile apps

.PHONY: up up-infra down clean ps logs env verify-env smoke-ingest drills eval \
	rebuild-apps rebuild-backend rebuild-workers rebuild-frontend \
	seed-dev-users api contracts contracts-check lint lint-worker lint-backend lint-frontend lint-api \
	test e2e test-unit test-integration test-slice test-worker test-backend test-frontend deps

.env:
	cp .env.example .env
	@echo "Created .env from .env.example (development values only)."

env: .env

## Start the whole local environment and wait until every service is healthy.
up: .env frontend/src/api/generated/schema.d.ts
	@infra/scripts/preflight.sh
	cd frontend && npm run --silent build
	$(COMPOSE) build worker-ingest
	$(COMPOSE) up -d --build
	@infra/scripts/wait-healthy.sh
	@infra/scripts/seed-dev-users.sh
	@$(COMPOSE) ps --format 'table {{.Service}}\t{{.Status}}'

## Dependencies only (no application containers).
up-infra: .env
	@infra/scripts/preflight.sh
	docker compose --env-file .env -f infra/compose/compose.yaml up -d
	@infra/scripts/wait-healthy.sh

## Rebuild and recreate only application containers; Keycloak, Postgres, RustFS, Qdrant, OpenBao
## and every volume are left alone (unlike `make clean`, which deletes all local data).
## Flyway runs on backend start, so new migrations apply to the existing database.
rebuild-backend: .env
	$(COMPOSE) build backend
	$(COMPOSE) up -d --no-deps --force-recreate backend
	@infra/scripts/wait-healthy.sh

## The ingest and agent workers and the development embedding stub share one image.
rebuild-workers: .env
	$(COMPOSE) build worker-ingest
	$(COMPOSE) up -d --no-deps --force-recreate embedding-stub llm-stub worker-ingest worker-agent
	@infra/scripts/wait-healthy.sh

## The SPA is a static build served by the gateway from frontend/dist: no restart needed.
rebuild-frontend: frontend/src/api/generated/schema.d.ts
	cd frontend && npm run --silent build

rebuild-apps: rebuild-frontend rebuild-backend rebuild-workers
	@$(COMPOSE) ps --format 'table {{.Service}}\t{{.Status}}'

down:
	$(COMPOSE) down

## Stop and delete local volumes (local data only; the application never deletes evidence).
clean:
	$(COMPOSE) down -v

ps:
	$(COMPOSE) ps

logs:
	$(COMPOSE) logs -f --tail=100 $(SERVICE)

## Development users member01, member02, observer01 (password DEV_SEED_USER_PASSWORD).
seed-dev-users: .env
	@infra/scripts/preflight.sh
	@infra/scripts/seed-dev-users.sh

## Check the provisioned environment (buckets, streams, roles, sign-in ...).
verify-env: .env
	@infra/scripts/verify-env.sh

# ---------------------------------------------------------------- dependencies
frontend/node_modules: frontend/package-lock.json
	cd frontend && npm ci
	@touch frontend/node_modules

worker/.venv: worker/uv.lock worker/pyproject.toml
	cd worker && uv sync --frozen
	@touch worker/.venv

deps: frontend/node_modules worker/.venv

# ---------------------------------------------------------------- contracts
## Typed client types, regenerated when the spec changes. Not committed.
frontend/src/api/generated/schema.d.ts: api/openapi.yaml frontend/node_modules
	cd frontend && npm run --silent api

## Lint api/openapi.yaml, generate the backend server interfaces and the typed
## TypeScript client. Generated code is not committed (build/ and src/api/generated/).
api: frontend/node_modules
	cd frontend && npm run --silent api:lint
	cd frontend && npm run --silent api
	cd backend && ./gradlew --console=plain --quiet openApiGenerate compileJava
	@echo "api: spec valid; server interfaces compiled; client types generated"

## Export JSON Schemas from the Pydantic message models, then test the backend
## against the exported files.
contracts: worker/.venv
	cd worker && uv run python -m tender_worker.contracts export ../contracts/messages
	cd backend && ./gradlew --console=plain --quiet test --tests '*MessageContractTest'
	@echo "contracts: schemas exported; backend accepts every valid sample and refuses every invalid one"

## Fails when the committed schemas differ from a fresh export (used by CI).
contracts-check: worker/.venv
	cd worker && uv run python -m tender_worker.contracts check ../contracts/messages

# ---------------------------------------------------------------- lint
lint-worker: worker/.venv
	cd worker && uv run ruff check . && uv run ruff format --check .

lint-backend:
	cd backend && ./gradlew --console=plain --quiet spotlessCheck

lint-frontend: frontend/node_modules
	cd frontend && npm run --silent lint

lint-api: frontend/node_modules
	cd frontend && npm run --silent api:lint

## Lint and format check everything.
lint: lint-worker lint-backend lint-frontend lint-api contracts-check
	@echo "lint: ok"

# ---------------------------------------------------------------- tests
test-worker: worker/.venv
	cd worker && uv run pytest -q

test-backend:
	cd backend && ./gradlew --console=plain --quiet test

test-frontend: frontend/src/api/generated/schema.d.ts
	cd frontend && npm run --silent typecheck && npm test --silent

## Unit tests of all three components (no Docker).
test-unit: test-worker test-backend test-frontend

## Integration tests against real containers (Docker): row-level security and the
## request transaction on PostgreSQL; the ingest role on NATS and RustFS.
test-integration: worker/.venv
	cd backend && ./gradlew --console=plain --quiet integrationTest --rerun
	cd worker && uv run pytest -q -m integration

## Walking slice through the gateway: backend, PostgreSQL, NATS, worker and back.
## Starts the environment if it is not running.
test-slice: up
	infra/scripts/walking-slice.sh

# The evaluation sets (synthetic documents): extraction, then grading (360 cases), pre-check and
# injection. Without arguments the rule-based stub runs in-process, which checks the harness. For
# a model: make eval EVAL_ARGS="--base-url URL --model NAME --write" (EVAL_QUALITY_ARGS takes the
# same plus --context-chars and --max-output-tokens for the grading sets).
eval:
	cd worker && uv run python -m tender_worker.evalrun extraction $(EVAL_ARGS)
	cd worker && uv run python -m tender_worker.evalquality all $(EVAL_ARGS) $(EVAL_QUALITY_ARGS)

# Real upload through the gateway to ready (and the three file causes): synthetic documents only.
smoke-ingest: up
	infra/scripts/ingest-smoke.sh

# Failure drills of the worker and the message loop (several minutes; restarts workers and Qdrant).
drills: up
	infra/scripts/drills.sh

## Everything, in order.
test: test-unit test-integration test-slice

## Browser journeys against the running stack (Phase 1 exit criterion). Starts it if needed.
## Needs Chromium once: cd frontend && npx playwright install chromium
e2e: up
	cd frontend && set -a && . ../.env && set +a && npx playwright test
