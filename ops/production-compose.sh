#!/usr/bin/env bash

set -Eeuo pipefail

# A caller may invoke this script through `bash -x`; never trace secret material loaded below.
case "$-" in
  *x*) set +x ;;
esac

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(dirname -- "$script_dir")"
compose_file="$repo_root/compose.production.yml"
round_compose_file="$repo_root/compose.round.production.yml"
env_file="${BATON_PRODUCTION_ENV_FILE:-$repo_root/.env.production}"

if [[ $# -eq 0 ]]; then
  printf 'Usage: %s <docker compose arguments...>\n' "$0" >&2
  exit 1
fi
compose_command="$1"
case "$compose_command" in
  scale)
    printf 'Production Compose does not allow scaling the in-memory session app.\n' >&2
    exit 1
    ;;
  run)
    printf 'Production Compose one-off run option is not allowed.\n' >&2
    exit 1
    ;;
  publish|commit)
    printf 'Production Compose artifact publication option is not allowed: %s\n' \
      "$compose_command" >&2
    exit 1
    ;;
  config)
    if [[ $# -ne 2 || "$2" != "--quiet" ]]; then
      printf 'Production Compose config option is not allowed except exact --quiet validation.\n' >&2
      exit 1
    fi
    ;;
  up|down|start|restart|stop|kill|rm|create|exec|cp|pause|unpause|watch|pull|build|\
    logs|ps|images|top|events|port|stats|wait|version)
    ;;
  -*)
    printf 'Production Compose accepts a subcommand first; global option overrides are not allowed: %s\n' \
      "$compose_command" >&2
    exit 1
    ;;
  *)
    printf 'Production Compose command option is not allowed: %s\n' \
      "$compose_command" >&2
    exit 1
    ;;
esac
for compose_argument in "$@"; do
  if [[ "$compose_argument" == "--" ]]; then
    break
  fi
  case "$compose_argument" in
    --scale|--scale=*)
      printf 'Production Compose does not allow scaling the in-memory session app: %s\n' \
        "$compose_argument" >&2
      exit 1
      ;;
    --remove-orphans|--remove-orphans=*|--no-recreate|--no-start|--down-project)
      printf 'Production Compose reconciliation option cannot be overridden: %s\n' \
        "$compose_argument" >&2
      exit 1
      ;;
    --volumes|--volumes=*|-v|-[^-]*v*)
      printf 'Production Compose data-volume deletion option is not allowed: %s\n' \
        "$compose_argument" >&2
      exit 1
      ;;
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

env_value() {
  local wanted_key="$1"
  local line

  while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ "$line" == "$wanted_key="* ]]; then
      printf '%s' "${line#*=}"
      return 0
    fi
  done < "$env_file"
  return 0
}

lifecycle_lock_required=false
case "$compose_command" in
  up|down|start|restart|stop|kill|rm|create|exec|cp|pause|unpause|watch)
    lifecycle_lock_required=true
    ;;
esac
if [[ "$lifecycle_lock_required" == true ]]; then
  # shellcheck source=ops/production-lifecycle-lock.sh
  source "$script_dir/production-lifecycle-lock.sh"
  acquire_production_lifecycle_lock || exit $?
fi

canonical_file() {
  local target="$1"
  local directory

  directory="$(CDPATH= cd -- "$(dirname -- "$target")" && pwd -P)"
  printf '%s/%s' "$directory" "$(basename -- "$target")"
}

read_secret_or_placeholder() {
  local target="$1"
  local placeholder="$2"

  if [[ -z "$target" ]]; then
    printf '%s' "$placeholder"
    return 0
  fi
  target="$(canonical_file "$target")"
  printf '%s' "$(< "$target")"
}

google_secret_file="$(env_value BATON_AUTH_OAUTH2_GOOGLE_CLIENT_SECRET_FILE)"
naver_secret_file="$(env_value BATON_AUTH_OAUTH2_NAVER_CLIENT_SECRET_FILE)"
smtp_password_file="$(env_value BATON_SMTP_PASSWORD_FILE)"
email_outbox_encryption_key_file="$(env_value BATON_EMAIL_OUTBOX_ENCRYPTION_KEY_FILE)"
round_private_key_file="$(env_value BATON_ROUND_PARTICIPATION_GRANT_PRIVATE_KEY_FILE)"
round_public_key_file="$(env_value BATON_ROUND_PARTICIPATION_GRANT_PUBLIC_KEY_FILE)"
round_previous_public_key_file="$(
  env_value BATON_ROUND_PARTICIPATION_GRANT_PREVIOUS_PUBLIC_KEY_FILE
)"
round_runtime_enabled="$(env_value BATON_ROUND_RUNTIME_ENABLED)"
round_runtime_enabled="${round_runtime_enabled:-false}"
round_turn_shared_secret_file="$(env_value BATON_ROUND_TURN_SHARED_SECRET_FILE)"
round_runtime_uid="$(id -u)"
round_runtime_gid="$(id -g)"
email_delivery="$(env_value BATON_EMAIL_VERIFICATION_DELIVERY)"
email_delivery="${email_delivery:-disabled}"

