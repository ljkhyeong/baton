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
  BATON_IDENTITY_BOOTSTRAP_KEY \
  BATON_IDENTITY_INVITATION_HMAC_SECRET \
  BATON_IDENTITY_BOOTSTRAP_INVITATION_TTL \
  BATON_IDENTITY_MEMBER_INVITATION_TTL \
  BATON_IDENTITY_OIDC_ENABLED \
  SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID \
  SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET \
  SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_REDIRECT_URI \
  BATON_GO_ENABLED \
  BATON_GO_BASE_URL \
  BATON_GO_PUBLIC_BASE_URL \
  BATON_GO_MANAGEMENT_TOKEN \
  BATON_ROUND_PUBLIC_BASE_URL \
  BATON_ROUND_GRANT_ENABLED \
  BATON_ROUND_GRANT_ACTIVE_KID \
  BATON_ROUND_GRANT_PRIVATE_KEY_FILE \
  BATON_ROUND_GRANT_JWK_SET_FILE \
  BATON_ROUND_WEB_IMAGE \
  BATON_ROUND_SIGNALING_IMAGE \
  BATON_ROUND_TURN_URLS \
  BATON_ROUND_TURN_SHARED_SECRET \
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
identity_bootstrap_key="5555555555555555555555555555555555555555555555555555555555555555"
identity_invitation_hmac_secret="6666666666666666666666666666666666666666666666666666666666666666"
google_client_secret="7777777777777777777777777777777777777777777777777777777777777777"
go_management_token="8888888888888888888888888888888888888888888888888888888888888888"
round_turn_shared_secret="9999999999999999999999999999999999999999999999999999999999999999"

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
    "BATON_IDENTITY_BOOTSTRAP_KEY=$identity_bootstrap_key" \
    "BATON_IDENTITY_INVITATION_HMAC_SECRET=$identity_invitation_hmac_secret" \
    'BATON_IDENTITY_BOOTSTRAP_INVITATION_TTL=PT1H' \
    'BATON_IDENTITY_MEMBER_INVITATION_TTL=PT24H' \
    'BATON_IDENTITY_OIDC_ENABLED=false' \
    'BATON_ROUND_GRANT_ENABLED=false' \
    > "$target"
  chmod 600 "$target"
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
  assert_not_contains "$identity_bootstrap_key" "$output" "$label secret leak"
  assert_not_contains "$identity_invitation_hmac_secret" "$output" "$label secret leak"
  assert_not_contains "$google_client_secret" "$output" "$label secret leak"
  assert_not_contains "$round_turn_shared_secret" "$output" "$label secret leak"
}

valid_env="$test_root/valid.env"
write_valid_env "$valid_env"
valid_env_canonical="$(CDPATH= cd -- "$(dirname -- "$valid_env")" && pwd -P)/$(basename -- "$valid_env")"
preflight_output="$(PATH="$fake_bin:$PATH" \
  FAKE_DOCKER_LOG="$test_root/docker.log" \
  BATON_HOST=ambient.invalid \
  BATON_DB_PASSWORD=ambient-password \
  BATON_IDENTITY_BOOTSTRAP_KEY=ambient-identity-bootstrap-key \
  BATON_IDENTITY_OIDC_ENABLED=true \
  SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET=ambient-google-secret \
  BATON_GO_ENABLED=true \
  BATON_GO_MANAGEMENT_TOKEN=ambient-go-token \
  BATON_HTTP_PUBLISH=127.0.0.1::80 \
  COMPOSE_ENV_FILES=/tmp/ambient.env \
  COMPOSE_PROJECT_NAME=ambient-project \
  "$repo_root/ops/preflight-production.sh" "$valid_env" 2>&1)" \
  || fail 'valid production preflight failed'
assert_contains 'Production preflight passed' "$preflight_output" 'valid production preflight'
assert_not_contains "$db_password" "$preflight_output" 'valid preflight secret leak'
assert_not_contains "$identity_bootstrap_key" "$preflight_output" \
  'valid preflight identity bootstrap secret leak'
