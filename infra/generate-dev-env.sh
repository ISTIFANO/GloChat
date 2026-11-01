#!/usr/bin/env bash
# Creates infra/.env with random DEV passwords. Never use these values outside your machine.
set -euo pipefail
cd "$(dirname "$0")"

if [[ -f .env ]]; then
  echo "infra/.env already exists — not overwriting." >&2
  exit 1
fi

rand() { head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24; }

sed -E "s/^([A-Z_]*(PASSWORD|SECRET))=change-me$/\1=__RAND__/" .env.example | while IFS= read -r line; do
  if [[ "$line" == *"=__RAND__" ]]; then
    echo "${line%__RAND__}$(rand)"
  else
    echo "$line"
  fi
done > .env
chmod 600 .env
echo "infra/.env created with random dev passwords."
