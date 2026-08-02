#!/usr/bin/env bash

set -Eeuo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(dirname -- "$script_dir")"
compose_file="$repo_root/compose.production.yml"
env_file="${BATON_PRODUCTION_ENV_FILE:-$repo_root/.env.production}"

if [[ $# -eq 0 ]]; then
  printf 'Usage: %s <docker compose arguments...>\n' "$0" >&2
  exit 1
fi
compose_command="$1"
case "$compose_command" in
  -*)
    printf 'Production Compose accepts a subcommand first; global option overrides are not allowed: %s\n' \
      "$compose_command" >&2
    exit 1
    ;;
esac
for compose_argument in "$@"; do
  if [[ "$compose_argument" == "--" ]]; then
    break
  fi
  case "$compose_argument" in
    --project-directory|--project-directory=*|--project-name|--project-name=*|-p|-p=*|-p?*|\
      --env-file|--env-file=*|--file|--file=*|--profile|--profile=*)
      printf 'Production Compose boundary option cannot be overridden: %s\n' \
        "$compose_argument" >&2
      exit 1
      ;;
    -f|-f=*|-f?*)
      if [[ "$compose_command" != "logs" && "$compose_command" != "rm" ]]; then
        printf 'Production Compose boundary option cannot be overridden: %s\n' \
          "$compose_argument" >&2
        exit 1
      fi
      ;;
  esac
done
if ! env_file="$("$script_dir/validate-production-env.sh" "$env_file")"; then
  exit 1
fi

exec env \
  -u BATON_HOST \
  -u BATON_DB_NAME \
  -u BATON_DB_USERNAME \
  -u BATON_DB_PASSWORD \
  -u BATON_DB_ROOT_PASSWORD \
  -u BATON_WORKSPACE_CREATION_KEY \
  -u BATON_WORKSPACE_RECOVERY_KEY \
  -u BATON_WATCH_ENABLED \
  -u BATON_WATCH_MONITORING_ENABLED \
  -u BATON_WATCH_BASE_URL \
  -u BATON_WATCH_BEARER_TOKEN \
  -u BATON_WATCH_SOURCE_NAMESPACE \
  -u BATON_HTTP_PUBLISH \
  -u BATON_HTTPS_TCP_PUBLISH \
  -u BATON_HTTPS_UDP_PUBLISH \
  -u COMPOSE_FILE \
  -u COMPOSE_ENV_FILES \
  -u COMPOSE_DISABLE_ENV_FILE \
  -u COMPOSE_PROJECT_NAME \
  -u COMPOSE_PROFILES \
  docker compose \
    --project-directory "$repo_root" \
    --project-name baton-production \
    --env-file "$env_file" \
    --file "$compose_file" \
    "$@"