assert_contains '--project-name baton-production' "$(cat "$test_root/docker.log")" \
  'production Compose project boundary'
assert_contains "--env-file $valid_env_canonical" "$(cat "$test_root/docker.log")" \
  'production Compose env file boundary'
assert_not_contains 'compose.production-oidc.yml' "$(cat "$test_root/docker.log")" \
  'disabled OIDC Compose overlay'
assert_not_contains 'compose.production-round.yml' "$(cat "$test_root/docker.log")" \
  'disabled ROUND Compose overlay'
caddy_config="$(cat "$repo_root/ops/Caddyfile")"
base_application_config="$(cat "$repo_root/bootstrap/src/main/resources/application.yml")"
production_application_config="$(
  cat "$repo_root/bootstrap/src/main/resources/application-production.yml"
)"
assert_contains 'path: /' "$base_application_config" \
  'BATON session host-prefix cookie path'
assert_not_contains 'path: /api/v1' "$base_application_config" \
  'BATON session host-prefix path must remain root'
assert_not_contains 'domain:' "$base_application_config" \
  'BATON session host-only base configuration'
assert_contains 'name: __Host-baton_session' "$production_application_config" \
  'BATON production host-prefixed session cookie name'
assert_contains 'secure: true' "$production_application_config" \
  'BATON production secure session cookie'
assert_not_contains 'domain:' "$production_application_config" \
  'BATON production host-only session cookie'
assert_contains 'rewrite * /actuator/health' "$caddy_config" \
  'internal Caddy health rewrite'
assert_contains 'request>headers delete' "$caddy_config" 'Caddy request header log redaction'
assert_contains 'request>uri delete' "$caddy_config" 'Caddy request URI log redaction'
assert_contains 'resp_headers>Set-Cookie delete' "$caddy_config" \
  'Caddy response cookie log redaction'
assert_contains '@operatorBootstrap path /api/v1/identity/bootstrap-invitations' \
  "$caddy_config" 'external owner bootstrap route block'
assert_contains 'handle @operatorBootstrap' "$caddy_config" \
  'external owner bootstrap handler'
assert_contains 'handle @roundJwks' "$caddy_config" 'public ROUND JWK Set handler'
assert_contains 'header_up -Cookie' "$caddy_config" \
  'ROUND static upstream cookie removal'
assert_contains 'header_regexp roundSignalGrantCookie Cookie ^(__Host-baton_session=' \
  "$caddy_config" \
  'ROUND signaling must allow the host-only BATON session beside one grant'
assert_contains 'header_regexp roundTurnGrantCookie Cookie ^(__Host-baton_session=' \
  "$caddy_config" \
  'ROUND TURN must allow the host-only BATON session beside one grant'
assert_contains 'header_up Cookie "__Secure-round_access={re.roundSignalGrantCookie.2}"' \
  "$caddy_config" \
  'ROUND signaling must rebuild Cookie from only the validated grant'
assert_contains 'header_up Cookie "__Secure-round_access={re.roundTurnGrantCookie.2}"' \
  "$caddy_config" \
  'ROUND TURN must rebuild Cookie from only the validated grant'
assert_contains 'header_regexp roundRefreshSessionFirstCookie Cookie ^__Host-baton_session=' \
  "$caddy_config" \
  'ROUND refresh must accept a session-only or session-first cookie'
assert_contains 'header_regexp roundRefreshGrantFirstCookie Cookie ^__Secure-round_access=' \
  "$caddy_config" \
  'ROUND refresh must accept a grant-first cookie only beside one session'
assert_contains 'path_regexp roundRefreshSessionFirst ^/round/rooms/' \
  "$caddy_config" 'ROUND refresh exact canonical room path'
