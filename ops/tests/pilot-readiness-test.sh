#!/usr/bin/env bash

set -Eeuo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(dirname -- "$(dirname -- "$script_dir")")"
test_base="${TMPDIR:-/tmp}"
test_base="${test_base%/}"
test_root="$(mktemp -d "$test_base/baton-pilot-readiness-test.XXXXXX")"

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
if [[ "${1:-}" != "compose" ]]; then
  printf 'Unexpected fake docker command: %s\n' "$*" >&2
  exit 70
fi

for forbidden_name in \
  BATON_HOST \
  BATON_DB_NAME \
  BATON_DB_USERNAME \
  BATON_DB_PASSWORD \
  BATON_DB_ROOT_PASSWORD \
  BATON_WORKSPACE_CREATION_KEY \
  BATON_WORKSPACE_RECOVERY_KEY \
  BATON_WATCH_ENABLED \
  BATON_WATCH_MONITORING_ENABLED \
  BATON_WATCH_BASE_URL \
  BATON_WATCH_BEARER_TOKEN \
  BATON_WATCH_SOURCE_NAMESPACE \
	  BATON_WATCH_EVENT_RECEIVER_ENABLED \
	  BATON_WATCH_EVENT_RECEIVER_BEARER_TOKEN \
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
  BATON_HTTP_PUBLISH \
  BATON_HTTPS_TCP_PUBLISH \
  BATON_HTTPS_UDP_PUBLISH \
  COMPOSE_FILE \
  COMPOSE_ENV_FILES \
  COMPOSE_DISABLE_ENV_FILE \
  COMPOSE_PROJECT_NAME \
  COMPOSE_PROFILES; do
  if [[ -n "${!forbidden_name+x}" ]]; then
    printf 'Ambient variable reached Compose: %s\n' "$forbidden_name" >&2
    exit 71
  fi
done

for required_secret_name in \
  BATON_SECRET_GOOGLE_OAUTH_CLIENT_SECRET \
  BATON_SECRET_NAVER_OAUTH_CLIENT_SECRET \
  BATON_SECRET_SMTP_PASSWORD \
  BATON_SECRET_EMAIL_OUTBOX_ENCRYPTION_KEY \
  BATON_SECRET_ROUND_CURRENT_PRIVATE_KEY \
  BATON_SECRET_ROUND_CURRENT_PUBLIC_KEY \
  BATON_SECRET_ROUND_PREVIOUS_PUBLIC_KEY \
  BATON_EFFECTIVE_SMTP_TEST_CONNECTION \
  BATON_EFFECTIVE_ROUND_PREVIOUS_PUBLIC_KEY_PATH; do
  if [[ -z "${!required_secret_name+x}" ]]; then
    printf 'Production wrapper omitted internal secret input: %s\n' \
      "$required_secret_name" >&2
    exit 73
  fi
done

printf '%s\n' "$*" > "$FAKE_DOCKER_LOG"
[[ "$*" == *"--project-name baton-production"* ]] || exit 72
if [[ "${FAKE_DOCKER_MODE:-healthy}" == "compose-failure" && "$*" == *"config --quiet"* ]]; then
  exit 74
fi
SCRIPT

cat > "$fake_bin/curl" <<'SCRIPT'
#!/usr/bin/env bash
set -Eeuo pipefail

headers_file=""
body_file=""
url=""
printf '%s\n' "$*" > "$FAKE_CURL_LOG"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dump-header)
      headers_file="$2"
      shift 2
      ;;
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

