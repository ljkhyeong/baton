#!/usr/bin/env bash

set -Eeuo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(dirname -- "$(dirname -- "$script_dir")")"
test_base="${TMPDIR:-/tmp}"
test_base="${test_base%/}"
test_base="$(CDPATH= cd -- "$test_base" && pwd -P)"
test_root="$(mktemp -d "$test_base/baton-pilot-readiness-test.XXXXXX")"
chmod 700 "$test_root"
BATON_PRODUCTION_LIFECYCLE_LOCK_TEST_PATH="$test_root/production-lifecycle.lock"
: > "$BATON_PRODUCTION_LIFECYCLE_LOCK_TEST_PATH"
chmod 600 "$BATON_PRODUCTION_LIFECYCLE_LOCK_TEST_PATH"

fixture_repo_root="$test_root/fixture-repo"
fixture_ops_dir="$fixture_repo_root/ops"
mkdir -p -- "$fixture_ops_dir"
for fixture_script in \
  preflight-production.sh \
  production-compose.sh \
  production-lifecycle-lock.sh \
  production-validation-common.sh \
  validate-production-env.sh \
  validate-production-auth-secrets.sh \
  validate-production-round-runtime.sh \
  verify-production-round-images.sh; do
  cp "$repo_root/ops/$fixture_script" "$fixture_ops_dir/$fixture_script"
done
cp "$repo_root/compose.production.yml" "$fixture_repo_root/compose.production.yml"
cp "$repo_root/compose.round.production.yml" "$fixture_repo_root/compose.round.production.yml"
sed \
  "s|/srv/baton/state/production-lifecycle.lock|$BATON_PRODUCTION_LIFECYCLE_LOCK_TEST_PATH|" \
  "$fixture_ops_dir/production-lifecycle-lock.sh" \
  > "$fixture_ops_dir/production-lifecycle-lock.sh.tmp"
mv "$fixture_ops_dir/production-lifecycle-lock.sh.tmp" \
  "$fixture_ops_dir/production-lifecycle-lock.sh"
