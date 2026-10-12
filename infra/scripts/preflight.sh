#!/usr/bin/env bash
# Fails early, with names, when a host port from .env is already taken by
# something that is not this compose project.
set -euo pipefail
cd "$(dirname "$0")/../.."
# A .env created before a key was added to .env.example gets the new keys appended with their
# development values, so an update never fails on an empty variable. Existing values are kept.
added=0
while IFS= read -r line; do
  case "$line" in ''|\#*) continue ;; esac
  key=${line%%=*}
  if ! grep -q "^${key}=" .env; then
    printf '%s\n' "$line" >> .env
    added=$((added+1))
  fi
done < .env.example
[ "$added" -eq 0 ] || echo "preflight: added $added new key(s) from .env.example to .env"

set -a; . ./.env; set +a
bad=0
for v in GATEWAY_PORT KEYCLOAK_PORT POSTGRES_PORT NATS_PORT NATS_MONITOR_PORT RUSTFS_PORT \
         RUSTFS_CONSOLE_PORT QDRANT_PORT PHOENIX_PORT OPENBAO_PORT MAILPIT_SMTP_PORT MAILPIT_HTTP_PORT; do
  port=${!v:-}
  [ -n "$port" ] || { echo "preflight: $v is not set in .env" >&2; bad=1; continue; }
  if ss -ltnH "sport = :$port" 2>/dev/null | grep -q .; then
    # Acceptable when our own container owns it.
    if ! docker ps --filter "label=com.docker.compose.project=tender-eval" --format '{{.Ports}}' | grep -q ":$port->"; then
      echo "preflight: port $port ($v) is already in use; change it in .env" >&2
      bad=1
    fi
  fi
done
exit $bad
