#!/usr/bin/env bash
# Phase 0 walking slice, end to end through the gateway:
#   browser-facing gateway -> backend (JWT, request transaction, PostgreSQL)
#   -> command published after commit -> worker consumes -> status published
#   -> backend receives it.
# Also checks the network separation behind AGENTS.md rule 3 and that the gateway
# does not buffer server-sent events. Needs `make up`. Development only.
set -uo pipefail
cd "$(dirname "$0")/../.."
set -a; . ./.env; set +a
GW="http://localhost:$GATEWAY_PORT/api/v1"
PROJECT=tender-eval
fails=0
ok()  { echo "ok    $1"; }
bad() { echo "FAIL  $1"; fails=$((fails+1)); }

echo "== sign in (code + PKCE) and take an access token"
out=$(python3 -I infra/scripts/signin_check.py --print-token) || { echo "$out"; echo "FAIL sign-in"; exit 1; }
TOKEN=$(printf '%s\n' "$out" | sed -n 's/^TOKEN=//p'); SUB=$(printf '%s\n' "$out" | sed -n 's/^SUB=//p')
[ -n "$TOKEN" ] && ok "token for sub $SUB" || { bad "no token"; exit 1; }

echo "== gateway -> backend -> PostgreSQL (inside the request transaction)"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GW/_dev/walking-slice")
[ "$code" = 401 ] && ok "no token: 401" || bad "no token got $code"
ctype=$(curl -s -D - -o /dev/null -X POST "$GW/_dev/walking-slice" | tr -d '\r' | grep -i '^content-type:' | head -1)
echo "$ctype" | grep -qi 'application/problem+json' && ok "401 body is problem+json" || bad "401 content type: $ctype"

me=$(curl -s -H "Authorization: Bearer $TOKEN" "$GW/me")
USER_ID=$(echo "$me" | jq -r .user.id)
[ "$(echo "$me" | jq -r .user.role)" = admin ] && ok "GET /me: bootstrap administrator row exists with role admin" || bad "GET /me: $me"
[ "$(echo "$me" | jq -r .client_config.session_idle_timeout_seconds)" = 1800 ] && ok "idle timeout read from the realm (1800 s)" || bad "idle timeout not 1800"

resp=$(curl -s -w '\n%{http_code}' -X POST -H "Authorization: Bearer $TOKEN" -H "traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01" "$GW/_dev/walking-slice")
code=${resp##*$'\n'}; body=${resp%$'\n'*}
[ "$code" = 202 ] && ok "start accepted (202)" || { bad "start got $code: $body"; exit 1; }
job=$(echo "$body" | jq -r .job_id)
[ "$(echo "$body" | jq -r .transaction_user_id)" = "$USER_ID" ] && ok "transaction ran as the platform user (users.id), not the token subject" || bad "app.user_id was $(echo "$body" | jq -r .transaction_user_id)"
[ "$(echo "$body" | jq -r .transaction_role)" = admin ] && ok "role mapped procurement_admin -> admin" || bad "app.role was $(echo "$body" | jq -r .transaction_role)"
[ "$(echo "$body" | jq -r .database_user)" = tender_app ] && ok "connected as tender_app (not the owner)" || bad "db user $(echo "$body" | jq -r .database_user)"
[ "$(echo "$body" | jq -r .trace_id)" = 4bf92f3577b34da6a3ce929d0e0e4736 ] && ok "trace id carried from traceparent" || bad "trace id not carried"

echo "== command -> worker -> status -> backend"
state=""
for _ in $(seq 1 60); do
  r=$(curl -s -H "Authorization: Bearer $TOKEN" "$GW/_dev/walking-slice/$job")
  state=$(echo "$r" | jq -r '.state // empty' 2>/dev/null)
  [ -n "$state" ] && break
  sleep 0.5
done
[ "$state" = completed ] && ok "backend received status 'completed' for job $job" || bad "no status for the job (state='$state')"

NET=${PROJECT}_default
cnt=$(docker run --rm --network "$NET" natsio/nats-box:latest nats --server nats://nats:4222 stream info ANALYSIS_EVENTS -j | jq .state.messages)
[ "${cnt:-0}" -ge 1 ] && ok "status stored in ANALYSIS_EVENTS ($cnt message(s))" || bad "ANALYSIS_EVENTS empty"
hdr=$(docker run --rm --network "$NET" natsio/nats-box:latest nats --server nats://nats:4222 stream get ANALYSIS_EVENTS --last-for "documents.analysis.status.document_processing" 2>&1)
echo "$hdr" | grep -q "Nats-Msg-Id: $job:s:1" && ok "status carries Nats-Msg-Id $job:s:1" || bad "status headers: $(echo "$hdr" | head -5 | tr '\n' ' ')"

echo "== workers have no path to the database or Keycloak (rule 3)"
for w in worker-ingest worker-agent; do
  for host in postgres keycloak mailpit; do
    if docker exec ${PROJECT}-$w-1 python -c "import socket,sys; socket.gethostbyname('$host')" >/dev/null 2>&1; then
      bad "$w can resolve $host"
    else
      ok "$w cannot resolve $host"
    fi
  done
  if docker exec ${PROJECT}-$w-1 env | grep -qiE 'DB_URL|DATABASE|POSTGRES|KEYCLOAK'; then bad "$w has database or Keycloak configuration"; else ok "$w holds no database or Keycloak configuration"; fi
done

echo "== gateway does not buffer server-sent events"
t0=$(date +%s.%N); first=""
while IFS= read -r line; do
  case "$line" in data:*) first=$(date +%s.%N); break;; esac
done < <(curl -sN -H "Authorization: Bearer $TOKEN" "$GW/_dev/walking-slice/sse")
if [ -n "$first" ]; then
  dt=$(echo "$first - $t0" | bc -l)
  # The stream lasts 4 s; a buffering proxy would deliver the first event only at the end.
  if (( $(echo "$dt < 2.0" | bc -l) )); then ok "first event after ${dt}s of a 4s stream"; else bad "first event only after ${dt}s: buffered"; fi
else
  bad "no event received"
fi

echo
[ $fails -eq 0 ] && echo "walking slice verified" || echo "$fails check(s) failed"
exit $((fails>0))
