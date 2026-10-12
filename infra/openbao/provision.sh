#!/bin/sh
# Loads the secret paths of PI 7.2 into a dev-mode OpenBao. Values come from the
# development .env; in a real environment an operator writes them.
# Local components still read their secrets from the environment (see
# docs/decisions.md D7); the paths exist so the layout is exercised from day one.
set -eu

bao secrets list -format=json | grep -q '"kv/"' || bao secrets enable -path=kv -version=2 kv >/dev/null

put() { path=$1; shift; bao kv put "kv/tender-eval/$path" "$@" >/dev/null; echo "kv/tender-eval/$path"; }

put backend/keycloak-admin client_secret="$KEYCLOAK_BACKEND_ADMIN_SECRET"
put backend/rustfs access_key="$RUSTFS_ACCESS_KEY" secret_key="$RUSTFS_SECRET_KEY"
put backend/nats credentials="dev-no-auth"
put backend/smtp password="$SMTP_PASSWORD"
put backend/db-app username=tender_app password="$TENDER_APP_PASSWORD"
put backend/db-ai-ingest username=tender_ai_ingest password="$TENDER_AI_INGEST_PASSWORD"
for role in worker-ingest worker-agent; do
  put "$role/rustfs" access_key="$RUSTFS_ACCESS_KEY" secret_key="$RUSTFS_SECRET_KEY"
  put "$role/nats" credentials="dev-no-auth"
  put "$role/embedding" api_key="dev-stub-embedding-key"
done
put worker-ingest/qdrant api_key="$QDRANT_API_KEY"
put worker-agent/qdrant api_key="$QDRANT_READ_ONLY_API_KEY"
put worker-agent/llm api_key="dev-stub-llm-key"
put worker-agent/phoenix api_key="dev-not-needed"
for svc in keycloak rustfs qdrant phoenix nats; do
  put "platform/$svc" note="dev values live in .env"
done
echo "openbao provisioning done"