chmod 700 "$fixture_ops_dir"/*.sh
preflight_script="$fixture_ops_dir/preflight-production.sh"
production_compose_script="$fixture_ops_dir/production-compose.sh"
production_env_validator_script="$fixture_ops_dir/validate-production-env.sh"
production_auth_validator_script="$fixture_ops_dir/validate-production-auth-secrets.sh"
production_round_image_verifier_script="$fixture_ops_dir/verify-production-round-images.sh"

cleanup() {
  case "$test_root" in
    "$test_base"/baton-pilot-readiness-test.*) rm -rf -- "$test_root" ;;
  esac
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

assert_contains() {
  local expected="$1"
  local actual="$2"
  local label="$3"

  [[ "$actual" == *"$expected"* ]] || fail "$label: expected output to contain '$expected'"
}

assert_not_contains() {
  local unexpected="$1"
  local actual="$2"
  local label="$3"

  [[ "$actual" != *"$unexpected"* ]] || fail "$label: output contained '$unexpected'"
}

assert_file_line_before() {
  local earlier="$1"
  local later="$2"
  local target="$3"
  local label="$4"
  local earlier_match
  local later_match
  local earlier_line
  local later_line

  earlier_match="$(grep -nF -m1 -- "$earlier" "$target")" \
    || fail "$label: missing earlier log entry '$earlier'"
  later_match="$(grep -nF -m1 -- "$later" "$target")" \
    || fail "$label: missing later log entry '$later'"
  earlier_line="${earlier_match%%:*}"
  later_line="${later_match%%:*}"
  (( earlier_line < later_line )) \
    || fail "$label: '$earlier' did not precede '$later'"
}

fake_bin="$test_root/fakebin"
mkdir -p -- "$fake_bin"

cat > "$fake_bin/docker" <<'SCRIPT'
#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "${1:-}" != "--host" || "${2:-}" != "unix:///var/run/docker.sock" ]]; then
  printf 'Docker socket boundary was not pinned: %s\n' "$*" >&2
  exit 69
fi
shift 2
printf '%s\n' "$*" >> "$FAKE_DOCKER_LOG"
for forbidden_docker_name in \
  DOCKER_HOST \
  DOCKER_CONTEXT \
  DOCKER_CONFIG \
  DOCKER_TLS_VERIFY \
  DOCKER_CERT_PATH \
  DOCKER_API_VERSION \
  DOCKER_DEFAULT_PLATFORM \
  BUILDX_BUILDER \
  BUILDX_CONFIG \
  BUILDKIT_HOST \
  DOCKER_BUILDKIT; do
  if [[ -n "${!forbidden_docker_name+x}" ]]; then
    printf 'Ambient Docker variable reached the pinned command: %s\n' \
      "$forbidden_docker_name" >&2
    exit 68
  fi
done

if [[ "${1:-}" == "info" ]]; then
  [[ "${FAKE_DOCKER_MODE:-healthy}" != "daemon-failure" ]]
  exit
fi
if [[ "${1:-}" == "compose" && "${2:-}" == "version" ]]; then
  exit 0
fi
if [[ "${1:-}" == "network" && "${2:-}" == "inspect" ]]; then
  [[ "${FAKE_DOCKER_MODE:-healthy}" != "brief-network-missing" ]] || exit 1
  if [[ "${FAKE_DOCKER_MODE:-healthy}" == "brief-network-noninternal" ]]; then
    printf 'false\n'
  else
    printf 'true\n'
  fi
  exit 0
fi
if [[ "${1:-}" == "pull" ]]; then
  [[ "${2:-}" == "--quiet" && "${3:-}" == *@sha256:* ]] || exit 75
  [[ "${FAKE_DOCKER_MODE:-healthy}" != "round-pull-failure" ]] || exit 76
  exit 0
fi
if [[ "${1:-}" == "image" && "${2:-}" == "inspect" ]]; then
  [[ "${FAKE_DOCKER_MODE:-healthy}" != "round-inspect-failure" ]] || exit 77
  if [[ "${3:-}" != "--format" ]]; then
    exit 0
  fi
  image="${5:-}"
  case "${4:-}" in
    *.RepoDigests*)
      if [[ "${FAKE_DOCKER_MODE:-healthy}" == "round-repodigest-mismatch" ]]; then
        printf 'registry.example.com/round/unexpected@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\n'
      else
        printf '%s\n' "$image"
      fi
      ;;
    *io.round.auth-mode*)
      if [[ "${FAKE_DOCKER_MODE:-healthy}" == "round-web-auth-mismatch" ]]; then
        printf 'standalone\n'
      else
        printf 'baton\n'
      fi
      ;;
    *org.opencontainers.image.revision*)
      if [[ "${FAKE_DOCKER_MODE:-healthy}" == "round-web-revision-mismatch" \
        && "$image" == *round/round-baton-web@* ]]; then
        printf 'ffffffffffffffffffffffffffffffffffffffff\n'
      elif [[ "${FAKE_DOCKER_MODE:-healthy}" == "round-signaling-revision-mismatch" \
        && "$image" == *round/round-signaling@* ]]; then
        printf 'ffffffffffffffffffffffffffffffffffffffff\n'
      else
        printf '%s\n' "${FAKE_ROUND_RELEASE_REVISION:-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa}"
      fi
      ;;
    *io.round.release.tag-object*)
      if [[ "${FAKE_DOCKER_MODE:-healthy}" == "round-empty-tag-object" ]]; then
        printf '\n'
      elif [[ "${FAKE_DOCKER_MODE:-healthy}" == "round-invalid-tag-object" ]]; then
        printf 'not-a-tag-object-hash\n'
      elif [[ "${FAKE_DOCKER_MODE:-healthy}" == "round-tag-object-mismatch" \
        && "$image" == *round/round-signaling@* ]]; then
        printf 'dddddddddddddddddddddddddddddddddddddddd\n'
      else
        printf 'cccccccccccccccccccccccccccccccccccccccc\n'
      fi
      ;;
    *) exit 78 ;;
  esac
  if [[ -n "${FAKE_MUTATE_ENV_AFTER_ATTESTATION_SOURCE:-}" \
    && -n "${FAKE_MUTATE_ENV_AFTER_ATTESTATION_REPLACEMENT:-}" \
    && "$image" == *round/round-signaling@* \
    && "${4:-}" == *io.round.release.tag-object* ]]; then
    cp -- \
      "$FAKE_MUTATE_ENV_AFTER_ATTESTATION_REPLACEMENT" \
      "$FAKE_MUTATE_ENV_AFTER_ATTESTATION_SOURCE.fake-swap"
    chmod 600 "$FAKE_MUTATE_ENV_AFTER_ATTESTATION_SOURCE.fake-swap"
    mv -- \
      "$FAKE_MUTATE_ENV_AFTER_ATTESTATION_SOURCE.fake-swap" \
      "$FAKE_MUTATE_ENV_AFTER_ATTESTATION_SOURCE"
  fi
  exit 0
fi
if [[ "${1:-}" != "compose" ]]; then
  printf 'Unexpected fake docker command: %s\n' "$*" >&2
  exit 70
fi

if [[ -n "${FAKE_EXPECTED_COMPOSE_ENV_REVISION:-}" ]]; then
  compose_arguments=("$@")
  compose_env_file=""
  for ((argument_index = 0; argument_index < ${#compose_arguments[@]}; argument_index += 1)); do
    if [[ "${compose_arguments[$argument_index]}" == "--env-file" ]]; then
      compose_env_file="${compose_arguments[$((argument_index + 1))]:-}"
      break
    fi
  done
  [[ -n "$compose_env_file" && -f "$compose_env_file" ]] || exit 81
  [[ -z "${FAKE_MUTATE_ENV_AFTER_ATTESTATION_SOURCE:-}" \
    || "$compose_env_file" != "$FAKE_MUTATE_ENV_AFTER_ATTESTATION_SOURCE" ]] || exit 82
  grep -Fxq \
    "BATON_ROUND_RELEASE_REVISION=$FAKE_EXPECTED_COMPOSE_ENV_REVISION" \
    "$compose_env_file" || exit 83
  grep -Fxq \
    "BATON_ROUND_WEB_IMAGE=$FAKE_EXPECTED_COMPOSE_ROUND_WEB_IMAGE" \
    "$compose_env_file" || exit 84
  grep -Fxq \
    "BATON_ROUND_SIGNALING_IMAGE=$FAKE_EXPECTED_COMPOSE_ROUND_SIGNALING_IMAGE" \
    "$compose_env_file" || exit 85
  if compose_env_mode="$(stat -f '%Lp' "$compose_env_file" 2>/dev/null)"; then
    :
  elif compose_env_mode="$(stat -c '%a' "$compose_env_file" 2>/dev/null)"; then
    :
  else
    exit 86
  fi
  [[ "$compose_env_mode" == "600" ]] || exit 87
fi

for forbidden_name in \
  BATON_HOST \
  BATON_DB_NAME \
  BATON_DB_USERNAME \
  BATON_DB_PASSWORD \
  BATON_DB_ROOT_PASSWORD \
  BATON_WORKSPACE_CREATION_KEY \
  BATON_WORKSPACE_RECOVERY_KEY \
  BATON_CAL_CAPTURE_ENABLED \
  BATON_CAL_BACKFILL_ENABLED \
  BATON_CAL_DELIVERY_ENABLED \
  BATON_CAL_SEASON_METADATA_ENABLED \
  BATON_CAL_SEASON_METADATA_MAINTENANCE \
  BATON_CAL_BASE_URL \
  BATON_CAL_BEARER_TOKEN \
  BATON_CAL_BEARER_TOKEN_FILE \
  BATON_WATCH_ENABLED \
  BATON_WATCH_MONITORING_ENABLED \
  BATON_WATCH_BASE_URL \
  BATON_WATCH_BEARER_TOKEN \
  BATON_WATCH_SOURCE_NAMESPACE \
	  BATON_WATCH_EVENT_RECEIVER_ENABLED \
	  BATON_WATCH_EVENT_RECEIVER_BEARER_TOKEN \
	  BATON_BRIEF_DELIVERY_ENABLED \
	  BATON_BRIEF_BASE_URL \
	  BATON_BRIEF_BEARER_TOKEN_FILE \
	  BATON_BRIEF_RECONCILIATION_INTERVAL \
	  BATON_BRIEF_SERVICE_API_ENABLED \
	  BATON_BRIEF_SERVICE_HOST \
	  BATON_BRIEF_PRIVATE_NETWORK \
	  BATON_BRIEF_SERVICE_API_BEARER_TOKEN_FILE \
	  BATON_BRIEF_SERVICE_TRUSTSTORE_FILE \
	  BATON_AUTH_OAUTH2_ENABLED \
	  BATON_AUTH_OAUTH2_GOOGLE_CLIENT_ID \
	  BATON_AUTH_OAUTH2_GOOGLE_CLIENT_SECRET_FILE \
	  BATON_AUTH_OAUTH2_NAVER_CLIENT_ID \
	  BATON_AUTH_OAUTH2_NAVER_CLIENT_SECRET_FILE \
	  BATON_AUTH_LOCAL_REGISTRATION_ENABLED \
	  BATON_EMAIL_VERIFICATION_DELIVERY \
	  BATON_EMAIL_OUTBOX_ENCRYPTION_KEY_FILE \
	  BATON_EMAIL_FROM_ADDRESS \
	  BATON_SMTP_HOST \
	  BATON_SMTP_PORT \
	  BATON_SMTP_USERNAME \
	  BATON_SMTP_PASSWORD_FILE \
	  BATON_ROUND_PARTICIPATION_GRANT_ENABLED \
	  BATON_ROUND_PARTICIPATION_GRANT_CURRENT_KID \
	  BATON_ROUND_PARTICIPATION_GRANT_PRIVATE_KEY_FILE \
	  BATON_ROUND_PARTICIPATION_GRANT_PUBLIC_KEY_FILE \
	  BATON_ROUND_PARTICIPATION_GRANT_PREVIOUS_KID \
	  BATON_ROUND_PARTICIPATION_GRANT_PREVIOUS_PUBLIC_KEY_FILE \
  BATON_ROUND_RUNTIME_ENABLED \
  BATON_ROUND_WEB_IMAGE \
  BATON_ROUND_SIGNALING_IMAGE \
	  BATON_ROUND_RELEASE_REVISION \
	  BATON_ROUND_TURN_URLS \
	  BATON_ROUND_TURN_SHARED_SECRET_FILE \
  BATON_SECRET_ROUND_TURN_SHARED_SECRET \
  BATON_HTTP_PUBLISH \
  BATON_HTTPS_TCP_PUBLISH \
  BATON_HTTPS_UDP_PUBLISH \
  COMPOSE_FILE \
  COMPOSE_ENV_FILES \
  COMPOSE_DISABLE_ENV_FILE \
  COMPOSE_PROJECT_NAME \
  COMPOSE_PROFILES \
  COMPOSE_PATH_SEPARATOR \
  COMPOSE_PARALLEL_LIMIT \
  COMPOSE_ANSI \
  COMPOSE_STATUS_STDOUT \
  COMPOSE_PROGRESS \
  COMPOSE_EXPERIMENTAL \
  COMPOSE_IGNORE_ORPHANS \
  COMPOSE_REMOVE_ORPHANS \
  COMPOSE_CONVERT_WINDOWS_PATHS; do
  if [[ -n "${!forbidden_name+x}" ]]; then
    printf 'Ambient variable reached Compose: %s\n' "$forbidden_name" >&2
    exit 71
  fi
done

if [[ "${COMPOSE_MENU:-}" != "false" ]]; then
  printf 'Production wrapper did not pin the Compose interactive menu off.\n' >&2
  exit 88
fi

for required_secret_name in \
  BATON_SECRET_GOOGLE_OAUTH_CLIENT_SECRET \
  BATON_SECRET_NAVER_OAUTH_CLIENT_SECRET \
  BATON_SECRET_CAL_BEARER_TOKEN \
  BATON_SECRET_SMTP_PASSWORD \
  BATON_SECRET_EMAIL_OUTBOX_ENCRYPTION_KEY \
  BATON_SECRET_BRIEF_BEARER_TOKEN \
  BATON_SECRET_ROUND_CURRENT_PRIVATE_KEY \
  BATON_SECRET_ROUND_CURRENT_PUBLIC_KEY \
  BATON_SECRET_ROUND_PREVIOUS_PUBLIC_KEY \
  BATON_EFFECTIVE_ROUND_UID \
  BATON_EFFECTIVE_ROUND_GID \
  BATON_EFFECTIVE_SPRING_PROFILES_ACTIVE \
  BATON_EFFECTIVE_ROUND_TURN_SHARED_SECRET_FILE \
  BATON_EFFECTIVE_SMTP_TEST_CONNECTION \
  BATON_EFFECTIVE_ROUND_PREVIOUS_PUBLIC_KEY_PATH; do
  if [[ -z "${!required_secret_name+x}" ]]; then
    printf 'Production wrapper omitted internal secret input: %s\n' \
      "$required_secret_name" >&2
    exit 73
  fi
done
if [[ -n "${FAKE_EXPECTED_BRIEF_BEARER_TOKEN:-}" \
  && "$BATON_SECRET_BRIEF_BEARER_TOKEN" != "$FAKE_EXPECTED_BRIEF_BEARER_TOKEN" ]]; then
  printf 'Production wrapper passed the wrong BRIEF Bearer token.\n' >&2
  exit 89
fi
if [[ -n "${FAKE_EXPECTED_SPRING_PROFILES_ACTIVE:-}" \
  && "$BATON_EFFECTIVE_SPRING_PROFILES_ACTIVE" \
    != "$FAKE_EXPECTED_SPRING_PROFILES_ACTIVE" ]]; then
  printf 'Production wrapper passed the wrong Spring profiles.\n' >&2
  exit 90
fi
if [[ "$BATON_EFFECTIVE_ROUND_UID" == "0" \
  || "$BATON_EFFECTIVE_ROUND_GID" == "0" \
  || ! "$BATON_EFFECTIVE_ROUND_UID" =~ ^[0-9]+$ \
  || ! "$BATON_EFFECTIVE_ROUND_GID" =~ ^[0-9]+$ ]]; then
  printf 'Production wrapper passed an invalid ROUND runtime UID/GID.\n' >&2
  exit 79
fi
if [[ -n "${FAKE_EXPECTED_ROUND_TURN_SECRET_FILE:-}" \
  && "$BATON_EFFECTIVE_ROUND_TURN_SHARED_SECRET_FILE" \
    != "$FAKE_EXPECTED_ROUND_TURN_SECRET_FILE" ]]; then
  printf 'Production wrapper passed the wrong ROUND TURN secret file.\n' >&2
  exit 80
fi

[[ "$*" == *"--project-name baton-production"* ]] || exit 72
if [[ "${FAKE_DOCKER_MODE:-healthy}" == "compose-failure" && "$*" == *"config --quiet"* ]]; then
  exit 74
fi
SCRIPT

cat > "$fake_bin/curl" <<'SCRIPT'
#!/usr/bin/env bash
set -Eeuo pipefail

body_file=""
url=""
printf '%s\n' "$*" > "$FAKE_CURL_LOG"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --output)
      body_file="$2"
      shift 2
      ;;
    --connect-timeout|--max-time|--max-filesize|--proto|--write-out)
      shift 2
      ;;
    --disable|--fail|--silent|--show-error|--tlsv1.2)
      shift
      ;;
    https://*)
      url="$1"
      shift
      ;;
    *)
      printf 'Unexpected fake curl argument: %s\n' "$1" >&2
      exit 80
      ;;
  esac
done

[[ -n "$body_file" && -n "$url" ]] || exit 81
case "${FAKE_CURL_MODE:-healthy}" in
  healthy)
    printf '{"status":"UP","groups":["liveness","readiness"]}\n' > "$body_file"
    printf '200'
    ;;
  down)
    printf '{"status":"DOWN"}\n' > "$body_file"
    printf '200'
    ;;
  redirect)
    : > "$body_file"
    printf '302'
    ;;
  trailing-garbage)
    printf '{"status":"UP"}garbage\n' > "$body_file"
    printf '200'
    ;;
  incomplete-json)
    printf '{"status":"UP",\n' > "$body_file"
    printf '200'
    ;;
  split-token)
    printf '{"sta tus":"U P"}\n' > "$body_file"
    printf '200'
    ;;
  transport-failure)
    exit 28
    ;;
  *)
    exit 82
    ;;
esac
SCRIPT

cat > "$fake_bin/flock" <<'SCRIPT'
#!/usr/bin/env bash
set -Eeuo pipefail

[[ "${1:-}" == "-n" && "${2:-}" =~ ^[0-9]+$ ]] || exit 64
if [[ -n "${FAKE_EXPECTED_LOCK_FILE:-}" ]]; then
  descriptor_path="/dev/fd/$2"
  if [[ -e "/proc/$$/fd/$2" ]]; then
    descriptor_path="/proc/$$/fd/$2"
  elif command -v lsof >/dev/null 2>&1; then
    descriptor_path="$(
      lsof -a -p "$$" -d "$2" -Fn 2>/dev/null | sed -n 's/^n//p'
    )" || exit 65
  fi
  [[ -n "$descriptor_path" && -f "$FAKE_EXPECTED_LOCK_FILE" ]] || exit 65
  [[ "$descriptor_path" -ef "$FAKE_EXPECTED_LOCK_FILE" ]] || exit 66
fi
exit "${FAKE_FLOCK_EXIT:-0}"
SCRIPT

chmod +x "$fake_bin/docker" "$fake_bin/curl" "$fake_bin/flock"

db_password="1111111111111111111111111111111111111111111111111111111111111111"
root_password="2222222222222222222222222222222222222222222222222222222222222222"
creation_key="3333333333333333333333333333333333333333333333333333333333333333"
recovery_key="4444444444444444444444444444444444444444444444444444444444444444"
cal_token="7777777777777777777777777777777777777777777777777777777777777777"
watch_token="5555555555555555555555555555555555555555555555555555555555555555"
watch_receiver_token="6666666666666666666666666666666666666666666666666666666666666666"
brief_bearer_token="brief-receiver-token-000000000000000000000001"
brief_service_bearer_token="brief-service-token-0000000000000000000000002"
google_oauth_secret="google-oauth-secret-777777777777777777777777"
naver_oauth_secret="naver-oauth-secret-8888888888888888888888888"
smtp_password="smtp-password-9999999999999999999999999999"
email_outbox_encryption_key="$(
  printf '%s' '0123456789abcdef0123456789abcdef' | openssl base64 -A
)"
round_web_digest="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
round_signaling_digest="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
replacement_round_web_digest="cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
replacement_round_signaling_digest="dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
round_release_revision="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
round_turn_urls="turn:turn.example.com:3478?transport=udp,turn:turn.example.com:3478?transport=tcp,turns:turn.example.com:5349?transport=tcp"
round_turn_shared_secret="7777777777777777777777777777777777777777777777777777777777777777"
auth_secret_dir="$test_root/auth-secrets"
mkdir -p -- "$auth_secret_dir"
chmod 700 "$auth_secret_dir"
google_oauth_secret_file="$auth_secret_dir/google-oauth"
naver_oauth_secret_file="$auth_secret_dir/naver-oauth"
cal_bearer_token_file="$auth_secret_dir/cal-bearer-token"
smtp_password_file="$auth_secret_dir/smtp-password"
email_outbox_encryption_key_file="$auth_secret_dir/email-outbox-encryption-key.base64"
brief_bearer_token_file="$auth_secret_dir/brief-bearer-token"
brief_service_bearer_token_file="$auth_secret_dir/brief-service-bearer-token"
brief_service_private_key_file="$auth_secret_dir/brief-service-private.pem"
brief_service_certificate_file="$auth_secret_dir/brief-service-certificate.pem"
brief_service_truststore_file="$auth_secret_dir/brief-service-truststore.p12"
round_turn_shared_secret_file="$auth_secret_dir/round-turn-shared-secret"
round_private_key_file="$auth_secret_dir/round-private.pem"
round_public_key_file="$auth_secret_dir/round-public.pem"
round_other_private_key_file="$auth_secret_dir/round-other-private.pem"
round_other_public_key_file="$auth_secret_dir/round-other-public.pem"
printf '%s' "$google_oauth_secret" > "$google_oauth_secret_file"
printf '%s' "$naver_oauth_secret" > "$naver_oauth_secret_file"
printf '%s' "$cal_token" > "$cal_bearer_token_file"
printf '%s' "$smtp_password" > "$smtp_password_file"
printf '%s' "$email_outbox_encryption_key" > "$email_outbox_encryption_key_file"
printf '%s' "$brief_bearer_token" > "$brief_bearer_token_file"
printf '%s' "$brief_service_bearer_token" > "$brief_service_bearer_token_file"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
  -subj '/CN=brief-service' \
  -addext 'subjectAltName=DNS:brief-service' \
  -keyout "$brief_service_private_key_file" \
  -out "$brief_service_certificate_file" >/dev/null 2>&1
keytool -importcert -noprompt -storetype PKCS12 -storepass changeit \
  -alias brief-service -file "$brief_service_certificate_file" \
  -keystore "$brief_service_truststore_file" >/dev/null 2>&1
printf '%s' "$round_turn_shared_secret" > "$round_turn_shared_secret_file"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out "$round_private_key_file" >/dev/null 2>&1
openssl pkey -in "$round_private_key_file" -pubout \
  -out "$round_public_key_file" >/dev/null 2>&1
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out "$round_other_private_key_file" >/dev/null 2>&1
openssl pkey -in "$round_other_private_key_file" -pubout \
  -out "$round_other_public_key_file" >/dev/null 2>&1
chmod 600 \
  "$google_oauth_secret_file" \
  "$naver_oauth_secret_file" \
  "$cal_bearer_token_file" \
  "$smtp_password_file" \
  "$email_outbox_encryption_key_file" \
  "$brief_bearer_token_file" \
  "$brief_service_bearer_token_file" \
  "$brief_service_private_key_file" \
  "$brief_service_certificate_file" \
  "$brief_service_truststore_file" \
  "$round_turn_shared_secret_file" \
  "$round_private_key_file" \
  "$round_public_key_file" \
  "$round_other_private_key_file" \
  "$round_other_public_key_file"

write_valid_env() {
  local target="$1"
  local host="${2:-baton.example.com}"

  printf '%s\n' \
    "BATON_HOST=$host" \
    'BATON_DB_NAME=baton' \
    'BATON_DB_USERNAME=baton' \
    "BATON_DB_PASSWORD=$db_password" \
    "BATON_DB_ROOT_PASSWORD=$root_password" \
    "BATON_WORKSPACE_CREATION_KEY=$creation_key" \
    "BATON_WORKSPACE_RECOVERY_KEY=$recovery_key" \
    "BATON_EMAIL_OUTBOX_ENCRYPTION_KEY_FILE=$email_outbox_encryption_key_file" \
    > "$target"
  chmod 600 "$target"
}

append_enabled_round_runtime() {
  local target="$1"

  printf '%s\n' \
    'BATON_ROUND_RUNTIME_ENABLED=true' \
    "BATON_ROUND_WEB_IMAGE=registry.example.com/round/round-baton-web@sha256:$round_web_digest" \
    "BATON_ROUND_SIGNALING_IMAGE=registry.example.com/round/round-signaling@sha256:$round_signaling_digest" \
    "BATON_ROUND_RELEASE_REVISION=$round_release_revision" \
    "BATON_ROUND_TURN_URLS=$round_turn_urls" \
    "BATON_ROUND_TURN_SHARED_SECRET_FILE=$round_turn_shared_secret_file" \
    >> "$target"
}

append_enabled_auth() {
  local target="$1"

  append_enabled_round_runtime "$target"
  printf '%s\n' \
    'BATON_AUTH_OAUTH2_ENABLED=true' \
    'BATON_AUTH_OAUTH2_GOOGLE_CLIENT_ID=google-client.apps.googleusercontent.com' \
    "BATON_AUTH_OAUTH2_GOOGLE_CLIENT_SECRET_FILE=$google_oauth_secret_file" \
    'BATON_AUTH_OAUTH2_NAVER_CLIENT_ID=naver-client-id' \
    "BATON_AUTH_OAUTH2_NAVER_CLIENT_SECRET_FILE=$naver_oauth_secret_file" \
    'BATON_AUTH_LOCAL_REGISTRATION_ENABLED=true' \
    'BATON_EMAIL_VERIFICATION_DELIVERY=smtp' \
    'BATON_EMAIL_FROM_ADDRESS=no-reply@example.com' \
    'BATON_SMTP_HOST=smtp.example.com' \
    'BATON_SMTP_PORT=587' \
    'BATON_SMTP_USERNAME=no-reply@example.com' \
    "BATON_SMTP_PASSWORD_FILE=$smtp_password_file" \
    'BATON_ROUND_PARTICIPATION_GRANT_ENABLED=true' \
    'BATON_ROUND_PARTICIPATION_GRANT_CURRENT_KID=round-current-2026-08' \
    "BATON_ROUND_PARTICIPATION_GRANT_PRIVATE_KEY_FILE=$round_private_key_file" \
    "BATON_ROUND_PARTICIPATION_GRANT_PUBLIC_KEY_FILE=$round_public_key_file" \
    >> "$target"
}

expect_preflight_failure() {
  local label="$1"
  local target="$2"
  local expected="$3"
  local output

  if output="$(PATH="$fake_bin:$PATH" \
    FAKE_DOCKER_LOG="$test_root/docker.log" \
    "$preflight_script" "$target" 2>&1)"; then
    fail "$label unexpectedly passed"
  fi
  assert_contains "$expected" "$output" "$label"
  assert_not_contains "$db_password" "$output" "$label secret leak"
  assert_not_contains "$root_password" "$output" "$label secret leak"
  assert_not_contains "$creation_key" "$output" "$label secret leak"
  assert_not_contains "$recovery_key" "$output" "$label secret leak"
  assert_not_contains "$cal_token" "$output" "$label secret leak"
  assert_not_contains "$watch_token" "$output" "$label secret leak"
  assert_not_contains "$watch_receiver_token" "$output" "$label secret leak"
  assert_not_contains "$brief_bearer_token" "$output" "$label BRIEF token leak"
  assert_not_contains "$google_oauth_secret" "$output" "$label Google secret leak"
  assert_not_contains "$naver_oauth_secret" "$output" "$label Naver secret leak"
  assert_not_contains "$smtp_password" "$output" "$label SMTP secret leak"
  assert_not_contains "$email_outbox_encryption_key" "$output" "$label outbox key leak"
  assert_not_contains "$round_turn_shared_secret" "$output" "$label TURN secret leak"
}

valid_env="$test_root/valid.env"
write_valid_env "$valid_env"
valid_env_canonical="$(CDPATH= cd -- "$(dirname -- "$valid_env")" && pwd -P)/$(basename -- "$valid_env")"
preflight_output="$(PATH="$fake_bin:$PATH" \
  FAKE_DOCKER_LOG="$test_root/docker.log" \
  BATON_HOST=ambient.invalid \
  BATON_DB_PASSWORD=ambient-password \
  BATON_CAL_CAPTURE_ENABLED=true \
  BATON_CAL_BACKFILL_ENABLED=true \
  BATON_CAL_DELIVERY_ENABLED=true \
  BATON_CAL_SEASON_METADATA_ENABLED=true \
  BATON_CAL_SEASON_METADATA_MAINTENANCE=REPLAY \
  BATON_CAL_BASE_URL=https://ambient-calendar.invalid \
  BATON_CAL_BEARER_TOKEN=ambient-calendar-token \
  BATON_CAL_BEARER_TOKEN_FILE=/tmp/ambient-calendar-token \
  BATON_AUTH_OAUTH2_ENABLED=true \
  BATON_EFFECTIVE_SPRING_PROFILES_ACTIVE=production,attacker \
  BATON_AUTH_OAUTH2_GOOGLE_CLIENT_SECRET_FILE=/tmp/ambient-google-secret \
  BATON_EMAIL_OUTBOX_ENCRYPTION_KEY_FILE=/tmp/ambient-outbox-key \
  BATON_ROUND_RUNTIME_ENABLED=true \
  BATON_ROUND_WEB_IMAGE=ambient.invalid/round/web:latest \
  BATON_ROUND_SIGNALING_IMAGE=ambient.invalid/round/signaling:latest \
  BATON_ROUND_RELEASE_REVISION=ambient-revision \
  BATON_ROUND_TURN_URLS='turn:ambient.invalid:3478?transport=udp' \
  BATON_ROUND_TURN_SHARED_SECRET_FILE=/tmp/ambient-turn-secret \
  BATON_SECRET_ROUND_TURN_SHARED_SECRET=ambient-round-turn-secret \
  BATON_BRIEF_DELIVERY_ENABLED=true \
  BATON_BRIEF_BASE_URL=https://ambient-brief.invalid \
  BATON_BRIEF_BEARER_TOKEN_FILE=/tmp/ambient-brief-token \
  BATON_BRIEF_RECONCILIATION_INTERVAL=PT1S \
  BATON_SECRET_BRIEF_BEARER_TOKEN=ambient-brief-token \
  BATON_HTTP_PUBLISH=127.0.0.1::80 \
  COMPOSE_ENV_FILES=/tmp/ambient.env \
  COMPOSE_PROJECT_NAME=ambient-project \
  COMPOSE_IGNORE_ORPHANS=1 \
  DOCKER_HOST=tcp://attacker.invalid:2376 \
  DOCKER_CONTEXT=attacker \
  DOCKER_CONFIG=/tmp/attacker-docker-config \
  DOCKER_TLS_VERIFY=1 \
  DOCKER_CERT_PATH=/tmp/attacker-certs \
  BUILDKIT_HOST=tcp://attacker.invalid:1234 \
  FAKE_EXPECTED_SPRING_PROFILES_ACTIVE=production \
  "$preflight_script" "$valid_env" 2>&1)" \
  || fail 'valid production preflight failed'
assert_contains 'Production preflight passed' "$preflight_output" 'valid production preflight'
assert_not_contains "$db_password" "$preflight_output" 'valid preflight secret leak'
assert_contains '--project-name baton-production' "$(cat "$test_root/docker.log")" \
  'production Compose project boundary'
assert_contains "--env-file $valid_env_canonical" "$(cat "$test_root/docker.log")" \
  'production Compose env file boundary'
assert_not_contains 'compose.round.production.yml' "$(cat "$test_root/docker.log")" \
  'disabled ROUND runtime preflight overlay selection'
assert_not_contains 'pull --quiet' "$(cat "$test_root/docker.log")" \
  'disabled ROUND runtime image pull'

round_runtime_enabled_env="$test_root/round-runtime-enabled.env"
write_valid_env "$round_runtime_enabled_env"
append_enabled_round_runtime "$round_runtime_enabled_env"
round_runtime_preflight_output="$(PATH="$fake_bin:$PATH" \
  FAKE_DOCKER_LOG="$test_root/round-runtime-docker.log" \
  FAKE_EXPECTED_ROUND_TURN_SECRET_FILE="$round_turn_shared_secret_file" \
  FAKE_ROUND_RELEASE_REVISION="$round_release_revision" \
  "$preflight_script" "$round_runtime_enabled_env" 2>&1)" \
  || fail 'enabled ROUND runtime production preflight failed'
assert_contains 'Production ROUND images verified' "$round_runtime_preflight_output" \
  'enabled ROUND runtime image verification'
assert_contains 'Production preflight passed' "$round_runtime_preflight_output" \
  'enabled ROUND runtime production preflight'
round_runtime_docker_log="$(cat "$test_root/round-runtime-docker.log")"
assert_contains 'compose.round.production.yml' "$round_runtime_docker_log" \
  'enabled ROUND runtime internal overlay selection'
assert_contains "pull --quiet registry.example.com/round/round-baton-web@sha256:$round_web_digest" \
  "$round_runtime_docker_log" \
  'enabled ROUND runtime exact web image pull'
assert_contains "pull --quiet registry.example.com/round/round-signaling@sha256:$round_signaling_digest" \
  "$round_runtime_docker_log" \
  'enabled ROUND runtime exact signaling image pull'
assert_not_contains "$round_turn_shared_secret" "$round_runtime_preflight_output" \
  'enabled ROUND runtime preflight TURN secret output'
assert_not_contains "$round_turn_shared_secret" "$round_runtime_docker_log" \
  'enabled ROUND runtime Docker arguments'

auth_enabled_env="$test_root/auth-enabled.env"
write_valid_env "$auth_enabled_env"
append_enabled_auth "$auth_enabled_env"
xtrace_env_validation_output="$(
  bash -x "$production_env_validator_script" "$auth_enabled_env" 2>&1
)" || fail 'xtrace production environment validation failed'
xtrace_auth_validation_output="$(
  bash -x "$production_auth_validator_script" "$auth_enabled_env" 2>&1
)" || fail 'xtrace production authentication validation failed'
xtrace_round_image_output="$(PATH="$fake_bin:$PATH" \
  FAKE_DOCKER_LOG="$test_root/xtrace-round-image-docker.log" \
  FAKE_ROUND_RELEASE_REVISION="$round_release_revision" \
  bash -x "$production_round_image_verifier_script" "$auth_enabled_env" 2>&1
)" || fail 'xtrace production ROUND image verification failed'
for xtrace_output in \
  "$xtrace_env_validation_output" \
  "$xtrace_auth_validation_output" \
  "$xtrace_round_image_output"; do
  assert_not_contains "$db_password" "$xtrace_output" 'xtrace DB password leak'
  assert_not_contains "$root_password" "$xtrace_output" 'xtrace root password leak'
  assert_not_contains "$creation_key" "$xtrace_output" 'xtrace creation key leak'
  assert_not_contains "$recovery_key" "$xtrace_output" 'xtrace recovery key leak'
  assert_not_contains "$google_oauth_secret" "$xtrace_output" 'xtrace Google secret leak'
  assert_not_contains "$naver_oauth_secret" "$xtrace_output" 'xtrace Naver secret leak'
  assert_not_contains "$smtp_password" "$xtrace_output" 'xtrace SMTP secret leak'
  assert_not_contains "$email_outbox_encryption_key" "$xtrace_output" \
    'xtrace outbox encryption key leak'
  assert_not_contains "$round_turn_shared_secret" "$xtrace_output" 'xtrace TURN secret leak'
done
auth_preflight_output="$(PATH="$fake_bin:$PATH" \
  FAKE_DOCKER_LOG="$test_root/auth-docker.log" \
  FAKE_EXPECTED_SPRING_PROFILES_ACTIVE=production,oauth2 \
  FAKE_EXPECTED_ROUND_TURN_SECRET_FILE="$round_turn_shared_secret_file" \
  FAKE_ROUND_RELEASE_REVISION="$round_release_revision" \
  "$preflight_script" "$auth_enabled_env" 2>&1)" \
  || fail 'enabled auth production preflight failed'
assert_contains 'Production preflight passed' "$auth_preflight_output" \
  'enabled auth production preflight'
assert_not_contains "$google_oauth_secret" "$auth_preflight_output" \
  'enabled auth Google secret output'
assert_not_contains "$naver_oauth_secret" "$auth_preflight_output" \
  'enabled auth Naver secret output'
assert_not_contains "$smtp_password" "$auth_preflight_output" \
  'enabled auth SMTP secret output'
assert_not_contains "$email_outbox_encryption_key" "$auth_preflight_output" \
  'enabled auth outbox key output'
assert_not_contains "$round_turn_shared_secret" "$auth_preflight_output" \
  'enabled auth TURN secret output'
assert_not_contains "$google_oauth_secret" "$(cat "$test_root/auth-docker.log")" \
  'enabled auth Google secret Docker arguments'
assert_not_contains "$round_turn_shared_secret" "$(cat "$test_root/auth-docker.log")" \
  'enabled auth TURN secret Docker arguments'

brief_enabled_env="$test_root/brief-enabled.env"
write_valid_env "$brief_enabled_env"
printf '%s\n' \
  'BATON_BRIEF_DELIVERY_ENABLED=true' \
  'BATON_BRIEF_BASE_URL=https://brief.example.com' \
  "BATON_BRIEF_BEARER_TOKEN_FILE=$brief_bearer_token_file" \
  'BATON_BRIEF_RECONCILIATION_INTERVAL=PT5M' \
  >> "$brief_enabled_env"
brief_preflight_output="$(PATH="$fake_bin:$PATH" \
  FAKE_DOCKER_LOG="$test_root/brief-docker.log" \
  FAKE_EXPECTED_BRIEF_BEARER_TOKEN="$brief_bearer_token" \
  "$preflight_script" "$brief_enabled_env" 2>&1)" \
  || fail 'enabled BRIEF production preflight failed'
assert_contains 'Production preflight passed' "$brief_preflight_output" \
  'enabled BRIEF production preflight'
assert_not_contains "$brief_bearer_token" "$brief_preflight_output" \
  'enabled BRIEF preflight token output'
assert_not_contains "$brief_bearer_token" "$(cat "$test_root/brief-docker.log")" \
  'enabled BRIEF Docker arguments'

brief_service_enabled_env="$test_root/brief-service-enabled.env"
write_valid_env "$brief_service_enabled_env"
printf '%s\n' \
  'BATON_BRIEF_SERVICE_API_ENABLED=true' \
  'BATON_BRIEF_SERVICE_HOST=brief-service' \
  'BATON_BRIEF_PRIVATE_NETWORK=baton-brief-private' \
  "BATON_BRIEF_SERVICE_API_BEARER_TOKEN_FILE=$brief_service_bearer_token_file" \
  "BATON_BRIEF_SERVICE_TRUSTSTORE_FILE=$brief_service_truststore_file" \
  >> "$brief_service_enabled_env"
brief_service_preflight_output="$(PATH="$fake_bin:$PATH" \
  FAKE_DOCKER_LOG="$test_root/brief-service-docker.log" \
  "$preflight_script" "$brief_service_enabled_env" 2>&1)" \
  || fail 'enabled BRIEF service API production preflight failed'
assert_contains 'Production preflight passed' "$brief_service_preflight_output" \
  'enabled BRIEF service API production preflight'
assert_contains 'compose.brief-service.production.yml' \
  "$(cat "$test_root/brief-service-docker.log")" \
  'enabled BRIEF service API Compose overlay'
assert_not_contains "$brief_service_bearer_token" "$brief_service_preflight_output" \
  'enabled BRIEF service API preflight token output'

if PATH="$fake_bin:$PATH" \
  FAKE_DOCKER_LOG="$test_root/brief-service-noninternal-docker.log" \
  FAKE_DOCKER_MODE=brief-network-noninternal \
  "$preflight_script" "$brief_service_enabled_env" >/dev/null 2>&1; then
  fail 'BRIEF service API preflight accepted a non-internal Docker network'
fi
preflight_env_output="$(PATH="$fake_bin:$PATH" \
  FAKE_DOCKER_LOG="$test_root/docker.log" \
  BATON_PRODUCTION_ENV_FILE="$valid_env_canonical" \
  "$preflight_script" 2>&1)" \
  || fail 'BATON_PRODUCTION_ENV_FILE preflight failed'
assert_contains 'Production preflight passed' "$preflight_env_output" \
  'BATON_PRODUCTION_ENV_FILE preflight'

cal_enabled_env="$test_root/cal-enabled.env"
write_valid_env "$cal_enabled_env"
printf '%s\n' \
  'BATON_CAL_SEASON_METADATA_ENABLED=true' \
  'BATON_CAL_CAPTURE_ENABLED=true' \
  'BATON_CAL_BACKFILL_ENABLED=true' \
  'BATON_CAL_DELIVERY_ENABLED=true' \
  'BATON_CAL_BASE_URL=https://calendar.example.com' \
  "BATON_CAL_BEARER_TOKEN_FILE=$cal_bearer_token_file" \
  >> "$cal_enabled_env"
cal_preflight_output="$(PATH="$fake_bin:$PATH" \
  FAKE_DOCKER_LOG="$test_root/cal-docker.log" \
  "$preflight_script" "$cal_enabled_env" 2>&1)" \
  || fail 'enabled CAL production preflight failed'
assert_contains 'Production preflight passed' "$cal_preflight_output" \
  'enabled CAL production preflight'
assert_not_contains "$cal_token" "$cal_preflight_output" \
  'enabled CAL preflight secret leak'

watch_enabled_env="$test_root/watch-enabled.env"
write_valid_env "$watch_enabled_env"
printf '%s\n' \
  'BATON_WATCH_ENABLED=true' \
  'BATON_WATCH_MONITORING_ENABLED=true' \
  'BATON_WATCH_BASE_URL=https://watch.example.com' \
  "BATON_WATCH_BEARER_TOKEN=$watch_token" \
  'BATON_WATCH_SOURCE_NAMESPACE=production' \
  >> "$watch_enabled_env"
watch_preflight_output="$(PATH="$fake_bin:$PATH" \
  FAKE_DOCKER_LOG="$test_root/watch-docker.log" \
  "$preflight_script" "$watch_enabled_env" 2>&1)" \
  || fail 'enabled WATCH production preflight failed'
assert_contains 'Production preflight passed' "$watch_preflight_output" \
  'enabled WATCH production preflight'
assert_not_contains "$watch_token" "$watch_preflight_output" \
  'enabled WATCH preflight secret leak'

watch_receiver_enabled_env="$test_root/watch-receiver-enabled.env"
write_valid_env "$watch_receiver_enabled_env"
printf '%s\n' \
  'BATON_WATCH_SOURCE_NAMESPACE=production' \
  'BATON_WATCH_EVENT_RECEIVER_ENABLED=true' \
  "BATON_WATCH_EVENT_RECEIVER_BEARER_TOKEN=$watch_receiver_token" \
  >> "$watch_receiver_enabled_env"
watch_receiver_preflight_output="$(PATH="$fake_bin:$PATH" \
  FAKE_DOCKER_LOG="$test_root/watch-receiver-docker.log" \
  "$preflight_script" "$watch_receiver_enabled_env" 2>&1)" \
  || fail 'enabled WATCH event receiver production preflight failed'
assert_contains 'Production preflight passed' "$watch_receiver_preflight_output" \
  'enabled WATCH event receiver production preflight'
assert_not_contains "$watch_receiver_token" "$watch_receiver_preflight_output" \
  'enabled WATCH event receiver preflight secret leak'

for protected_header in \
  Authorization \
  Cookie \
  Idempotency-Key \
  X-Baton-Access-Key \
  X-Baton-Creation-Key \
  X-Baton-Recovery-Key \
  X-Csrf-Token \
  X-Request-Id; do
  grep -Fq "request>headers>$protected_header delete" "$repo_root/ops/Caddyfile" \
    || fail "Caddy access log does not redact $protected_header"
done
grep -Fq 'resp_headers>Set-Cookie delete' "$repo_root/ops/Caddyfile" \
  || fail 'Caddy access log does not redact Set-Cookie'
grep -Fq 'resp_headers>Location delete' "$repo_root/ops/Caddyfile" \
  || fail 'Caddy access log does not redact OAuth redirect Location'
grep -Fq 'request>uri query {' "$repo_root/ops/Caddyfile" \
  || fail 'Caddy access log does not filter OAuth callback query values'
grep -Fq 'delete code' "$repo_root/ops/Caddyfile" \
  || fail 'Caddy access log does not delete OAuth code'
grep -Fq 'delete state' "$repo_root/ops/Caddyfile" \
  || fail 'Caddy access log does not delete OAuth state'
for exact_oauth_path in \
  /oauth2/authorization/google \
  /oauth2/authorization/naver \
  /login/oauth2/code/google \
  /login/oauth2/code/naver; do
  grep -Fq "$exact_oauth_path" "$repo_root/ops/Caddyfile" \
    || fail "Caddy does not proxy exact OAuth path: $exact_oauth_path"
done
if grep -Fq '/oauth2/*' "$repo_root/ops/Caddyfile" \
  || grep -Fq '/login/oauth2/*' "$repo_root/ops/Caddyfile"; then
  fail 'Caddy OAuth proxy matcher is broader than the configured providers'
fi
grep -Fq 'method POST' "$repo_root/ops/Caddyfile" \
  || fail 'Caddy ROUND refresh matcher does not require POST'
grep -Fq 'path_regexp roundRefresh ^/round/rooms/' "$repo_root/ops/Caddyfile" \
  || fail 'Caddy ROUND refresh matcher does not enforce a canonical room ID'
grep -Fq 'method GET' "$repo_root/ops/Caddyfile" \
  || fail 'Caddy OAuth/JWK matcher does not require GET'
grep -Fq 'path /.well-known/round-participation-jwks.json' "$repo_root/ops/Caddyfile" \
  || fail 'Caddy does not proxy the exact ROUND JWK path'
grep -Fq 'request_header -Forwarded' "$repo_root/ops/Caddyfile" \
  || fail 'Caddy does not remove untrusted Forwarded headers'
grep -Fq 'request_header -X-Forwarded-*' "$repo_root/ops/Caddyfile" \
  || fail 'Caddy does not remove untrusted X-Forwarded headers'
if grep -Fq 'header_up -X-Forwarded-*' "$repo_root/ops/Caddyfile"; then
  fail 'Caddy deletes canonical X-Forwarded headers in the proxy header operation'
fi
grep -Fq 'header_up X-Forwarded-Host {$BATON_HOST}' "$repo_root/ops/Caddyfile" \
  || fail 'Caddy does not pin the forwarded public host'
grep -Fq 'header_up X-Forwarded-Proto https' "$repo_root/ops/Caddyfile" \
  || fail 'Caddy does not pin the forwarded HTTPS scheme'
grep -Fq '@versionedAsset path /assets/*' "$repo_root/ops/Caddyfile" \
  || fail 'Caddy does not isolate versioned frontend assets'
grep -Fq 'header Cache-Control "public, max-age=31536000, immutable"' "$repo_root/ops/Caddyfile" \
  || fail 'Caddy does not cache versioned frontend assets immutably'
grep -Fq 'header Cache-Control "no-cache"' "$repo_root/ops/Caddyfile" \
  || fail 'Caddy does not revalidate the SPA entry document'

grep -Fq 'SPRING_CONFIG_IMPORT: configtree:/run/baton-config/' \
  "$repo_root/compose.production.yml" \
  || fail 'production Compose does not import scalar secrets through configtree'
grep -Fq 'target: /run/baton-config/baton.identity.email-verification.outbox-encryption-key' \
  "$repo_root/compose.production.yml" \
  || fail 'production Compose does not mount the email outbox encryption key'
grep -Fq 'target: /run/baton-config/baton.brief.bearer-token' \
  "$repo_root/compose.production.yml" \
  || fail 'production Compose does not mount the BRIEF Bearer token'
grep -Fq 'BATON_BRIEF_RECONCILIATION_INTERVAL: ${BATON_BRIEF_RECONCILIATION_INTERVAL:-false}' \
  "$repo_root/compose.production.yml" \
  || fail 'production Compose does not preserve explicit BRIEF reconciliation scheduling'
grep -Fq 'target: /run/baton-config/baton.brief.service-api.bearer-token' \
  "$repo_root/compose.brief-service.production.yml" \
  || fail 'BRIEF service Compose does not mount the separate Bearer through configtree'
grep -Fq 'target: /run/baton-keys/brief-service-truststore.p12' \
  "$repo_root/compose.brief-service.production.yml" \
  || fail 'BRIEF service Compose does not mount the TLS truststore'
grep -Fq 'target: /run/baton-keys/current-private.pem' \
  "$repo_root/compose.production.yml" \
  || fail 'production Compose does not mount the ROUND private key at a fixed path'
grep -Fq 'SERVER_SERVLET_SESSION_TIMEOUT: PT30M' \
  "$repo_root/compose.production.yml" \
  || fail 'production Compose does not pin the in-memory session timeout'
grep -Fq 'MANAGEMENT_HEALTH_MAIL_ENABLED: "false"' \
  "$repo_root/compose.production.yml" \
  || fail 'production Compose lets SMTP availability take down application health'
grep -Fq 'SERVER_FORWARD_HEADERS_STRATEGY: FRAMEWORK' \
  "$repo_root/compose.production.yml" \
  || fail 'production Compose does not use Spring-managed Caddy-sanitized forwarded headers'
grep -Fq 'BATON_CAL_CAPTURE_ENABLED: ${BATON_CAL_CAPTURE_ENABLED:-false}' \
  "$repo_root/compose.production.yml" \
  || fail 'production Compose does not forward the CAL capture gate'
grep -Fq 'BATON_CAL_BACKFILL_ENABLED: ${BATON_CAL_BACKFILL_ENABLED:-false}' \
  "$repo_root/compose.production.yml" \
  || fail 'production Compose does not forward the CAL backfill gate'
grep -Fq 'BATON_CAL_DELIVERY_ENABLED: ${BATON_CAL_DELIVERY_ENABLED:-false}' \
  "$repo_root/compose.production.yml" \
  || fail 'production Compose does not forward the CAL delivery gate'
grep -Fq 'BATON_CAL_SEASON_METADATA_MAINTENANCE: ${BATON_CAL_SEASON_METADATA_MAINTENANCE:-OFF}' \
  "$repo_root/compose.production.yml" \
  || fail 'production Compose가 CAL 시즌 이름 보정 모드를 전달하지 않습니다'
grep -Fq 'BATON_CAL_BASE_URL: ${BATON_CAL_BASE_URL:-}' \
  "$repo_root/compose.production.yml" \
  || fail 'production Compose does not forward the CAL HTTPS origin'
grep -Fq 'target: /run/baton-config/baton.calendar.bearer-token' \
  "$repo_root/compose.production.yml" \
  || fail 'production Compose does not mount the CAL Bearer token through configtree'

expect_compose_boundary_failure() {
  local label="$1"
  local output
  shift

  if output="$(PATH="$fake_bin:$PATH" \
    BATON_PRODUCTION_ENV_FILE="$valid_env_canonical" \
    FAKE_DOCKER_LOG="$test_root/docker.log" \
    "$production_compose_script" "$@" 2>&1)"; then
    fail "$label unexpectedly passed"
  fi
  assert_contains 'option' "$output" "$label rejection reason"
  if [[ "$output" != *"not allowed"* && "$output" != *"cannot be overridden"* ]]; then
    fail "$label rejection reason was not specific"
  fi
}

expect_compose_no_docker_failure() {
  local label="$1"
  local expected="$2"
  local output
  local docker_log="$test_root/rejected-$1-docker.log"
  shift 2

  rm -f -- "$docker_log"
  if output="$(PATH="$fake_bin:$PATH" \
    BATON_PRODUCTION_ENV_FILE="$valid_env_canonical" \
    FAKE_DOCKER_LOG="$docker_log" \
    "$production_compose_script" "$@" 2>&1)"; then
    fail "$label unexpectedly passed"
  fi
  assert_contains "$expected" "$output" "$label rejection reason"
  [[ ! -e "$docker_log" ]] || fail "$label reached Docker"
}

expect_compose_env_failure() {
  local label="$1"
  local target="$2"
  local expected="$3"
  local output
  local docker_log="$test_root/rejected-compose-docker.log"

  rm -f -- "$docker_log"
  if output="$(PATH="$fake_bin:$PATH" \
    BATON_PRODUCTION_ENV_FILE="$target" \
    FAKE_DOCKER_LOG="$docker_log" \
    "$production_compose_script" ps 2>&1)"; then
    fail "$label unexpectedly passed"
  fi
  assert_contains "$expected" "$output" "$label"
  assert_not_contains "$db_password" "$output" "$label secret leak"
  assert_not_contains "$root_password" "$output" "$label secret leak"
  assert_not_contains "$creation_key" "$output" "$label secret leak"
  assert_not_contains "$recovery_key" "$output" "$label secret leak"
  assert_not_contains "$google_oauth_secret" "$output" "$label Google secret leak"
  assert_not_contains "$naver_oauth_secret" "$output" "$label Naver secret leak"
  assert_not_contains "$smtp_password" "$output" "$label SMTP secret leak"
  assert_not_contains "$email_outbox_encryption_key" "$output" "$label outbox key leak"
  [[ ! -e "$docker_log" ]] || fail "$label reached Docker before environment validation"
}

expect_compose_round_image_failure() {
  local label="$1"
  local target="$2"
  local mode="$3"
  local expected="$4"
  local output
  local docker_log="$test_root/$1-docker.log"
  shift 4

  rm -f -- "$docker_log"
  if output="$(PATH="$fake_bin:$PATH" \
    BATON_PRODUCTION_ENV_FILE="$target" \
    FAKE_DOCKER_LOG="$docker_log" \
    FAKE_DOCKER_MODE="$mode" \
    FAKE_EXPECTED_LOCK_FILE="$BATON_PRODUCTION_LIFECYCLE_LOCK_TEST_PATH" \
    FAKE_EXPECTED_ROUND_TURN_SECRET_FILE="$round_turn_shared_secret_file" \
    FAKE_ROUND_RELEASE_REVISION="$round_release_revision" \
    "$production_compose_script" "$@" 2>&1)"; then
    fail "$label unexpectedly passed"
  fi
  assert_contains "$expected" "$output" "$label"
  assert_not_contains "$round_turn_shared_secret" "$output" "$label TURN secret output"
  assert_not_contains 'compose --project-directory' "$(cat "$docker_log")" \
    "$label Compose boundary"
}

expect_compose_boundary_failure 'leading project override' --project-name other ps
expect_compose_boundary_failure 'leading file override' --file other.yml ps
expect_compose_boundary_failure 'leading env override' --env-file other.env ps
expect_compose_boundary_failure 'leading directory override' --project-directory /tmp ps
expect_compose_boundary_failure 'leading profile override' --profile other ps
expect_compose_boundary_failure 'leading short file override' -f other.yml ps
expect_compose_boundary_failure 'leading short project override' -p other ps
expect_compose_boundary_failure 'trailing project override' ps --project-name other
expect_compose_boundary_failure 'logs file override' logs --file other.yml
expect_compose_boundary_failure 'logs profile override' logs --profile other
expect_compose_boundary_failure \
  'orphan cleanup disable override' \
  up --remove-orphans=false
expect_compose_boundary_failure \
  'no recreate reconciliation override' \
  up --no-recreate
expect_compose_boundary_failure \
  'boolean no recreate reconciliation override' \
  up --no-recreate=true
expect_compose_boundary_failure \
  'no start reconciliation override' \
  up --no-start
expect_compose_boundary_failure \
  'boolean no start reconciliation override' \
  up --no-start=true
expect_compose_boundary_failure \
  'production volume deletion long option' \
  down --volumes
expect_compose_boundary_failure \
  'production volume deletion short option' \
  down -v
expect_compose_boundary_failure \
  'production volume deletion combined short option' \
  down -tv
expect_compose_boundary_failure \
  'wait down-project lifecycle override' \
  wait --down-project
expect_compose_boundary_failure \
  'boolean wait down-project lifecycle override' \
  wait --down-project=true
expect_compose_boundary_failure \
  'one-off published service bypass' \
  run --detach --publish 0.0.0.0:8787:8787 --use-aliases round-signaling
expect_compose_boundary_failure \
  'Compose config environment secret output' \
  config --environment
expect_compose_boundary_failure \
  'Compose artifact environment publication' \
  publish --with-env --yes app
expect_compose_boundary_failure \
  'Compose container commit' \
  commit app
expect_compose_boundary_failure \
  'Compose config convert alias output' \
  convert
expect_compose_boundary_failure \
  'Compose bridge resolved-model output' \
  bridge convert
expect_compose_boundary_failure \
  'Compose attach signal proxy bypass' \
  attach app
expect_compose_boundary_failure \
  'Compose image push' \
  push app
expect_compose_boundary_failure \
  'Compose unknown future command' \
  future-command

for state_only_command in start restart pause unpause watch; do
  expect_compose_no_docker_failure \
    "state-only-$state_only_command" \
    'state-only lifecycle option is not allowed' \
    "$state_only_command"
done

for watch_arguments in \
  'up --watch' \
  'up --watch=true' \
  'up -w' \
  'up -w=true'; do
  read -r -a watch_parts <<< "$watch_arguments"
  expect_compose_no_docker_failure \
    "continuous-watch-${watch_arguments//[^A-Za-z0-9]/-}" \
    'continuous watch option is not allowed' \
    "${watch_parts[@]}"
done

for menu_arguments in 'up --menu' 'up --menu=true'; do
  read -r -a menu_parts <<< "$menu_arguments"
  expect_compose_no_docker_failure \
    "interactive-menu-${menu_arguments//[^A-Za-z0-9]/-}" \
    'interactive menu option is not allowed' \
    "${menu_parts[@]}"
done

for standalone_build_arguments in \
  'build' \
  'build --push' \
  'build --push=true' \
  'build --builder remote-builder' \
  'build --builder=remote-builder' \
  'build --print' \
  'build --print=true'; do
  read -r -a standalone_build_parts <<< "$standalone_build_arguments"
  expect_compose_no_docker_failure \
    "standalone-build-${standalone_build_arguments//[^A-Za-z0-9]/-}" \
    'standalone build option is not allowed' \
    "${standalone_build_parts[@]}"
done

for scale_arguments in 'scale app=2' 'up --scale app=2' 'up --scale=app=2'; do
  read -r -a scale_parts <<< "$scale_arguments"
  if scale_output="$(PATH="$fake_bin:$PATH" \
    BATON_PRODUCTION_ENV_FILE="$valid_env_canonical" \
    FAKE_DOCKER_LOG="$test_root/docker.log" \
    "$production_compose_script" "${scale_parts[@]}" 2>&1)"; then
    fail "production Compose scaling unexpectedly passed: $scale_arguments"
  fi
  assert_contains 'does not allow scaling' "$scale_output" \
    "production Compose scaling rejection: $scale_arguments"
done

run_disabled_round_compose() {
  local docker_log="$1"
  shift

  PATH="$fake_bin:$PATH" \
  BATON_PRODUCTION_ENV_FILE="$valid_env_canonical" \
  FAKE_DOCKER_LOG="$docker_log" \
  "$production_compose_script" "$@"
}

disabled_logs_docker_log="$test_root/disabled-logs-docker.log"
run_disabled_round_compose "$disabled_logs_docker_log" logs -f >/dev/null \
  || fail 'production Compose logs -f was incorrectly rejected'
assert_contains 'compose.round.production.yml' "$(cat "$disabled_logs_docker_log")" \
  'disabled ROUND runtime logs overlay selection'

for management_command in stop kill ps; do
  management_log="$test_root/disabled-$management_command-docker.log"
  run_disabled_round_compose "$management_log" "$management_command" >/dev/null \
    || fail "disabled ROUND runtime $management_command failed"
  assert_contains 'compose.round.production.yml' "$(cat "$management_log")" \
    "disabled ROUND runtime $management_command overlay selection"
done
disabled_rm_docker_log="$test_root/disabled-rm-docker.log"
run_disabled_round_compose "$disabled_rm_docker_log" rm -f >/dev/null \
  || fail 'disabled ROUND runtime rm -f failed'
assert_contains 'compose.round.production.yml' "$(cat "$disabled_rm_docker_log")" \
  'disabled ROUND runtime rm overlay selection'

disabled_up_docker_log="$test_root/disabled-up-docker.log"
run_disabled_round_compose "$disabled_up_docker_log" up -d >/dev/null \
  || fail 'disabled ROUND runtime up failed'
disabled_up_arguments="$(cat "$disabled_up_docker_log")"
assert_contains 'up --remove-orphans -d' "$disabled_up_arguments" \
  'production Compose up orphan cleanup'
assert_contains 'mysql app web' "$disabled_up_arguments" \
  'disabled ROUND runtime full base-service reconciliation'
assert_not_contains 'compose.round.production.yml' "$disabled_up_arguments" \
  'disabled ROUND runtime up overlay selection'

disabled_targeted_up_docker_log="$test_root/disabled-targeted-up-docker.log"
run_disabled_round_compose "$disabled_targeted_up_docker_log" up -d app >/dev/null \
  || fail 'disabled ROUND runtime targeted up failed'
assert_contains 'app mysql app web' "$(cat "$disabled_targeted_up_docker_log")" \
  'disabled ROUND runtime targeted up full reconciliation'

set +e
alternate_lifecycle_env="$test_root/alternate-lifecycle.env"
write_valid_env "$alternate_lifecycle_env" alternate-baton.example.com
append_enabled_round_runtime "$alternate_lifecycle_env"
lifecycle_lock_file="$BATON_PRODUCTION_LIFECYCLE_LOCK_TEST_PATH"
lifecycle_contention_docker_log="$test_root/lifecycle-contention-docker.log"
rm -f -- "$lifecycle_contention_docker_log"
lifecycle_contention_output="$(PATH="$fake_bin:$PATH" \
  BATON_PRODUCTION_ENV_FILE="$alternate_lifecycle_env" \
  FAKE_DOCKER_LOG="$lifecycle_contention_docker_log" \
  FAKE_EXPECTED_LOCK_FILE="$lifecycle_lock_file" \
  FAKE_FLOCK_EXIT=75 \
  "$production_compose_script" up -d 2>&1)"
lifecycle_contention_status=$?
set -e
if [[ "$lifecycle_contention_status" != "75" ]]; then
  fail "production lifecycle contention exit status was not 75: $lifecycle_contention_status"
fi
assert_contains 'already locked by another operation' "$lifecycle_contention_output" \
  'production lifecycle contention message'
[[ ! -e "$lifecycle_contention_docker_log" ]] \
  || fail '생명주기 잠금을 잡기 전에 ROUND 이미지 검증이 Docker에 접근했습니다'

copied_lifecycle_helper="$test_root/other-checkout/ops/production-lifecycle-lock.sh"
mkdir -p -- "$(dirname -- "$copied_lifecycle_helper")"
cp "$fixture_ops_dir/production-lifecycle-lock.sh" "$copied_lifecycle_helper"
set +e
copied_helper_contention_output="$(PATH="$fake_bin:$PATH" \
  FAKE_EXPECTED_LOCK_FILE="$lifecycle_lock_file" \
  FAKE_FLOCK_EXIT=75 \
  bash -c 'source "$1"; acquire_production_lifecycle_lock' \
  _ "$copied_lifecycle_helper" 2>&1)"
copied_helper_contention_status=$?
set -e
if [[ "$copied_helper_contention_status" != "75" ]]; then
  fail "copied checkout lifecycle contention exit status was not 75: $copied_helper_contention_status"
fi
assert_contains 'already locked by another operation' "$copied_helper_contention_output" \
  'copied checkout lifecycle contention message'

disabled_down_docker_log="$test_root/disabled-down-docker.log"
run_disabled_round_compose "$disabled_down_docker_log" down >/dev/null \
  || fail 'disabled ROUND runtime down failed'
disabled_down_arguments="$(cat "$disabled_down_docker_log")"
assert_contains 'down --remove-orphans' "$disabled_down_arguments" \
  'production Compose down orphan cleanup'
assert_contains 'compose.round.production.yml' "$disabled_down_arguments" \
  'disabled ROUND runtime down overlay selection'

for inactive_command in create pull; do
  inactive_log="$test_root/disabled-$inactive_command-docker.log"
  run_disabled_round_compose "$inactive_log" "$inactive_command" >/dev/null \
    || fail "disabled ROUND runtime $inactive_command failed"
  assert_not_contains 'compose.round.production.yml' "$(cat "$inactive_log")" \
    "disabled ROUND runtime $inactive_command overlay selection"
done
disabled_create_arguments="$(cat "$test_root/disabled-create-docker.log")"
assert_contains 'create --remove-orphans' "$disabled_create_arguments" \
  'disabled ROUND runtime create orphan cleanup'
assert_contains 'mysql app web' "$disabled_create_arguments" \
  'disabled ROUND runtime create full base-service reconciliation'
disabled_config_docker_log="$test_root/disabled-config-docker.log"
run_disabled_round_compose "$disabled_config_docker_log" config --quiet >/dev/null \
  || fail 'disabled ROUND runtime config --quiet failed'
assert_not_contains 'compose.round.production.yml' "$(cat "$disabled_config_docker_log")" \
  'disabled ROUND runtime config overlay selection'

enabled_ps_docker_log="$test_root/enabled-ps-docker.log"
PATH="$fake_bin:$PATH" \
BATON_PRODUCTION_ENV_FILE="$round_runtime_enabled_env" \
FAKE_DOCKER_LOG="$enabled_ps_docker_log" \
FAKE_EXPECTED_ROUND_TURN_SECRET_FILE="$round_turn_shared_secret_file" \
BATON_ROUND_RUNTIME_ENABLED=false \
BATON_ROUND_WEB_IMAGE=ambient.invalid/round/web:latest \
BATON_ROUND_SIGNALING_IMAGE=ambient.invalid/round/signaling:latest \
BATON_ROUND_RELEASE_REVISION=ambient-revision \
BATON_ROUND_TURN_URLS='turn:ambient.invalid:3478?transport=udp' \
BATON_ROUND_TURN_SHARED_SECRET_FILE=/tmp/ambient-turn-secret \
BATON_SECRET_ROUND_TURN_SHARED_SECRET=ambient-turn-shared-secret \
COMPOSE_IGNORE_ORPHANS=1 \
"$production_compose_script" ps >/dev/null \
  || fail 'enabled ROUND runtime ps failed'
assert_contains 'compose.round.production.yml' "$(cat "$enabled_ps_docker_log")" \
  'enabled ROUND runtime wrapper overlay selection'
assert_not_contains "$round_turn_shared_secret" "$(cat "$enabled_ps_docker_log")" \
  'enabled ROUND runtime wrapper TURN secret arguments'

enabled_targeted_up_docker_log="$test_root/enabled-targeted-up-docker.log"
PATH="$fake_bin:$PATH" \
BATON_PRODUCTION_ENV_FILE="$round_runtime_enabled_env" \
FAKE_DOCKER_LOG="$enabled_targeted_up_docker_log" \
FAKE_EXPECTED_ROUND_TURN_SECRET_FILE="$round_turn_shared_secret_file" \
FAKE_EXPECTED_LOCK_FILE="$BATON_PRODUCTION_LIFECYCLE_LOCK_TEST_PATH" \
"$production_compose_script" up -d app >/dev/null \
  || fail 'enabled ROUND runtime targeted up failed'
enabled_targeted_up_arguments="$(cat "$enabled_targeted_up_docker_log")"
assert_contains "pull --quiet registry.example.com/round/round-baton-web@sha256:$round_web_digest" \
  "$enabled_targeted_up_arguments" \
  '활성 ROUND 런타임의 직접 up 이미지 검증'
assert_contains 'compose.round.production.yml' "$enabled_targeted_up_arguments" \
  'enabled ROUND runtime targeted up overlay selection'
assert_contains 'app mysql app web round-web round-signaling' \
  "$enabled_targeted_up_arguments" \
  'enabled ROUND runtime targeted up full reconciliation'
assert_file_line_before \
  "pull --quiet registry.example.com/round/round-baton-web@sha256:$round_web_digest" \
  'compose --project-directory' \
  "$enabled_targeted_up_docker_log" \
  '활성 ROUND 런타임의 up 전 이미지 검증'

enabled_create_docker_log="$test_root/enabled-create-docker.log"
PATH="$fake_bin:$PATH" \
BATON_PRODUCTION_ENV_FILE="$round_runtime_enabled_env" \
FAKE_DOCKER_LOG="$enabled_create_docker_log" \
FAKE_EXPECTED_LOCK_FILE="$BATON_PRODUCTION_LIFECYCLE_LOCK_TEST_PATH" \
FAKE_EXPECTED_ROUND_TURN_SECRET_FILE="$round_turn_shared_secret_file" \
"$production_compose_script" create >/dev/null \
  || fail 'enabled ROUND runtime create failed'
enabled_create_arguments="$(cat "$enabled_create_docker_log")"
assert_contains 'create --remove-orphans mysql app web round-web round-signaling' \
  "$enabled_create_arguments" \
  'enabled ROUND runtime create full reconciliation'
assert_file_line_before \
  "pull --quiet registry.example.com/round/round-baton-web@sha256:$round_web_digest" \
  'compose --project-directory' \
  "$enabled_create_docker_log" \
  '활성 ROUND 런타임의 create 전 이미지 검증'

enabled_pull_docker_log="$test_root/enabled-pull-docker.log"
PATH="$fake_bin:$PATH" \
BATON_PRODUCTION_ENV_FILE="$round_runtime_enabled_env" \
FAKE_DOCKER_LOG="$enabled_pull_docker_log" \
FAKE_EXPECTED_LOCK_FILE="$BATON_PRODUCTION_LIFECYCLE_LOCK_TEST_PATH" \
FAKE_EXPECTED_ROUND_TURN_SECRET_FILE="$round_turn_shared_secret_file" \
"$production_compose_script" pull round-web >/dev/null \
  || fail 'enabled ROUND runtime pull failed'
assert_contains 'compose.round.production.yml' "$(cat "$enabled_pull_docker_log")" \
  'enabled ROUND runtime pull overlay selection'
assert_file_line_before \
  "pull --quiet registry.example.com/round/round-baton-web@sha256:$round_web_digest" \
  'compose --project-directory' \
  "$enabled_pull_docker_log" \
  '활성 ROUND 런타임의 pull 전 이미지 검증'

direct_up_unverified_env="$test_root/direct-up-unverified.env"
cp "$round_runtime_enabled_env" "$direct_up_unverified_env"
chmod 600 "$direct_up_unverified_env"
expect_compose_round_image_failure \
  'direct-up-image-mismatch' \
  "$direct_up_unverified_env" \
  round-web-auth-mismatch \
  'io.round.auth-mode=baton' \
  up -d

post_preflight_image_swap_env="$test_root/post-preflight-image-swap.env"
cp "$round_runtime_enabled_env" "$post_preflight_image_swap_env"
chmod 600 "$post_preflight_image_swap_env"
PATH="$fake_bin:$PATH" \
FAKE_DOCKER_LOG="$test_root/post-preflight-image-swap-preflight-docker.log" \
FAKE_EXPECTED_ROUND_TURN_SECRET_FILE="$round_turn_shared_secret_file" \
FAKE_ROUND_RELEASE_REVISION="$round_release_revision" \
"$preflight_script" "$post_preflight_image_swap_env" >/dev/null \
  || fail 'image swap environment did not pass its initial preflight'
sed \
  's/^BATON_ROUND_RELEASE_REVISION=.*/BATON_ROUND_RELEASE_REVISION=ffffffffffffffffffffffffffffffffffffffff/' \
  "$post_preflight_image_swap_env" > "$test_root/post-preflight-image-swap.tmp"
