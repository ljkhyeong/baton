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
docker_command=(
  env
  -u DOCKER_HOST
  -u DOCKER_CONTEXT
  -u DOCKER_CONFIG
  -u DOCKER_TLS_VERIFY
  -u DOCKER_CERT_PATH
  -u DOCKER_API_VERSION
  -u DOCKER_DEFAULT_PLATFORM
  -u BUILDX_BUILDER
  -u BUILDX_CONFIG
  -u BUILDKIT_HOST
  -u DOCKER_BUILDKIT
  docker
  --host unix:///var/run/docker.sock
)
"${docker_command[@]}" info >/dev/null 2>&1 \
  || fail "local Docker daemon is not available through /var/run/docker.sock"
"${docker_command[@]}" compose version >/dev/null 2>&1 \
  || fail "Docker Compose v2 is required"

if ! production_validation_parse_literal_env "$env_file"; then
  fail "$PRODUCTION_VALIDATION_ERROR"
fi
production_validation_read_env_value BATON_BRIEF_SERVICE_API_ENABLED \
  || fail "$PRODUCTION_VALIDATION_ERROR"
brief_service_api_enabled="${PRODUCTION_VALIDATION_VALUE:-false}"
if [[ "$brief_service_api_enabled" == "true" ]]; then
  production_validation_read_env_value BATON_BRIEF_PRIVATE_NETWORK \
    || fail "$PRODUCTION_VALIDATION_ERROR"
  brief_private_network="$PRODUCTION_VALIDATION_VALUE"
  brief_network_internal="$(
    "${docker_command[@]}" network inspect \
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
