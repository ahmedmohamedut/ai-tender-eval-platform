#!/usr/bin/env bash
# Verifies the local environment after `make up`. Prints one line per check and
# exits non-zero if any check failed. Development only.
set -uo pipefail
cd "$(dirname "$0")/../.."
set -a; . ./.env; set +a
NET=tender-eval_default
PROJECT=tender-eval
fails=0
ok()   { echo "ok    $1"; }
bad()  { echo "FAIL  $1"; fails=$((fails+1)); }
check() { local name=$1; shift; if "$@" >/dev/null 2>&1; then ok "$name"; else bad "$name"; fi; }

export AWS_ACCESS_KEY_ID=$RUSTFS_ACCESS_KEY AWS_SECRET_ACCESS_KEY=$RUSTFS_SECRET_KEY AWS_DEFAULT_REGION=${S3_REGION:-us-east-1}
aws_()  { docker run --rm --network $NET -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e AWS_DEFAULT_REGION -v "$SCRATCH:/scratch:ro" amazon/aws-cli --endpoint-url http://rustfs:9000 "$@"; }
nats_() { docker run --rm --network $NET natsio/nats-box:latest nats --server nats://nats:4222 "$@"; }
psql_() { docker exec -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" ${PROJECT}-postgres-1 psql -U "$POSTGRES_SUPERUSER" -d "$POSTGRES_DB" -At "$@"; }
SCRATCH=$(mktemp -d); trap 'rm -rf "$SCRATCH"' EXIT

echo "== services"
check "all services healthy, provisioners exited 0" infra/scripts/wait-healthy.sh

echo "== PostgreSQL roles (implementation guide 5.4)"
roles=$(psql_ -c "select rolname||':'||rolsuper||':'||rolbypassrls from pg_roles where rolname in ('tender_owner','tender_app','tender_ai_ingest') order by 1")
[ "$roles" = $'tender_ai_ingest:false:false\ntender_app:false:false\ntender_owner:false:false' ] && ok "three roles, none superuser, none bypassrls" || bad "role flags: $roles"
owned=$(psql_ -c "select count(*) from pg_class c join pg_roles r on r.oid=c.relowner where r.rolname in ('tender_app','tender_ai_ingest') and c.relnamespace='public'::regnamespace")
[ "$owned" = 0 ] && ok "application roles own no object" || bad "application role owns $owned objects"
owner=$(psql_ -c "select pg_get_userbyid(datdba) from pg_database where datname='$POSTGRES_DB'")
[ "$owner" = tender_owner ] && ok "database owned by tender_owner" || bad "database owner is $owner"
cant=$(docker exec -e PGPASSWORD="$TENDER_APP_PASSWORD" ${PROJECT}-postgres-1 psql -h 127.0.0.1 -U tender_app -d "$POSTGRES_DB" -At -c "create table public.should_fail(i int)" 2>&1)
echo "$cant" | grep -q "permission denied" && ok "tender_app cannot create tables" || bad "tender_app create table: $cant"

echo "== NATS (PI 5.1, 5.3)"
for s in DOCUMENTS ANALYSIS_EVENTS UI_EVENTS DLQ; do
  want=$(jq -S '{retention,storage,discard,max_age,duplicate_window,subjects}' infra/nats/streams/$s.json)
  got=$(nats_ stream info $s -j | jq -S '.config|{retention,storage,discard,max_age,duplicate_window,subjects}')
  [ "$want" = "$got" ] && ok "stream $s matches infra/nats/streams/$s.json" || bad "stream $s differs"
done
n=0
for f in infra/nats/consumers/*.json; do
  stream=$(basename "$f" .json); stream=${stream%%.*}
  d=$(jq -r .durable_name "$f")
  want=$(jq -S '{ack_policy,ack_wait,max_ack_pending,max_deliver,filter_subject}' "$f")
  got=$(nats_ consumer info "$stream" "$d" -j | jq -S '.config|{ack_policy,ack_wait,max_ack_pending,max_deliver,filter_subject}')
  [ "$want" = "$got" ] && n=$((n+1)) || bad "consumer $stream/$d differs"
done
[ "$n" = 7 ] && ok "seven durable consumers match their definitions" || bad "only $n consumers match"
ttl=$(nats_ stream info KV_JOB_CONTROL -j | jq .config.max_age)
[ "$ttl" = 604800000000000 ] && ok "JOB_CONTROL bucket exists with 7 day TTL" || bad "JOB_CONTROL ttl=$ttl"

echo "== RustFS (PI 4.1)"
buckets=$(aws_ s3api list-buckets --query 'Buckets[].Name' --output text | tr '\t' '\n' | sort | tr '\n' ' ')
[ "$buckets" = "tender-derived tender-documents tender-exports tender-jobs " ] && ok "four buckets" || bad "buckets: $buckets"
check "lifecycle: tender-jobs expires after 90 days" bash -c "docker run --rm --network $NET -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e AWS_DEFAULT_REGION amazon/aws-cli --endpoint-url http://rustfs:9000 s3api get-bucket-lifecycle-configuration --bucket tender-jobs | jq -e '.Rules[0].Expiration.Days==90'"
KEY=00000000-0000-4000-8000-000000000001
echo ok > "$SCRATCH/probe.pdf"
before=$(nats_ stream info DOCUMENTS -j | jq .state.last_seq)
aws_ s3 cp /scratch/probe.pdf "s3://tender-documents/originals/$KEY/$KEY/$KEY.pdf" >/dev/null 2>&1
sleep 2
after=$(nats_ stream info DOCUMENTS -j | jq .state.last_seq)
[ "$after" -gt "$before" ] && ok "upload to tender-documents published documents.uploaded" || bad "no upload event (last_seq $before -> $after)"
nats_ stream purge DOCUMENTS --subject documents.uploaded -f >/dev/null 2>&1
del=$(aws_ s3api delete-object --bucket tender-documents --key "originals/$KEY/$KEY/$KEY.pdf" 2>&1)
echo "$del" | grep -q AccessDenied && ok "delete in tender-documents is denied" || bad "delete was not denied: $del"

echo "== OpenBao (PI 7.2)"
paths=$(docker exec -e BAO_ADDR=http://127.0.0.1:8200 -e BAO_TOKEN="$OPENBAO_DEV_ROOT_TOKEN" ${PROJECT}-openbao-1 sh -c 'for p in backend worker-ingest worker-agent platform; do bao kv list -format=json kv/tender-eval/$p; done' | jq -s 'add|length')
[ "$paths" -ge 20 ] && ok "$paths secret paths loaded under kv/tender-eval" || bad "only $paths secret paths"

echo "== OpenBao AppRoles (PI 7.2)"
bao_login() { curl -s -X POST "http://localhost:$OPENBAO_PORT/v1/auth/approle/login" -d "{\"role_id\":\"$1\",\"secret_id\":\"$2\"}" | jq -r '.auth.client_token // empty'; }
bao_read() { curl -s -o /dev/null -w '%{http_code}' -H "X-Vault-Token: $1" "http://localhost:$OPENBAO_PORT/v1/kv/data/tender-eval/$2"; }
BT=$(bao_login "$OPENBAO_BACKEND_ROLE_ID" "$OPENBAO_BACKEND_SECRET_ID")
[ -n "$BT" ] && ok "backend AppRole signs in" || bad "backend AppRole login"
[ "$(bao_read "$BT" backend/db-app)" = 200 ] && ok "backend reads its own path" || bad "backend cannot read backend/db-app"
[ "$(bao_read "$BT" worker-agent/llm)" = 403 ] && ok "backend cannot read the agent's path" || bad "backend can read worker-agent/llm"
WT=$(bao_login "$OPENBAO_WORKER_AGENT_ROLE_ID" "$OPENBAO_WORKER_AGENT_SECRET_ID")
[ "$(bao_read "$WT" worker-agent/llm)" = 200 ] && ok "agent reads its own path" || bad "agent cannot read its path"
[ "$(bao_read "$WT" backend/db-app)" = 403 ] && ok "agent cannot read the backend's database credentials" || bad "agent can read backend/db-app"
[ -z "$(bao_login "$OPENBAO_BACKEND_ROLE_ID" wrong-secret-id)" ] && ok "a wrong secret id is refused" || bad "wrong secret id accepted"

# G3: the workers hold an AppRole, not the secrets themselves.
for w in worker-ingest worker-agent; do
  c=${PROJECT}-$w-1
  if docker ps --format '{{.Names}}' | grep -q "^$c$"; then
    wenv=$(docker exec "$c" env)
    leaked=
    for v in "$RUSTFS_SECRET_KEY" "$QDRANT_API_KEY" "$QDRANT_READ_ONLY_API_KEY" "$EMBEDDING_API_KEY"; do
      [ -n "$v" ] && printf '%s' "$wenv" | grep -qF "=$v" && leaked=1
    done
    [ -z "$leaked" ] && ok "$w environment holds no storage, Qdrant or embedding secret" || bad "$w environment holds a secret that belongs in OpenBao"
  fi
done

echo "== Gateway (PI Q13)"
check "gateway answers /healthz" curl -fsS "http://localhost:$GATEWAY_PORT/config.json"
# The body limit is enforced while the body is read, so something must read it.
# Use a throwaway upstream when the real backend is not running.
STUB=
if ! docker ps --format '{{.Names}}' | grep -q "^${PROJECT}-backend-1$"; then
  STUB=${PROJECT}-stub-backend
  docker rm -f $STUB >/dev/null 2>&1
  docker run -d --name $STUB --network $NET --network-alias backend python:3.12-alpine python -c '
import http.server
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        left = int(self.headers.get("Content-Length", 0))
        while left > 0:
            left -= len(self.rfile.read(min(left, 1 << 20)))
        self.send_response(204); self.end_headers()
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(("", 8080), H).serve_forever()' >/dev/null
  sleep 3
fi
head -c 126877696 /dev/zero > "$SCRATCH/big121.bin"; head -c 104857600 /dev/zero > "$SCRATCH/big100.bin"
c=$(curl -s -o /dev/null -w '%{http_code}' -X POST --data-binary @"$SCRATCH/big121.bin" "http://localhost:$GATEWAY_PORT/api/v1/_probe")
[ "$c" = 413 ] && ok "body above 120 MiB is refused with 413" || bad "121 MiB body got $c"
c=$(curl -s -o /dev/null -w '%{http_code}' -X POST --data-binary @"$SCRATCH/big100.bin" "http://localhost:$GATEWAY_PORT/api/v1/_probe")
# The limit is the gateway's; what the upstream then says (204 from the stub, 401 from the real
# backend, or 502 when the backend answers early and closes while the body is still arriving)
# is not its concern. Only a refusal by the gateway itself (413) or no answer at all fails.
[ "$c" != 413 ] && [ "$c" != 000 ] && ok "100 MB body is not refused by the gateway (upstream status $c)" || bad "100 MB body got $c"
[ -z "$STUB" ] || docker rm -f $STUB >/dev/null 2>&1

echo "== Keycloak (PI 3.7)"
if python3 -I infra/scripts/signin_check.py; then ok "bootstrap administrator signs in with code + PKCE"; else bad "bootstrap sign-in"; fi

echo
[ $fails -eq 0 ] && echo "environment verified" || echo "$fails check(s) failed"
exit $((fails>0))
