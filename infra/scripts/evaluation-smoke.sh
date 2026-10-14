#!/usr/bin/env bash
# Phase 4 smoke on the real stack with synthetic documents (AGENTS rule 15): requirements are
# extracted and confirmed, a proposal is screened and accepted before the baseline exists (the vendor
# waits), then the baseline is confirmed and the evaluation runs: grades with citations, the members'
# evaluations and rows. The model is whatever LLM_BASE_URL says (the stub by default). Needs `make up`.
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
wait_version() { local s=""; for _ in $(seq 1 90); do s=$(api "$ADMIN_TOKEN" GET "/document-versions/$1" | jq -r .status); [ "$s" = ready ] && return 0; sleep 1; done; echo "status $s"; return 1; }
wait_job() { # token job seconds -> final status
  local st=""
  for _ in $(seq 1 "$3"); do
    st=$(api "$1" GET "/jobs/$2" | jq -r .status)
    case "$st" in completed|completed_with_warnings|failed|cancelled) echo "$st"; return 0;; esac; sleep 1
  done; echo "$st"; return 1
}

m1=$(sql "select id from users where email='member01@example.test'")
m2=$(sql "select id from users where email='member02@example.test'")
tender=$(api "$ADMIN_TOKEN" POST /tenders "{\"name\":\"Evaluation smoke $(date +%s)\",\"chair_id\":\"$m1\",\"member_ids\":[\"$m1\",\"$m2\"]}" | jq -r .id)
[ -n "$tender" ] && [ "$tender" != null ] && ok "tender $tender" || { bad "tender"; exit 1; }
rfp=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" -F doc_type=rfp -F title=RFP -F "file=@$DIR/rfp-structured.pdf" "$GW/tenders/$tender/documents" | jq -r .current_version.id)
wait_version "$rfp" && ok "RFP ready" || { bad "RFP not ready"; exit 1; }
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $ADMIN_TOKEN" "$GW/tenders/$tender/open")" = 200 ] && ok "tender opened" || { bad "open"; exit 1; }

# requirements: extract, categorise, review
job=$(api "$M2" POST "/tenders/$tender/extractions" "{\"scope\":[{\"document_version_id\":\"$rfp\"}]}" | jq -r .id)
st=$(wait_job "$M1" "$job" 120)
case "$st" in completed|completed_with_warnings) ok "extraction $st";; *) bad "extraction $st"; exit 1;; esac
n=$(api "$M1" GET "/tenders/$tender/requirements/summary" | jq -r .total)
[ "${n:-0}" -gt 0 ] && ok "$n requirements proposed" || { bad "no requirements"; exit 1; }
cat=$(api "$ADMIN_TOKEN" GET /categories | jq -r '.[0].id // empty')
[ -n "$cat" ] || cat=$(api "$ADMIN_TOKEN" POST /categories '{"name":"Security"}' | jq -r .id)
ids=$(api "$M1" GET "/tenders/$tender/requirements?limit=500" | jq -c '[.items[].id]')
api "$M1" POST "/tenders/$tender/requirements/bulk" "{\"ids\":$ids,\"action\":\"change_category\",\"category_id\":\"$cat\",\"note\":\"Categorised in the smoke test\"}" >/dev/null
api "$M1" POST "/tenders/$tender/requirements/bulk" "{\"ids\":$ids,\"action\":\"mark_reviewed\",\"note\":\"Reviewed in the smoke test\"}" >/dev/null
for f in $(sql "select f.requirement_id || ':' || f.id from requirement_flags f join requirements r on r.id=f.requirement_id where r.tender_id='$tender' and f.resolved_at is null"); do
  api "$M1" POST "/requirements/${f%%:*}/flags/${f##*:}/resolve" '{"note":"Checked in the smoke test"}' >/dev/null
done

