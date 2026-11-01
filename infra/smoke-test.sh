#!/usr/bin/env bash
# Step 0b test: every infrastructure component is up and correctly configured.
set -uo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: do not rewrite /opt/... paths passed to containers
cd "$(dirname "$0")"
set -a; source .env; set +a

DC="docker compose -f docker-compose.yml --env-file .env"
fail=0
check() { # name, command...
  local name="$1"; shift
  if out=$("$@" 2>&1); then echo "PASS  $name"; else echo "FAIL  $name"; echo "      $out" | head -5; fail=1; fi
}

# 1. All long-running containers healthy
for s in postgres kafka redis keycloak otel-lgtm; do
  check "container $s healthy" bash -c "[ \"\$($DC ps --format '{{.Health}}' $s)\" = healthy ]"
done

# 2. Postgres: 4 databases, one owner each, extensions in commerce_db
check "postgres databases" bash -c "$DC exec -T postgres psql -U postgres -tAc \"select string_agg(datname||':'||pg_get_userbyid(datdba), ',' order by datname) from pg_database where datname like '%_db'\" | grep -qx 'agent_db:agent,commerce_db:commerce,keycloak_db:keycloak,payment_db:payment'"
check "commerce_db extensions (pg_trgm, vector)" bash -c "$DC exec -T postgres psql -U postgres -d commerce_db -tAc \"select string_agg(extname, ',' order by extname) from pg_extension where extname in ('pg_trgm','vector')\" | grep -qx 'pg_trgm,vector'"
check "service role cannot access another DB" bash -c "! $DC exec -T -e PGPASSWORD=\"$COMMERCE_DB_PASSWORD\" postgres psql -h localhost -U commerce -d payment_db -tAc 'select 1' >/dev/null 2>&1"

# 3. Kafka topics
check "kafka topics" bash -c "$DC exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list | sort | tr '\n' ' ' | grep -q 'order.events order.events.DLT payment.events payment.events.DLT'"

# 4. Redis requires the password
check "redis auth required" bash -c "$DC exec -T redis redis-cli ping | grep -q NOAUTH"
check "redis ping with password" bash -c "$DC exec -T redis redis-cli -a \"$REDIS_PASSWORD\" --no-auth-warning ping | grep -q PONG"

# 5. Keycloak: user token (dev cli) with issuer + audience; service token with 'internal' scope
user_token=$(curl -s -X POST "$KEYCLOAK_TOKEN_URI" \
  -d grant_type=password -d client_id=marketchat-dev-cli \
  --data-urlencode "username=demo@marketchat.local" --data-urlencode "password=$DEMO_USER_PASSWORD" | python -c "import sys,json;print(json.load(sys.stdin).get('access_token',''))")
claims() { python -c "import sys,json,base64;p=sys.argv[1].split('.')[1];p+='='*(-len(p)%4);print(json.dumps(json.loads(base64.urlsafe_b64decode(p))))" "$1"; }
check "keycloak user token issued" test -n "$user_token"
check "user token issuer + audience" bash -c "claims=\$(python -c \"import sys,json,base64;p=sys.argv[1].split('.')[1];p+='='*(-len(p)%4);c=json.loads(base64.urlsafe_b64decode(p));print(c['iss'], c['aud'] if isinstance(c['aud'],str) else ' '.join(c['aud']))\" '$user_token'); echo \$claims | grep -q '$KEYCLOAK_ISSUER_URI' && echo \$claims | grep -q marketchat-api"
svc_token=$(curl -s -X POST "$KEYCLOAK_TOKEN_URI" \
  -d grant_type=client_credentials -d client_id=commerce-service --data-urlencode "client_secret=$COMMERCE_CLIENT_SECRET" | python -c "import sys,json;print(json.load(sys.stdin).get('access_token',''))")
check "commerce-service token has scope 'internal'" bash -c "python -c \"import sys,json,base64;p=sys.argv[1].split('.')[1];p+='='*(-len(p)%4);c=json.loads(base64.urlsafe_b64decode(p));sys.exit(0 if 'internal' in c.get('scope','').split() else 1)\" '$svc_token'"
check "wrong client secret rejected" bash -c "curl -s -o /dev/null -w '%{http_code}' -X POST $KEYCLOAK_TOKEN_URI -d grant_type=client_credentials -d client_id=commerce-service -d client_secret=wrong | grep -q 401"

# 6. Grafana LGTM
check "grafana health" bash -c "curl -sf $GRAFANA_URL/api/health | grep -q '\"database\": *\"ok\"'"

echo
if [ $fail -eq 0 ]; then echo "ALL INFRA CHECKS PASSED"; else echo "SOME CHECKS FAILED"; exit 1; fi