mv "$test_root/post-preflight-image-swap.tmp" "$post_preflight_image_swap_env"
chmod 600 "$post_preflight_image_swap_env"
expect_compose_round_image_failure \
  'post-preflight-image-swap' \
  "$post_preflight_image_swap_env" \
  healthy \
  'revision label does not match' \
  up -d

same_invocation_env="$test_root/same-invocation.env"
write_valid_env "$same_invocation_env"
append_enabled_round_runtime "$same_invocation_env"
same_invocation_replacement_env="$test_root/same-invocation-replacement.env"
sed \
  -e "s|^BATON_ROUND_WEB_IMAGE=.*|BATON_ROUND_WEB_IMAGE=registry.example.com/round/round-baton-web@sha256:$replacement_round_web_digest|" \
  -e "s|^BATON_ROUND_SIGNALING_IMAGE=.*|BATON_ROUND_SIGNALING_IMAGE=registry.example.com/round/round-signaling@sha256:$replacement_round_signaling_digest|" \
  -e 's/^BATON_ROUND_RELEASE_REVISION=.*/BATON_ROUND_RELEASE_REVISION=ffffffffffffffffffffffffffffffffffffffff/' \
  "$same_invocation_env" > "$same_invocation_replacement_env"
chmod 600 "$same_invocation_replacement_env"
same_invocation_docker_log="$test_root/same-invocation-docker.log"
PATH="$fake_bin:$PATH" \
BATON_PRODUCTION_ENV_FILE="$same_invocation_env" \
FAKE_DOCKER_LOG="$same_invocation_docker_log" \
FAKE_EXPECTED_LOCK_FILE="$BATON_PRODUCTION_LIFECYCLE_LOCK_TEST_PATH" \
FAKE_EXPECTED_ROUND_TURN_SECRET_FILE="$round_turn_shared_secret_file" \
FAKE_EXPECTED_COMPOSE_ENV_REVISION="$round_release_revision" \
FAKE_EXPECTED_COMPOSE_ROUND_WEB_IMAGE="registry.example.com/round/round-baton-web@sha256:$round_web_digest" \
FAKE_EXPECTED_COMPOSE_ROUND_SIGNALING_IMAGE="registry.example.com/round/round-signaling@sha256:$round_signaling_digest" \
FAKE_MUTATE_ENV_AFTER_ATTESTATION_SOURCE="$same_invocation_env" \
FAKE_MUTATE_ENV_AFTER_ATTESTATION_REPLACEMENT="$same_invocation_replacement_env" \
"$production_compose_script" up -d >/dev/null \
  || fail 'same-invocation environment replacement escaped the protected snapshot'