assert_contains 'path_regexp roundGrantRefresh ^/round/rooms/' \
  "$caddy_config" 'ROUND refresh must keep a canonical unauthenticated fallback'
assert_contains 'header_up Cookie "__Host-baton_session={re.roundRefreshSessionFirstCookie.1}"' \
  "$caddy_config" \
  'ROUND refresh must rebuild a session-only Cookie for BATON'
assert_contains 'header_up Cookie "__Host-baton_session={re.roundRefreshGrantFirstCookie.1}"' \
  "$caddy_config" \
  'ROUND refresh must remove the previous grant before BATON authorization'
assert_contains 'header_up -X-Baton-Access-Key' "$caddy_config" \
  'ROUND upstreams must remove legacy BATON authority headers'
assert_contains 'header_up -X-Forwarded-*' "$caddy_config" \
  'ROUND upstreams must remove spoofed forwarding headers'
assert_contains 'max_size 1KB' "$caddy_config" 'ROUND refresh request body limit'
assert_contains 'header ?X-Request-ID "{http.request.uuid}"' \
  "$caddy_config" \
  'ROUND refresh must preserve Spring request IDs and fill only edge errors'
assert_contains 'respond "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"로그인이 필요합니다\"}" 401' \
  "$caddy_config" \
  'ROUND refresh without one validated session must follow the public 401 contract'
assert_contains 'header_down -Set-Cookie' "$caddy_config" \
  'ROUND non-BATON upstreams must not plant cookies on the BATON origin'
assert_not_contains 'rewrite * /api/v1/round/rooms/' "$caddy_config" \
  'ROUND refresh must be handled directly by the BATON app'
assert_contains 'rewrite * /rooms/{re.roundSignal.1}/signal' \
  "$caddy_config" 'room-scoped ROUND WebSocket rewrite'
assert_contains 'rewrite * /api/rooms/{re.roundTurnCredentials.1}/turn-credentials' \
  "$caddy_config" 'room-scoped ROUND TURN rewrite'
assert_contains 'camera=(self)' "$caddy_config" 'ROUND camera permission'
assert_contains 'rate_limit {' "$caddy_config" 'ROUND pre-auth rate limit'
preflight_env_output="$(PATH="$fake_bin:$PATH" \
  FAKE_DOCKER_LOG="$test_root/docker.log" \
  BATON_PRODUCTION_ENV_FILE="$valid_env_canonical" \
  "$repo_root/ops/preflight-production.sh" 2>&1)" \
  || fail 'BATON_PRODUCTION_ENV_FILE preflight failed'
assert_contains 'Production preflight passed' "$preflight_env_output" \
  'BATON_PRODUCTION_ENV_FILE preflight'

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
  assert_not_contains "$identity_bootstrap_key" "$output" "$label secret leak"
  assert_not_contains "$identity_invitation_hmac_secret" "$output" "$label secret leak"
  assert_not_contains "$google_client_secret" "$output" "$label secret leak"
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

short_identity_secret_env="$test_root/short-identity-secret.env"
write_valid_env "$short_identity_secret_env"
sed 's/^BATON_IDENTITY_BOOTSTRAP_KEY=.*/BATON_IDENTITY_BOOTSTRAP_KEY=too-short/' \
  "$short_identity_secret_env" > "$test_root/short-identity-secret.tmp"
mv "$test_root/short-identity-secret.tmp" "$short_identity_secret_env"
chmod 600 "$short_identity_secret_env"
expect_preflight_failure \
  'short identity bootstrap key' \
  "$short_identity_secret_env" \
  'BATON_IDENTITY_BOOTSTRAP_KEY must be 32-200 URL-safe ASCII'

reused_identity_secret_env="$test_root/reused-identity-secret.env"
write_valid_env "$reused_identity_secret_env"
sed "s/^BATON_IDENTITY_INVITATION_HMAC_SECRET=.*/BATON_IDENTITY_INVITATION_HMAC_SECRET=$creation_key/" \
  "$reused_identity_secret_env" > "$test_root/reused-identity-secret.tmp"
