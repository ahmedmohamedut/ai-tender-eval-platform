# Single entry point (AGENTS.md "Commands"). Targets are added milestone by milestone.
SHELL := /bin/bash
COMPOSE := docker compose --env-file .env -f infra/compose/compose.yaml

.PHONY: up up-infra down clean ps logs env verify-env

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