grep -Fxq \
  'BATON_ROUND_RELEASE_REVISION=ffffffffffffffffffffffffffffffffffffffff' \
  "$same_invocation_env" \
  || fail 'same-invocation environment replacement did not occur'
if compgen -G "$test_root/.production-compose.env.*" >/dev/null; then
  fail 'production Compose left a protected environment snapshot behind'
fi

expect_compose_round_image_failure \
  'pull-image-mismatch' \
  "$round_runtime_enabled_env" \
  round-signaling-revision-mismatch \
  'revision label does not match' \
  pull

mutated_env="$test_root/mutated-after-preflight.env"
write_valid_env "$mutated_env"
PATH="$fake_bin:$PATH" \
FAKE_DOCKER_LOG="$test_root/mutated-preflight-docker.log" \
"$preflight_script" "$mutated_env" >/dev/null \
  || fail 'mutable environment did not pass its initial preflight'
printf 'BATON_HTTP_PUBLISH=127.0.0.1::80\n' >> "$mutated_env"
expect_compose_env_failure \
  'environment mutated after preflight' \
  "$mutated_env" \
  'unknown or unsafe production environment key'

permission_mutated_env="$test_root/permission-mutated-after-preflight.env"
write_valid_env "$permission_mutated_env"
PATH="$fake_bin:$PATH" \
FAKE_DOCKER_LOG="$test_root/permission-preflight-docker.log" \
"$preflight_script" "$permission_mutated_env" >/dev/null \
  || fail 'permission mutation environment did not pass its initial preflight'
