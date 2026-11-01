#!/usr/bin/env bash
# Step 0c end-to-end test: real Keycloak tokens through the real gateway to the real commerce-service.
# Prerequisites: infra stack up (docker compose), services built (./mvnw package -DskipTests).
set -uo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."
set -a; source infra/.env; set +a

KC="$KEYCLOAK_TOKEN_URI"
GW="$GATEWAY_URL"
COMMERCE="$COMMERCE_URL"
pids=()
cleanup() { for p in "${pids[@]}"; do kill "$p" 2>/dev/null; done; }
trap cleanup EXIT

start() { # module port
  java -jar "services/$1/target/$1-0.1.0-SNAPSHOT.jar" > "infra/.$1.log" 2>&1 &
  pids+=($!)
  for i in $(seq 1 60); do curl -sf "http://localhost:$2/actuator/health/readiness" >/dev/null && return 0; sleep 1; done
  echo "$1 did not start"; tail -20 "infra/.$1.log"; exit 1
}
start commerce-service 8081
start api-gateway 8080

fail=0
expect() { # name expected_code actual_code [body-check-regex body]
  if [ "$2" = "$3" ] && { [ -z "${4:-}" ] || echo "$5" | grep -qE "$4"; }; then echo "PASS  $1 ($3)"; else echo "FAIL  $1 expected $2 got $3 ${5:-}"; fail=1; fi
}
call() { curl -s -o infra/.body.tmp -w '%{http_code}' "$@"; }

user_token=$(curl -s -X POST $KC -d grant_type=password -d client_id=marketchat-dev-cli \
  --data-urlencode username=demo@marketchat.local --data-urlencode "password=$DEMO_USER_PASSWORD" | python -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
svc_token=$(curl -s -X POST $KC -d grant_type=client_credentials -d client_id=payment-service \
  --data-urlencode "client_secret=$PAYMENT_CLIENT_SECRET" | python -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
user_sub=$(python -c "import sys,json,base64;p=sys.argv[1].split('.')[1];p+='='*(-len(p)%4);print(json.loads(base64.urlsafe_b64decode(p))['sub'])" "$user_token")

# Through the gateway
code=$(call $GW/api/catalog/ping);                                         expect "gateway: public catalog without token" 200 "$code"
code=$(call $GW/api/me/whoami);                                            expect "gateway: whoami without token" 401 "$code"
code=$(call -H "Authorization: Bearer $user_token" $GW/api/me/whoami);     expect "gateway: whoami with user token -> identity from token" 200 "$code" "\"customerId\":\"$user_sub\"" "$(cat infra/.body.tmp)"
code=$(call -H "Authorization: Bearer ${user_token}x" $GW/api/me/whoami);  expect "gateway: tampered signature" 401 "$code"
code=$(call -H "Authorization: Bearer $svc_token" $GW/internal/ping);      expect "gateway: /internal never exposed (even with service token)" 403 "$code"

# Directly on commerce-service (defence in depth)
code=$(call -H "Authorization: Bearer $user_token" $COMMERCE/internal/ping); expect "commerce: /internal with user token" 403 "$code"
code=$(call -H "Authorization: Bearer $svc_token" $COMMERCE/internal/ping);  expect "commerce: /internal with service token" 200 "$code" '"caller":"payment-service"' "$(cat infra/.body.tmp)"
code=$(call -H "Authorization: Bearer ${user_token}x" $COMMERCE/api/me/whoami); expect "commerce: tampered signature" 401 "$code"

# A token from the same realm but without the MarketChat audience/scope must be rejected:
# agent-service's service-account token has neither (its client has no 'internal' scope).
agent_token=$(curl -s -X POST $KC -d grant_type=client_credentials -d client_id=agent-service \
  --data-urlencode "client_secret=$AGENT_CLIENT_SECRET" | python -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
code=$(call -H "Authorization: Bearer $agent_token" $COMMERCE/api/me/whoami); expect "commerce: realm token without audience/scope" 401 "$code"

echo
if [ $fail -eq 0 ]; then echo "ALL SECURITY E2E CHECKS PASSED"; else echo "SOME CHECKS FAILED"; exit 1; fi