mv "$test_root/reused-identity-secret.tmp" "$reused_identity_secret_env"
chmod 600 "$reused_identity_secret_env"
expect_preflight_failure \
  'reused identity invitation secret' \
  "$reused_identity_secret_env" \
  'production secrets must all be independently generated'

invalid_identity_ttl_env="$test_root/invalid-identity-ttl.env"
write_valid_env "$invalid_identity_ttl_env"
sed 's/^BATON_IDENTITY_BOOTSTRAP_INVITATION_TTL=.*/BATON_IDENTITY_BOOTSTRAP_INVITATION_TTL=PT2H/' \
  "$invalid_identity_ttl_env" > "$test_root/invalid-identity-ttl.tmp"
mv "$test_root/invalid-identity-ttl.tmp" "$invalid_identity_ttl_env"
chmod 600 "$invalid_identity_ttl_env"
expect_preflight_failure \
  'invalid identity invitation TTL' \
  "$invalid_identity_ttl_env" \
  'BATON_IDENTITY_BOOTSTRAP_INVITATION_TTL must be exactly PT1H'

invalid_member_invitation_ttl_env="$test_root/invalid-member-invitation-ttl.env"
write_valid_env "$invalid_member_invitation_ttl_env"
sed 's/^BATON_IDENTITY_MEMBER_INVITATION_TTL=.*/BATON_IDENTITY_MEMBER_INVITATION_TTL=PT48H/' \
  "$invalid_member_invitation_ttl_env" > "$test_root/invalid-member-invitation-ttl.tmp"
mv "$test_root/invalid-member-invitation-ttl.tmp" "$invalid_member_invitation_ttl_env"
chmod 600 "$invalid_member_invitation_ttl_env"
expect_preflight_failure \
  'invalid member invitation TTL' \
  "$invalid_member_invitation_ttl_env" \
  'BATON_IDENTITY_MEMBER_INVITATION_TTL must be exactly PT24H'

oidc_enabled_env="$test_root/oidc-enabled.env"
write_valid_env "$oidc_enabled_env"
sed 's/^BATON_IDENTITY_OIDC_ENABLED=false$/BATON_IDENTITY_OIDC_ENABLED=true/' \
  "$oidc_enabled_env" > "$test_root/oidc-enabled.tmp"
mv "$test_root/oidc-enabled.tmp" "$oidc_enabled_env"
printf '%s\n' \
  'SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID=google-client.apps.googleusercontent.com' \
  "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET=$google_client_secret" \
  'SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_REDIRECT_URI={baseUrl}/api/v1/auth/oidc/callback/{registrationId}' \
  >> "$oidc_enabled_env"
chmod 600 "$oidc_enabled_env"
PATH="$fake_bin:$PATH" \
FAKE_DOCKER_LOG="$test_root/oidc-enabled-docker.log" \
"$repo_root/ops/preflight-production.sh" "$oidc_enabled_env" >/dev/null \
  || fail 'valid Google OIDC production settings were rejected'
assert_contains 'compose.production-oidc.yml' "$(cat "$test_root/oidc-enabled-docker.log")" \
  'enabled OIDC Compose overlay'

oidc_missing_secret_env="$test_root/oidc-missing-secret.env"
write_valid_env "$oidc_missing_secret_env"
sed 's/^BATON_IDENTITY_OIDC_ENABLED=false$/BATON_IDENTITY_OIDC_ENABLED=true/' \
  "$oidc_missing_secret_env" > "$test_root/oidc-missing-secret.tmp"
mv "$test_root/oidc-missing-secret.tmp" "$oidc_missing_secret_env"
printf '%s\n' \
  'SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID=google-client.apps.googleusercontent.com' \
  'SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_REDIRECT_URI={baseUrl}/api/v1/auth/oidc/callback/{registrationId}' \
  >> "$oidc_missing_secret_env"