chmod 644 "$permission_mutated_env"
expect_compose_env_failure \
  'environment permissions changed after preflight' \
  "$permission_mutated_env" \
  'must not grant group or other permissions'

tracked_after_preflight_root="$test_root/tracked-after-preflight-repo"
mkdir -p -- "$tracked_after_preflight_root"
git -C "$tracked_after_preflight_root" init -q
tracked_after_preflight_env="$tracked_after_preflight_root/production.env"
write_valid_env "$tracked_after_preflight_env"
PATH="$fake_bin:$PATH" \
FAKE_DOCKER_LOG="$test_root/tracked-preflight-docker.log" \
"$preflight_script" "$tracked_after_preflight_env" >/dev/null \
  || fail 'tracked mutation environment did not pass its initial preflight'
git -C "$tracked_after_preflight_root" add production.env
expect_compose_env_failure \
  'environment tracked after preflight' \
  "$tracked_after_preflight_env" \
  'must not be tracked by Git'

world_readable_env="$test_root/world-readable.env"
write_valid_env "$world_readable_env"
chmod 644 "$world_readable_env"
expect_preflight_failure \
  'world-readable environment' "$world_readable_env" 'must not grant group or other permissions'

symlink_target="$test_root/symlink-target.env"
write_valid_env "$symlink_target"
symlink_env="$test_root/symlink.env"
ln -s "$symlink_target" "$symlink_env"
expect_preflight_failure \
  'symlink environment' "$symlink_env" 'must not be a symbolic link'

tracked_env_root="$test_root/tracked-env-repo"
mkdir -p -- "$tracked_env_root"
git -C "$tracked_env_root" init -q
tracked_env="$tracked_env_root/tracked.env"
write_valid_env "$tracked_env"
git -C "$tracked_env_root" add tracked.env
expect_preflight_failure 'Git-tracked environment' "$tracked_env" 'must not be tracked by Git'

duplicate_env="$test_root/duplicate.env"
write_valid_env "$duplicate_env"
printf 'BATON_HOST=other.example.com\n' >> "$duplicate_env"
expect_preflight_failure 'duplicate environment key' "$duplicate_env" 'duplicate key: BATON_HOST'

duplicate_auth_env="$test_root/duplicate-auth.env"
write_valid_env "$duplicate_auth_env"
printf '%s\n' \
  'BATON_AUTH_OAUTH2_ENABLED=false' \
  'BATON_AUTH_OAUTH2_ENABLED=false' \
  >> "$duplicate_auth_env"
expect_preflight_failure \
  'duplicate delegated authentication key' \
  "$duplicate_auth_env" \
  'duplicate key: BATON_AUTH_OAUTH2_ENABLED'

duplicate_round_env="$test_root/duplicate-round.env"
write_valid_env "$duplicate_round_env"
printf '%s\n' \
  'BATON_ROUND_PARTICIPATION_GRANT_ENABLED=false' \
  'BATON_ROUND_PARTICIPATION_GRANT_ENABLED=false' \
  >> "$duplicate_round_env"