if [[ ! "$round_runtime_uid" =~ ^[0-9]+$ || ! "$round_runtime_gid" =~ ^[0-9]+$ ]]; then
  printf 'Production Compose could not resolve the current numeric UID/GID.\n' >&2
  exit 1
fi
if [[ "$round_runtime_enabled" == "true" \
  && ( "$round_runtime_uid" == "0" || "$round_runtime_gid" == "0" ) ]]; then
  printf 'Production ROUND runtime must be managed by a non-root user and group.\n' >&2
  exit 1
fi

google_client_secret="$(
  read_secret_or_placeholder "$google_secret_file" disabled-google-oauth-client-secret
)"
naver_client_secret="$(
  read_secret_or_placeholder "$naver_secret_file" disabled-naver-oauth-client-secret
)"
smtp_password="$(
  read_secret_or_placeholder "$smtp_password_file" disabled-smtp-password
)"
email_outbox_encryption_key="$(< "$(canonical_file "$email_outbox_encryption_key_file")")"
round_private_key="$(
  read_secret_or_placeholder "$round_private_key_file" disabled-round-private-key
)"
round_public_key="$(
  read_secret_or_placeholder "$round_public_key_file" disabled-round-public-key
)"
round_previous_public_key="$(
  read_secret_or_placeholder \
    "$round_previous_public_key_file" \
    disabled-round-previous-public-key
)"
round_turn_shared_secret_path=/dev/null
if [[ -n "$round_turn_shared_secret_file" ]]; then
  round_turn_shared_secret_path="$(canonical_file "$round_turn_shared_secret_file")"
fi

smtp_test_connection=false
if [[ "$email_delivery" == "smtp" ]]; then
  smtp_test_connection=true
fi
round_previous_container_path=""
if [[ -n "$round_previous_public_key_file" ]]; then
  round_previous_container_path=/run/baton-keys/previous-public.pem
fi

include_round_overlay=false
if [[ "$round_runtime_enabled" == "true" ]]; then
  include_round_overlay=true
else
  case "$compose_command" in
    down|stop|kill|rm|logs|ps) include_round_overlay=true ;;
  esac
fi

compose_files=(--file "$compose_file")
if [[ "$include_round_overlay" == true ]]; then
  compose_files+=(--file "$round_compose_file")
fi
compose_arguments=("$@")
if [[ "$compose_command" == "up" ]]; then
  reconciled_services=(mysql app web)
  if [[ "$include_round_overlay" == true ]]; then
    reconciled_services+=(round-web round-signaling)
  fi
  compose_arguments=(
    "$compose_command"
    --remove-orphans
    "${@:2}"
    "${reconciled_services[@]}"
  )
elif [[ "$compose_command" == "down" ]]; then
  compose_arguments=("$compose_command" --remove-orphans "${@:2}")
fi

