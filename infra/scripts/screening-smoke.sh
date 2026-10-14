#!/usr/bin/env bash
# Phase 4 smoke on the real stack with synthetic documents (AGENTS rule 15): an RFP that names the
# sections a proposal must contain, one proposal that lacks one, the member's declaration, the AI
# pre-check, the checklist and recommendation, and the Chair's decision. The model is whatever
# LLM_BASE_URL says (the stub by default). Needs `make up`.
set -uo pipefail
cd "$(dirname "$0")/../.."
set -a; . ./.env; set +a
GW="http://localhost:${GATEWAY_PORT:-18080}/api/v1"
PROJECT="${COMPOSE_PROJECT_NAME:-tender-eval}"
PASS=0; FAIL=0
ok()  { echo "ok    $*"; PASS=$((PASS+1)); }
bad() { echo "FAIL  $*"; FAIL=$((FAIL+1)); }
sql() { docker exec -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" "${PROJECT}-postgres-1" psql -U "$POSTGRES_SUPERUSER" -d "$POSTGRES_DB" -Atc "$1" 2>/dev/null; }
token_for() {
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
(cd worker && uv run python tests/fixtures/generate.py "$DIR" 6) >/dev/null || { echo "FAIL fixtures"; exit 1; }

m1=$(sql "select id from users where email='member01@example.test'")
m2=$(sql "select id from users where email='member02@example.test'")
tender=$(api "$ADMIN_TOKEN" POST /tenders "{\"name\":\"Screening smoke $(date +%s)\",\"chair_id\":\"$m1\",\"member_ids\":[\"$m1\",\"$m2\"]}" | jq -r .id)
[ -n "$tender" ] && [ "$tender" != null ] && ok "tender $tender" || { bad "tender"; exit 1; }

wait_version() { # version
  local s=""
  for _ in $(seq 1 90); do
    s=$(api "$ADMIN_TOKEN" GET "/document-versions/$1" | jq -r .status); [ "$s" = ready ] && return 0; sleep 1
  done; echo "status $s"; return 1
}
rfp=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" -F doc_type=rfp -F title=RFP -F "file=@$DIR/rfp-structured.pdf" "$GW/tenders/$tender/documents" | jq -r .current_version.id)
wait_version "$rfp" && ok "RFP ready" || { bad "RFP not ready"; exit 1; }
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $ADMIN_TOKEN" "$GW/tenders/$tender/open")
[ "$code" = 200 ] && ok "tender opened" || { bad "open: $code"; exit 1; }

tv=$(api "$ADMIN_TOKEN" POST "/tenders/$tender/vendors" "{\"new_vendor\":{\"name\":\"Smoke Vendor $RANDOM$RANDOM\",\"confirm_duplicate\":true}}" | jq -r .id)
[ -n "$tv" ] && [ "$tv" != null ] && ok "vendor $tv" || { bad "vendor"; exit 1; }
up=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" -H "Idempotency-Key: smoke-$RANDOM$RANDOM" -F file_roles=main -F "files=@$DIR/proposal-structured.pdf" "$GW/tender-vendors/$tv/proposal-versions")
pv=$(echo "$up" | jq -r .id)
[ -n "$pv" ] && [ "$pv" != null ] || { bad "proposal upload: $up"; exit 1; }
file=$(echo "$up" | jq -r '.files[0].document_version_id')
wait_version "$file" && ok "proposal ready" || { bad "proposal not ready"; exit 1; }

# the declaration comes first
code=$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H "Authorization: Bearer $M1" -H 'Content-Type: application/json' "$GW/tender-vendors/$tv/screening/recommendation" -d '{"recommendation":"accept"}')
[ "$code" = 403 ] && ok "screening needs a declaration first (403)" || bad "recommendation before declaring: $code"
d=$(api "$M1" POST "/tender-vendors/$tv/declaration" '{"has_conflict":false}' | jq -r .has_conflict)
[ "$d" = false ] && ok "member declared no conflict" || bad "declaration: $d"
api "$M2" POST "/tender-vendors/$tv/declaration" '{"has_conflict":true,"statement":"I advised this vendor in 2025"}' >/dev/null
[ "$(sql "select count(*) from conflict_declarations where tender_vendor_id='$tv' and has_conflict")" = 1 ] && ok "a conflict was recorded for the other member" || bad "conflict not recorded"

# AI pre-check
job=$(api "$M1" POST "/tender-vendors/$tv/screening/precheck" | jq -r .id)
[ -n "$job" ] && [ "$job" != null ] && ok "pre-check started: job $job" || { bad "pre-check not started"; exit 1; }
for _ in $(seq 1 90); do
  st=$(api "$M1" GET "/jobs/$job" | jq -r .status)
  case "$st" in completed|completed_with_warnings|failed|cancelled) break;; esac; sleep 1
done
case "$st" in completed|completed_with_warnings) ok "pre-check $st";; *) bad "pre-check ended $st: $(api "$M1" GET "/jobs/$job" | jq -c .failure)"; exit 1;; esac
state=$(api "$M1" GET "/tender-vendors/$tv/screening")
n=$(echo "$state" | jq '.prechecks | length')
missing=$(echo "$state" | jq -r '[.prechecks[] | select(.found == false) | .expected_section] | join(",")')
[ "$n" = 4 ] && ok "4 expected sections found in the RFP" || bad "findings: $n"
[ "$missing" = "Implementation plan" ] && ok "the missing section is named: $missing" || bad "missing: $missing"

# checklist, recommendation, decision
item=$(echo "$state" | jq -r '.checklist[0].item.id')
c=$(api "$M1" PUT "/tender-vendors/$tv/screening/checks" "{\"checks\":[{\"checklist_item_id\":\"$item\",\"is_checked\":true,\"note\":\"All forms present\"}]}" | jq -r '.proposal_version.status')
[ "$c" = in_screening ] && ok "a check moved the proposal into screening" || bad "checks: $c"
api "$M1" PUT "/tender-vendors/$tv/screening/recommendation" '{"recommendation":"accept"}' >/dev/null
rec=$(api "$M1" GET "/tender-vendors/$tv/screening" | jq -r '.my_recommendation.recommendation')
[ "$rec" = accept ] && ok "recommendation saved" || bad "recommendation: $rec"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $M2" -H 'Content-Type: application/json' -H "Idempotency-Key: smoke-$RANDOM" "$GW/tender-vendors/$tv/screening/decision" -d '{"decision":"accept"}')
[ "$code" = 403 ] && ok "a member cannot decide (403)" || bad "member decision: $code"
dec=$(api "$M1" POST "/tender-vendors/$tv/screening/decision" '{"decision":"accept"}' | jq -r '.proposal_version.status')
[ "$dec" = accepted ] && ok "the Chair accepted the vendor" || bad "decision: $dec"

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