expect_preflight_failure \
  'duplicate delegated ROUND key' \
  "$duplicate_round_env" \
  'duplicate key: BATON_ROUND_PARTICIPATION_GRANT_ENABLED'

crlf_comment_env="$test_root/crlf-comment.env"
write_valid_env "$crlf_comment_env"
printf '# comment with CRLF\r\n' >> "$crlf_comment_env"
expect_preflight_failure \
  'CRLF environment comment' \
  "$crlf_comment_env" \
  'must use LF line endings'

unknown_env="$test_root/unknown.env"
write_valid_env "$unknown_env"
printf 'BATON_HTTP_PUBLISH=127.0.0.1::80\n' >> "$unknown_env"
expect_preflight_failure \
  'unsafe publish override' "$unknown_env" 'unknown or unsafe production environment key'

quoted_env="$test_root/quoted.env"
write_valid_env "$quoted_env"
sed 's/BATON_DB_NAME=baton/BATON_DB_NAME="baton"/' "$quoted_env" > "$test_root/quoted.tmp"
mv "$test_root/quoted.tmp" "$quoted_env"
chmod 600 "$quoted_env"
expect_preflight_failure 'quoted environment value' "$quoted_env" 'simple literal KEY=VALUE'

invalid_host_env="$test_root/invalid-host.env"
write_valid_env "$invalid_host_env" 'https://baton.example.com'
expect_preflight_failure 'invalid production host' "$invalid_host_env" 'public DNS hostname'

round_grant_without_runtime_env="$test_root/round-grant-without-runtime.env"
write_valid_env "$round_grant_without_runtime_env"
printf '%s\n' 'BATON_ROUND_PARTICIPATION_GRANT_ENABLED=true' \
  >> "$round_grant_without_runtime_env"
expect_preflight_failure \
  'ROUND grant without runtime' \
  "$round_grant_without_runtime_env" \
  'requires BATON_ROUND_RUNTIME_ENABLED=true'

invalid_round_runtime_gate_env="$test_root/invalid-round-runtime-gate.env"
write_valid_env "$invalid_round_runtime_gate_env"
printf '%s\n' 'BATON_ROUND_RUNTIME_ENABLED=TRUE' >> "$invalid_round_runtime_gate_env"
expect_preflight_failure \
  'invalid ROUND runtime gate' \
  "$invalid_round_runtime_gate_env" \
  'BATON_ROUND_RUNTIME_ENABLED must be exactly true or false'

missing_round_runtime_image_env="$test_root/missing-round-runtime-image.env"
write_valid_env "$missing_round_runtime_image_env"
append_enabled_round_runtime "$missing_round_runtime_image_env"
sed '/^BATON_ROUND_SIGNALING_IMAGE=/d' \
  "$missing_round_runtime_image_env" > "$test_root/missing-round-runtime-image.tmp"
mv "$test_root/missing-round-runtime-image.tmp" "$missing_round_runtime_image_env"
chmod 600 "$missing_round_runtime_image_env"
expect_preflight_failure \
  'missing ROUND runtime image' \
  "$missing_round_runtime_image_env" \
  'BATON_ROUND_SIGNALING_IMAGE is required'

tag_round_runtime_image_env="$test_root/tag-round-runtime-image.env"
write_valid_env "$tag_round_runtime_image_env"
append_enabled_round_runtime "$tag_round_runtime_image_env"
sed 's|^BATON_ROUND_WEB_IMAGE=.*|BATON_ROUND_WEB_IMAGE=registry.example.com/round/round-baton-web:latest|' \
  "$tag_round_runtime_image_env" > "$test_root/tag-round-runtime-image.tmp"
mv "$test_root/tag-round-runtime-image.tmp" "$tag_round_runtime_image_env"
chmod 600 "$tag_round_runtime_image_env"
expect_preflight_failure \
  'tag-only ROUND runtime image' \
  "$tag_round_runtime_image_env" \
  'immutable repository@sha256:<64 lowercase hex> reference'

digest_only_round_runtime_image_env="$test_root/digest-only-round-runtime-image.env"
write_valid_env "$digest_only_round_runtime_image_env"
append_enabled_round_runtime "$digest_only_round_runtime_image_env"
sed "s|^BATON_ROUND_WEB_IMAGE=.*|BATON_ROUND_WEB_IMAGE=sha256:$round_web_digest|" \
  "$digest_only_round_runtime_image_env" > "$test_root/digest-only-round-runtime-image.tmp"
mv "$test_root/digest-only-round-runtime-image.tmp" "$digest_only_round_runtime_image_env"
chmod 600 "$digest_only_round_runtime_image_env"
expect_preflight_failure \
  'digest-only ROUND runtime image' \
  "$digest_only_round_runtime_image_env" \
  'immutable repository@sha256:<64 lowercase hex> reference'

tagged_digest_round_runtime_image_env="$test_root/tagged-digest-round-runtime-image.env"
write_valid_env "$tagged_digest_round_runtime_image_env"
append_enabled_round_runtime "$tagged_digest_round_runtime_image_env"
sed "s|^BATON_ROUND_WEB_IMAGE=.*|BATON_ROUND_WEB_IMAGE=registry.example.com/round/round-baton-web:release@sha256:$round_web_digest|" \
  "$tagged_digest_round_runtime_image_env" > "$test_root/tagged-digest-round-runtime-image.tmp"
mv "$test_root/tagged-digest-round-runtime-image.tmp" "$tagged_digest_round_runtime_image_env"
chmod 600 "$tagged_digest_round_runtime_image_env"
expect_preflight_failure \
  'tagged digest ROUND runtime image' \
  "$tagged_digest_round_runtime_image_env" \
  'immutable repository@sha256:<64 lowercase hex> reference'

uppercase_digest="$(printf '%s' "$round_web_digest" | tr 'a-f' 'A-F')"
uppercase_digest_round_runtime_image_env="$test_root/uppercase-digest-round-runtime-image.env"
write_valid_env "$uppercase_digest_round_runtime_image_env"
append_enabled_round_runtime "$uppercase_digest_round_runtime_image_env"
sed "s|^BATON_ROUND_WEB_IMAGE=.*|BATON_ROUND_WEB_IMAGE=registry.example.com/round/round-baton-web@sha256:$uppercase_digest|" \
  "$uppercase_digest_round_runtime_image_env" > "$test_root/uppercase-digest-round-runtime-image.tmp"
mv "$test_root/uppercase-digest-round-runtime-image.tmp" "$uppercase_digest_round_runtime_image_env"
chmod 600 "$uppercase_digest_round_runtime_image_env"
expect_preflight_failure \
  'uppercase digest ROUND runtime image' \
  "$uppercase_digest_round_runtime_image_env" \
  '64 lowercase hex'

wrong_round_web_repository_env="$test_root/wrong-round-web-repository.env"
write_valid_env "$wrong_round_web_repository_env"
append_enabled_round_runtime "$wrong_round_web_repository_env"
sed "s|^BATON_ROUND_WEB_IMAGE=.*|BATON_ROUND_WEB_IMAGE=registry.example.com/round/web@sha256:$round_web_digest|" \
  "$wrong_round_web_repository_env" > "$test_root/wrong-round-web-repository.tmp"
mv "$test_root/wrong-round-web-repository.tmp" "$wrong_round_web_repository_env"
chmod 600 "$wrong_round_web_repository_env"
expect_preflight_failure \
  'wrong ROUND web repository basename' \
  "$wrong_round_web_repository_env" \
  'repository basename must be exactly round-baton-web'

same_round_image_refs_env="$test_root/same-round-image-refs.env"
write_valid_env "$same_round_image_refs_env"
append_enabled_round_runtime "$same_round_image_refs_env"
sed "s|^BATON_ROUND_SIGNALING_IMAGE=.*|BATON_ROUND_SIGNALING_IMAGE=registry.example.com/round/round-baton-web@sha256:$round_web_digest|" \
  "$same_round_image_refs_env" > "$test_root/same-round-image-refs.tmp"
mv "$test_root/same-round-image-refs.tmp" "$same_round_image_refs_env"
chmod 600 "$same_round_image_refs_env"
expect_preflight_failure \
  'same ROUND image references' \
  "$same_round_image_refs_env" \
  'must use different image references'

invalid_round_revision_env="$test_root/invalid-round-revision.env"
write_valid_env "$invalid_round_revision_env"
append_enabled_round_runtime "$invalid_round_revision_env"
sed 's/^BATON_ROUND_RELEASE_REVISION=.*/BATON_ROUND_RELEASE_REVISION=ABCDEF/' \
  "$invalid_round_revision_env" > "$test_root/invalid-round-revision.tmp"
mv "$test_root/invalid-round-revision.tmp" "$invalid_round_revision_env"
chmod 600 "$invalid_round_revision_env"
expect_preflight_failure \
  'invalid ROUND release revision' \
  "$invalid_round_revision_env" \
  'exactly 40 lowercase hexadecimal characters'

credential_turn_urls_env="$test_root/credential-turn-urls.env"
write_valid_env "$credential_turn_urls_env"
append_enabled_round_runtime "$credential_turn_urls_env"
sed 's|^BATON_ROUND_TURN_URLS=.*|BATON_ROUND_TURN_URLS=turn:user@turn.example.com:3478?transport=udp,turn:turn.example.com:3478?transport=tcp,turns:turn.example.com:5349?transport=tcp|' \
  "$credential_turn_urls_env" > "$test_root/credential-turn-urls.tmp"
mv "$test_root/credential-turn-urls.tmp" "$credential_turn_urls_env"
chmod 600 "$credential_turn_urls_env"
expect_preflight_failure \
  'credential-bearing ROUND TURN URL' \
  "$credential_turn_urls_env" \
  'credential-free DNS TURN URLs'

ip_turn_urls_env="$test_root/ip-turn-urls.env"
write_valid_env "$ip_turn_urls_env"
append_enabled_round_runtime "$ip_turn_urls_env"
sed 's|^BATON_ROUND_TURN_URLS=.*|BATON_ROUND_TURN_URLS=turn:192.0.2.10:3478?transport=udp,turn:turn.example.com:3478?transport=tcp,turns:turn.example.com:5349?transport=tcp|' \
  "$ip_turn_urls_env" > "$test_root/ip-turn-urls.tmp"
mv "$test_root/ip-turn-urls.tmp" "$ip_turn_urls_env"
chmod 600 "$ip_turn_urls_env"
expect_preflight_failure \
  'IP-based ROUND TURN URL' \
  "$ip_turn_urls_env" \
  'must use DNS hostnames'

missing_turn_transport_env="$test_root/missing-turn-transport.env"
write_valid_env "$missing_turn_transport_env"
append_enabled_round_runtime "$missing_turn_transport_env"
sed 's|^BATON_ROUND_TURN_URLS=.*|BATON_ROUND_TURN_URLS=turn:turn-a.example.com:3478?transport=udp,turn:turn-b.example.com:3478?transport=udp,turns:turn.example.com:5349?transport=tcp|' \
  "$missing_turn_transport_env" > "$test_root/missing-turn-transport.tmp"
mv "$test_root/missing-turn-transport.tmp" "$missing_turn_transport_env"
chmod 600 "$missing_turn_transport_env"
expect_preflight_failure \
  'ROUND TURN URL list missing TCP route' \
  "$missing_turn_transport_env" \
  'must include a turn URL with transport=tcp'

missing_turn_secret_setting_env="$test_root/missing-turn-secret-setting.env"
write_valid_env "$missing_turn_secret_setting_env"
append_enabled_round_runtime "$missing_turn_secret_setting_env"
sed '/^BATON_ROUND_TURN_SHARED_SECRET_FILE=/d' \
  "$missing_turn_secret_setting_env" > "$test_root/missing-turn-secret-setting.tmp"
mv "$test_root/missing-turn-secret-setting.tmp" "$missing_turn_secret_setting_env"
chmod 600 "$missing_turn_secret_setting_env"
expect_preflight_failure \
  'missing ROUND TURN secret setting' \
  "$missing_turn_secret_setting_env" \
  'BATON_ROUND_TURN_SHARED_SECRET_FILE is required'

missing_turn_secret_file_env="$test_root/missing-turn-secret-file.env"
write_valid_env "$missing_turn_secret_file_env"
append_enabled_round_runtime "$missing_turn_secret_file_env"
sed "s|^BATON_ROUND_TURN_SHARED_SECRET_FILE=.*|BATON_ROUND_TURN_SHARED_SECRET_FILE=$auth_secret_dir/missing-turn-secret|" \
  "$missing_turn_secret_file_env" > "$test_root/missing-turn-secret-file.tmp"
mv "$test_root/missing-turn-secret-file.tmp" "$missing_turn_secret_file_env"
chmod 600 "$missing_turn_secret_file_env"
expect_preflight_failure \
  'missing ROUND TURN secret file' \
  "$missing_turn_secret_file_env" \
  'must be a readable regular file'

world_readable_turn_secret_file="$auth_secret_dir/world-readable-round-turn-secret"
cp "$round_turn_shared_secret_file" "$world_readable_turn_secret_file"
chmod 644 "$world_readable_turn_secret_file"
world_readable_turn_secret_env="$test_root/world-readable-round-turn-secret.env"
write_valid_env "$world_readable_turn_secret_env"
append_enabled_round_runtime "$world_readable_turn_secret_env"
sed "s|^BATON_ROUND_TURN_SHARED_SECRET_FILE=.*|BATON_ROUND_TURN_SHARED_SECRET_FILE=$world_readable_turn_secret_file|" \
  "$world_readable_turn_secret_env" > "$test_root/world-readable-round-turn-secret.tmp"
mv "$test_root/world-readable-round-turn-secret.tmp" "$world_readable_turn_secret_env"
chmod 600 "$world_readable_turn_secret_env"
expect_preflight_failure \
  'world-readable ROUND TURN secret' \
  "$world_readable_turn_secret_env" \
  'must not grant group or other permissions'

uppercase_turn_secret_file="$auth_secret_dir/uppercase-round-turn-secret"
printf '%s' 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' \
  > "$uppercase_turn_secret_file"
chmod 600 "$uppercase_turn_secret_file"
uppercase_turn_secret_env="$test_root/uppercase-round-turn-secret.env"
write_valid_env "$uppercase_turn_secret_env"
append_enabled_round_runtime "$uppercase_turn_secret_env"
sed "s|^BATON_ROUND_TURN_SHARED_SECRET_FILE=.*|BATON_ROUND_TURN_SHARED_SECRET_FILE=$uppercase_turn_secret_file|" \
  "$uppercase_turn_secret_env" > "$test_root/uppercase-round-turn-secret.tmp"
mv "$test_root/uppercase-round-turn-secret.tmp" "$uppercase_turn_secret_env"
chmod 600 "$uppercase_turn_secret_env"
expect_preflight_failure \
  'uppercase ROUND TURN secret' \
  "$uppercase_turn_secret_env" \
  'exactly 64 lowercase hexadecimal characters'

newline_turn_secret_file="$auth_secret_dir/newline-round-turn-secret"
printf '%s\n' "$round_turn_shared_secret" > "$newline_turn_secret_file"
chmod 600 "$newline_turn_secret_file"
newline_turn_secret_env="$test_root/newline-round-turn-secret.env"
write_valid_env "$newline_turn_secret_env"
append_enabled_round_runtime "$newline_turn_secret_env"
sed "s|^BATON_ROUND_TURN_SHARED_SECRET_FILE=.*|BATON_ROUND_TURN_SHARED_SECRET_FILE=$newline_turn_secret_file|" \
  "$newline_turn_secret_env" > "$test_root/newline-round-turn-secret.tmp"
mv "$test_root/newline-round-turn-secret.tmp" "$newline_turn_secret_env"
chmod 600 "$newline_turn_secret_env"
expect_preflight_failure \
  'newline ROUND TURN secret' \
  "$newline_turn_secret_env" \
  'without a line break'

reused_turn_secret_file="$auth_secret_dir/reused-round-turn-secret"
printf '%s' "$db_password" > "$reused_turn_secret_file"
chmod 600 "$reused_turn_secret_file"
reused_turn_secret_env="$test_root/reused-round-turn-secret.env"
write_valid_env "$reused_turn_secret_env"
append_enabled_round_runtime "$reused_turn_secret_env"
sed "s|^BATON_ROUND_TURN_SHARED_SECRET_FILE=.*|BATON_ROUND_TURN_SHARED_SECRET_FILE=$reused_turn_secret_file|" \
  "$reused_turn_secret_env" > "$test_root/reused-round-turn-secret.tmp"
