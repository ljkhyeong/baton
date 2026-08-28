#!/usr/bin/env bash

set -Eeuo pipefail
export LC_ALL=C

# 운영자가 이 검증기를 `bash -x`로 실행하더라도 TURN 비밀값을 노출하지 않는다.
case "$-" in
  *x*) set +x ;;
esac

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/production-validation-common.sh
source "$script_dir/production-validation-common.sh"

fail() {
  printf 'Production ROUND runtime validation failed: %s\n' "$1" >&2
  exit 1
}

if [[ $# -ne 1 ]]; then
  printf 'Usage: %s /absolute/path/to/.env.production\n' "$0" >&2
  exit 1
fi

env_file="$1"
[[ "$env_file" == /* ]] || fail "production environment file must be absolute"
[[ -f "$env_file" && -r "$env_file" && ! -L "$env_file" ]] \
  || fail "production environment file must be a readable regular file"

round_runtime_enabled="false"
round_grant_enabled="false"
round_web_image=""
round_signaling_image=""
round_release_revision=""
round_turn_urls=""
round_turn_shared_secret_file=""

db_password=""
db_root_password=""
workspace_creation_key=""
workspace_recovery_key=""
cal_bearer_token_file=""
watch_bearer_token=""
watch_receiver_bearer_token=""
email_outbox_encryption_key_file=""
google_client_secret_file=""
naver_client_secret_file=""
smtp_password_file=""
if ! production_validation_parse_literal_env "$env_file"; then
  fail "$PRODUCTION_VALIDATION_ERROR"
fi

for ((env_index = 0; env_index < ${#PRODUCTION_VALIDATION_ENV_KEYS[@]}; env_index += 1)); do
  key="${PRODUCTION_VALIDATION_ENV_KEYS[$env_index]}"
  value="${PRODUCTION_VALIDATION_ENV_VALUES[$env_index]}"
  case "$key" in
    BATON_ROUND_RUNTIME_ENABLED)
      round_runtime_enabled="$value"
      ;;
    BATON_ROUND_PARTICIPATION_GRANT_ENABLED)
      round_grant_enabled="$value"
      ;;
    BATON_ROUND_WEB_IMAGE)
      round_web_image="$value"
      ;;
    BATON_ROUND_SIGNALING_IMAGE)
      round_signaling_image="$value"
      ;;
    BATON_ROUND_RELEASE_REVISION)
      round_release_revision="$value"
      ;;
    BATON_ROUND_TURN_URLS)
      round_turn_urls="$value"
      ;;
    BATON_ROUND_TURN_SHARED_SECRET_FILE)
      round_turn_shared_secret_file="$value"
      ;;
    BATON_DB_PASSWORD) db_password="$value" ;;
    BATON_DB_ROOT_PASSWORD) db_root_password="$value" ;;
    BATON_WORKSPACE_CREATION_KEY) workspace_creation_key="$value" ;;
    BATON_WORKSPACE_RECOVERY_KEY) workspace_recovery_key="$value" ;;
    BATON_CAL_BEARER_TOKEN_FILE) cal_bearer_token_file="$value" ;;
    BATON_WATCH_BEARER_TOKEN) watch_bearer_token="$value" ;;
    BATON_WATCH_EVENT_RECEIVER_BEARER_TOKEN) watch_receiver_bearer_token="$value" ;;
    BATON_EMAIL_OUTBOX_ENCRYPTION_KEY_FILE)
      email_outbox_encryption_key_file="$value"
      ;;
    BATON_AUTH_OAUTH2_GOOGLE_CLIENT_SECRET_FILE)
      google_client_secret_file="$value"
      ;;
    BATON_AUTH_OAUTH2_NAVER_CLIENT_SECRET_FILE)
      naver_client_secret_file="$value"
      ;;
    BATON_SMTP_PASSWORD_FILE) smtp_password_file="$value" ;;
  esac
done

require_value() {
  local name="$1"
  local value="$2"

  [[ -n "$value" ]] || fail "$name is required when BATON_ROUND_RUNTIME_ENABLED=true"
}

validate_digest_image() {
  local name="$1"
  local value="$2"
  local repository
  local registry
  local registry_port

  if [[ ! "$value" =~ @sha256:[0-9a-f]{64}$ ]]; then
    fail "$name must be an immutable repository@sha256:<64 lowercase hex> reference"
  fi
  repository="${value%@sha256:*}"
  if [[ ! "$repository" =~ ^[a-z0-9]+([._-][a-z0-9]+)*(:[0-9]{1,5})?(/[a-z0-9]+([._-][a-z0-9]+)*)+$ ]]; then
    fail "$name must be an immutable repository@sha256:<64 lowercase hex> reference"
  fi
  registry="${repository%%/*}"
  if [[ "$registry" == *:* ]]; then
    registry_port="${registry##*:}"
    if (( 10#$registry_port < 1 || 10#$registry_port > 65535 )); then
      fail "$name registry port must be between 1 and 65535"
    fi
  fi
}

validate_turn_urls() {
  local value="$1"
  local entry
  local scheme
  local hostname
  local port
  local transport
  local old_ifs
  local entries
  local seen_entries=$'\n'
  local has_turn_udp=false
  local has_turn_tcp=false
  local has_turns_tcp=false

  if [[ ${#value} -gt 2048 || "${value:0:1}" == "," || "${value: -1}" == "," \
    || "$value" == *,,* ]]; then
    fail "BATON_ROUND_TURN_URLS must be a comma-separated credential-free TURN URL list"
  fi
  old_ifs="$IFS"
  IFS=','
  read -r -a entries <<< "$value"
  IFS="$old_ifs"
  if (( ${#entries[@]} < 3 || ${#entries[@]} > 8 )); then
    fail "BATON_ROUND_TURN_URLS must contain 3-8 TURN URLs"
  fi

  for entry in "${entries[@]}"; do
    if [[ ! "$entry" =~ ^(turn|turns):([A-Za-z0-9.-]+):([1-9][0-9]{0,4})\?transport=(udp|tcp)$ ]]; then
      fail "BATON_ROUND_TURN_URLS entries must be credential-free DNS TURN URLs with an explicit port and transport"
    fi
    scheme="${BASH_REMATCH[1]}"
    hostname="${BASH_REMATCH[2]}"
    port="${BASH_REMATCH[3]}"
    transport="${BASH_REMATCH[4]}"
    production_validation_is_dns_hostname "$hostname" \
      || fail "BATON_ROUND_TURN_URLS entries must use DNS hostnames, not localhost or IP addresses"
    if (( 10#$port > 65535 )); then
      fail "BATON_ROUND_TURN_URLS entry ports must be between 1 and 65535"
    fi
    if [[ "$scheme" == "turns" && "$transport" != "tcp" ]]; then
      fail "BATON_ROUND_TURN_URLS only permits turns URLs with transport=tcp"
    fi
    case "$seen_entries" in
      *$'\n'"$entry"$'\n'*) fail "BATON_ROUND_TURN_URLS must not contain duplicate entries" ;;
    esac
    seen_entries+="$entry"$'\n'
    case "$scheme:$transport" in
      turn:udp) has_turn_udp=true ;;
      turn:tcp) has_turn_tcp=true ;;
      turns:tcp) has_turns_tcp=true ;;
    esac
  done

  [[ "$has_turn_udp" == true ]] \
    || fail "BATON_ROUND_TURN_URLS must include a turn URL with transport=udp"
  [[ "$has_turn_tcp" == true ]] \
    || fail "BATON_ROUND_TURN_URLS must include a turn URL with transport=tcp"
  [[ "$has_turns_tcp" == true ]] \
    || fail "BATON_ROUND_TURN_URLS must include a turns URL with transport=tcp"
}

validate_turn_secret_file() {
  local name="$1"
  local target="$2"
  local canonical_target
  local directory
  local directory_mode
  local file_mode
  local size
  local value

  [[ "$target" == /* ]] || fail "$name must be an absolute file path"
  [[ ! -L "$target" ]] || fail "$name must not be a symbolic link"
  [[ -f "$target" && -r "$target" ]] \
    || fail "$name must be a readable regular file"
  [[ -O "$target" ]] || fail "$name must be owned by the current user"
  canonical_target="$(production_validation_canonical_file fail "$target")"
  [[ ! -L "$canonical_target" && -f "$canonical_target" && -r "$canonical_target" ]] \
    || fail "$name canonical target must be a readable regular file"
  [[ -O "$canonical_target" ]] || fail "$name canonical target must be owned by the current user"

  directory="$(dirname -- "$canonical_target")"
  [[ -O "$directory" ]] || fail "$name parent directory must be owned by the current user"
  directory_mode="$(production_validation_portable_mode fail "$directory")"
  file_mode="$(production_validation_portable_mode fail "$canonical_target")"
  [[ "$directory_mode" =~ ^[0-7]{3,4}$ && "$file_mode" =~ ^[0-7]{3,4}$ ]] \
    || fail "$name permissions are invalid"
  if (( (8#$directory_mode & 077) != 0 )); then
    fail "$name parent directory must not grant group or other permissions"
  fi
  if (( (8#$file_mode & 077) != 0 )); then
    fail "$name must not grant group or other permissions"
  fi

  size="$(wc -c < "$canonical_target" | tr -d '[:space:]')"
  value="$(< "$canonical_target")"
  if [[ "$size" != "64" || ! "$value" =~ ^[0-9a-f]{64}$ ]]; then
    fail "$name must contain exactly 64 lowercase hexadecimal characters without a line break"
  fi
  printf '%s' "$canonical_target"
}

production_validation_validate_boolean fail BATON_ROUND_RUNTIME_ENABLED "$round_runtime_enabled"
production_validation_validate_boolean \
  fail BATON_ROUND_PARTICIPATION_GRANT_ENABLED "$round_grant_enabled"
if [[ "$round_grant_enabled" == "true" && "$round_runtime_enabled" != "true" ]]; then
  fail "BATON_ROUND_PARTICIPATION_GRANT_ENABLED=true requires BATON_ROUND_RUNTIME_ENABLED=true"
fi

runtime_material_count=0
for value in \
  "$round_web_image" \
  "$round_signaling_image" \
  "$round_release_revision" \
  "$round_turn_urls" \
  "$round_turn_shared_secret_file"; do
  [[ -z "$value" ]] || runtime_material_count=$((runtime_material_count + 1))
done

if [[ "$round_runtime_enabled" == "true" || "$runtime_material_count" -gt 0 ]]; then
  require_value BATON_ROUND_WEB_IMAGE "$round_web_image"
  require_value BATON_ROUND_SIGNALING_IMAGE "$round_signaling_image"
  require_value BATON_ROUND_RELEASE_REVISION "$round_release_revision"
  require_value BATON_ROUND_TURN_URLS "$round_turn_urls"
  require_value BATON_ROUND_TURN_SHARED_SECRET_FILE "$round_turn_shared_secret_file"
  validate_digest_image BATON_ROUND_WEB_IMAGE "$round_web_image"
  validate_digest_image BATON_ROUND_SIGNALING_IMAGE "$round_signaling_image"
  if [[ "$round_web_image" == "$round_signaling_image" ]]; then
    fail "BATON_ROUND_WEB_IMAGE and BATON_ROUND_SIGNALING_IMAGE must use different image references"
  fi
  round_web_repository="${round_web_image%@sha256:*}"
  round_signaling_repository="${round_signaling_image%@sha256:*}"
  if [[ "${round_web_repository##*/}" != "round-baton-web" ]]; then
    fail "BATON_ROUND_WEB_IMAGE repository basename must be exactly round-baton-web"
  fi
  if [[ "${round_signaling_repository##*/}" != "round-signaling" ]]; then
    fail "BATON_ROUND_SIGNALING_IMAGE repository basename must be exactly round-signaling"
  fi
  if [[ ! "$round_release_revision" =~ ^[0-9a-f]{40}$ ]]; then
    fail "BATON_ROUND_RELEASE_REVISION must be exactly 40 lowercase hexadecimal characters"
  fi
  validate_turn_urls "$round_turn_urls"
  round_turn_shared_secret_file="$(
    validate_turn_secret_file \
      BATON_ROUND_TURN_SHARED_SECRET_FILE \
      "$round_turn_shared_secret_file"
  )"

  round_turn_shared_secret="$(< "$round_turn_shared_secret_file")"
  existing_scalar_secrets=(
    "$db_password"
    "$db_root_password"
    "$workspace_creation_key"
    "$workspace_recovery_key"
    "$watch_bearer_token"
    "$watch_receiver_bearer_token"
  )
  for existing_secret in "${existing_scalar_secrets[@]}"; do
    if [[ -n "$existing_secret" && "$round_turn_shared_secret" == "$existing_secret" ]]; then
      fail "ROUND TURN shared secret must differ from existing production secrets"
    fi
  done
  existing_scalar_secret_files=(
    "$email_outbox_encryption_key_file"
    "$cal_bearer_token_file"
    "$google_client_secret_file"
    "$naver_client_secret_file"
    "$smtp_password_file"
  )
  for existing_secret_file in "${existing_scalar_secret_files[@]}"; do
    if [[ -n "$existing_secret_file" && -f "$existing_secret_file" ]] \
      && cmp -s -- "$round_turn_shared_secret_file" "$existing_secret_file"; then
      fail "ROUND TURN shared secret must differ from authentication scalar secrets"
    fi
  done
fi
