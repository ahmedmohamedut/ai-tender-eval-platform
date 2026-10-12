#!/usr/bin/env bash
# Returns 0 only when every long-running service is healthy and every
# provision-* one-shot has exited with 0. Exits 1 with the offender named.
set -euo pipefail
cd "$(dirname "$0")/../.."
COMPOSE="docker compose --env-file .env -f infra/compose/compose.yaml --profile apps"
deadline=$(( $(date +%s) + ${WAIT_TIMEOUT:-300} ))
while :; do
  pending=(); failed=()
  while read -r svc state health exit_code; do
    [ -n "$svc" ] || continue
    case "$svc" in
      provision-*)
        if [ "$state" = exited ]; then [ "$exit_code" = 0 ] || failed+=("$svc(exit $exit_code)"); else pending+=("$svc"); fi ;;
      *)
        if [ "$state" != running ]; then failed+=("$svc($state)");
        elif [ "$health" != healthy ]; then pending+=("$svc($health)"); fi ;;
    esac
  done < <($COMPOSE ps -a --format json | jq -r '[.Service,.State,(.Health|if .=="" then "-" else . end),.ExitCode]|join(" ")')
  if [ ${#failed[@]} -gt 0 ]; then echo "unhealthy: ${failed[*]}" >&2; exit 1; fi
  if [ ${#pending[@]} -eq 0 ]; then echo "all services healthy"; exit 0; fi
  if [ "$(date +%s)" -ge "$deadline" ]; then echo "timeout waiting for: ${pending[*]}" >&2; exit 1; fi
  sleep 3
done
