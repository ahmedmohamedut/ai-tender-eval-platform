#!/usr/bin/env bash
# Development only. Creates three users so the platform can be tried without the invite flow:
#   member01@example.test, member02@example.test (committee members), observer01@example.test.
# Each is created in Keycloak (email as username, as PI 3.1 requires: the realm turns any
# username into the email, so the sign-in name is member01@example.test; password
# DEV_SEED_USER_PASSWORD, email verified, realm role mapped) and as an active platform user.
# Safe to run again: existing users are left as they are, and the password is set again.
# Needs the backend to have migrated the database (`make up`).
set -euo pipefail
cd "$(dirname "$0")/../.."
set -a; . ./.env; set +a

PROJECT=tender-eval
KC="docker exec ${PROJECT}-keycloak-1 /opt/keycloak/bin/kcadm.sh"
REALM=tender-eval
PASSWORD=${DEV_SEED_USER_PASSWORD:?DEV_SEED_USER_PASSWORD is not set in .env}

$KC config credentials --server http://localhost:8080 --realm master \
    --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null

psql_() { docker exec -e PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD" ${PROJECT}-postgres-1 \
          psql -U "$POSTGRES_SUPERUSER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -At "$@"; }

seed() { # short_name display_name platform_role realm_role
  local email="$1@example.test" name=$2 role=$3 realm_role=$4 first last id
  local user=$email
  first=${name% *}; last=${name##* }

  id=$($KC get users -r $REALM -q "username=$user" -q exact=true --fields id 2>/dev/null \
       | sed -n 's/.*"id" *: *"\([^"]*\)".*/\1/p' | head -1)
  if [ -z "$id" ]; then
    $KC create users -r $REALM -s username="$user" -s email="$email" -s firstName="$first" \
        -s lastName="$last" -s enabled=true -s emailVerified=true >/dev/null
    id=$($KC get users -r $REALM -q "username=$user" -q exact=true --fields id \
         | sed -n 's/.*"id" *: *"\([^"]*\)".*/\1/p' | head -1)
    created="created"
  else
    created="already in Keycloak"
  fi
  # Role and password are ensured every time, so a half-finished earlier run is repaired.
  $KC add-roles -r $REALM --uusername "$user" --rolename "$realm_role"
  if ! out=$($KC set-password -r $REALM --username "$user" --new-password "$PASSWORD" 2>&1); then
    # The realm refuses a password equal to one of the last five: it is already set.
    echo "$out" | grep -q "last 5 passwords" || { echo "$out" >&2; exit 1; }
  fi

  psql_ -c "INSERT INTO users (email, display_name, role, status, sso_subject)
            SELECT '$email', '$name', '$role', 'active', '$id'
            WHERE NOT EXISTS (SELECT 1 FROM users WHERE sso_subject = '$id' OR lower(email) = '$email')" >/dev/null
  echo "seeded $user ($role): $created"
}

# `seed-dev-users.sh --one <short_name> "<Display Name>" <admin|member|observer>` seeds one more
# user, for example the administrator the browser tests sign in as.
if [ "${1:-}" = "--one" ]; then
  case "$4" in
    admin) realm_role=procurement_admin ;;
    member) realm_role=committee_member ;;
    observer) realm_role=observer ;;
    *) echo "role must be admin, member or observer" >&2; exit 2 ;;
  esac
  seed "$2" "$3" "$4" "$realm_role"
  exit 0
fi

seed member01   "Member 01"   member   committee_member
seed member02   "Member 02"   member   committee_member
seed observer01 "Observer 01" observer observer