[[ -n "$headers_file" && -n "$body_file" && -n "$url" ]] || exit 81
case "${FAKE_CURL_MODE:-healthy}" in
  healthy)
    printf 'HTTP/2 200\ncontent-type: application/vnd.spring-boot.actuator.v3+json\n\n' \
      > "$headers_file"
    printf '{"status":"UP","groups":["liveness","readiness"]}\n' > "$body_file"
    printf '200'
    ;;
  down)
    printf 'HTTP/2 200\ncontent-type: application/vnd.spring-boot.actuator.v3+json\n\n' \
      > "$headers_file"
    printf '{"status":"DOWN"}\n' > "$body_file"
    printf '200'
    ;;
  redirect)
    printf 'HTTP/2 302\nlocation: https://other.example.com/actuator/health\n\n' \
      > "$headers_file"
    : > "$body_file"
    printf '302'
    ;;
  trailing-garbage)
    printf 'HTTP/2 200\ncontent-type: application/json\n\n' > "$headers_file"
    printf '{"status":"UP"}garbage\n' > "$body_file"
    printf '200'
    ;;
  incomplete-json)
    printf 'HTTP/2 200\ncontent-type: application/json\n\n' > "$headers_file"
    printf '{"status":"UP",\n' > "$body_file"
    printf '200'
    ;;
  split-token)
    printf 'HTTP/2 200\ncontent-type: application/json\n\n' > "$headers_file"
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

chmod +x "$fake_bin/docker" "$fake_bin/curl"

db_password="1111111111111111111111111111111111111111111111111111111111111111"
root_password="2222222222222222222222222222222222222222222222222222222222222222"
creation_key="3333333333333333333333333333333333333333333333333333333333333333"
recovery_key="4444444444444444444444444444444444444444444444444444444444444444"
watch_token="5555555555555555555555555555555555555555555555555555555555555555"
watch_receiver_token="6666666666666666666666666666666666666666666666666666666666666666"
google_oauth_secret="google-oauth-secret-777777777777777777777777"
naver_oauth_secret="naver-oauth-secret-8888888888888888888888888"
smtp_password="smtp-password-9999999999999999999999999999"
email_outbox_encryption_key="$(
  printf '%s' '0123456789abcdef0123456789abcdef' | openssl base64 -A
)"

auth_secret_dir="$test_root/auth-secrets"
mkdir -p -- "$auth_secret_dir"
chmod 700 "$auth_secret_dir"
google_oauth_secret_file="$auth_secret_dir/google-oauth"
naver_oauth_secret_file="$auth_secret_dir/naver-oauth"
smtp_password_file="$auth_secret_dir/smtp-password"
email_outbox_encryption_key_file="$auth_secret_dir/email-outbox-encryption-key.base64"
round_private_key_file="$auth_secret_dir/round-private.pem"
round_public_key_file="$auth_secret_dir/round-public.pem"
round_other_private_key_file="$auth_secret_dir/round-other-private.pem"
round_other_public_key_file="$auth_secret_dir/round-other-public.pem"
printf '%s' "$google_oauth_secret" > "$google_oauth_secret_file"
printf '%s' "$naver_oauth_secret" > "$naver_oauth_secret_file"
printf '%s' "$smtp_password" > "$smtp_password_file"
printf '%s' "$email_outbox_encryption_key" > "$email_outbox_encryption_key_file"
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
  "$smtp_password_file" \
  "$email_outbox_encryption_key_file" \
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

append_enabled_auth() {
  local target="$1"

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
    "$repo_root/ops/preflight-production.sh" "$target" 2>&1)"; then
    fail "$label unexpectedly passed"
  fi
  assert_contains "$expected" "$output" "$label"
  assert_not_contains "$db_password" "$output" "$label secret leak"
  assert_not_contains "$root_password" "$output" "$label secret leak"
  assert_not_contains "$creation_key" "$output" "$label secret leak"
  assert_not_contains "$recovery_key" "$output" "$label secret leak"
  assert_not_contains "$watch_token" "$output" "$label secret leak"
  assert_not_contains "$watch_receiver_token" "$output" "$label secret leak"
  assert_not_contains "$google_oauth_secret" "$output" "$label Google secret leak"
  assert_not_contains "$naver_oauth_secret" "$output" "$label Naver secret leak"
  assert_not_contains "$smtp_password" "$output" "$label SMTP secret leak"
  assert_not_contains "$email_outbox_encryption_key" "$output" "$label outbox key leak"
}

