#!/usr/bin/env bash
# Phase 2 failure drills (AGENTS.md, Testing expectations): the worker and the message loop against
# real services, synthetic documents only (rule 15). Needs `make up`. Takes several minutes.
#   1. a 300-page PDF and a DOCX reach ready (counts and timings recorded)
#   2. the worker is killed while parsing: the redelivery finishes the job, nothing is stored twice
#   3. Qdrant is down at indexing: the job fails index_unavailable; after Qdrant returns, a retry
#      finds the manifest and does not parse again
#   4. the bucket event is lost: the queued-job timer publishes the command and the job completes
#   5. the same upload event is delivered again: nothing changes
#   6. an unreadable event goes to the dead-letter stream with its reason
# Phase 3 (requirement extraction, stub model endpoint, synthetic RFP):
#   7. the agent is killed mid-run: the redelivery finishes it, same result as an undisturbed run
#   8. the model endpoint is down: the breaker opens and the job fails llm_unavailable, retryable;
#      after the endpoint returns, a retry completes
#   9. a result part is delivered again: no requirement is stored twice
#  10. cancel during a run: the job is cancelled and nothing is stored
set -uo pipefail
cd "$(dirname "$0")/../.."
set -a; . ./.env; set +a
GW="http://localhost:${GATEWAY_PORT:-18080}/api/v1"
PROJECT="${COMPOSE_PROJECT_NAME:-tender-eval}"
NET="${PROJECT}_default"
PASS=0; FAIL=0
ok()  { echo "ok    $*"; PASS=$((PASS+1)); }
bad() { echo "FAIL  $*"; FAIL=$((FAIL+1)); }
sql() { docker exec -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" "${PROJECT}-postgres-1" psql -U "$POSTGRES_SUPERUSER" -d "$POSTGRES_DB" -Atc "$1" 2>/dev/null; }
nats_() { docker run --rm --network "$NET" natsio/nats-box:latest nats --server nats://nats:4222 "$@"; }
QUEUED_WAIT="${DRILL_QUEUED_TIMEOUT_SECONDS:-${WATCHDOG_QUEUED_TIMEOUT_SECONDS:-120}}"

# Access tokens are short-lived and the drills run for many minutes: fetch a fresh one when due.
TOKEN_FILE=$(mktemp)
auth() {  # runs in subshells, so the token is cached in a file
  local now; now=$(date +%s)
  if [ ! -s "$TOKEN_FILE" ] || [ $((now - $(stat -c %Y "$TOKEN_FILE"))) -gt 90 ]; then
    local out
    out=$(python3 -I infra/scripts/signin_check.py --print-token) || { echo "FAIL sign-in" >&2; exit 1; }
    printf '%s\n' "$out" | sed -n 's/^TOKEN=//p' > "$TOKEN_FILE"
  fi
  printf 'Authorization: Bearer %s' "$(cat "$TOKEN_FILE")"
}
COMPOSE=(docker compose --env-file .env -f infra/compose/compose.yaml --profile apps)
stub_control() { docker exec "${PROJECT}-llm-stub-1" python -c "import json,sys,urllib.request as u; r=u.Request('http://127.0.0.1:8000/control', json.dumps(json.loads(sys.argv[1])).encode(), {'Content-Type':'application/json'}); print(u.urlopen(r,timeout=5).read().decode())" "$1" >/dev/null; }
restore_agent() { "${COMPOSE[@]}" up -d --no-deps --force-recreate worker-agent >/dev/null 2>&1; }
reset_stub() { stub_control '{"mode":"ok","delay_ms":0}' >/dev/null 2>&1; }
cleanup() { rm -rf "$DIR" "$TOKEN_FILE" "${M1TOKEN_FILE:-}"; docker start "${PROJECT}-qdrant-1" "${PROJECT}-worker-ingest-1" "${PROJECT}-llm-stub-1" "${PROJECT}-worker-agent-1" >/dev/null 2>&1; reset_stub; }
DIR=$(mktemp -d); trap cleanup EXIT
(cd worker && uv run python tests/fixtures/generate.py "$DIR" 300) >/dev/null || { echo "FAIL fixtures"; exit 1; }
(cd worker && uv run python - "$DIR" <<'PY'
import sys; sys.path.insert(0, "tests")
from pathlib import Path
from fixtures import synthetic
Path(sys.argv[1], "small.pdf").write_bytes(synthetic.make_rfp(6, seed=11))
Path(sys.argv[1], "small2.pdf").write_bytes(synthetic.make_rfp(6, seed=12))
Path(sys.argv[1], "small3.pdf").write_bytes(synthetic.make_rfp(6, seed=13))
Path(sys.argv[1], "small4.pdf").write_bytes(synthetic.make_rfp(6, seed=14))
PY
) >/dev/null

chair=$(sql "select id from users where email='member01@example.test'")
new_tender() {
  curl -s -H "$(auth)" -H 'Content-Type: application/json' -X POST "$GW/tenders" \
    -d "{\"name\":\"Drill $1 $(date +%s)\",\"chair_id\":\"$chair\",\"member_ids\":[\"$chair\"]}" | jq -r .id
}
upload() { # tender file [doc_type] -> version id
  curl -s -H "$(auth)" -F "doc_type=${3:-other}" -F "title=$2" -F "file=@$DIR/$2" "$GW/tenders/$1/documents" | jq -r .current_version.id
}
status_of() { curl -s -H "$(auth)" "$GW/document-versions/$1" | jq -r '[.status, (.failure_reason // "")] | join(":")'; }
wait_for() { # version wanted-prefix seconds
  local end=$((SECONDS + $3)) s=""
  while [ $SECONDS -lt $end ]; do
    s=$(status_of "$1"); case "$s" in "$2"*) echo "$s"; return 0;; esac; sleep 2
  done; echo "$s"; return 1
}

base_drills() {
# ---- 1. long documents -----------------------------------------------------------------------------
t1=$(new_tender large)
start=$SECONDS; v=$(upload "$t1" rfp.pdf)
if s=$(wait_for "$v" ready 300); then
  took=$((SECONDS - start))
  pages=$(sql "select page_count from document_versions where id='$v'")
  pass=$(sql "select count(*) from passages where document_version_id='$v'")
  qd=$(curl -s -H "api-key: $QDRANT_API_KEY" -H 'Content-Type: application/json' "http://localhost:${QDRANT_PORT:-16333}/collections/passages/points/count" -d "{\"exact\":true,\"filter\":{\"must\":[{\"key\":\"document_version_id\",\"match\":{\"value\":\"$v\"}}]}}" | jq -r .result.count)
  [ "$pages" = 300 ] && [ "$qd" = "$pass" ] && ok "300-page PDF ready in ${took}s: $pages pages, $pass passages, $qd points" || bad "300-page PDF: pages=$pages passages=$pass points=$qd"
else bad "300-page PDF did not reach ready ($s)"; fi
start=$SECONDS; v=$(upload "$t1" sow.docx)
if s=$(wait_for "$v" ready 300); then ok "DOCX ready in $((SECONDS - start))s ($(sql "select count(*) from passages where document_version_id='$v'") passages)"; else bad "DOCX did not reach ready ($s)"; fi

# ---- 2. the worker is killed while parsing ------------------------------------------------------------
t2=$(new_tender kill)
v=$(upload "$t2" rfp.pdf)
for _ in $(seq 1 60); do
  st=$(sql "select status || ':' || coalesce(current_step,'') from jobs where target_id='$v'")
  case "$st" in running:parse|running:write|running:convert) break;; esac; sleep 0.5
done
docker kill "${PROJECT}-worker-ingest-1" >/dev/null
echo "      worker killed at job state '$st'"
sleep 3; docker start "${PROJECT}-worker-ingest-1" >/dev/null
if s=$(wait_for "$v" ready 240); then
  n=$(sql "select count(*) from passages where document_version_id='$v'")
  d=$(sql "select count(*) - count(distinct (page_no, passage_no)) from passages where document_version_id='$v'")
  j=$(sql "select count(*) from jobs where target_id='$v' and job_type='document_processing'")
  [ "$d" = 0 ] && [ "$j" = 1 ] && ok "killed mid-parse: redelivery finished it ($n passages, no duplicates, one job)" || bad "after kill: duplicates=$d jobs=$j"
else bad "after the kill the document is '$s'"; fi

# ---- 3. Qdrant is down at indexing ----------------------------------------------------------------------
t3=$(new_tender qdrant)
docker stop "${PROJECT}-qdrant-1" >/dev/null
v=$(upload "$t3" small.pdf)
if s=$(wait_for "$v" failed 180); then
  [ "$s" = "failed:index_unavailable" ] && ok "Qdrant down: failed:index_unavailable" || bad "Qdrant down: $s"
else bad "Qdrant down: still '$s'"; fi
docker start "${PROJECT}-qdrant-1" >/dev/null
for _ in $(seq 1 60); do curl -sf -H "api-key: $QDRANT_API_KEY" "http://localhost:${QDRANT_PORT:-16333}/readyz" >/dev/null && break; sleep 1; done
before=$(docker logs "${PROJECT}-worker-ingest-1" 2>&1 | grep -c "manifest found, parse skipped")
curl -s -o /dev/null -H "$(auth)" -X POST "$GW/document-versions/$v/retry"
if s=$(wait_for "$v" ready 180); then
  after=$(docker logs "${PROJECT}-worker-ingest-1" 2>&1 | grep -c "manifest found, parse skipped")
  [ "$after" -gt "$before" ] && ok "retry after the outage: ready, the manifest was found and nothing was parsed again" || bad "retry parsed again (skipped $before -> $after)"
else bad "retry after the outage: '$s'"; fi

# ---- 4. the bucket event is lost -----------------------------------------------------------------------------
t4=$(new_tender lost)
docker stop "${PROJECT}-worker-ingest-1" >/dev/null
v=$(upload "$t4" small2.pdf)
nats_ stream purge DOCUMENTS -f >/dev/null 2>&1
docker start "${PROJECT}-worker-ingest-1" >/dev/null
echo "      event purged; the queued-job timer acts after ${QUEUED_WAIT}s"
if s=$(wait_for "$v" ready $((QUEUED_WAIT + 150))); then ok "lost event: the timer published the command and the document is ready"; else bad "lost event: '$s'"; fi

# ---- 5. duplicate delivery ---------------------------------------------------------------------------------------
t5=$(new_tender duplicate)
v=$(upload "$t5" small3.pdf); wait_for "$v" ready 120 >/dev/null
key=$(sql "select storage_key from document_versions where id='$v'")
event="{\"EventName\":\"s3:ObjectCreated:CompleteMultipartUpload\",\"Records\":[{\"data\":{\"s3\":{\"bucket\":{\"name\":\"${S3_BUCKET_DOCUMENTS:-tender-documents}\"},\"object\":{\"key\":\"$(echo "$key" | sed 's#/#%2F#g')\"}}}}]}"
p0=$(sql "select count(*) from passages where document_version_id='$v'"); j0=$(sql "select count(*) from jobs where target_id='$v'")
for _ in 1 2 3; do nats_ pub documents.uploaded "$event" >/dev/null 2>&1; done
sleep 15
p1=$(sql "select count(*) from passages where document_version_id='$v'"); j1=$(sql "select count(*) from jobs where target_id='$v'")
s=$(status_of "$v")
[ "$p0" = "$p1" ] && [ "$j0" = "$j1" ] && [ "$s" = "ready:" ] && ok "three duplicate events: $p1 passages, $j1 job, still ready" || bad "duplicates changed state: passages $p0->$p1 jobs $j0->$j1 status $s"

# ---- 6. an unreadable event ---------------------------------------------------------------------------------------
d0=$(nats_ stream info DLQ 2>&1 | sed -n 's/^ *Messages: *\([0-9][0-9]*\) *$/\1/p' | head -1)
nats_ pub documents.uploaded "this is not json" >/dev/null 2>&1
for _ in $(seq 1 20); do
  d1=$(nats_ stream info DLQ 2>&1 | sed -n 's/^ *Messages: *\([0-9][0-9]*\) *$/\1/p' | head -1)
  [ "${d1:-0}" -gt "${d0:-0}" ] && break; sleep 1
done
[ "${d1:-0}" -gt "${d0:-0}" ] && ok "unreadable event copied to the dead-letter stream ($d0 -> $d1)" || bad "no dead letter ($d0 -> $d1)"

}
[ "${DRILL_SET:-all}" = extraction ] || base_drills   # DRILL_SET=extraction runs only 7 to 10