mv "$test_root/reused-round-turn-secret.tmp" "$reused_turn_secret_env"
chmod 600 "$reused_turn_secret_env"
expect_preflight_failure \
  'ROUND TURN secret reused from database' \
  "$reused_turn_secret_env" \
  'must differ from existing production secrets'

missing_outbox_key_env="$test_root/missing-outbox-key.env"
write_valid_env "$missing_outbox_key_env"
sed '/^BATON_EMAIL_OUTBOX_ENCRYPTION_KEY_FILE=/d' \
  "$missing_outbox_key_env" > "$test_root/missing-outbox-key.tmp"
mv "$test_root/missing-outbox-key.tmp" "$missing_outbox_key_env"
chmod 600 "$missing_outbox_key_env"
expect_preflight_failure \
  'missing email outbox encryption key' \
  "$missing_outbox_key_env" \
  'BATON_EMAIL_OUTBOX_ENCRYPTION_KEY_FILE is required'

short_outbox_key_file="$auth_secret_dir/short-outbox-key.base64"
printf '%s' 'dG9vLXNob3J0' > "$short_outbox_key_file"
chmod 600 "$short_outbox_key_file"
short_outbox_key_env="$test_root/short-outbox-key.env"
write_valid_env "$short_outbox_key_env"
sed "s|^BATON_EMAIL_OUTBOX_ENCRYPTION_KEY_FILE=.*|BATON_EMAIL_OUTBOX_ENCRYPTION_KEY_FILE=$short_outbox_key_file|" \
  "$short_outbox_key_env" > "$test_root/short-outbox-key.tmp"
mv "$test_root/short-outbox-key.tmp" "$short_outbox_key_env"
chmod 600 "$short_outbox_key_env"
expect_preflight_failure \
  'short email outbox encryption key' \
  "$short_outbox_key_env" \
  'must decode to exactly 32 bytes'

noncanonical_outbox_key_file="$auth_secret_dir/noncanonical-outbox-key.base64"
printf '%s' "${email_outbox_encryption_key%Y=}Z=" > "$noncanonical_outbox_key_file"
chmod 600 "$noncanonical_outbox_key_file"
noncanonical_outbox_key_env="$test_root/noncanonical-outbox-key.env"
write_valid_env "$noncanonical_outbox_key_env"
sed "s|^BATON_EMAIL_OUTBOX_ENCRYPTION_KEY_FILE=.*|BATON_EMAIL_OUTBOX_ENCRYPTION_KEY_FILE=$noncanonical_outbox_key_file|" \
  "$noncanonical_outbox_key_env" > "$test_root/noncanonical-outbox-key.tmp"
mv "$test_root/noncanonical-outbox-key.tmp" "$noncanonical_outbox_key_env"
chmod 600 "$noncanonical_outbox_key_env"
expect_preflight_failure \
  'noncanonical email outbox encryption key' \
  "$noncanonical_outbox_key_env" \
  'must contain canonical Base64 without line breaks'

oauth_partial_env="$test_root/oauth-partial.env"
write_valid_env "$oauth_partial_env"
printf '%s\n' \
  'BATON_AUTH_OAUTH2_ENABLED=true' \
  'BATON_AUTH_OAUTH2_GOOGLE_CLIENT_ID=google-client.apps.googleusercontent.com' \
  "BATON_AUTH_OAUTH2_GOOGLE_CLIENT_SECRET_FILE=$google_oauth_secret_file" \
  >> "$oauth_partial_env"
expect_preflight_failure \
  'OAuth missing Naver pair' \
  "$oauth_partial_env" \
  'BATON_AUTH_OAUTH2_NAVER_CLIENT_ID is required'

turn_reused_google_secret_file="$auth_secret_dir/turn-reused-google-secret"
cp "$round_turn_shared_secret_file" "$turn_reused_google_secret_file"
chmod 600 "$turn_reused_google_secret_file"
turn_reused_auth_secret_env="$test_root/turn-reused-auth-secret.env"
write_valid_env "$turn_reused_auth_secret_env"
append_enabled_auth "$turn_reused_auth_secret_env"
sed "s|^BATON_AUTH_OAUTH2_GOOGLE_CLIENT_SECRET_FILE=.*|BATON_AUTH_OAUTH2_GOOGLE_CLIENT_SECRET_FILE=$turn_reused_google_secret_file|" \
  "$turn_reused_auth_secret_env" > "$test_root/turn-reused-auth-secret.tmp"
mv "$test_root/turn-reused-auth-secret.tmp" "$turn_reused_auth_secret_env"
chmod 600 "$turn_reused_auth_secret_env"
expect_preflight_failure \
  'ROUND TURN secret reused as authentication scalar' \
  "$turn_reused_auth_secret_env" \
  'must differ from authentication scalar secrets'

local_gate_without_smtp_env="$test_root/local-gate-without-smtp.env"
write_valid_env "$local_gate_without_smtp_env"
printf '%s\n' \
  'BATON_AUTH_LOCAL_REGISTRATION_ENABLED=true' \
  'BATON_EMAIL_VERIFICATION_DELIVERY=disabled' \
  >> "$local_gate_without_smtp_env"
expect_preflight_failure \
  'local registration without SMTP' \
  "$local_gate_without_smtp_env" \
  'requires SMTP delivery'

smtp_insecure_port_env="$test_root/smtp-insecure-port.env"
write_valid_env "$smtp_insecure_port_env"
append_enabled_auth "$smtp_insecure_port_env"
sed 's/^BATON_SMTP_PORT=587$/BATON_SMTP_PORT=25/' \
  "$smtp_insecure_port_env" > "$test_root/smtp-insecure-port.tmp"
mv "$test_root/smtp-insecure-port.tmp" "$smtp_insecure_port_env"
chmod 600 "$smtp_insecure_port_env"
expect_preflight_failure \
  'SMTP insecure port' "$smtp_insecure_port_env" 'BATON_SMTP_PORT must be exactly 587'

smtp_ip_host_env="$test_root/smtp-ip-host.env"
write_valid_env "$smtp_ip_host_env"
append_enabled_auth "$smtp_ip_host_env"
sed 's/^BATON_SMTP_HOST=smtp.example.com$/BATON_SMTP_HOST=192.0.2.25/' \
  "$smtp_ip_host_env" > "$test_root/smtp-ip-host.tmp"
mv "$test_root/smtp-ip-host.tmp" "$smtp_ip_host_env"
chmod 600 "$smtp_ip_host_env"
expect_preflight_failure \
  'IP-based SMTP hostname' \
  "$smtp_ip_host_env" \
  'BATON_SMTP_HOST must be a DNS hostname'

newline_google_secret_file="$auth_secret_dir/google-oauth-newline"
printf '%s\n' "$google_oauth_secret" > "$newline_google_secret_file"
chmod 600 "$newline_google_secret_file"
newline_secret_env="$test_root/newline-auth-secret.env"
write_valid_env "$newline_secret_env"
append_enabled_auth "$newline_secret_env"
sed "s|^BATON_AUTH_OAUTH2_GOOGLE_CLIENT_SECRET_FILE=.*|BATON_AUTH_OAUTH2_GOOGLE_CLIENT_SECRET_FILE=$newline_google_secret_file|" \
  "$newline_secret_env" > "$test_root/newline-auth-secret.tmp"
mv "$test_root/newline-auth-secret.tmp" "$newline_secret_env"
chmod 600 "$newline_secret_env"
expect_preflight_failure \
  'newline authentication secret' "$newline_secret_env" 'without spaces or line breaks'

world_readable_google_secret_file="$auth_secret_dir/google-oauth-world-readable"
cp "$google_oauth_secret_file" "$world_readable_google_secret_file"
chmod 644 "$world_readable_google_secret_file"
world_readable_auth_secret_env="$test_root/world-readable-auth-secret.env"
write_valid_env "$world_readable_auth_secret_env"
append_enabled_auth "$world_readable_auth_secret_env"
sed "s|^BATON_AUTH_OAUTH2_GOOGLE_CLIENT_SECRET_FILE=.*|BATON_AUTH_OAUTH2_GOOGLE_CLIENT_SECRET_FILE=$world_readable_google_secret_file|" \
  "$world_readable_auth_secret_env" > "$test_root/world-readable-auth-secret.tmp"
mv "$test_root/world-readable-auth-secret.tmp" "$world_readable_auth_secret_env"
chmod 600 "$world_readable_auth_secret_env"
expect_preflight_failure \
  'world-readable authentication secret' \
  "$world_readable_auth_secret_env" \
  'must not grant group or other permissions'

symlink_google_secret_file="$auth_secret_dir/google-oauth-symlink"
ln -s "$google_oauth_secret_file" "$symlink_google_secret_file"
symlink_auth_secret_env="$test_root/symlink-auth-secret.env"
write_valid_env "$symlink_auth_secret_env"
append_enabled_auth "$symlink_auth_secret_env"
sed "s|^BATON_AUTH_OAUTH2_GOOGLE_CLIENT_SECRET_FILE=.*|BATON_AUTH_OAUTH2_GOOGLE_CLIENT_SECRET_FILE=$symlink_google_secret_file|" \
  "$symlink_auth_secret_env" > "$test_root/symlink-auth-secret.tmp"
mv "$test_root/symlink-auth-secret.tmp" "$symlink_auth_secret_env"
chmod 600 "$symlink_auth_secret_env"
expect_preflight_failure \
  'symlink authentication secret' "$symlink_auth_secret_env" 'must not be a symbolic link'

mismatched_round_key_env="$test_root/mismatched-round-key.env"
write_valid_env "$mismatched_round_key_env"
append_enabled_auth "$mismatched_round_key_env"
sed "s|^BATON_ROUND_PARTICIPATION_GRANT_PUBLIC_KEY_FILE=.*|BATON_ROUND_PARTICIPATION_GRANT_PUBLIC_KEY_FILE=$round_other_public_key_file|" \
  "$mismatched_round_key_env" > "$test_root/mismatched-round-key.tmp"
mv "$test_root/mismatched-round-key.tmp" "$mismatched_round_key_env"
chmod 600 "$mismatched_round_key_env"
expect_preflight_failure \
  'mismatched ROUND key pair' "$mismatched_round_key_env" 'do not match'

round_dsa_parameters_file="$auth_secret_dir/round-dsa-parameters.pem"
round_dsa_private_key_file="$auth_secret_dir/round-dsa-private.pem"
round_dsa_public_key_file="$auth_secret_dir/round-dsa-public.pem"
openssl genpkey -genparam -algorithm DSA -pkeyopt dsa_paramgen_bits:2048 \
  -out "$round_dsa_parameters_file" >/dev/null 2>&1
openssl genpkey -paramfile "$round_dsa_parameters_file" \
  -out "$round_dsa_private_key_file" >/dev/null 2>&1
openssl pkey -in "$round_dsa_private_key_file" -pubout \
  -out "$round_dsa_public_key_file" >/dev/null 2>&1
chmod 600 \
  "$round_dsa_parameters_file" \
  "$round_dsa_private_key_file" \
  "$round_dsa_public_key_file"
non_rsa_round_key_env="$test_root/non-rsa-round-key.env"
write_valid_env "$non_rsa_round_key_env"
append_enabled_auth "$non_rsa_round_key_env"
sed \
  -e "s|^BATON_ROUND_PARTICIPATION_GRANT_PRIVATE_KEY_FILE=.*|BATON_ROUND_PARTICIPATION_GRANT_PRIVATE_KEY_FILE=$round_dsa_private_key_file|" \
  -e "s|^BATON_ROUND_PARTICIPATION_GRANT_PUBLIC_KEY_FILE=.*|BATON_ROUND_PARTICIPATION_GRANT_PUBLIC_KEY_FILE=$round_dsa_public_key_file|" \
  "$non_rsa_round_key_env" > "$test_root/non-rsa-round-key.tmp"
mv "$test_root/non-rsa-round-key.tmp" "$non_rsa_round_key_env"
chmod 600 "$non_rsa_round_key_env"
expect_preflight_failure \
  'non-RSA ROUND key pair' "$non_rsa_round_key_env" 'valid RSA private key'

non_rsa_round_public_key_env="$test_root/non-rsa-round-public-key.env"
write_valid_env "$non_rsa_round_public_key_env"
append_enabled_auth "$non_rsa_round_public_key_env"
sed "s|^BATON_ROUND_PARTICIPATION_GRANT_PUBLIC_KEY_FILE=.*|BATON_ROUND_PARTICIPATION_GRANT_PUBLIC_KEY_FILE=$round_dsa_public_key_file|" \
  "$non_rsa_round_public_key_env" > "$test_root/non-rsa-round-public-key.tmp"
mv "$test_root/non-rsa-round-public-key.tmp" "$non_rsa_round_public_key_env"
chmod 600 "$non_rsa_round_public_key_env"
expect_preflight_failure \
  'non-RSA ROUND public key' \
  "$non_rsa_round_public_key_env" \
  'valid RSA PUBLIC KEY PEM'

partial_previous_round_key_env="$test_root/partial-previous-round-key.env"
write_valid_env "$partial_previous_round_key_env"
append_enabled_auth "$partial_previous_round_key_env"
printf '%s\n' 'BATON_ROUND_PARTICIPATION_GRANT_PREVIOUS_KID=round-old-2026-07' \
  >> "$partial_previous_round_key_env"
expect_preflight_failure \
  'partial previous ROUND key' \
  "$partial_previous_round_key_env" \
  'BATON_ROUND_PARTICIPATION_GRANT_PREVIOUS_PUBLIC_KEY_FILE is required'

cal_missing_token_env="$test_root/cal-missing-token.env"
invalid_cal_metadata_env="$test_root/invalid-cal-metadata.env"
write_valid_env "$invalid_cal_metadata_env"
printf '%s\n' 'BATON_CAL_SEASON_METADATA_ENABLED=maybe' >> "$invalid_cal_metadata_env"
expect_preflight_failure \
  'CAL 시즌 이름 설정 오류' "$invalid_cal_metadata_env" 'BATON_CAL_SEASON_METADATA_ENABLED'

write_valid_env "$cal_missing_token_env"
printf '%s\n' \
  'BATON_CAL_DELIVERY_ENABLED=true' \
  'BATON_CAL_BASE_URL=https://calendar.example.com' \
  >> "$cal_missing_token_env"
expect_preflight_failure \
  'CAL missing token' "$cal_missing_token_env" 'BATON_CAL_BEARER_TOKEN_FILE is required'

short_cal_token_file="$auth_secret_dir/short-cal-bearer-token"
printf '%s' 'too-short' > "$short_cal_token_file"
chmod 600 "$short_cal_token_file"
short_cal_token_env="$test_root/short-cal-token.env"
write_valid_env "$short_cal_token_env"
printf '%s\n' \
  'BATON_CAL_DELIVERY_ENABLED=true' \
  'BATON_CAL_BASE_URL=https://calendar.example.com' \
  "BATON_CAL_BEARER_TOKEN_FILE=$short_cal_token_file" \
  >> "$short_cal_token_env"
expect_preflight_failure \
  'CAL short token' "$short_cal_token_env" 'must contain 32-200 URL-safe ASCII characters'

cal_http_env="$test_root/cal-http.env"
write_valid_env "$cal_http_env"
printf '%s\n' \
  'BATON_CAL_DELIVERY_ENABLED=true' \
  'BATON_CAL_BASE_URL=http://calendar.example.com' \
  "BATON_CAL_BEARER_TOKEN_FILE=$cal_bearer_token_file" \
  >> "$cal_http_env"
expect_preflight_failure \
  'CAL insecure URL' "$cal_http_env" 'absolute HTTPS origin'

for invalid_cal_port in 00000 99999; do
  invalid_cal_port_env="$test_root/cal-invalid-port-$invalid_cal_port.env"
  write_valid_env "$invalid_cal_port_env"
  printf '%s\n' \
    'BATON_CAL_DELIVERY_ENABLED=true' \
    "BATON_CAL_BASE_URL=https://calendar.example.com:$invalid_cal_port" \
    "BATON_CAL_BEARER_TOKEN_FILE=$cal_bearer_token_file" \
    >> "$invalid_cal_port_env"
  expect_preflight_failure \
    "CAL invalid port $invalid_cal_port" \
    "$invalid_cal_port_env" \
    '포트는 1~65535 범위여야 합니다'
