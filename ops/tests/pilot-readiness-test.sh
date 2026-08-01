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
  BATON_WATCH_ENABLED \
  BATON_WATCH_MONITORING_ENABLED \
  BATON_WATCH_BASE_URL \
  BATON_WATCH_BEARER_TOKEN \
  BATON_WATCH_SOURCE_NAMESPACE \
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
watch_token="5555555555555555555555555555555555555555555555555555555555555555"

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
  assert_not_contains "$watch_token" "$output" "$label secret leak"
}

valid_env="$test_root/valid.env"
write_valid_env "$valid_env"
valid_env_canonical="$(CDPATH= cd -- "$(dirname -- "$valid_env")" && pwd -P)/$(basename -- "$valid_env")"
preflight_output="$(PATH="$fake_bin:$PATH" \
  FAKE_DOCKER_LOG="$test_root/docker.log" \
  BATON_HOST=ambient.invalid \
  BATON_DB_PASSWORD=ambient-password \
  BATON_HTTP_PUBLISH=127.0.0.1::80 \
  COMPOSE_ENV_FILES=/tmp/ambient.env \
  COMPOSE_PROJECT_NAME=ambient-project \
  "$repo_root/ops/preflight-production.sh" "$valid_env" 2>&1)" \
  || fail 'valid production preflight failed'
assert_contains 'Production preflight passed' "$preflight_output" 'valid production preflight'
assert_not_contains "$db_password" "$preflight_output" 'valid preflight secret leak'
assert_contains '--project-name baton-production' "$(cat "$test_root/docker.log")" \
  'production Compose project boundary'
assert_contains "--env-file $valid_env_canonical" "$(cat "$test_root/docker.log")" \
  'production Compose env file boundary'
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