# ---- extraction drills ------------------------------------------------------------------------------------------------
M1TOKEN_FILE=$(mktemp)
mauth() {
  local now; now=$(date +%s)
  if [ ! -s "$M1TOKEN_FILE" ] || [ $((now - $(stat -c %Y "$M1TOKEN_FILE"))) -gt 90 ]; then
    SIGNIN_USER=member01@example.test SIGNIN_PASSWORD="$DEV_SEED_USER_PASSWORD" python3 -I infra/scripts/signin_check.py --print-token | sed -n 's/^TOKEN=//p' > "$M1TOKEN_FILE"
  fi
  printf 'Authorization: Bearer %s' "$(cat "$M1TOKEN_FILE")"
}
ext_tender() { # name -> sets EXT_TENDER and EXT_VERSION: a tender opened to the committee with a ready RFP
  EXT_TENDER=$(new_tender "$1"); EXT_VERSION=$(upload "$EXT_TENDER" small4.pdf rfp)
  wait_for "$EXT_VERSION" ready 120 >/dev/null || echo "FAIL RFP for $1 not ready" >&2
  curl -s -o /dev/null -H "$(auth)" -X POST "$GW/tenders/$EXT_TENDER/open"
}
start_ext() { # tender version -> job id
  curl -s -H "$(mauth)" -H 'Content-Type: application/json' -H "Idempotency-Key: drill-$RANDOM$RANDOM$RANDOM" -X POST "$GW/tenders/$1/extractions" -d "{\"scope\":[{\"document_version_id\":\"$2\"}]}" | jq -r .id
}
job_state() { sql "select status || ':' || coalesce(failure_code,'') from jobs where id='$1'"; }
wait_job() { # job wanted-prefix seconds
  local end=$((SECONDS + $3)) s=""
  while [ $SECONDS -lt $end ]; do
    s=$(job_state "$1"); case "$s" in "$2"*) echo "$s"; return 0;; esac; sleep 2
  done; echo "$s"; return 1
}
req_count() { sql "select count(*) from requirements where tender_id='$1'"; }

