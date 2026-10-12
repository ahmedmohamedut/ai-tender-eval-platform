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
# ---- AppRole: one role and one policy per component (PI 7.2) ----------------------------
bao auth list -format=json | grep -q '"approle/"' || bao auth enable approle >/dev/null

role() { # name role_id secret_id
  name=$1; rid=$2; sid=$3
  bao policy write "$name" - >/dev/null <<POLICY
path "kv/data/tender-eval/$name/*" { capabilities = ["read"] }
path "kv/metadata/tender-eval/$name/*" { capabilities = ["list", "read"] }
POLICY
  bao write "auth/approle/role/$name" token_policies="$name" token_ttl=1h token_max_ttl=4h \
      secret_id_ttl=0 secret_id_num_uses=0 >/dev/null
  bao write "auth/approle/role/$name/role-id" role_id="$rid" >/dev/null
  # Local development delivers the secret id by environment, not response-wrapped.
  if ! bao write auth/approle/login role_id="$rid" secret_id="$sid" >/dev/null 2>&1; then
    bao write "auth/approle/role/$name/custom-secret-id" secret_id="$sid" >/dev/null
  fi
  echo "approle $name (policy: read kv/tender-eval/$name/*)"
}
role backend        "$OPENBAO_BACKEND_ROLE_ID"       "$OPENBAO_BACKEND_SECRET_ID"
role worker-ingest  "$OPENBAO_WORKER_INGEST_ROLE_ID" "$OPENBAO_WORKER_INGEST_SECRET_ID"
role worker-agent   "$OPENBAO_WORKER_AGENT_ROLE_ID"  "$OPENBAO_WORKER_AGENT_SECRET_ID"
echo "openbao provisioning done"
