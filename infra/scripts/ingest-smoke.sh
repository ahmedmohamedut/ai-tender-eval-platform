#!/usr/bin/env bash
# Phase 2 smoke: real upload through the gateway, real worker, real RustFS, Qdrant not required.
#   upload RFP (PDF) and a DOCX -> both reach ready with page, section and passage rows;
#   protected, text-less and corrupt files end failed with their own reason.
# Uses only synthetic documents (AGENTS rule 15). Needs `make up`.
set -uo pipefail
cd "$(dirname "$0")/../.."
set -a; . ./.env; set +a
GW="http://localhost:${GATEWAY_PORT:-18080}/api/v1"
PROJECT="${COMPOSE_PROJECT_NAME:-tender-eval}"
PASS=0; FAIL=0
ok()  { echo "ok    $*"; PASS=$((PASS+1)); }
bad() { echo "FAIL  $*"; FAIL=$((FAIL+1)); }
sql() { docker exec -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" "${PROJECT}-postgres-1" psql -U "$POSTGRES_SUPERUSER" -d "$POSTGRES_DB" -Atc "$1" 2>/dev/null; }

out=$(python3 -I infra/scripts/signin_check.py --print-token) || { echo "$out"; echo "FAIL sign-in"; exit 1; }
TOKEN=$(printf '%s\n' "$out" | sed -n 's/^TOKEN=//p')
AUTH="Authorization: Bearer $TOKEN"
DIR=$(mktemp -d); trap 'rm -rf "$DIR"' EXIT
(cd worker && uv run python tests/fixtures/generate.py "$DIR" "${INGEST_SMOKE_PAGES:-30}") >/dev/null || { echo "FAIL fixtures"; exit 1; }

chair=$(sql "select id from users where email='member01@example.test'")
[ -n "$chair" ] || { echo "FAIL no development user member01 (make seed-dev-users)"; exit 1; }
tender=$(curl -s -H "$AUTH" -H 'Content-Type: application/json' -X POST "$GW/tenders" \
  -d "{\"name\":\"Ingest smoke $(date +%s)\",\"chair_id\":\"$chair\",\"member_ids\":[\"$chair\"]}" | jq -r .id)
[ -n "$tender" ] && [ "$tender" != null ] && ok "tender $tender" || { bad "tender not created"; exit 1; }

# the push stream of this tender, read through the gateway while the documents are processed
EVENTS="$DIR/events.txt"
curl -sN -H "$AUTH" "$GW/events?tender_id=$tender" > "$EVENTS" 2>/dev/null &
EVENTS_PID=$!
trap 'kill $EVENTS_PID 2>/dev/null; rm -rf "$DIR"' EXIT

# upload <type> <file> [title] -> prints "<document_id> <version_id>"
upload() {
  curl -s -H "$AUTH" -F "doc_type=$1" -F "title=${3:-$2}" -F "file=@$DIR/$2" "$GW/tenders/$tender/documents" \
    | jq -r '[.id, .current_version.id] | @tsv'
}
# wait_status <version_id> <status> [seconds]
wait_status() {
  local end=$((SECONDS + ${3:-120})) s=""
  while [ $SECONDS -lt $end ]; do
    s=$(curl -s -H "$AUTH" "$GW/document-versions/$1" | jq -r .status)
    [ "$s" = "$2" ] && return 0
    case "$s" in ready|failed) [ "$s" != "$2" ] && { echo "$s"; return 1; } ;; esac
    sleep 1
  done
  echo "${s:-timeout}"; return 1
}

read -r doc_rfp v_rfp < <(upload rfp rfp.pdf "Cloud RFP")
if st=$(wait_status "$v_rfp" ready 180); then ok "RFP reached ready"; else bad "RFP status: $st"; fi
pages=$(sql "select page_count from document_versions where id='$v_rfp'")
pass=$(sql "select count(*) from passages where document_version_id='$v_rfp'")
secs=$(sql "select count(*) from document_sections where document_version_id='$v_rfp'")
[ "${pages:-0}" = "${INGEST_SMOKE_PAGES:-30}" ] && ok "RFP has $pages pages, $secs sections, $pass passages" || bad "RFP rows: pages=$pages sections=$secs passages=$pass"
box=$(sql "select count(*) from passages where document_version_id='$v_rfp' and jsonb_array_length(bbox) >= 1 and page_no >= 1 and char_end >= char_start")
[ "$box" = "$pass" ] && ok "every passage has page, boxes and offsets" || bad "passages without boxes: $((pass-box))"
qd=$(curl -s -H "api-key: $QDRANT_API_KEY" -H 'Content-Type: application/json' \
  "http://localhost:${QDRANT_PORT:-16333}/collections/passages/points/count" \
  -d "{\"exact\":true,\"filter\":{\"must\":[{\"key\":\"document_version_id\",\"match\":{\"value\":\"$v_rfp\"}}]}}" | jq -r .result.count)
[ "$qd" = "$pass" ] && ok "Qdrant holds $qd points for the RFP (= passages)" || bad "Qdrant points: $qd, passages: $pass"
jobst=$(sql "select status from jobs where target_id='$v_rfp' and job_type='document_processing' order by queued_at desc limit 1")
case "$jobst" in completed|completed_with_warnings) ok "job $jobst";; *) bad "job status '$jobst'";; esac

read -r _ v_docx < <(upload other sow.docx "Statement of work")
if st=$(wait_status "$v_docx" ready 240); then ok "DOCX reached ready (LibreOffice)"; else bad "DOCX status: $st"; fi
dp=$(sql "select count(*) from passages where document_version_id='$v_docx'")
[ "${dp:-0}" -gt 5 ] && ok "DOCX has $dp passages" || bad "DOCX passages: $dp"

expect_failed() {
  local file=$1 reason=$2 v
  read -r _ v < <(upload other "$file")
  wait_status "$v" failed 90 >/dev/null
  got=$(curl -s -H "$AUTH" "$GW/document-versions/$v" | jq -r '[.status, .failure_reason] | join(":")')
  [ "$got" = "failed:$reason" ] && ok "$file -> failed:$reason" || bad "$file -> $got (wanted failed:$reason)"
}
expect_failed protected.pdf password_protected
expect_failed textless.pdf no_selectable_text
expect_failed corrupt.pdf unsupported_format

sleep 2; kill $EVENTS_PID 2>/dev/null
grep -q '^event: \?job.updated' "$EVENTS" && ok "push stream delivered job.updated" || bad "no job.updated on the stream"
grep -q '^event: \?document.updated' "$EVENTS" && ok "push stream delivered document.updated" || bad "no document.updated on the stream"
grep -qE '^id: ?[0-9]+$' "$EVENTS" && ok "events carry their stream sequence as id" || bad "no numeric event id"

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
