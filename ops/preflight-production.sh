#!/usr/bin/env bash

set -Eeuo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(dirname -- "$script_dir")"
env_file="${BATON_PRODUCTION_ENV_FILE:-$repo_root/.env.production}"

fail() {
  printf 'Production preflight failed: %s\n' "$1" >&2
  exit 1
}

if [[ $# -gt 1 ]]; then
  printf 'Usage: %s [/absolute/path/to/.env.production]\n' "$0" >&2
  exit 1
fi
if [[ $# -eq 1 ]]; then
  env_file="$1"
fi
if ! env_file="$("$script_dir/validate-production-env.sh" "$env_file")"; then
  exit 1
fi

command -v docker >/dev/null 2>&1 || fail "docker is required"
docker info >/dev/null 2>&1 || fail "Docker daemon is not available to the current user"
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is required"

if ! BATON_PRODUCTION_ENV_FILE="$env_file" \
  "$script_dir/production-compose.sh" config --quiet; then
  fail "production Compose configuration is invalid"
fi

printf 'Production preflight passed: env=%s compose=%s\n' \
  "$env_file" "$repo_root/compose.production.yml"
