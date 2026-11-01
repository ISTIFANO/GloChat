#!/usr/bin/env bash
# Step 0d end-to-end test: one request through gateway -> commerce must appear as ONE trace in Tempo,
# its log line in Loki with the same trace id, metrics in Prometheus, and JSON logs on the console.
# Prerequisites: infra stack up, services packaged.
set -uo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."
set -a; source infra/.env; set +a

GRAFANA=http://localhost:3001/api/datasources/proxy/uid
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
check() { local name="$1"; shift; if "$@"; then echo "PASS  $name"; else echo "FAIL  $name"; fail=1; fi; }

token=$(curl -s -X POST http://localhost:8180/realms/marketchat/protocol/openid-connect/token \
  -d grant_type=password -d client_id=marketchat-dev-cli \
  --data-urlencode username=demo@marketchat.local --data-urlencode "password=$DEMO_USER_PASSWORD" | python -c "import sys,json;print(json.load(sys.stdin)['access_token'])")

# Known W3C trace id, so we can look it up afterwards
trace_id=$(python -c "import secrets;print(secrets.token_hex(16))")
parent_span=$(python -c "import secrets;print(secrets.token_hex(8))")
code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $token" \
  -H "traceparent: 00-$trace_id-$parent_span-01" http://localhost:8080/api/me/whoami)
check "request through gateway succeeded ($code)" test "$code" = 200
echo "      trace id: $trace_id"

# Batch exporters flush every few seconds; retry for up to 60 s
services_in_trace=""
for i in $(seq 1 20); do
  services_in_trace=$(curl -s "$GRAFANA/tempo/api/traces/$trace_id" | python -c "
import sys,json
try:
    d=json.load(sys.stdin)
except Exception:
    print(''); sys.exit()
names=set()
for b in d.get('batches', d.get('resourceSpans', [])):
    for a in b.get('resource',{}).get('attributes',[]):
        if a['key']=='service.name': names.add(a['value'].get('stringValue'))
print(' '.join(sorted(names)))")
  [[ "$services_in_trace" == *api-gateway* && "$services_in_trace" == *commerce-service* ]] && break
  sleep 3
done
check "Tempo: one trace spans api-gateway AND commerce-service [$services_in_trace]" \
  bash -c "[[ '$services_in_trace' == *api-gateway* && '$services_in_trace' == *commerce-service* ]]"

# Loki: the 'whoami requested' log line carries the same trace id
loki_hits=0
for i in $(seq 1 20); do
  now=$(python -c "import time;print(int(time.time()*1e9))")
  start_ns=$(python -c "import time;print(int((time.time()-600)*1e9))")
  loki_hits=$(curl -s -G "$GRAFANA/loki/loki/api/v1/query_range" \
    --data-urlencode "query={service_name=\"commerce-service\"} | trace_id=\"$trace_id\" |= \"whoami requested\"" \
    --data-urlencode "start=$start_ns" --data-urlencode "end=$now" | python -c "
import sys,json
try:
    print(sum(len(s['values']) for s in json.load(sys.stdin)['data']['result']))
except Exception:
    print(0)")
  [ "$loki_hits" -ge 1 ] 2>/dev/null && break
  sleep 3
done
check "Loki: log line found with the same trace id ($loki_hits)" test "$loki_hits" -ge 1

# Prometheus: HTTP server metrics from commerce-service
metric_series=0
for i in $(seq 1 20); do
  metric_series=$(curl -s -G "$GRAFANA/prometheus/api/v1/query" \
    --data-urlencode 'query={__name__=~"http_server_requests.*", job=~".*commerce-service.*"}' | python -c "
import sys,json
try:
    print(len(json.load(sys.stdin)['data']['result']))
except Exception:
    print(0)")
  [ "$metric_series" -ge 1 ] 2>/dev/null && break
  sleep 3
done
check "Prometheus: http_server_requests series for commerce-service ($metric_series)" test "$metric_series" -ge 1

# Console logs are JSON (ECS) with trace.id
check "console log is JSON with trace.id" bash -c "grep -m1 'whoami requested' infra/.commerce-service.log | python -c \"import sys,json;l=json.loads(sys.stdin.read());sys.exit(0 if '$trace_id' in (l.get('traceId'), l.get('trace',{}).get('id'), l.get('trace.id')) else 1)\""

echo
if [ $fail -eq 0 ]; then echo "ALL OBSERVABILITY E2E CHECKS PASSED"; else echo "SOME CHECKS FAILED"; exit 1; fi