chmod 600 "$oidc_missing_secret_env"
expect_preflight_failure \
  'missing Google OIDC client secret' \
  "$oidc_missing_secret_env" \
  'Google client secret is required when BATON_IDENTITY_OIDC_ENABLED=true'

oidc_invalid_redirect_env="$test_root/oidc-invalid-redirect.env"
write_valid_env "$oidc_invalid_redirect_env"
sed 's/^BATON_IDENTITY_OIDC_ENABLED=false$/BATON_IDENTITY_OIDC_ENABLED=true/' \
  "$oidc_invalid_redirect_env" > "$test_root/oidc-invalid-redirect.tmp"
mv "$test_root/oidc-invalid-redirect.tmp" "$oidc_invalid_redirect_env"
printf '%s\n' \
  'SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID=google-client.apps.googleusercontent.com' \
  "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET=$google_client_secret" \
  'SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_REDIRECT_URI=https://baton.example.com/login/oauth2/code/google' \
  >> "$oidc_invalid_redirect_env"
chmod 600 "$oidc_invalid_redirect_env"
expect_preflight_failure \
  'invalid Google OIDC redirect URI' \
  "$oidc_invalid_redirect_env" \
  'Google redirect URI must use the fixed BATON OIDC callback template'

go_enabled_env="$test_root/go-enabled.env"
write_valid_env "$go_enabled_env"
printf '%s\n' \
  'BATON_GO_ENABLED=true' \
  'BATON_GO_BASE_URL=https://go.example.com' \
  'BATON_GO_PUBLIC_BASE_URL=https://go.example.com' \
  "BATON_GO_MANAGEMENT_TOKEN=$go_management_token" \
  'BATON_ROUND_PUBLIC_BASE_URL=https://round.example.com' \
  >> "$go_enabled_env"
PATH="$fake_bin:$PATH" \
FAKE_DOCKER_LOG="$test_root/go-enabled-docker.log" \
"$repo_root/ops/preflight-production.sh" "$go_enabled_env" >/dev/null \
  || fail 'valid BATON GO production settings were rejected'

go_missing_token_env="$test_root/go-missing-token.env"
write_valid_env "$go_missing_token_env"
printf '%s\n' \
  'BATON_GO_ENABLED=true' \
  'BATON_GO_BASE_URL=https://go.example.com' \
  'BATON_GO_PUBLIC_BASE_URL=https://go.example.com' \
  'BATON_ROUND_PUBLIC_BASE_URL=https://round.example.com' \
  >> "$go_missing_token_env"
expect_preflight_failure \
  'missing BATON GO token' \
  "$go_missing_token_env" \
  'BATON_GO_MANAGEMENT_TOKEN is required'

go_insecure_origin_env="$test_root/go-insecure-origin.env"
write_valid_env "$go_insecure_origin_env"
printf '%s\n' \
  'BATON_GO_ENABLED=true' \
  'BATON_GO_BASE_URL=http://go.example.com' \
  'BATON_GO_PUBLIC_BASE_URL=https://go.example.com' \
  "BATON_GO_MANAGEMENT_TOKEN=$go_management_token" \
  'BATON_ROUND_PUBLIC_BASE_URL=https://round.example.com' \
  >> "$go_insecure_origin_env"
expect_preflight_failure \
  'insecure BATON GO origin' \
  "$go_insecure_origin_env" \
  'BATON_GO_BASE_URL must be an HTTPS origin'

round_private_key_file="$test_root/round-signing-key.pem"
round_jwk_set_file="$test_root/round-jwks.json"
printf '%s\n' 'test-only-private-key-fixture' > "$round_private_key_file"
printf '%s\n' '{"keys":[]}' > "$round_jwk_set_file"
chmod 600 "$round_private_key_file"
chmod 644 "$round_jwk_set_file"

