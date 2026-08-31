#!/usr/bin/env bash

set -Eeuo pipefail
export LC_ALL=C

# 운영자가 이 검증기를 `bash -x`로 실행하더라도 인라인 또는 파일 기반 자격 증명을 노출하지 않는다.
case "$-" in
  *x*) set +x ;;
esac

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/production-validation-common.sh
source "$script_dir/production-validation-common.sh"

fail() {
  printf 'Production authentication validation failed: %s\n' "$1" >&2
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

oauth_enabled="false"
google_client_id=""
google_client_secret_file=""
naver_client_id=""
naver_client_secret_file=""
local_registration_enabled="false"
password_reset_enabled="false"
email_delivery="disabled"
email_outbox_encryption_key_file=""
email_from_address=""
smtp_host=""
smtp_port=""
smtp_username=""
smtp_password_file=""
round_enabled="false"
round_current_kid=""
round_private_key_file=""
round_public_key_file=""
round_previous_kid=""
round_previous_public_key_file=""
cal_bearer_token_file=""

db_password=""
db_root_password=""
workspace_creation_key=""
workspace_recovery_key=""
watch_bearer_token=""
watch_receiver_bearer_token=""
brief_delivery_enabled="false"
brief_bearer_token_file=""
brief_service_api_enabled="false"
brief_service_bearer_token_file=""
brief_service_truststore_file=""
if ! production_validation_parse_literal_env "$env_file"; then
  fail "$PRODUCTION_VALIDATION_ERROR"
fi

for ((env_index = 0; env_index < ${#PRODUCTION_VALIDATION_ENV_KEYS[@]}; env_index += 1)); do
  key="${PRODUCTION_VALIDATION_ENV_KEYS[$env_index]}"
  value="${PRODUCTION_VALIDATION_ENV_VALUES[$env_index]}"
  case "$key" in
    BATON_AUTH_OAUTH2_ENABLED)
      oauth_enabled="$value"
      ;;
    BATON_AUTH_OAUTH2_GOOGLE_CLIENT_ID)
      google_client_id="$value"
      ;;
    BATON_AUTH_OAUTH2_GOOGLE_CLIENT_SECRET_FILE)
      google_client_secret_file="$value"
      ;;
    BATON_AUTH_OAUTH2_NAVER_CLIENT_ID)
      naver_client_id="$value"
      ;;
    BATON_AUTH_OAUTH2_NAVER_CLIENT_SECRET_FILE)
      naver_client_secret_file="$value"
      ;;
    BATON_AUTH_LOCAL_REGISTRATION_ENABLED)
      local_registration_enabled="$value"
      ;;
    BATON_AUTH_PASSWORD_RESET_ENABLED)
      password_reset_enabled="$value"
      ;;
    BATON_EMAIL_VERIFICATION_DELIVERY)
      email_delivery="$value"
      ;;
    BATON_EMAIL_OUTBOX_ENCRYPTION_KEY_FILE)
      email_outbox_encryption_key_file="$value"
      ;;
    BATON_EMAIL_FROM_ADDRESS)
      email_from_address="$value"
      ;;
    BATON_SMTP_HOST)
      smtp_host="$value"
      ;;
    BATON_SMTP_PORT)
      smtp_port="$value"
      ;;
    BATON_SMTP_USERNAME)
      smtp_username="$value"
      ;;
    BATON_SMTP_PASSWORD_FILE)
      smtp_password_file="$value"
      ;;
    BATON_ROUND_PARTICIPATION_GRANT_ENABLED)
      round_enabled="$value"
      ;;
    BATON_ROUND_PARTICIPATION_GRANT_CURRENT_KID)
      round_current_kid="$value"
      ;;
    BATON_ROUND_PARTICIPATION_GRANT_PRIVATE_KEY_FILE)
      round_private_key_file="$value"
      ;;
    BATON_ROUND_PARTICIPATION_GRANT_PUBLIC_KEY_FILE)
      round_public_key_file="$value"
      ;;
    BATON_ROUND_PARTICIPATION_GRANT_PREVIOUS_KID)
      round_previous_kid="$value"
      ;;
    BATON_ROUND_PARTICIPATION_GRANT_PREVIOUS_PUBLIC_KEY_FILE)
      round_previous_public_key_file="$value"
      ;;
    BATON_DB_PASSWORD) db_password="$value" ;;
    BATON_DB_ROOT_PASSWORD) db_root_password="$value" ;;
    BATON_WORKSPACE_CREATION_KEY) workspace_creation_key="$value" ;;
    BATON_WORKSPACE_RECOVERY_KEY) workspace_recovery_key="$value" ;;
    BATON_CAL_BEARER_TOKEN_FILE) cal_bearer_token_file="$value" ;;
    BATON_WATCH_BEARER_TOKEN) watch_bearer_token="$value" ;;
    BATON_WATCH_EVENT_RECEIVER_BEARER_TOKEN) watch_receiver_bearer_token="$value" ;;
    BATON_BRIEF_DELIVERY_ENABLED) brief_delivery_enabled="$value" ;;
    BATON_BRIEF_BEARER_TOKEN_FILE) brief_bearer_token_file="$value" ;;
    BATON_BRIEF_SERVICE_API_ENABLED) brief_service_api_enabled="$value" ;;
    BATON_BRIEF_SERVICE_API_BEARER_TOKEN_FILE)
      brief_service_bearer_token_file="$value"
      ;;
    BATON_BRIEF_SERVICE_TRUSTSTORE_FILE) brief_service_truststore_file="$value" ;;
  esac