# 7. killed mid-run ------------------------------------------------------------------------------------------------------
ext_tender control; tc=$EXT_TENDER; vc=$EXT_VERSION
jc=$(start_ext "$tc" "$vc")
if wait_job "$jc" completed 180 >/dev/null; then base=$(req_count "$tc"); else base=""; bad "undisturbed extraction: $(job_state "$jc")"; fi
ext_tender kill-agent; tk=$EXT_TENDER; vk=$EXT_VERSION
stub_control '{"delay_ms":20000}'
jk=$(start_ext "$tk" "$vk")
for _ in $(seq 1 60); do case "$(job_state "$jk")" in running:*) break;; esac; sleep 1; done
sleep 3
docker kill "${PROJECT}-worker-agent-1" >/dev/null
echo "      agent killed at job state '$(job_state "$jk")'"
stub_control '{"delay_ms":0}'
docker start "${PROJECT}-worker-agent-1" >/dev/null
if s=$(wait_job "$jk" completed 420); then
  n=$(req_count "$tk"); jn=$(sql "select count(*) from jobs where tender_id='$tk' and job_type='requirement_extraction'")
  [ -n "$base" ] && [ "$n" = "$base" ] && [ "$jn" = 1 ] && ok "agent killed mid-run: redelivery finished it ($n requirements, same as the undisturbed run, one job)" || bad "after the kill: $n requirements (expected $base), $jn jobs"
