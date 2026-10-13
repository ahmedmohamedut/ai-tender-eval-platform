#!/usr/bin/env bash
# Backlog of every durable consumer and the dead-letter stream (plan decision 12: the Phase 2
# dashboard is this query set plus each worker's /metrics). Read-only.
set -uo pipefail
cd "$(dirname "$0")/../.."
set -a; . ./.env; set +a
NET="${COMPOSE_PROJECT_NAME:-tender-eval}_default"
nats_() { docker run --rm --network "$NET" natsio/nats-box:latest nats --server nats://nats:4222 "$@"; }
printf '%-18s %-22s %10s %12s %12s\n' STREAM CONSUMER UNPROCESSED ACK_PENDING REDELIVERED
for pair in DOCUMENTS:ingest-uploaded DOCUMENTS:ingest-commands ANALYSIS_EVENTS:backend-status ANALYSIS_EVENTS:backend-results; do
  stream=${pair%%:*}; durable=${pair##*:}
  info=$(nats_ consumer info "$stream" "$durable" 2>/dev/null)
  u=$(printf '%s\n' "$info" | sed -n 's/.*Unprocessed Messages: *\([0-9]*\).*/\1/p' | head -1)
  a=$(printf '%s\n' "$info" | sed -n 's/.*Outstanding Acks: *\([0-9]*\).*/\1/p' | head -1)
  r=$(printf '%s\n' "$info" | sed -n 's/.*Redelivered Messages: *\([0-9]*\).*/\1/p' | head -1)
  printf '%-18s %-22s %10s %12s %12s\n' "$stream" "$durable" "${u:-?}" "${a:-?}" "${r:-?}"
done
dlq=$(nats_ stream info DLQ 2>/dev/null | sed -n 's/^ *Messages: *\([0-9][0-9]*\) *$/\1/p' | head -1)
echo "dead letters: ${dlq:-?}"
