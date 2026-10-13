#!/usr/bin/env bash
# Phase 3 smoke on the real stack with synthetic documents (AGENTS rule 15): upload an RFP, open
# the tender, extract requirements as a committee member, review, confirm the baseline as the
# Chair, reopen it and confirm version 2. The model is whatever LLM_BASE_URL says (the stub by
# default). Needs `make up`.
set -uo pipefail
cd "$(dirname "$0")/../.."
set -a; . ./.env; set +a
GW="http://localhost:${GATEWAY_PORT:-18080}/api/v1"
PROJECT="${COMPOSE_PROJECT_NAME:-tender-eval}"
PASS=0; FAIL=0
ok()  { echo "ok    $*"; PASS=$((PASS+1)); }
bad() { echo "FAIL  $*"; FAIL=$((FAIL+1)); }
sql() { docker exec -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" "${PROJECT}-postgres-1" psql -U "$POSTGRES_SUPERUSER" -d "$POSTGRES_DB" -Atc "$1" 2>/dev/null; }

token_for() { # email password
  local out
  out=$(SIGNIN_USER="$1" SIGNIN_PASSWORD="$2" python3 -I infra/scripts/signin_check.py --print-token) || return 1
  printf '%s\n' "$out" | sed -n 's/^TOKEN=//p'
}
ADMIN_TOKEN=$(python3 -I infra/scripts/signin_check.py --print-token | sed -n 's/^TOKEN=//p') || { echo "FAIL admin sign-in"; exit 1; }
M1=$(token_for member01@example.test "$DEV_SEED_USER_PASSWORD") || { echo "FAIL member01 sign-in"; exit 1; }
M2=$(token_for member02@example.test "$DEV_SEED_USER_PASSWORD") || { echo "FAIL member02 sign-in"; exit 1; }
api() { # token method path [json]
  local t=$1 m=$2 p=$3; shift 3
  if [ $# -gt 0 ]; then
    curl -s -X "$m" -H "Authorization: Bearer $t" -H 'Content-Type: application/json' -H "Idempotency-Key: smoke-$RANDOM$RANDOM$RANDOM" "$GW$p" -d "$1"
  else
    curl -s -X "$m" -H "Authorization: Bearer $t" -H "Idempotency-Key: smoke-$RANDOM$RANDOM$RANDOM" "$GW$p"
  fi
}
DIR=$(mktemp -d); trap 'rm -rf "$DIR"' EXIT
(cd worker && uv run python tests/fixtures/generate.py "$DIR" 12) >/dev/null || { echo "FAIL fixtures"; exit 1; }

m1=$(sql "select id from users where email='member01@example.test'")
m2=$(sql "select id from users where email='member02@example.test'")
tender=$(api "$ADMIN_TOKEN" POST /tenders "{\"name\":\"Extraction smoke $(date +%s)\",\"chair_id\":\"$m1\",\"member_ids\":[\"$m1\",\"$m2\"]}" | jq -r .id)
[ -n "$tender" ] && [ "$tender" != null ] && ok "tender $tender" || { bad "tender"; exit 1; }
cat=$(api "$ADMIN_TOKEN" GET /categories | jq -r '.[0].id // empty')
if [ -z "$cat" ]; then cat=$(api "$ADMIN_TOKEN" POST /categories '{"name":"Security"}' | jq -r .id); fi

v=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" -F doc_type=rfp -F title=RFP -F "file=@$DIR/rfp.pdf" "$GW/tenders/$tender/documents" | jq -r .current_version.id)
for _ in $(seq 1 90); do
  s=$(api "$ADMIN_TOKEN" GET "/document-versions/$v" | jq -r .status); [ "$s" = ready ] && break; sleep 1
done
[ "$s" = ready ] && ok "RFP ready" || { bad "RFP status $s"; exit 1; }

code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $ADMIN_TOKEN" "$GW/tenders/$tender/open")
[ "$code" = 200 ] && ok "tender opened to the committee" || { bad "open: $code"; exit 1; }

sec=$(api "$M1" GET "/document-versions/$v/outline" | jq -r '.sections[0].id // empty')
scope="{\"scope\":[{\"document_version_id\":\"$v\"}]}"
job=$(api "$M2" POST "/tenders/$tender/extractions" "$scope" | jq -r .id)
[ -n "$job" ] && [ "$job" != null ] && ok "extraction started by a member: job $job" || { bad "extraction not started"; exit 1; }
for _ in $(seq 1 180); do
  st=$(api "$M1" GET "/jobs/$job" | jq -r .status)
  case "$st" in completed|completed_with_warnings|failed|cancelled) break;; esac; sleep 2
done
case "$st" in completed|completed_with_warnings) ok "extraction $st";; *) bad "extraction ended $st: $(api "$M1" GET "/jobs/$job" | jq -c .failure)"; exit 1;; esac