done

validate_identifier() {
  local name="$1"
  local value="$2"

  if [[ ${#value} -gt 512 || ! "$value" =~ ^[A-Za-z0-9._~:/+-]+$ ]]; then
    fail "$name must be 1-512 visible provider identifier characters"
  fi
}

validate_secret_file_boundary() {
  local name="$1"
  local target="$2"
  local canonical_target
  local directory
  local file_mode
  local directory_mode

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
}

validate_scalar_secret_file() {
  local name="$1"
  local target="$2"
  local size
  local invalid_bytes

  validate_secret_file_boundary "$name" "$target"
  size="$(wc -c < "$target" | tr -d '[:space:]')"
  if [[ ! "$size" =~ ^[0-9]+$ ]] || (( size < 1 || size > 512 )); then
    fail "$name must contain 1-512 bytes"
  fi
  invalid_bytes="$(LC_ALL=C tr -d '\041-\176' < "$target" | wc -c | tr -d '[:space:]')"
  if [[ "$invalid_bytes" != "0" ]]; then
    fail "$name must contain visible ASCII without spaces or line breaks"
  fi
}

validate_bearer_token_file() {
  local name="$1"
  local target="$2"
  local value

  validate_scalar_secret_file "$name" "$target"
  value="$(< "$target")"
  if [[ ${#value} -lt 32 || ${#value} -gt 200 || ! "$value" =~ ^[A-Za-z0-9._~-]+$ ]]; then
    fail "$name must contain 32-200 URL-safe ASCII characters"
  fi
}

validate_truststore_file() {
  local name="$1"
  local target="$2"

  validate_secret_file_boundary "$name" "$target"
  command -v keytool >/dev/null 2>&1 \
    || fail "keytool is required to validate the BRIEF service truststore"
  if ! keytool -list -rfc -storetype PKCS12 -storepass changeit \
    -keystore "$target" 2>/dev/null | grep -q '^-----BEGIN CERTIFICATE-----$'; then
    fail "$name must be a PKCS12 truststore with password changeit and at least one certificate"
  fi
}

validate_base64_32_byte_key() {
  local name="$1"
  local target="$2"
  local decoded_size
  local canonical_value

  validate_scalar_secret_file "$name" "$target"
  command -v openssl >/dev/null 2>&1 \
    || fail "openssl is required to validate the email outbox encryption key"
  if ! decoded_size="$(
    openssl base64 -d -A -in "$target" 2>/dev/null \
      | wc -c \
      | tr -d '[:space:]'
  )"; then
    fail "$name must contain valid Base64"
  fi
  if [[ "$decoded_size" != "32" ]]; then
    fail "$name must decode to exactly 32 bytes"
  fi
  if ! canonical_value="$(
    openssl base64 -d -A -in "$target" 2>/dev/null \
      | openssl base64 -A 2>/dev/null
  )"; then
    fail "$name must contain valid Base64"
  fi
  if [[ "$(< "$target")" != "$canonical_value" ]]; then
    fail "$name must contain canonical Base64 without line breaks"
  fi
}

validate_public_key() {
  local name="$1"
  local target="$2"
  local bits
  local size

  validate_secret_file_boundary "$name" "$target"
  size="$(wc -c < "$target" | tr -d '[:space:]')"
  if [[ ! "$size" =~ ^[0-9]+$ ]] || (( size < 256 || size > 65536 )); then
    fail "$name PEM size is invalid"
  fi
  if ! openssl rsa -pubin -in "$target" -noout >/dev/null 2>&1; then
    fail "$name must contain a valid RSA PUBLIC KEY PEM"
  fi
  bits="$(openssl pkey -pubin -in "$target" -text -noout 2>/dev/null \
    | sed -n 's/^Public-Key: (\([0-9][0-9]*\) bit)$/\1/p' \
    | head -n 1)"
  if [[ ! "$bits" =~ ^[0-9]+$ ]] || (( bits < 2048 )); then
    fail "$name must contain an RSA key of at least 2048 bits"
  fi
}

validate_private_key() {
  local name="$1"
  local target="$2"
  local first_line
  local last_line
  local size

  validate_secret_file_boundary "$name" "$target"
  size="$(wc -c < "$target" | tr -d '[:space:]')"
  if [[ ! "$size" =~ ^[0-9]+$ ]] || (( size < 512 || size > 65536 )); then
    fail "$name PEM size is invalid"
  fi
  IFS= read -r first_line < "$target" || true
  last_line="$(tail -n 1 "$target")"
  if [[ "$first_line" != "-----BEGIN PRIVATE KEY-----" \
    || "$last_line" != "-----END PRIVATE KEY-----" ]]; then
    fail "$name must use unencrypted PKCS#8 PRIVATE KEY PEM"
  fi
  if ! openssl rsa -in "$target" -check -noout >/dev/null 2>&1; then
    fail "$name must contain a valid RSA private key"
  fi
}

public_key_digest() {
  local target="$1"

  openssl pkey -pubin -in "$target" -outform DER 2>/dev/null \
    | openssl dgst -sha256 -r 2>/dev/null \
    | awk '{print $1}'
}

private_key_public_digest() {
  local target="$1"

  openssl pkey -in "$target" -pubout -outform DER 2>/dev/null \
    | openssl dgst -sha256 -r 2>/dev/null \
    | awk '{print $1}'
}

require_value() {
  local name="$1"
  local value="$2"

  [[ -n "$value" ]] || fail "$name is required by the enabled production feature"
}

production_validation_validate_boolean fail BATON_AUTH_OAUTH2_ENABLED "$oauth_enabled"
production_validation_validate_boolean \
  fail BATON_AUTH_LOCAL_REGISTRATION_ENABLED "$local_registration_enabled"
production_validation_validate_boolean \
  fail BATON_AUTH_PASSWORD_RESET_ENABLED "$password_reset_enabled"
production_validation_validate_boolean \
  fail BATON_ROUND_PARTICIPATION_GRANT_ENABLED "$round_enabled"
production_validation_validate_boolean \
  fail BATON_BRIEF_SERVICE_API_ENABLED "$brief_service_api_enabled"
require_value BATON_EMAIL_OUTBOX_ENCRYPTION_KEY_FILE "$email_outbox_encryption_key_file"
validate_base64_32_byte_key \
  BATON_EMAIL_OUTBOX_ENCRYPTION_KEY_FILE "$email_outbox_encryption_key_file"
if [[ "$brief_delivery_enabled" == "true" || -n "$brief_bearer_token_file" ]]; then
  require_value BATON_BRIEF_BEARER_TOKEN_FILE "$brief_bearer_token_file"
  validate_bearer_token_file BATON_BRIEF_BEARER_TOKEN_FILE "$brief_bearer_token_file"
fi
if [[ "$brief_service_api_enabled" == "true" \
  || -n "$brief_service_bearer_token_file" \
  || -n "$brief_service_truststore_file" ]]; then
  require_value \
    BATON_BRIEF_SERVICE_API_BEARER_TOKEN_FILE "$brief_service_bearer_token_file"
  require_value BATON_BRIEF_SERVICE_TRUSTSTORE_FILE "$brief_service_truststore_file"
  validate_bearer_token_file \
    BATON_BRIEF_SERVICE_API_BEARER_TOKEN_FILE "$brief_service_bearer_token_file"
  validate_truststore_file \
    BATON_BRIEF_SERVICE_TRUSTSTORE_FILE "$brief_service_truststore_file"
fi
if [[ -n "$brief_bearer_token_file" && -n "$brief_service_bearer_token_file" \
  && "$(< "$brief_bearer_token_file")" == "$(< "$brief_service_bearer_token_file")" ]]; then
  fail "BRIEF event delivery and service API Bearer tokens must be different"
fi
if [[ -n "$cal_bearer_token_file" ]]; then
  validate_bearer_token_file BATON_CAL_BEARER_TOKEN_FILE "$cal_bearer_token_file"
fi

oauth_material_count=0
for value in \
  "$google_client_id" \
  "$google_client_secret_file" \
  "$naver_client_id" \
  "$naver_client_secret_file"; do
  [[ -z "$value" ]] || oauth_material_count=$((oauth_material_count + 1))
done
if [[ "$oauth_enabled" == "true" || "$oauth_material_count" -gt 0 ]]; then
  require_value BATON_AUTH_OAUTH2_GOOGLE_CLIENT_ID "$google_client_id"
  require_value BATON_AUTH_OAUTH2_GOOGLE_CLIENT_SECRET_FILE "$google_client_secret_file"
  require_value BATON_AUTH_OAUTH2_NAVER_CLIENT_ID "$naver_client_id"
  require_value BATON_AUTH_OAUTH2_NAVER_CLIENT_SECRET_FILE "$naver_client_secret_file"
  validate_identifier BATON_AUTH_OAUTH2_GOOGLE_CLIENT_ID "$google_client_id"
  validate_identifier BATON_AUTH_OAUTH2_NAVER_CLIENT_ID "$naver_client_id"
  validate_scalar_secret_file \
    BATON_AUTH_OAUTH2_GOOGLE_CLIENT_SECRET_FILE "$google_client_secret_file"
  validate_scalar_secret_file \
    BATON_AUTH_OAUTH2_NAVER_CLIENT_SECRET_FILE "$naver_client_secret_file"
fi

case "$email_delivery" in
  disabled|smtp) ;;
  *) fail "BATON_EMAIL_VERIFICATION_DELIVERY must be exactly disabled or smtp" ;;
esac
if [[ "$local_registration_enabled" == "true" && "$email_delivery" != "smtp" ]]; then
  fail "BATON_AUTH_LOCAL_REGISTRATION_ENABLED=true requires SMTP delivery"
fi
if [[ "$password_reset_enabled" == "true" && "$email_delivery" != "smtp" ]]; then
  fail "BATON_AUTH_PASSWORD_RESET_ENABLED=true 설정에는 SMTP 발송 설정이 필요합니다"
fi
if [[ "$email_delivery" == "smtp" ]]; then
  require_value BATON_EMAIL_FROM_ADDRESS "$email_from_address"
  require_value BATON_SMTP_HOST "$smtp_host"
  require_value BATON_SMTP_PORT "$smtp_port"
  require_value BATON_SMTP_USERNAME "$smtp_username"
  require_value BATON_SMTP_PASSWORD_FILE "$smtp_password_file"
  if [[ ! "$email_from_address" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,63}$ ]]; then
    fail "BATON_EMAIL_FROM_ADDRESS must be a simple mailbox address"
  fi
  production_validation_is_dns_hostname "$smtp_host" \
    || fail "BATON_SMTP_HOST must be a DNS hostname without scheme, port, path, localhost, or IP"
  [[ "$smtp_port" == "587" ]] || fail "BATON_SMTP_PORT must be exactly 587"
  if [[ ${#smtp_username} -gt 320 ]]; then
    fail "BATON_SMTP_USERNAME must be 1-320 characters"
  fi
  validate_scalar_secret_file BATON_SMTP_PASSWORD_FILE "$smtp_password_file"
elif [[ -n "$email_from_address" || -n "$smtp_host" || -n "$smtp_port" \
  || -n "$smtp_username" || -n "$smtp_password_file" ]]; then
  fail "SMTP settings require BATON_EMAIL_VERIFICATION_DELIVERY=smtp"
fi

round_current_count=0
for value in "$round_current_kid" "$round_private_key_file" "$round_public_key_file"; do
  [[ -z "$value" ]] || round_current_count=$((round_current_count + 1))
done
if [[ "$round_enabled" == "true" || "$round_current_count" -gt 0 ]]; then
  require_value BATON_ROUND_PARTICIPATION_GRANT_CURRENT_KID "$round_current_kid"
  require_value BATON_ROUND_PARTICIPATION_GRANT_PRIVATE_KEY_FILE "$round_private_key_file"
  require_value BATON_ROUND_PARTICIPATION_GRANT_PUBLIC_KEY_FILE "$round_public_key_file"
  if [[ ! "$round_current_kid" =~ ^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$ ]]; then
    fail "BATON_ROUND_PARTICIPATION_GRANT_CURRENT_KID has an invalid kid"
  fi
  validate_private_key BATON_ROUND_PARTICIPATION_GRANT_PRIVATE_KEY_FILE \
    "$round_private_key_file"
  validate_public_key BATON_ROUND_PARTICIPATION_GRANT_PUBLIC_KEY_FILE \
    "$round_public_key_file"
  if [[ "$(private_key_public_digest "$round_private_key_file")" \
    != "$(public_key_digest "$round_public_key_file")" ]]; then
    fail "current ROUND private and public keys do not match"
  fi
fi

if [[ -n "$round_previous_kid" || -n "$round_previous_public_key_file" ]]; then
  require_value BATON_ROUND_PARTICIPATION_GRANT_PREVIOUS_KID "$round_previous_kid"
  require_value BATON_ROUND_PARTICIPATION_GRANT_PREVIOUS_PUBLIC_KEY_FILE \
    "$round_previous_public_key_file"
  if [[ ! "$round_previous_kid" =~ ^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$ ]]; then
    fail "BATON_ROUND_PARTICIPATION_GRANT_PREVIOUS_KID has an invalid kid"
  fi
  if [[ "$round_previous_kid" == "$round_current_kid" ]]; then
    fail "current and previous ROUND kid values must differ"
  fi
  validate_public_key BATON_ROUND_PARTICIPATION_GRANT_PREVIOUS_PUBLIC_KEY_FILE \
    "$round_previous_public_key_file"
fi

scalar_secret_files=()
scalar_secret_count=0
scalar_secret_files+=("$email_outbox_encryption_key_file")
scalar_secret_count=$((scalar_secret_count + 1))
if [[ -n "$google_client_secret_file" ]]; then
  scalar_secret_files+=("$google_client_secret_file")
  scalar_secret_count=$((scalar_secret_count + 1))
fi
if [[ -n "$naver_client_secret_file" ]]; then
  scalar_secret_files+=("$naver_client_secret_file")
  scalar_secret_count=$((scalar_secret_count + 1))
fi
if [[ -n "$smtp_password_file" ]]; then
  scalar_secret_files+=("$smtp_password_file")
  scalar_secret_count=$((scalar_secret_count + 1))
fi
if [[ -n "$brief_bearer_token_file" ]]; then
  scalar_secret_files+=("$brief_bearer_token_file")
  scalar_secret_count=$((scalar_secret_count + 1))
fi
if [[ -n "$cal_bearer_token_file" ]]; then
  scalar_secret_files+=("$cal_bearer_token_file")
  scalar_secret_count=$((scalar_secret_count + 1))
fi
if (( scalar_secret_count > 0 )); then
  for ((left = 0; left < scalar_secret_count; left += 1)); do
    for ((right = left + 1; right < scalar_secret_count; right += 1)); do
      if cmp -s -- "${scalar_secret_files[$left]}" "${scalar_secret_files[$right]}"; then
        fail "production scalar secrets must be independently generated"
      fi
    done
  done
fi

existing_secrets=(
  "$db_password"
  "$db_root_password"
  "$workspace_creation_key"
  "$workspace_recovery_key"
  "$watch_bearer_token"
  "$watch_receiver_bearer_token"
)
if (( scalar_secret_count > 0 )); then
  for secret_file in "${scalar_secret_files[@]}"; do
    secret_value="$(< "$secret_file")"
    for existing_secret in "${existing_secrets[@]}"; do
      if [[ -n "$existing_secret" && "$secret_value" == "$existing_secret" ]]; then
        fail "production scalar secrets must differ from existing production secrets"
      fi
    done
  done
fi