# vendor and proposal; screening and the Chair's acceptance before the baseline exists
tv=$(api "$ADMIN_TOKEN" POST "/tenders/$tender/vendors" "{\"new_vendor\":{\"name\":\"Smoke Vendor $RANDOM$RANDOM\",\"confirm_duplicate\":true}}" | jq -r .id)
up=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" -H "Idempotency-Key: smoke-$RANDOM$RANDOM" -F file_roles=main -F "files=@$DIR/proposal-structured.pdf" "$GW/tender-vendors/$tv/proposal-versions")
pv=$(echo "$up" | jq -r .id)
file=$(echo "$up" | jq -r '.files[0].document_version_id')
wait_version "$file" && ok "proposal ready" || { bad "proposal not ready: $up"; exit 1; }
api "$M1" POST "/tender-vendors/$tv/declaration" '{"has_conflict":false}' >/dev/null
api "$M2" POST "/tender-vendors/$tv/declaration" '{"has_conflict":false}' >/dev/null
api "$M1" PUT "/tender-vendors/$tv/screening/recommendation" '{"recommendation":"accept"}' >/dev/null
dec=$(api "$M1" POST "/tender-vendors/$tv/screening/decision" '{"decision":"accept"}' | jq -r '.proposal_version.status')
[ "$dec" = accepted ] && ok "the Chair accepted the vendor" || bad "decision: $dec"
[ "$(sql "select count(*) from jobs where job_type='evaluation' and target_id='$pv'")" = 0 ] && ok "no evaluation yet: the baseline is not confirmed" || bad "evaluation started early"

# confirming the baseline starts the evaluation by itself
b=$(api "$M1" POST "/tenders/$tender/baselines" | jq -r '.version_no')
[ "$b" = 1 ] && ok "baseline version 1 confirmed" || { bad "confirm: $b"; exit 1; }
ej=$(sql "select id from jobs where job_type='evaluation' and target_id='$pv' order by queued_at desc limit 1")
[ -n "$ej" ] && ok "evaluation job started by the system: $ej" || { bad "no evaluation job"; exit 1; }
[ "$(sql "select started_by is null from jobs where id='$ej'")" = t ] && ok "no starter on the job" || bad "job has a starter"
st=$(wait_job "$M1" "$ej" 180)
case "$st" in completed|completed_with_warnings) ok "evaluation $st";; *) bad "evaluation $st: $(api "$M1" GET "/jobs/$ej" | jq -c .failure)"; exit 1;; esac

reqs=$(sql "select count(*) from baseline_requirements br join baselines b on b.id=br.baseline_id where b.tender_id='$tender' and b.status='confirmed'")
grades=$(sql "select count(*) from ai_grades where proposal_version_id='$pv' and is_current")
[ "$grades" = "$reqs" ] && ok "one current grade for each of the $reqs requirements" || bad "grades $grades of $reqs"
nocite=$(sql "select count(*) from ai_grades g where g.proposal_version_id='$pv' and g.outcome='graded' and g.non_compliance_reason is distinct from 'not_addressed' and not exists (select 1 from grade_citations c where c.ai_grade_id=g.id)")
[ "$nocite" = 0 ] && ok "every graded row that claims evidence has a citation" || bad "$nocite rows without a citation"
verb=$(sql "select count(*) from grade_citations c join ai_grades g on g.id=c.ai_grade_id join passages p on p.id=c.passage_id where g.proposal_version_id='$pv' and position(c.excerpt in p.text) > 0")
all=$(sql "select count(*) from grade_citations c join ai_grades g on g.id=c.ai_grade_id where g.proposal_version_id='$pv'")
[ "$all" -gt 0 ] && [ "$verb" = "$all" ] && ok "all $all citation excerpts are verbatim in their passages" || bad "citations: $verb of $all verbatim"
[ "$(sql "select count(*) from ai_grades where proposal_version_id='$pv' and grade='fully_compliant'")" -ge 1 ] && ok "the proposal's encryption statement was graded fully compliant" || bad "no fully compliant grade"
[ "$(sql "select status from proposal_versions where id='$pv'")" = evaluated ] && ok "proposal evaluated" || bad "proposal status"
[ "$(sql "select count(*) from member_evaluations e join tender_vendors v on v.id=e.tender_vendor_id where v.id='$tv'")" = 2 ] && ok "an evaluation for each of the two committee members" || bad "member evaluations"
rows=$(sql "select count(*) from member_evaluation_rows r join member_evaluations e on e.id=r.member_evaluation_id where e.tender_vendor_id='$tv'")
[ "$rows" = "$((grades * 2))" ] && ok "$rows member rows (2 per requirement)" || bad "rows $rows"

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