valid_env="$test_root/valid.env"
write_valid_env "$valid_env"
valid_env_canonical="$(CDPATH= cd -- "$(dirname -- "$valid_env")" && pwd -P)/$(basename -- "$valid_env")"
preflight_output="$(PATH="$fake_bin:$PATH" \
  FAKE_DOCKER_LOG="$test_root/docker.log" \
  BATON_HOST=ambient.invalid \
  BATON_DB_PASSWORD=ambient-password \
  BATON_AUTH_OAUTH2_ENABLED=true \
  BATON_AUTH_OAUTH2_GOOGLE_CLIENT_SECRET_FILE=/tmp/ambient-google-secret \
  BATON_EMAIL_OUTBOX_ENCRYPTION_KEY_FILE=/tmp/ambient-outbox-key \
  BATON_HTTP_PUBLISH=127.0.0.1::80 \
  COMPOSE_ENV_FILES=/tmp/ambient.env \
  COMPOSE_PROJECT_NAME=ambient-project \
  DOCKER_HOST=tcp://attacker.invalid:2376 \
  DOCKER_CONTEXT=attacker \
  DOCKER_CONFIG=/tmp/attacker-docker-config \
  DOCKER_TLS_VERIFY=1 \
  DOCKER_CERT_PATH=/tmp/attacker-certs \
  BUILDKIT_HOST=tcp://attacker.invalid:1234 \
  "$repo_root/ops/preflight-production.sh" "$valid_env" 2>&1)" \
  || fail 'valid production preflight failed'
assert_contains 'Production preflight passed' "$preflight_output" 'valid production preflight'
assert_not_contains "$db_password" "$preflight_output" 'valid preflight secret leak'
assert_contains '--project-name baton-production' "$(cat "$test_root/docker.log")" \
  'production Compose project boundary'
assert_contains "--env-file $valid_env_canonical" "$(cat "$test_root/docker.log")" \
  'production Compose env file boundary'

auth_enabled_env="$test_root/auth-enabled.env"
write_valid_env "$auth_enabled_env"
append_enabled_auth "$auth_enabled_env"
auth_preflight_output="$(PATH="$fake_bin:$PATH" \
  FAKE_DOCKER_LOG="$test_root/auth-docker.log" \
  "$repo_root/ops/preflight-production.sh" "$auth_enabled_env" 2>&1)" \
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
assert_not_contains "$google_oauth_secret" "$(cat "$test_root/auth-docker.log")" \
  'enabled auth Google secret Docker arguments'
preflight_env_output="$(PATH="$fake_bin:$PATH" \
  FAKE_DOCKER_LOG="$test_root/docker.log" \
  BATON_PRODUCTION_ENV_FILE="$valid_env_canonical" \
  "$repo_root/ops/preflight-production.sh" 2>&1)" \
  || fail 'BATON_PRODUCTION_ENV_FILE preflight failed'
assert_contains 'Production preflight passed' "$preflight_env_output" \
  'BATON_PRODUCTION_ENV_FILE preflight'

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
  "$repo_root/ops/preflight-production.sh" "$watch_enabled_env" 2>&1)" \
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
  "$repo_root/ops/preflight-production.sh" "$watch_receiver_enabled_env" 2>&1)" \
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
grep -Fq 'header_up -Forwarded' "$repo_root/ops/Caddyfile" \
  || fail 'Caddy does not remove untrusted Forwarded headers'
grep -Fq 'header_up -X-Forwarded-*' "$repo_root/ops/Caddyfile" \
  || fail 'Caddy does not remove untrusted X-Forwarded headers'
grep -Fq 'header_up X-Forwarded-Host {$BATON_HOST}' "$repo_root/ops/Caddyfile" \
  || fail 'Caddy does not pin the forwarded public host'
grep -Fq 'header_up X-Forwarded-Proto https' "$repo_root/ops/Caddyfile" \
  || fail 'Caddy does not pin the forwarded HTTPS scheme'

grep -Fq 'SPRING_CONFIG_IMPORT: configtree:/run/baton-config/' \
  "$repo_root/compose.production.yml" \
  || fail 'production Compose does not import scalar secrets through configtree'
grep -Fq 'target: /run/baton-config/baton.identity.email-verification.outbox-encryption-key' \
  "$repo_root/compose.production.yml" \
  || fail 'production Compose does not mount the email outbox encryption key'
grep -Fq 'target: /run/baton-keys/current-private.pem' \
  "$repo_root/compose.production.yml" \
  || fail 'production Compose does not mount the ROUND private key at a fixed path'
