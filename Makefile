# Single entry point (AGENTS.md "Commands"). Targets are added milestone by milestone.
SHELL := /bin/bash
COMPOSE := docker compose --env-file .env -f infra/compose/compose.yaml

.PHONY: up up-infra down clean ps logs env verify-env \
	api contracts contracts-check lint lint-worker lint-backend lint-frontend lint-api \
	test test-unit test-integration test-worker test-backend test-frontend deps

.env:
	cp .env.example .env
	@echo "Created .env from .env.example (development values only)."

env: .env

## Start the whole local environment and wait until every service is healthy.
up: .env
	@infra/scripts/preflight.sh
	$(COMPOSE) up -d
	@infra/scripts/wait-healthy.sh
	@$(COMPOSE) ps --format 'table {{.Service}}\t{{.Status}}'

## Dependencies only (no application containers).
up-infra: up

down:
	$(COMPOSE) down

## Stop and delete local volumes (local data only; the application never deletes evidence).
clean:
	$(COMPOSE) down -v

ps:
	$(COMPOSE) ps

logs:
	$(COMPOSE) logs -f --tail=100 $(SERVICE)

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

test-frontend: frontend/node_modules api
	cd frontend && npm run --silent typecheck && npm test --silent

## Unit tests of all three components (no Docker).
test-unit: test-worker test-backend test-frontend

## Integration tests against real containers (Docker): row-level security and the
## request transaction on PostgreSQL; the ingest role on NATS and RustFS.
test-integration: worker/.venv
	cd backend && ./gradlew --console=plain --quiet integrationTest --rerun
	cd worker && uv run pytest -q -m integration

## Everything, in order. The walking slice is added in M6.
test: test-unit test-integration
