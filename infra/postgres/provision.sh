#!/bin/sh
# Idempotent: creates the three application roles, the Keycloak role and the two
# databases, and sets the privileges. Safe to run on every `make up`.
# The application role never owns a table (implementation guide 5.3, PI 7.5).
set -eu

psql -v ON_ERROR_STOP=1 -d postgres \
  -v owner_pw="$TENDER_OWNER_PASSWORD" \
  -v app_pw="$TENDER_APP_PASSWORD" \
  -v ingest_pw="$TENDER_AI_INGEST_PASSWORD" \
  -v kc_pw="$KEYCLOAK_DB_PASSWORD" \
  -v appdb="$POSTGRES_DB" <<'SQL'
SELECT 'CREATE ROLE tender_owner LOGIN' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tender_owner') \gexec
SELECT 'CREATE ROLE tender_app LOGIN' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tender_app') \gexec
SELECT 'CREATE ROLE tender_ai_ingest LOGIN' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tender_ai_ingest') \gexec
SELECT 'CREATE ROLE keycloak LOGIN' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'keycloak') \gexec

ALTER ROLE tender_owner     LOGIN PASSWORD :'owner_pw'  NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB;
ALTER ROLE tender_app       LOGIN PASSWORD :'app_pw'    NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB;
ALTER ROLE tender_ai_ingest LOGIN PASSWORD :'ingest_pw' NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB;
ALTER ROLE keycloak         LOGIN PASSWORD :'kc_pw'     NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB;

SELECT format('CREATE DATABASE %I OWNER tender_owner', :'appdb')
  WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'appdb') \gexec
SELECT 'CREATE DATABASE keycloak OWNER keycloak'
  WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'keycloak') \gexec
SQL

psql -v ON_ERROR_STOP=1 -d "$POSTGRES_DB" <<'SQL'
REVOKE ALL ON DATABASE :"DBNAME" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"DBNAME" TO tender_owner, tender_app, tender_ai_ingest;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO tender_owner;
GRANT USAGE ON SCHEMA public TO tender_app, tender_ai_ingest;
-- Nothing is deleted through the application: no DELETE by default. Later
-- migrations grant per table what each role needs.
ALTER DEFAULT PRIVILEGES FOR ROLE tender_owner IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE ON TABLES TO tender_app;
ALTER DEFAULT PRIVILEGES FOR ROLE tender_owner IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO tender_app;
SQL
echo "postgres provisioning done"