total=$(api "$M1" GET "/tenders/$tender/requirements/summary" | jq -r .total)
[ "${total:-0}" -gt 0 ] && ok "$total requirements proposed" || { bad "no requirements"; exit 1; }
# every shown requirement has a verbatim quote in a passage it cites
ungrounded=$(sql "select count(*) from requirements r where r.tender_id='$tender' and r.origin='ai' and not exists (
   select 1 from requirement_sources s join passages p on p.id = s.passage_id
   where s.requirement_id = r.id and position(s.quote in p.text) > 0)")
[ "$ungrounded" = 0 ] && ok "every requirement cites a passage that contains its quote" || bad "$ungrounded requirements without a verbatim source"
cov=$(api "$M1" GET "/tenders/$tender/extractions/coverage" | jq -r '.items[0].status')
[ "$cov" = completed ] && ok "coverage shows the extracted document" || bad "coverage: $cov"

# review: mark all reviewed (bulk), assign categories, resolve flags
ids=$(api "$M1" GET "/tenders/$tender/requirements?limit=500" | jq -c '[.items[].id]')
res=$(api "$M1" POST "/tenders/$tender/requirements/bulk" "{\"ids\":$ids,\"action\":\"change_category\",\"category_id\":\"$cat\",\"note\":\"Categorised in the smoke test\"}")
[ "$(echo "$res" | jq -r .updated)" -gt 0 ] && ok "bulk category change" || bad "bulk change: $res"
res=$(api "$M1" POST "/tenders/$tender/requirements/bulk" "{\"ids\":$ids,\"action\":\"mark_reviewed\",\"note\":\"Reviewed in the smoke test\"}")
ok "bulk review: $(echo "$res" | jq -c '{updated, skipped: (.skipped|length)}')"
for f in $(sql "select f.requirement_id || ':' || f.id from requirement_flags f join requirements r on r.id=f.requirement_id where r.tender_id='$tender' and f.resolved_at is null"); do
  api "$M1" POST "/requirements/${f%%:*}/flags/${f##*:}/resolve" '{"note":"Checked in the smoke test"}' >/dev/null
done

pre=$(api "$M1" GET "/tenders/$tender/baselines/precheck")
[ "$(echo "$pre" | jq -r .ready)" = true ] && ok "pre-check ready" || bad "pre-check: $(echo "$pre" | jq -c .failures)"
b1=$(api "$M1" POST "/tenders/$tender/baselines" | jq -r '.version_no')
[ "$b1" = 1 ] && ok "baseline version 1 confirmed by the Chair" || bad "confirm: $b1"
code=$(curl -s -o /dev/null -w '%{http_code}' -X PATCH -H "Authorization: Bearer $M2" -H 'Content-Type: application/json' -H 'If-Match: "1"' "$GW/requirements/$(echo "$ids" | jq -r '.[0]')" -d '{"note":"Trying after the lock","text":"Changed"}')
[ "$code" = 409 ] && ok "a locked baseline refuses edits (409)" || bad "edit after lock: $code"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $M1" -H 'Content-Type: application/json' "$GW/tenders/$tender/baselines/current/reopen" -d '{"reason":"Scope changed after sponsor review"}')
[ "$code" = 200 ] && ok "reopened with a reason" || bad "reopen: $code"
b2=$(api "$M1" POST "/tenders/$tender/baselines" | jq -r '.version_no')
[ "$b2" = 2 ] && ok "baseline version 2 confirmed" || bad "second confirm: $b2"

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