round_enabled_env="$test_root/round-enabled.env"
write_valid_env "$round_enabled_env"
sed \
  -e 's/^BATON_IDENTITY_OIDC_ENABLED=false$/BATON_IDENTITY_OIDC_ENABLED=true/' \
  -e 's/^BATON_ROUND_GRANT_ENABLED=false$/BATON_ROUND_GRANT_ENABLED=true/' \
  "$round_enabled_env" > "$test_root/round-enabled.tmp"
mv "$test_root/round-enabled.tmp" "$round_enabled_env"
printf '%s\n' \
  'SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID=google-client.apps.googleusercontent.com' \
  "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET=$google_client_secret" \
  'SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_REDIRECT_URI={baseUrl}/api/v1/auth/oidc/callback/{registrationId}' \
  'BATON_GO_ENABLED=true' \
  'BATON_GO_BASE_URL=https://go.example.com' \
  'BATON_GO_PUBLIC_BASE_URL=https://go.example.com' \
  "BATON_GO_MANAGEMENT_TOKEN=$go_management_token" \
  'BATON_ROUND_PUBLIC_BASE_URL=https://baton.example.com' \
  'BATON_ROUND_GRANT_ACTIVE_KID=round-2026-01' \
  "BATON_ROUND_GRANT_PRIVATE_KEY_FILE=$round_private_key_file" \
  "BATON_ROUND_GRANT_JWK_SET_FILE=$round_jwk_set_file" \
  'BATON_ROUND_WEB_IMAGE=ghcr.io/example/round-baton-web@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
  'BATON_ROUND_SIGNALING_IMAGE=ghcr.io/example/round-signaling@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' \
  'BATON_ROUND_TURN_URLS=turns:turn.example.com:5349?transport=tcp' \
  "BATON_ROUND_TURN_SHARED_SECRET=$round_turn_shared_secret" \
  >> "$round_enabled_env"
chmod 600 "$round_enabled_env"
PATH="$fake_bin:$PATH" \
FAKE_DOCKER_LOG="$test_root/round-enabled-docker.log" \
"$repo_root/ops/preflight-production.sh" "$round_enabled_env" >/dev/null \
  || fail 'valid BATON ROUND production settings were rejected'
assert_contains 'compose.production-oidc.yml' \
  "$(cat "$test_root/round-enabled-docker.log")" \
  'ROUND requires OIDC Compose overlay'
assert_contains 'compose.production-round.yml' \
  "$(cat "$test_root/round-enabled-docker.log")" \
  'enabled ROUND Compose overlay'

round_wrong_origin_env="$test_root/round-wrong-origin.env"
cp "$round_enabled_env" "$round_wrong_origin_env"
sed 's#^BATON_ROUND_PUBLIC_BASE_URL=https://baton.example.com$#BATON_ROUND_PUBLIC_BASE_URL=https://round.example.com#' \
  "$round_wrong_origin_env" > "$test_root/round-wrong-origin.tmp"
mv "$test_root/round-wrong-origin.tmp" "$round_wrong_origin_env"
chmod 600 "$round_wrong_origin_env"
expect_preflight_failure \
  'ROUND cross-origin deployment' \
  "$round_wrong_origin_env" \
  'BATON_ROUND_PUBLIC_BASE_URL must equal the BATON HTTPS origin'

round_mutable_image_env="$test_root/round-mutable-image.env"
cp "$round_enabled_env" "$round_mutable_image_env"
sed 's#^BATON_ROUND_WEB_IMAGE=.*$#BATON_ROUND_WEB_IMAGE=ghcr.io/example/round-baton-web:latest#' \
  "$round_mutable_image_env" > "$test_root/round-mutable-image.tmp"
mv "$test_root/round-mutable-image.tmp" "$round_mutable_image_env"
chmod 600 "$round_mutable_image_env"
expect_preflight_failure \
  'mutable ROUND web image' \
  "$round_mutable_image_env" \
  'BATON_ROUND_WEB_IMAGE must be an immutable image reference'

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
