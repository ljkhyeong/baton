#!/usr/bin/env bash

set -Eeuo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(dirname -- "$script_dir")"
env_file="${BATON_PRODUCTION_ENV_FILE:-$repo_root/.env.production}"
# shellcheck source=ops/production-validation-common.sh
source "$script_dir/production-validation-common.sh"

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
"${PRODUCTION_DOCKER_COMMAND[@]}" info >/dev/null 2>&1 \
  || fail "local Docker daemon is not available through /var/run/docker.sock"
"${PRODUCTION_DOCKER_COMMAND[@]}" compose version >/dev/null 2>&1 \
  || fail "Docker Compose v2 is required"

if ! production_validation_parse_literal_env "$env_file"; then
  fail "$PRODUCTION_VALIDATION_ERROR"
fi
brief_service_api_enabled="$(production_validation_env_value BATON_BRIEF_SERVICE_API_ENABLED)"
brief_service_api_enabled="${brief_service_api_enabled:-false}"
if [[ "$brief_service_api_enabled" == "true" ]]; then
  brief_private_network="$(production_validation_env_value BATON_BRIEF_PRIVATE_NETWORK)"
  brief_network_internal="$(
    "${PRODUCTION_DOCKER_COMMAND[@]}" network inspect \
      --format '{{.Internal}}' "$brief_private_network" 2>/dev/null
  )" || fail "BRIEF private Docker network does not exist: $brief_private_network"
  [[ "$brief_network_internal" == "true" ]] \
    || fail "BRIEF private Docker network must have Internal=true: $brief_private_network"
fi

if ! BATON_PRODUCTION_ENV_FILE="$env_file" \
  "$script_dir/production-compose.sh" config --quiet; then
  fail "production Compose configuration is invalid"
fi

if ! "$script_dir/verify-production-round-images.sh" "$env_file"; then
  fail "production ROUND images are invalid"
fi

printf 'Production preflight passed: env=%s compose=%s\n' \
  "$env_file" "$repo_root/compose.production.yml"