env \
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
  -u BATON_WATCH_EVENT_RECEIVER_ENABLED \
  -u BATON_WATCH_EVENT_RECEIVER_BEARER_TOKEN \
  -u BATON_AUTH_OAUTH2_ENABLED \
  -u BATON_AUTH_OAUTH2_GOOGLE_CLIENT_ID \
  -u BATON_AUTH_OAUTH2_GOOGLE_CLIENT_SECRET_FILE \
  -u BATON_AUTH_OAUTH2_NAVER_CLIENT_ID \
  -u BATON_AUTH_OAUTH2_NAVER_CLIENT_SECRET_FILE \
  -u BATON_AUTH_LOCAL_REGISTRATION_ENABLED \
  -u BATON_EMAIL_VERIFICATION_DELIVERY \
  -u BATON_EMAIL_OUTBOX_ENCRYPTION_KEY_FILE \
  -u BATON_EMAIL_FROM_ADDRESS \
  -u BATON_SMTP_HOST \
  -u BATON_SMTP_PORT \
  -u BATON_SMTP_USERNAME \
  -u BATON_SMTP_PASSWORD_FILE \
  -u BATON_ROUND_PARTICIPATION_GRANT_ENABLED \
  -u BATON_ROUND_PARTICIPATION_GRANT_CURRENT_KID \
  -u BATON_ROUND_PARTICIPATION_GRANT_PRIVATE_KEY_FILE \
  -u BATON_ROUND_PARTICIPATION_GRANT_PUBLIC_KEY_FILE \
  -u BATON_ROUND_PARTICIPATION_GRANT_PREVIOUS_KID \
  -u BATON_ROUND_PARTICIPATION_GRANT_PREVIOUS_PUBLIC_KEY_FILE \
  -u BATON_ROUND_RUNTIME_ENABLED \
  -u BATON_ROUND_WEB_IMAGE \
  -u BATON_ROUND_SIGNALING_IMAGE \
  -u BATON_ROUND_RELEASE_REVISION \
  -u BATON_ROUND_TURN_URLS \
  -u BATON_ROUND_TURN_SHARED_SECRET_FILE \
  -u BATON_BACKUP_STATE_DIR \
  -u BATON_PRODUCTION_LIFECYCLE_LOCK_FD \
  -u BATON_SECRET_GOOGLE_OAUTH_CLIENT_SECRET \
  -u BATON_SECRET_NAVER_OAUTH_CLIENT_SECRET \
  -u BATON_SECRET_SMTP_PASSWORD \
  -u BATON_SECRET_EMAIL_OUTBOX_ENCRYPTION_KEY \
  -u BATON_SECRET_ROUND_CURRENT_PRIVATE_KEY \
  -u BATON_SECRET_ROUND_CURRENT_PUBLIC_KEY \
  -u BATON_SECRET_ROUND_PREVIOUS_PUBLIC_KEY \
  -u BATON_SECRET_ROUND_TURN_SHARED_SECRET \
  -u BATON_EFFECTIVE_ROUND_UID \
  -u BATON_EFFECTIVE_ROUND_GID \
  -u BATON_EFFECTIVE_ROUND_TURN_SHARED_SECRET_FILE \
  -u BATON_EFFECTIVE_SMTP_TEST_CONNECTION \
  -u BATON_EFFECTIVE_ROUND_PREVIOUS_PUBLIC_KEY_PATH \
  -u BATON_HTTP_PUBLISH \
  -u BATON_HTTPS_TCP_PUBLISH \
  -u BATON_HTTPS_UDP_PUBLISH \
  -u COMPOSE_FILE \
  -u COMPOSE_ENV_FILES \
  -u COMPOSE_DISABLE_ENV_FILE \
  -u COMPOSE_PROJECT_NAME \
  -u COMPOSE_PROFILES \
  -u COMPOSE_PATH_SEPARATOR \
  -u COMPOSE_PARALLEL_LIMIT \
  -u COMPOSE_ANSI \
  -u COMPOSE_STATUS_STDOUT \
  -u COMPOSE_PROGRESS \
  -u COMPOSE_MENU \
  -u COMPOSE_EXPERIMENTAL \
  -u COMPOSE_IGNORE_ORPHANS \
  -u COMPOSE_REMOVE_ORPHANS \
  -u COMPOSE_CONVERT_WINDOWS_PATHS \
  -u DOCKER_HOST \
  -u DOCKER_CONTEXT \
  -u DOCKER_CONFIG \
  -u DOCKER_TLS_VERIFY \
  -u DOCKER_CERT_PATH \
  -u DOCKER_API_VERSION \
  -u DOCKER_DEFAULT_PLATFORM \
  -u BUILDX_BUILDER \
  -u BUILDX_CONFIG \
  -u BUILDKIT_HOST \
  -u DOCKER_BUILDKIT \
  BATON_SECRET_GOOGLE_OAUTH_CLIENT_SECRET="$google_client_secret" \
  BATON_SECRET_NAVER_OAUTH_CLIENT_SECRET="$naver_client_secret" \
  BATON_SECRET_SMTP_PASSWORD="$smtp_password" \
  BATON_SECRET_EMAIL_OUTBOX_ENCRYPTION_KEY="$email_outbox_encryption_key" \
  BATON_SECRET_ROUND_CURRENT_PRIVATE_KEY="$round_private_key" \
  BATON_SECRET_ROUND_CURRENT_PUBLIC_KEY="$round_public_key" \
  BATON_SECRET_ROUND_PREVIOUS_PUBLIC_KEY="$round_previous_public_key" \
  BATON_EFFECTIVE_ROUND_UID="$round_runtime_uid" \
  BATON_EFFECTIVE_ROUND_GID="$round_runtime_gid" \
  BATON_EFFECTIVE_ROUND_TURN_SHARED_SECRET_FILE="$round_turn_shared_secret_path" \
  BATON_EFFECTIVE_SMTP_TEST_CONNECTION="$smtp_test_connection" \
  BATON_EFFECTIVE_ROUND_PREVIOUS_PUBLIC_KEY_PATH="$round_previous_container_path" \
  docker --host unix:///var/run/docker.sock compose \
    --project-directory "$repo_root" \
    --project-name baton-production \
    --env-file "$env_file" \
    "${compose_files[@]}" \
    "${compose_arguments[@]}"
