#!/usr/bin/env bash
# Adds (or updates) "Continue with Google" on the marketchat realm: Keycloak brokers the Google login,
# so the app keeps one identity system (same tokens, same backend checks) whatever the sign-in method.
#
# 1. Google Cloud Console -> APIs & Services -> Credentials -> Create OAuth client ID (type: Web application)
#    Authorized redirect URI:  http://localhost:8180/realms/marketchat/broker/google/endpoint
# 2. Put GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET in infra/.env (never committed)
# 3. bash infra/keycloak/configure-google.sh
#
# The web app's "Continuer avec Google" button sends kc_idp_hint=google: Keycloak goes straight to Google.
# Until this script runs, the same button falls back to the Keycloak form (nothing breaks).
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/../.."
set -a; source infra/.env; set +a

: "${GOOGLE_CLIENT_ID:?set GOOGLE_CLIENT_ID in infra/.env}"
: "${GOOGLE_CLIENT_SECRET:?set GOOGLE_CLIENT_SECRET in infra/.env}"
KC=marketchat-keycloak-1
kcadm() { docker exec "$KC" /opt/keycloak/bin/kcadm.sh "$@"; }

kcadm config credentials --server http://localhost:8080 --realm master \
  --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null

args=(-r marketchat -s alias=google -s providerId=google -s enabled=true -s trustEmail=true
      -s storeToken=false -s firstBrokerLoginFlowAlias="first broker login"
      -s "config.clientId=$GOOGLE_CLIENT_ID" -s "config.clientSecret=$GOOGLE_CLIENT_SECRET"
      -s "config.defaultScope=openid profile email" -s config.syncMode=IMPORT -s config.guiOrder=1)

if kcadm get identity-provider/instances/google -r marketchat >/dev/null 2>&1; then
  kcadm update identity-provider/instances/google "${args[@]}"
  echo "Google identity provider updated"
else
  kcadm create identity-provider/instances "${args[@]}"
  echo "Google identity provider created"
fi
# Google already verified the e-mail and gives the name: skip Keycloak's "review profile" page on first login
exec_id=$(kcadm get "authentication/flows/first%20broker%20login/executions" -r marketchat \
  | python -c "import sys,json;print(next(e['id'] for e in json.load(sys.stdin) if e.get('providerId')=='idp-review-profile'))")
cfg_id=$(kcadm get "authentication/flows/first%20broker%20login/executions" -r marketchat \
  | python -c "import sys,json;print(next((e.get('authenticationConfig') or '') for e in json.load(sys.stdin) if e.get('providerId')=='idp-review-profile'))")
if [ -n "$cfg_id" ]; then
  kcadm update "authentication/config/$cfg_id" -r marketchat -s 'config."update.profile.on.first.login"=missing' >/dev/null
fi
echo "Done: the app's 'Continuer avec Google' now opens Google directly (execution $exec_id)."