else bad "after the agent kill the job is '$s'"; fi

# 8. the model endpoint is down -----------------------------------------------------------------------------------
ext_tender outage; to=$EXT_TENDER; vo=$EXT_VERSION
LLM_OUTAGE_GIVE_UP_SECONDS=40 "${COMPOSE[@]}" up -d --no-deps --force-recreate worker-agent >/dev/null 2>&1
docker stop "${PROJECT}-llm-stub-1" >/dev/null
for _ in $(seq 1 60); do docker exec "${PROJECT}-worker-agent-1" true >/dev/null 2>&1 && break; sleep 1; done
jo=$(start_ext "$to" "$vo")
if s=$(wait_job "$jo" failed 300); then
  [ "$s" = "failed:llm_unavailable" ] && ok "endpoint down: breaker gave up, job failed:llm_unavailable" || bad "endpoint down: $s"
else bad "endpoint down: job is '$s'"; fi
rt=$(curl -s -H "$(mauth)" "$GW/jobs/$jo" | jq -r '[.failure.code, .can_retry] | join(" ")')
[ "$rt" = "llm_unavailable true" ] && ok "the failure is retryable ($rt)" || bad "retry not offered ($rt)"
docker start "${PROJECT}-llm-stub-1" >/dev/null
for _ in $(seq 1 60); do [ "$(docker inspect -f '{{.State.Health.Status}}' "${PROJECT}-llm-stub-1")" = healthy ] && break; sleep 1; done
restore_agent
jr=$(curl -s -H "$(mauth)" -H "Idempotency-Key: drill-retry-$RANDOM" -X POST "$GW/jobs/$jo/retry" | jq -r .id)
if [ -n "$jr" ] && [ "$jr" != null ] && s=$(wait_job "$jr" completed 240); then
  [ "$(sql "select retry_of_job_id from jobs where id='$jr'")" = "$jo" ] && ok "retry after the outage: a new job pointing back completed ($(req_count "$to") requirements)" || bad "retry does not point back to the failed job"