grep -Fq 'SERVER_SERVLET_SESSION_TIMEOUT: PT30M' \
  "$repo_root/compose.production.yml" \
  || fail 'production Compose does not pin the in-memory session timeout'
grep -Fq 'MANAGEMENT_HEALTH_MAIL_ENABLED: "false"' \
  "$repo_root/compose.production.yml" \
  || fail 'production Compose lets SMTP availability take down application health'
grep -Fq 'SERVER_FORWARD_HEADERS_STRATEGY: NATIVE' \
  "$repo_root/compose.production.yml" \
  || fail 'production Compose does not let Spring consume Caddy-sanitized forwarded headers'

expect_compose_boundary_failure() {
  local label="$1"
  local output
  shift

  if output="$(PATH="$fake_bin:$PATH" \
    BATON_PRODUCTION_ENV_FILE="$valid_env_canonical" \
    FAKE_DOCKER_LOG="$test_root/docker.log" \
    "$repo_root/ops/production-compose.sh" "$@" 2>&1)"; then
    fail "$label unexpectedly passed"
  fi
  assert_contains 'option' "$output" "$label rejection reason"
  if [[ "$output" != *"not allowed"* && "$output" != *"cannot be overridden"* ]]; then
    fail "$label rejection reason was not specific"
  fi
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
    "$repo_root/ops/production-compose.sh" ps 2>&1)"; then
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

expect_compose_boundary_failure 'leading project override' --project-name other ps
expect_compose_boundary_failure 'leading file override' --file other.yml ps
expect_compose_boundary_failure 'leading env override' --env-file other.env ps
expect_compose_boundary_failure 'leading directory override' --project-directory /tmp ps
expect_compose_boundary_failure 'leading profile override' --profile other ps
expect_compose_boundary_failure 'leading short file override' -f other.yml ps
expect_compose_boundary_failure 'leading short project override' -p other ps
expect_compose_boundary_failure 'trailing project override' ps --project-name other

for scale_arguments in 'scale app=2' 'up --scale app=2' 'up --scale=app=2'; do
  read -r -a scale_parts <<< "$scale_arguments"
  if scale_output="$(PATH="$fake_bin:$PATH" \
    BATON_PRODUCTION_ENV_FILE="$valid_env_canonical" \
    FAKE_DOCKER_LOG="$test_root/docker.log" \
    "$repo_root/ops/production-compose.sh" "${scale_parts[@]}" 2>&1)"; then
    fail "production Compose scaling unexpectedly passed: $scale_arguments"
  fi
  assert_contains 'does not allow scaling' "$scale_output" \
    "production Compose scaling rejection: $scale_arguments"
done

PATH="$fake_bin:$PATH" \
BATON_PRODUCTION_ENV_FILE="$valid_env_canonical" \
FAKE_DOCKER_LOG="$test_root/docker.log" \
"$repo_root/ops/production-compose.sh" logs -f >/dev/null \
  || fail 'production Compose logs -f was incorrectly rejected'

mutated_env="$test_root/mutated-after-preflight.env"
write_valid_env "$mutated_env"
PATH="$fake_bin:$PATH" \
FAKE_DOCKER_LOG="$test_root/mutated-preflight-docker.log" \
"$repo_root/ops/preflight-production.sh" "$mutated_env" >/dev/null \
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
"$repo_root/ops/preflight-production.sh" "$permission_mutated_env" >/dev/null \
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
"$repo_root/ops/preflight-production.sh" "$tracked_after_preflight_env" >/dev/null \
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

if PATH="$fake_bin:$PATH" \
  FAKE_DOCKER_LOG="$test_root/docker.log" \
  FAKE_DOCKER_MODE=daemon-failure \
  "$repo_root/ops/preflight-production.sh" "$valid_env" >/dev/null 2>&1; then
  fail 'Docker daemon failure unexpectedly passed preflight'
fi
if PATH="$fake_bin:$PATH" \
  FAKE_DOCKER_LOG="$test_root/docker.log" \
  FAKE_DOCKER_MODE=compose-failure \
  "$repo_root/ops/preflight-production.sh" "$valid_env" >/dev/null 2>&1; then
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
