#!/usr/bin/env bash

set -Eeuo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(dirname -- "$(dirname -- "$script_dir")")"
stack_script="$script_dir/round-local-tls-stack.sh"
test_base_input="${TMPDIR:-/tmp}"
test_base="$(CDPATH= cd -- "$test_base_input" && pwd -P)"
test_root="$(mktemp -d "$test_base/baton-round-local-stack-test.XXXXXX")"
state_base="$test_root/state-base"
state_name="baton-round-local-tls-${UID}"
state_directory="$state_base/$state_name"
fake_bin="$test_root/fake-bin"
fake_docker_log="$test_root/fake-docker.log"

cleanup() {
  case "$test_root" in
    "$test_base"/baton-round-local-stack-test.*) rm -rf -- "$test_root" ;;
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
  [[ "$actual" == *"$expected"* ]] \
    || fail "$label: expected output to contain '$expected'"
}

mkdir -p -- "$state_base" "$fake_bin"

cat >"$fake_bin/docker" <<'SCRIPT'
#!/usr/bin/env bash
set -Eeuo pipefail

mode="${FAKE_DOCKER_MODE:-healthy}"

if [[ "${1:-}" == context && "${2:-}" == show ]]; then
  printf '%s\n' test-context
  exit 0
fi
if [[ "${1:-}" == info ]]; then
  if [[ "${2:-}" == --format ]]; then
    printf '%s\n' test-daemon
  fi
  exit 0
fi
if [[ "${1:-}" == compose && "${2:-}" == version ]]; then
  printf '%s\n' "${FAKE_COMPOSE_VERSION:-5.3.1}"
  exit 0
fi
if [[ "${1:-}" == ps ]]; then
  [[ "$mode" == stale-container ]] && printf '%s\n' stale-container
  exit 0
fi
if [[ "${1:-}" == volume && "${2:-}" == ls ]]; then
  [[ "$mode" == stale-volume ]] && printf '%s\n' stale-volume
  exit 0
fi
if [[ "${1:-}" == network && "${2:-}" == ls ]]; then
  [[ "$mode" == stale-network ]] && printf '%s\n' stale-network
  exit 0
fi
if [[ "${1:-}" == volume && "${2:-}" == inspect ]]; then
  exit 1
fi
if [[ "${1:-}" == network && "${2:-}" == inspect ]]; then
  exit 1
fi
if [[ "${1:-}" != compose ]]; then
  printf 'Unexpected fake docker command: %s\n' "$*" >&2
  exit 70
fi

for forbidden_name in \
  BATON_HOST \
  BATON_DB_PASSWORD \
  BATON_ROUND_TURN_SHARED_SECRET \
  COMPOSE_FILE \
  COMPOSE_PROJECT_NAME \
  ROUND_REPOSITORY_ROOT; do
  if [[ -n "${!forbidden_name+x}" ]]; then
    printf 'Ambient variable reached Compose: %s\n' "$forbidden_name" >&2
    exit 71
  fi
done
printf 'ambient-clean\n' >>"$FAKE_DOCKER_LOG"

if [[ "$*" == *" down --volumes --remove-orphans "* ]]; then
  [[ "$mode" == down-failure ]] && exit 74
  exit 0
fi
if [[ "$*" == *" config --quiet"* ]]; then
  case "$mode" in
    config-failure) exit 75 ;;
    signal-int)
      kill -INT "$PPID"
      exit 130
      ;;
    signal-term)
      kill -TERM "$PPID"
      exit 143
      ;;
    *) exit 0 ;;
  esac
fi

printf 'Unexpected fake compose command: %s\n' "$*" >&2
exit 76
SCRIPT
chmod 0700 "$fake_bin/docker"

cat >"$fake_bin/lsof" <<'SCRIPT'
#!/bin/sh
exit 1
SCRIPT
chmod 0700 "$fake_bin/lsof"

run_stack() {
  env \
    PATH="$fake_bin:$PATH" \
    BATON_ROUND_LOCAL_TEMP_BASE="$state_base" \
    FAKE_DOCKER_LOG="$fake_docker_log" \
    BATON_HOST=ambient.invalid \
    BATON_DB_PASSWORD=ambient-password \
    BATON_ROUND_TURN_SHARED_SECRET=ambient-turn-secret \
    COMPOSE_FILE=/tmp/ambient-compose.yml \
    COMPOSE_PROJECT_NAME=ambient-project \
    ROUND_REPOSITORY_ROOT="$repo_root" \
    "$stack_script" "$@"
}

