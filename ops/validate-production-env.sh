#!/usr/bin/env bash

set -Eeuo pipefail
export LC_ALL=C

# 운영자가 이 검증기를 `bash -x`로 실행하더라도 인라인 프로덕션 자격 증명을 노출하지 않는다.
case "$-" in
  *x*) set +x ;;
esac

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/production-validation-common.sh
source "$script_dir/production-validation-common.sh"

fail() {
  printf 'Production environment validation failed: %s\n' "$1" >&2
  exit 1
}

if [[ $# -ne 1 ]]; then
  printf 'Usage: %s /absolute/path/to/.env.production\n' "$0" >&2
  exit 1
fi
env_file="$1"
if [[ "$env_file" == *$'\n'* || "$env_file" == *$'\r'* ]]; then
  fail "production environment file path must not contain line breaks"
fi
case "$env_file" in
  /*) ;;
  *) fail "production environment file must be an absolute path: $env_file" ;;
esac

validate_file_boundary() {
  local target="$1"

  if [[ -L "$target" ]]; then
    fail "environment file must not be a symbolic link: $target"
  fi
  if [[ ! -f "$target" || ! -r "$target" ]]; then
    fail "environment file must be a readable regular file: $target"
  fi
  if [[ ! -O "$target" ]]; then
    fail "environment file must be owned by the current user: $target"
  fi
}

validate_file_boundary "$env_file"
if ! env_dir="$(CDPATH= cd -- "$(dirname -- "$env_file")" && pwd -P)"; then
  fail "environment file parent directory could not be resolved: $env_file"
fi
env_file="$env_dir/$(basename -- "$env_file")"
validate_file_boundary "$env_file"

file_mode="$(production_validation_portable_mode fail "$env_file")"
if [[ ! "$file_mode" =~ ^[0-7]{3,4}$ ]]; then
  fail "environment file permissions are invalid: $file_mode"
fi
file_mode_decimal=$((8#$file_mode))
if (( (file_mode_decimal & 077) != 0 )); then
  fail "environment file must not grant group or other permissions: mode=$file_mode"
fi

command -v git >/dev/null 2>&1 || fail "git is required to verify that the environment file is untracked"
git_command=(
  env
  -u GIT_DIR
  -u GIT_WORK_TREE
  -u GIT_INDEX_FILE
  -u GIT_CEILING_DIRECTORIES
  -u GIT_DISCOVERY_ACROSS_FILESYSTEM
  GIT_LITERAL_PATHSPECS=1
  git
)
env_git_root=""
if env_git_root="$("${git_command[@]}" -C "$env_dir" rev-parse --show-toplevel 2>/dev/null)"; then
  env_git_root="$(CDPATH= cd -- "$env_git_root" && pwd -P)"
  relative_env_file="${env_file#"$env_git_root"/}"
  if [[ "$relative_env_file" == "$env_file" ]]; then
    fail "could not resolve environment file inside its Git worktree: $env_file"
  fi
  if ! tracked_env_file="$("${git_command[@]}" -C "$env_git_root" ls-files -- "$relative_env_file")"; then
    fail "could not inspect whether the environment file is tracked by Git: $env_file"
  fi
  if [[ -n "$tracked_env_file" ]]; then
    fail "environment file must not be tracked by Git: $env_file"
  fi
else
  git_probe_dir="$env_dir"
  while [[ "$git_probe_dir" != "/" ]]; do
    if [[ -e "$git_probe_dir/.git" || -L "$git_probe_dir/.git" ]]; then
      fail "could not inspect the Git worktree containing the environment file: $env_file"
    fi
    git_probe_dir="${git_probe_dir%/*}"
    [[ -n "$git_probe_dir" ]] || git_probe_dir="/"
  done
fi

baton_host=""
baton_db_name=""
baton_db_username=""
baton_db_password=""
baton_db_root_password=""
baton_workspace_creation_key=""
baton_workspace_recovery_key=""
baton_cal_capture_enabled="false"
baton_cal_backfill_enabled="false"
baton_cal_delivery_enabled="false"
baton_cal_base_url=""
baton_cal_bearer_token=""
baton_watch_enabled="false"
baton_watch_monitoring_enabled="true"
baton_watch_base_url=""
baton_watch_bearer_token=""
baton_watch_source_namespace=""
baton_watch_event_receiver_enabled="false"
baton_watch_event_receiver_bearer_token=""
seen_baton_host=false
seen_baton_db_name=false
seen_baton_db_username=false
seen_baton_db_password=false
seen_baton_db_root_password=false
seen_baton_workspace_creation_key=false
seen_baton_workspace_recovery_key=false
seen_baton_watch_base_url=false
seen_baton_watch_bearer_token=false
seen_baton_watch_source_namespace=false
seen_baton_watch_event_receiver_bearer_token=false
if ! production_validation_parse_literal_env "$env_file"; then
  fail "$PRODUCTION_VALIDATION_ERROR"
fi

for ((env_index = 0; env_index < ${#PRODUCTION_VALIDATION_ENV_KEYS[@]}; env_index += 1)); do
  key="${PRODUCTION_VALIDATION_ENV_KEYS[$env_index]}"
  value="${PRODUCTION_VALIDATION_ENV_VALUES[$env_index]}"
  case "$key" in
    BATON_HOST)
      seen_baton_host=true
      baton_host="$value"
      ;;
    BATON_DB_NAME)
      seen_baton_db_name=true
      baton_db_name="$value"
      ;;
    BATON_DB_USERNAME)
      seen_baton_db_username=true
      baton_db_username="$value"
      ;;
    BATON_DB_PASSWORD)
      seen_baton_db_password=true
      baton_db_password="$value"
      ;;
    BATON_DB_ROOT_PASSWORD)
      seen_baton_db_root_password=true
      baton_db_root_password="$value"
      ;;
    BATON_WORKSPACE_CREATION_KEY)
      seen_baton_workspace_creation_key=true
      baton_workspace_creation_key="$value"
      ;;
    BATON_WORKSPACE_RECOVERY_KEY)
      seen_baton_workspace_recovery_key=true
      baton_workspace_recovery_key="$value"
      ;;
    BATON_CAL_CAPTURE_ENABLED)
      baton_cal_capture_enabled="$value"
      ;;
    BATON_CAL_BACKFILL_ENABLED)
      baton_cal_backfill_enabled="$value"
      ;;
    BATON_CAL_DELIVERY_ENABLED)
      baton_cal_delivery_enabled="$value"
      ;;
    BATON_CAL_BASE_URL)
      baton_cal_base_url="$value"
      ;;
    BATON_CAL_BEARER_TOKEN)
      baton_cal_bearer_token="$value"
      ;;
    BATON_WATCH_ENABLED)
      baton_watch_enabled="$value"
      ;;
    BATON_WATCH_MONITORING_ENABLED)
      baton_watch_monitoring_enabled="$value"
      ;;
    BATON_WATCH_BASE_URL)
      seen_baton_watch_base_url=true
      baton_watch_base_url="$value"
      ;;
    BATON_WATCH_BEARER_TOKEN)
      seen_baton_watch_bearer_token=true
      baton_watch_bearer_token="$value"
      ;;
    BATON_WATCH_SOURCE_NAMESPACE)
      seen_baton_watch_source_namespace=true
      baton_watch_source_namespace="$value"
      ;;
    BATON_WATCH_EVENT_RECEIVER_ENABLED)
      baton_watch_event_receiver_enabled="$value"
      ;;
    BATON_WATCH_EVENT_RECEIVER_BEARER_TOKEN)
      seen_baton_watch_event_receiver_bearer_token=true
      baton_watch_event_receiver_bearer_token="$value"
      ;;
    BATON_AUTH_OAUTH2_ENABLED|\
      BATON_AUTH_OAUTH2_GOOGLE_CLIENT_ID|\
      BATON_AUTH_OAUTH2_GOOGLE_CLIENT_SECRET_FILE|\
      BATON_AUTH_OAUTH2_NAVER_CLIENT_ID|\
      BATON_AUTH_OAUTH2_NAVER_CLIENT_SECRET_FILE|\
      BATON_AUTH_LOCAL_REGISTRATION_ENABLED|\
      BATON_EMAIL_VERIFICATION_DELIVERY|\
      BATON_EMAIL_OUTBOX_ENCRYPTION_KEY_FILE|\
      BATON_EMAIL_FROM_ADDRESS|\
      BATON_SMTP_HOST|\
      BATON_SMTP_PORT|\
      BATON_SMTP_USERNAME|\
      BATON_SMTP_PASSWORD_FILE|\
      BATON_ROUND_PARTICIPATION_GRANT_ENABLED|\
      BATON_ROUND_PARTICIPATION_GRANT_CURRENT_KID|\
      BATON_ROUND_PARTICIPATION_GRANT_PRIVATE_KEY_FILE|\
      BATON_ROUND_PARTICIPATION_GRANT_PUBLIC_KEY_FILE|\
      BATON_ROUND_PARTICIPATION_GRANT_PREVIOUS_KID|\
      BATON_ROUND_PARTICIPATION_GRANT_PREVIOUS_PUBLIC_KEY_FILE|\
      BATON_ROUND_RUNTIME_ENABLED|\
      BATON_ROUND_WEB_IMAGE|\
      BATON_ROUND_SIGNALING_IMAGE|\
      BATON_ROUND_RELEASE_REVISION|\
      BATON_ROUND_TURN_URLS|\
      BATON_ROUND_TURN_SHARED_SECRET_FILE)
      # 조건부 완전성과 파일 내용은 전용 검증기가 책임진다.
      ;;
    *)
      fail "unknown or unsafe production environment key: $key"
      ;;
  esac
done

for required_key in \
  BATON_HOST \
  BATON_DB_NAME \
  BATON_DB_USERNAME \
  BATON_DB_PASSWORD \
  BATON_DB_ROOT_PASSWORD \
  BATON_WORKSPACE_CREATION_KEY \
  BATON_WORKSPACE_RECOVERY_KEY; do
  case "$required_key" in
    BATON_HOST) seen="$seen_baton_host" ;;
    BATON_DB_NAME) seen="$seen_baton_db_name" ;;
    BATON_DB_USERNAME) seen="$seen_baton_db_username" ;;
    BATON_DB_PASSWORD) seen="$seen_baton_db_password" ;;
    BATON_DB_ROOT_PASSWORD) seen="$seen_baton_db_root_password" ;;
    BATON_WORKSPACE_CREATION_KEY) seen="$seen_baton_workspace_creation_key" ;;
    BATON_WORKSPACE_RECOVERY_KEY) seen="$seen_baton_workspace_recovery_key" ;;
  esac
  [[ "$seen" == true ]] || fail "required key is missing: $required_key"
done

validate_secret() {
  local name="$1"
  local value="$2"

  if [[ ${#value} -lt 32 || ${#value} -gt 200 || ! "$value" =~ ^[A-Za-z0-9._~-]+$ ]]; then
    fail "$name must be 32-200 URL-safe ASCII characters"
  fi
}

validate_https_origin() {
  local name="$1"
  local value="$2"

  if [[ ! "$value" =~ ^https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:[0-9]{1,5})?/?$ ]]; then
    fail "$name must be an absolute HTTPS origin without user info, path, query, or fragment"
  fi
}

production_validation_is_dns_hostname "$baton_host" \
  || fail "BATON_HOST must be a public DNS hostname without scheme, port, path, localhost, or IP"
if [[ ${#baton_db_name} -gt 64 || ! "$baton_db_name" =~ ^[A-Za-z0-9_]+$ ]]; then
  fail "BATON_DB_NAME must be 1-64 letters, digits, or underscores"
fi
if [[ ${#baton_db_username} -gt 32 \
  || ! "$baton_db_username" =~ ^[A-Za-z0-9_]+$ \
  || "$baton_db_username" == "root" ]]; then
  fail "BATON_DB_USERNAME must be a non-root 1-32 character identifier"
fi

validate_secret BATON_DB_PASSWORD "$baton_db_password"
validate_secret BATON_DB_ROOT_PASSWORD "$baton_db_root_password"
validate_secret BATON_WORKSPACE_CREATION_KEY "$baton_workspace_creation_key"
validate_secret BATON_WORKSPACE_RECOVERY_KEY "$baton_workspace_recovery_key"

production_validation_validate_boolean \
  fail BATON_CAL_CAPTURE_ENABLED "$baton_cal_capture_enabled"
production_validation_validate_boolean \
  fail BATON_CAL_BACKFILL_ENABLED "$baton_cal_backfill_enabled"
production_validation_validate_boolean \
  fail BATON_CAL_DELIVERY_ENABLED "$baton_cal_delivery_enabled"
if [[ "$baton_cal_delivery_enabled" == "true" ]]; then
  [[ -n "$baton_cal_base_url" ]] \
    || fail "BATON_CAL_BASE_URL is required when CAL delivery is enabled"
  [[ -n "$baton_cal_bearer_token" ]] \
    || fail "BATON_CAL_BEARER_TOKEN is required when CAL delivery is enabled"
fi
if [[ -n "$baton_cal_base_url" ]]; then
  validate_https_origin BATON_CAL_BASE_URL "$baton_cal_base_url"
fi
if [[ -n "$baton_cal_bearer_token" ]]; then
  validate_secret BATON_CAL_BEARER_TOKEN "$baton_cal_bearer_token"
fi

production_validation_validate_boolean \
  fail BATON_WATCH_ENABLED "$baton_watch_enabled"
production_validation_validate_boolean \
  fail BATON_WATCH_MONITORING_ENABLED "$baton_watch_monitoring_enabled"
production_validation_validate_boolean \
  fail BATON_WATCH_EVENT_RECEIVER_ENABLED "$baton_watch_event_receiver_enabled"
if [[ "$baton_watch_enabled" == "true" ]]; then
  [[ "$seen_baton_watch_base_url" == true ]] \
    || fail "BATON_WATCH_BASE_URL is required when WATCH is enabled"
  [[ "$seen_baton_watch_bearer_token" == true ]] \
    || fail "BATON_WATCH_BEARER_TOKEN is required when WATCH is enabled"
  [[ "$seen_baton_watch_source_namespace" == true ]] \
    || fail "BATON_WATCH_SOURCE_NAMESPACE is required when WATCH is enabled"
fi
if [[ "$baton_watch_event_receiver_enabled" == "true" ]]; then
  [[ "$seen_baton_watch_event_receiver_bearer_token" == true ]] \
    || fail "BATON_WATCH_EVENT_RECEIVER_BEARER_TOKEN is required when the WATCH event receiver is enabled"
  [[ "$seen_baton_watch_source_namespace" == true ]] \
    || fail "BATON_WATCH_SOURCE_NAMESPACE is required when the WATCH event receiver is enabled"
fi
if [[ -n "$baton_watch_base_url" ]]; then
  validate_https_origin BATON_WATCH_BASE_URL "$baton_watch_base_url"
fi
if [[ -n "$baton_watch_bearer_token" ]]; then
  validate_secret BATON_WATCH_BEARER_TOKEN "$baton_watch_bearer_token"
fi
if [[ -n "$baton_watch_event_receiver_bearer_token" ]]; then
  validate_secret \
    BATON_WATCH_EVENT_RECEIVER_BEARER_TOKEN \
    "$baton_watch_event_receiver_bearer_token"
fi
if [[ -n "$baton_watch_source_namespace" \
  && ( ${#baton_watch_source_namespace} -gt 63 \
    || ! "$baton_watch_source_namespace" =~ ^[A-Za-z0-9._-]+$ ) ]]; then
  fail "BATON_WATCH_SOURCE_NAMESPACE must be 1-63 letters, digits, dots, underscores, or hyphens"
fi

secrets=(
  "$baton_db_password"
  "$baton_db_root_password"
  "$baton_workspace_creation_key"
  "$baton_workspace_recovery_key"
)
optional_secrets=(
  "$baton_cal_bearer_token"
  "$baton_watch_bearer_token"
  "$baton_watch_event_receiver_bearer_token"
)
for optional_secret in "${optional_secrets[@]}"; do
  if [[ -n "$optional_secret" ]]; then
    secrets+=("$optional_secret")
  fi
done
for ((left = 0; left < ${#secrets[@]}; left += 1)); do
  for ((right = left + 1; right < ${#secrets[@]}; right += 1)); do
    if [[ "${secrets[$left]}" == "${secrets[$right]}" ]]; then
      fail "production secrets must all be independently generated"
    fi
  done
done

"$script_dir/validate-production-round-runtime.sh" "$env_file"
"$script_dir/validate-production-auth-secrets.sh" "$env_file"

printf '%s\n' "$env_file"
