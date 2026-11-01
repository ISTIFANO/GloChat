#!/bin/bash
# Creates one database + one owner role per service (least privilege), plus Keycloak's DB.
# Passwords come from the container environment (infra/.env), never from git.
set -euo pipefail

create_db() {
  local db="$1" user="$2" password="$3"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-SQL
    CREATE ROLE ${user} LOGIN PASSWORD '${password}';
    CREATE DATABASE ${db} OWNER ${user};
    REVOKE ALL ON DATABASE ${db} FROM PUBLIC;
SQL
}

create_db commerce_db commerce "$COMMERCE_DB_PASSWORD"
create_db payment_db  payment  "$PAYMENT_DB_PASSWORD"
create_db agent_db    agent    "$AGENT_DB_PASSWORD"
create_db keycloak_db keycloak "$KEYCLOAK_DB_PASSWORD"

# Extensions need superuser: create them here so the service roles don't need it.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname commerce_db <<-SQL
  CREATE EXTENSION IF NOT EXISTS pg_trgm;
  CREATE EXTENSION IF NOT EXISTS vector;
SQL
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname agent_db <<-SQL
  CREATE EXTENSION IF NOT EXISTS vector;
SQL
