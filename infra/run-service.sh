#!/usr/bin/env bash
# Runs one service locally with the secrets from infra/.env (never committed).
# Usage: bash infra/run-service.sh <commerce-service|payment-service|agent-service|api-gateway>
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source infra/.env; set +a
svc="$1"
case "$svc" in
  commerce-service) export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-demo}" ;;
esac
exec java -jar "services/$svc/target/$svc-0.1.0-SNAPSHOT.jar" > "infra/.$svc.log" 2>&1