done

watch_missing_token_env="$test_root/watch-missing-token.env"
write_valid_env "$watch_missing_token_env"
printf '%s\n' \
  'BATON_WATCH_ENABLED=true' \
  'BATON_WATCH_BASE_URL=https://watch.example.com' \
  'BATON_WATCH_SOURCE_NAMESPACE=production' \
  >> "$watch_missing_token_env"
expect_preflight_failure \
  'WATCH missing token' "$watch_missing_token_env" 'BATON_WATCH_BEARER_TOKEN is required'

watch_http_env="$test_root/watch-http.env"
write_valid_env "$watch_http_env"
printf '%s\n' \
  'BATON_WATCH_ENABLED=true' \
  'BATON_WATCH_BASE_URL=http://watch.example.com' \
  "BATON_WATCH_BEARER_TOKEN=$watch_token" \
  'BATON_WATCH_SOURCE_NAMESPACE=production' \
  >> "$watch_http_env"
expect_preflight_failure \
  'WATCH insecure URL' "$watch_http_env" 'absolute HTTPS origin'

watch_receiver_missing_token_env="$test_root/watch-receiver-missing-token.env"
write_valid_env "$watch_receiver_missing_token_env"
printf '%s\n' \
  'BATON_WATCH_SOURCE_NAMESPACE=production' \
  'BATON_WATCH_EVENT_RECEIVER_ENABLED=true' \
  >> "$watch_receiver_missing_token_env"
expect_preflight_failure \
  'WATCH event receiver missing token' \
  "$watch_receiver_missing_token_env" \
  'BATON_WATCH_EVENT_RECEIVER_BEARER_TOKEN is required'

watch_receiver_missing_namespace_env="$test_root/watch-receiver-missing-namespace.env"
write_valid_env "$watch_receiver_missing_namespace_env"
printf '%s\n' \
  'BATON_WATCH_EVENT_RECEIVER_ENABLED=true' \
  "BATON_WATCH_EVENT_RECEIVER_BEARER_TOKEN=$watch_receiver_token" \
  >> "$watch_receiver_missing_namespace_env"
expect_preflight_failure \
  'WATCH event receiver missing source namespace' \
  "$watch_receiver_missing_namespace_env" \
  'BATON_WATCH_SOURCE_NAMESPACE is required'

brief_missing_token_file_env="$test_root/brief-missing-token-file.env"
write_valid_env "$brief_missing_token_file_env"
printf '%s\n' \
  'BATON_BRIEF_DELIVERY_ENABLED=true' \
  'BATON_BRIEF_BASE_URL=https://brief.example.com' \
  >> "$brief_missing_token_file_env"
expect_preflight_failure \
  'BRIEF missing token file' \
  "$brief_missing_token_file_env" \
  'BATON_BRIEF_BEARER_TOKEN_FILE is required'

brief_http_env="$test_root/brief-http.env"
write_valid_env "$brief_http_env"
printf '%s\n' \
  'BATON_BRIEF_DELIVERY_ENABLED=true' \
  'BATON_BRIEF_BASE_URL=http://brief.example.com' \
  "BATON_BRIEF_BEARER_TOKEN_FILE=$brief_bearer_token_file" \
  >> "$brief_http_env"
expect_preflight_failure \
  'BRIEF insecure URL' \
  "$brief_http_env" \
  'absolute HTTPS origin'

for invalid_brief_port in 00000 99999; do
  invalid_brief_port_env="$test_root/brief-invalid-port-$invalid_brief_port.env"
  write_valid_env "$invalid_brief_port_env"
  printf '%s\n' \
    'BATON_BRIEF_DELIVERY_ENABLED=true' \
    "BATON_BRIEF_BASE_URL=https://brief.example.com:$invalid_brief_port" \
    "BATON_BRIEF_BEARER_TOKEN_FILE=$brief_bearer_token_file" \
    >> "$invalid_brief_port_env"
  expect_preflight_failure \
    "BRIEF invalid port $invalid_brief_port" \
    "$invalid_brief_port_env" \
    '포트는 1~65535 범위여야 합니다'
done

watch_receiver_reused_token_env="$test_root/watch-receiver-reused-token.env"
write_valid_env "$watch_receiver_reused_token_env"
printf '%s\n' \
  'BATON_WATCH_ENABLED=true' \
  'BATON_WATCH_BASE_URL=https://watch.example.com' \
  "BATON_WATCH_BEARER_TOKEN=$watch_token" \
  'BATON_WATCH_SOURCE_NAMESPACE=production' \
  'BATON_WATCH_EVENT_RECEIVER_ENABLED=true' \
  "BATON_WATCH_EVENT_RECEIVER_BEARER_TOKEN=$watch_token" \
  >> "$watch_receiver_reused_token_env"
expect_preflight_failure \
  'WATCH event receiver reused outbound token' \
  "$watch_receiver_reused_token_env" \
  'must all be independently generated'

short_secret_env="$test_root/short-secret.env"
write_valid_env "$short_secret_env"
sed 's/^BATON_DB_PASSWORD=.*/BATON_DB_PASSWORD=too-short/' \
  "$short_secret_env" > "$test_root/short-secret.tmp"
mv "$test_root/short-secret.tmp" "$short_secret_env"
chmod 600 "$short_secret_env"
expect_preflight_failure 'short database password' "$short_secret_env" '32-200 URL-safe ASCII'

reused_secret_env="$test_root/reused-secret.env"
write_valid_env "$reused_secret_env"
sed "s/^BATON_DB_ROOT_PASSWORD=.*/BATON_DB_ROOT_PASSWORD=$db_password/" \
  "$reused_secret_env" > "$test_root/reused-secret.tmp"
mv "$test_root/reused-secret.tmp" "$reused_secret_env"
chmod 600 "$reused_secret_env"
expect_preflight_failure \
  'reused production secret' "$reused_secret_env" 'must all be independently generated'

expect_round_image_failure() {
  local label="$1"
  local mode="$2"
  local expected="$3"
  local output
  local docker_log="$test_root/$mode-docker.log"

  if output="$(PATH="$fake_bin:$PATH" \
    FAKE_DOCKER_LOG="$docker_log" \
    FAKE_DOCKER_MODE="$mode" \
    FAKE_EXPECTED_ROUND_TURN_SECRET_FILE="$round_turn_shared_secret_file" \
    FAKE_ROUND_RELEASE_REVISION="$round_release_revision" \
    "$preflight_script" "$round_runtime_enabled_env" 2>&1)"; then
    fail "$label unexpectedly passed"
  fi
  assert_contains "$expected" "$output" "$label"
  assert_not_contains "$round_turn_shared_secret" "$output" "$label TURN secret output"
  assert_not_contains "$round_turn_shared_secret" "$(cat "$docker_log")" \
    "$label TURN secret Docker arguments"
}

expect_round_image_failure \
  'ROUND web auth-mode label mismatch' \
  round-web-auth-mismatch \
  'io.round.auth-mode=baton'
expect_round_image_failure \
  'ROUND web revision label mismatch' \
  round-web-revision-mismatch \
  'revision label does not match'
expect_round_image_failure \
  'ROUND signaling revision label mismatch' \
  round-signaling-revision-mismatch \
  'revision label does not match'
expect_round_image_failure \
  'ROUND tag-object label mismatch' \
  round-tag-object-mismatch \
  'must declare the same io.round.release.tag-object label'
expect_round_image_failure \
  'ROUND empty tag-object label' \
  round-empty-tag-object \
  'exactly 40 lowercase hexadecimal characters'
expect_round_image_failure \
  'ROUND invalid tag-object label' \
  round-invalid-tag-object \
  'exactly 40 lowercase hexadecimal characters'
expect_round_image_failure \
  'ROUND RepoDigest mismatch' \
  round-repodigest-mismatch \
  'RepoDigests do not include the exact configured digest'

if PATH="$fake_bin:$PATH" \
  FAKE_DOCKER_LOG="$test_root/docker.log" \
  FAKE_DOCKER_MODE=daemon-failure \
  "$preflight_script" "$valid_env" >/dev/null 2>&1; then
  fail 'Docker daemon failure unexpectedly passed preflight'
fi
if PATH="$fake_bin:$PATH" \
  FAKE_DOCKER_LOG="$test_root/docker.log" \
  FAKE_DOCKER_MODE=compose-failure \
  "$preflight_script" "$valid_env" >/dev/null 2>&1; then
  fail 'invalid Compose configuration unexpectedly passed preflight'
fi

run_health() {
  PATH="$fake_bin:$PATH" \
  FAKE_CURL_LOG="$test_root/curl.log" \
  BATON_HEALTH_URL="${BATON_HEALTH_URL:-https://baton.example.com/actuator/health}" \
  BATON_HEALTH_CONNECT_TIMEOUT_SECONDS="${BATON_HEALTH_CONNECT_TIMEOUT_SECONDS:-5}" \
  BATON_HEALTH_TIMEOUT_SECONDS="${BATON_HEALTH_TIMEOUT_SECONDS:-15}" \
  "$repo_root/ops/check-service-health.sh"
}

health_output="$(run_health)" || fail 'healthy public service check failed'
assert_contains 'BATON public service is healthy' "$health_output" 'healthy public service'
curl_arguments="$(cat "$test_root/curl.log")"
[[ "$curl_arguments" == --disable* ]] || fail 'curl config isolation must be the first option'
assert_contains '--proto =https' "$curl_arguments" 'HTTPS protocol restriction'
assert_contains '--disable' "$curl_arguments" 'curl config isolation'
assert_contains '--tlsv1.2' "$curl_arguments" 'TLS minimum'
assert_contains '--connect-timeout 5' "$curl_arguments" 'connect timeout'
assert_contains '--max-time 15' "$curl_arguments" 'overall timeout'
assert_contains '--max-filesize 65536' "$curl_arguments" 'health response size limit'
assert_not_contains '--insecure' "$curl_arguments" 'certificate verification'
assert_not_contains ' -k ' " $curl_arguments " 'certificate verification'
assert_not_contains '--location' "$curl_arguments" 'redirect behavior'

if FAKE_CURL_MODE=down run_health >/dev/null 2>&1; then
  fail 'DOWN service health unexpectedly passed'
fi
if FAKE_CURL_MODE=redirect run_health >/dev/null 2>&1; then
  fail 'redirected service health unexpectedly passed'
fi
if FAKE_CURL_MODE=trailing-garbage run_health >/dev/null 2>&1; then
  fail 'health response with trailing garbage unexpectedly passed'
fi
if FAKE_CURL_MODE=incomplete-json run_health >/dev/null 2>&1; then
  fail 'incomplete health JSON unexpectedly passed'
fi
if FAKE_CURL_MODE=split-token run_health >/dev/null 2>&1; then
  fail 'whitespace-spoofed health JSON unexpectedly passed'
fi
if FAKE_CURL_MODE=transport-failure run_health >/dev/null 2>&1; then
  fail 'transport failure unexpectedly passed service health'
fi
if BATON_HEALTH_URL=http://baton.example.com/actuator/health run_health >/dev/null 2>&1; then
  fail 'HTTP service health URL unexpectedly passed'
fi
if BATON_HEALTH_URL=https://user@baton.example.com/actuator/health run_health >/dev/null 2>&1; then
  fail 'credential-bearing service health URL unexpectedly passed'
fi
if BATON_HEALTH_URL='https://baton.example.com/actuator/health?details=true' \
  run_health >/dev/null 2>&1; then
  fail 'query-bearing service health URL unexpectedly passed'
fi
if BATON_HEALTH_URL=https://bad-.example.com/actuator/health \
  run_health >/dev/null 2>&1; then
  fail 'invalid service health hostname unexpectedly passed'
fi
if BATON_HEALTH_CONNECT_TIMEOUT_SECONDS=20 BATON_HEALTH_TIMEOUT_SECONDS=10 \
  run_health >/dev/null 2>&1; then
  fail 'invalid health timeouts unexpectedly passed'
fi

format_epoch() {
  local epoch="$1"
  local format="$2"

  if date --version >/dev/null 2>&1; then
    date -u -d "@$epoch" "$format"
  else
    date -u -r "$epoch" "$format"
  fi
}

write_freshness_state() {
  local target_dir="$1"
  local snapshot_epoch="$2"
  local verified_epoch="$3"
  local stored_epoch="${4:-$snapshot_epoch}"
  local timestamp
  local verified_time

  mkdir -p -- "$target_dir"
  chmod 700 "$target_dir"
  timestamp="$(format_epoch "$snapshot_epoch" '+%Y%m%dT%H%M%SZ')"
  verified_time="$(format_epoch "$verified_epoch" '+%Y-%m-%dT%H:%M:%SZ')"
  printf '%s\n%s\n%s\n%s\n%s\n' \
    "$stored_epoch" \
    "baton-$timestamp-test.sql.gz" \
    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
    "$verified_time" \
    'fake_crypt:daily' \
    > "$target_dir/last-success"
  chmod 600 "$target_dir/last-success"
}

now_epoch="$(date -u '+%s')"
fresh_state="$test_root/fresh-state"
write_freshness_state "$fresh_state" "$((now_epoch - 60))" "$now_epoch"
BATON_BACKUP_STATE_DIR="$fresh_state" \
BATON_BACKUP_MAX_AGE_HOURS=36 \
"$repo_root/ops/check-backup-freshness.sh" >/dev/null \
  || fail 'fresh verified backup state failed'
if BATON_BACKUP_STATE_DIR="$fresh_state" \
  BATON_BACKUP_MAX_AGE_HOURS=999999999999999999999 \
  "$repo_root/ops/check-backup-freshness.sh" >/dev/null 2>&1; then
  fail 'overflowing backup freshness threshold unexpectedly passed'
fi

stale_state="$test_root/stale-state"
write_freshness_state "$stale_state" "$((now_epoch - 7200))" "$((now_epoch - 60))"
if BATON_BACKUP_STATE_DIR="$stale_state" \
  BATON_BACKUP_MAX_AGE_HOURS=1 \
  "$repo_root/ops/check-backup-freshness.sh" >/dev/null 2>&1; then
  fail 'stale verified backup unexpectedly passed'
fi

mismatched_state="$test_root/mismatched-state"
write_freshness_state \
  "$mismatched_state" "$((now_epoch - 60))" "$now_epoch" "$((now_epoch - 30))"
if BATON_BACKUP_STATE_DIR="$mismatched_state" \
  "$repo_root/ops/check-backup-freshness.sh" >/dev/null 2>&1; then
  fail 'backup state with mismatched filename and epoch unexpectedly passed'
fi

future_verification_state="$test_root/future-verification-state"
write_freshness_state \
  "$future_verification_state" "$((now_epoch - 60))" "$((now_epoch + 3600))"
if BATON_BACKUP_STATE_DIR="$future_verification_state" \
  "$repo_root/ops/check-backup-freshness.sh" >/dev/null 2>&1; then
  fail 'future backup verification time unexpectedly passed'
fi

symlink_state="$test_root/symlink-state"
write_freshness_state "$test_root/real-state" "$((now_epoch - 60))" "$now_epoch"
mkdir -p -- "$symlink_state"
ln -s "$test_root/real-state/last-success" "$symlink_state/last-success"
if BATON_BACKUP_STATE_DIR="$symlink_state" \
  "$repo_root/ops/check-backup-freshness.sh" >/dev/null 2>&1; then
  fail 'symlinked backup success state unexpectedly passed'
fi

world_readable_state="$test_root/world-readable-state"
write_freshness_state "$world_readable_state" "$((now_epoch - 60))" "$now_epoch"
chmod 755 "$world_readable_state"
if BATON_BACKUP_STATE_DIR="$world_readable_state" \
  "$repo_root/ops/check-backup-freshness.sh" >/dev/null 2>&1; then
  fail 'world-readable backup state directory unexpectedly passed'
fi

extra_line_state="$test_root/extra-line-state"
write_freshness_state "$extra_line_state" "$((now_epoch - 60))" "$now_epoch"
printf '\n' >> "$extra_line_state/last-success"
if BATON_BACKUP_STATE_DIR="$extra_line_state" \
  "$repo_root/ops/check-backup-freshness.sh" >/dev/null 2>&1; then
  fail 'backup success state with an extra blank line unexpectedly passed'
fi

printf 'Pilot production preflight, public health, and backup freshness checks passed.\n'