else bad "retry after the outage: job '$jr' is '$s'"; fi

# 9. a result part is delivered again ----------------------------------------------------------------------------
last=$(nats_ stream info ANALYSIS_EVENTS --json 2>/dev/null | jq -r .state.last_seq)
sent=0
for seq in $(seq "$last" -1 $((last > 400 ? last - 400 : 1))); do
  m=$(nats_ stream get ANALYSIS_EVENTS "$seq" --json 2>/dev/null) || continue
  [ "$(echo "$m" | jq -r .subject)" = documents.analysis.result.requirement_extraction ] || continue
  data=$(echo "$m" | jq -r '.data|@base64d')
  [ "$(echo "$data" | jq -r .job_id)" = "$jc" ] || continue
  nats_ pub documents.analysis.result.requirement_extraction "$data" -H "Tender-Schema:tender.analysis.result/1" -H "Nats-Msg-Id:drill-dup-$RANDOM$RANDOM" >/dev/null 2>&1 && sent=$((sent+1))
done
before=$(req_count "$tc"); sleep 15; after=$(req_count "$tc")
[ "$sent" -gt 0 ] && [ "$before" = "$after" ] && [ "$after" = "$base" ] && ok "result part delivered again ($sent message(s)): still $after requirements" || bad "duplicate result: sent=$sent requirements $before -> $after (expected $base)"

# 10. cancel during a run ----------------------------------------------------------------------------------------------
ext_tender cancel; tx=$EXT_TENDER; vx=$EXT_VERSION
stub_control '{"delay_ms":5000}'
jx=$(start_ext "$tx" "$vx")
for _ in $(seq 1 60); do case "$(job_state "$jx")" in running:*) break;; esac; sleep 1; done
curl -s -o /dev/null -H "$(mauth)" -H "Idempotency-Key: drill-cancel-$RANDOM" -X POST "$GW/jobs/$jx/cancel"
if s=$(wait_job "$jx" cancelled 120); then
  stub_control '{"delay_ms":0}'; sleep 20
  n=$(req_count "$tx"); s2=$(job_state "$jx")
  [ "$n" = 0 ] && [ "$s2" = "cancelled:" ] && ok "cancelled mid-run: stays cancelled, nothing stored" || bad "after cancel: $n requirements, job '$s2'"
else stub_control '{"delay_ms":0}'; bad "cancel: job is '$s'"; fi
rm -f "$M1TOKEN_FILE"

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