reset_state() {
  if [[ -e "$state_directory" || -L "$state_directory" ]]; then
    rm -rf -- "$state_directory"
  fi
}

write_valid_state() {
  reset_state
  mkdir -m 0700 -- "$state_directory"
  printf 'version=1\nuid=%s\nproject=%s\ndocker_context=test-context\ndocker_daemon_id=test-daemon\n' \
    "$UID" \
    "$state_name" \
    >"$state_directory/.round-local-tls-state"
  printf '%s\n' "BATON_HOST='baton.localhost'" >"$state_directory/compose.env"
  chmod 0600 \
    "$state_directory/.round-local-tls-state" \
    "$state_directory/compose.env"
}

outside_parent="$test_root/outside"
mkdir -p -- "$outside_parent"
if output="$(BATON_ROUND_LOCAL_STATE_DIRECTORY="$outside_parent/$state_name" \
  run_stack up 2>&1)"; then
  fail 'state directory outside the canonical temporary root unexpectedly passed'
fi
assert_contains '직계 자식' "$output" 'state parent boundary'

symlink_target="$test_root/symlink-target"
mkdir -m 0700 -- "$symlink_target"
ln -s "$symlink_target" "$state_directory"
if output="$(run_stack down 2>&1)"; then
  fail 'symlinked state directory unexpectedly passed'
fi
assert_contains '심볼릭 링크' "$output" 'symlinked state directory'
[[ -d "$symlink_target" ]] || fail 'symlink target was removed'
reset_state

mkdir -m 0700 -- "$state_directory"
printf '%s\n' forged >"$state_directory/.round-local-tls-state"
printf '%s\n' "BATON_HOST='baton.localhost'" >"$state_directory/compose.env"
chmod 0600 "$state_directory/.round-local-tls-state" "$state_directory/compose.env"
if output="$(run_stack down 2>&1)"; then
  fail 'forged state sentinel unexpectedly passed'
fi
assert_contains 'sentinel' "$output" 'forged state sentinel'
[[ -d "$state_directory" ]] || fail 'forged state directory was removed'
reset_state

if output="$(FAKE_COMPOSE_VERSION=2.24.3 run_stack up 2>&1)"; then
  fail 'Docker Compose 2.24.3 unexpectedly passed'
fi
assert_contains '2.24.4 이상' "$output" 'minimum Compose version'
[[ ! -e "$state_directory" ]] || fail 'old Compose version created state'

for stale_mode in stale-volume stale-network; do
  if output="$(FAKE_DOCKER_MODE="$stale_mode" run_stack up 2>&1)"; then
    fail "$stale_mode unexpectedly passed"
  fi
  assert_contains 'container, volume 또는 network' "$output" "$stale_mode rejection"
  [[ ! -e "$state_directory" ]] || fail "$stale_mode created state"
done

write_valid_state
if output="$(FAKE_DOCKER_MODE=down-failure run_stack down 2>&1)"; then
  fail 'Compose down failure unexpectedly passed'
fi
assert_contains '상태 파일을 보존' "$output" 'failed down state preservation'
assert_contains "BATON_ROUND_LOCAL_TEMP_BASE='$state_base'" "$output" \
  'failed down recovery temp base'
assert_contains "BATON_ROUND_LOCAL_STATE_DIRECTORY='$state_directory'" "$output" \
  'failed down recovery state directory'
[[ -d "$state_directory" ]] || fail 'failed down removed recovery state'

write_valid_state
run_stack down >/dev/null || fail 'successful down failed'
[[ ! -e "$state_directory" ]] || fail 'successful down did not remove state'

: >"$fake_docker_log"
if output="$(FAKE_DOCKER_MODE=config-failure run_stack up 2>&1)"; then
  fail 'injected Compose config failure unexpectedly passed'
fi
[[ ! -e "$state_directory" ]] || fail 'pre-start failure did not remove managed state'
assert_contains 'ambient-clean' "$(cat "$fake_docker_log")" 'ambient Compose isolation'

for signal_mode in signal-int signal-term; do
  expected_status=130
  [[ "$signal_mode" == signal-term ]] && expected_status=143
  set +e
  output="$(FAKE_DOCKER_MODE="$signal_mode" run_stack up 2>&1)"
  actual_status=$?
  set -e
  [[ $actual_status -eq $expected_status ]] \
    || fail "$signal_mode returned $actual_status instead of $expected_status"
  [[ ! -e "$state_directory" ]] || fail "$signal_mode did not clean managed state"
done

printf 'ROUND local TLS stack safety checks passed.\n'
