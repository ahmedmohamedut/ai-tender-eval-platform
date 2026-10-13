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
DIR=$(mktemp -d); trap 'rm -rf "$DIR" "$TOKEN_FILE"; docker start "${PROJECT}-qdrant-1" "${PROJECT}-worker-ingest-1" >/dev/null 2>&1' EXIT
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
upload() { # tender file -> version id
  curl -s -H "$(auth)" -F "doc_type=other" -F "title=$2" -F "file=@$DIR/$2" "$GW/tenders/$1/documents" | jq -r .current_version.id
}
status_of() { curl -s -H "$(auth)" "$GW/document-versions/$1" | jq -r '[.status, (.failure_reason // "")] | join(":")'; }
wait_for() { # version wanted-prefix seconds
  local end=$((SECONDS + $3)) s=""
  while [ $SECONDS -lt $end ]; do
    s=$(status_of "$1"); case "$s" in "$2"*) echo "$s"; return 0;; esac; sleep 2
  done; echo "$s"; return 1
}

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

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
