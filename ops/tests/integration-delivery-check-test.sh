#!/usr/bin/env bash

set -Eeuo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(dirname -- "$(dirname -- "$script_dir")")"
test_base="${TMPDIR:-/tmp}"
test_base="${test_base%/}"
test_base="$(CDPATH= cd -- "$test_base" && pwd -P)"
test_root="$(mktemp -d "$test_base/baton-integration-delivery-test.XXXXXX")"
fixture_ops="$test_root/ops"
metrics_file="$test_root/metrics.prom"

cleanup() {
  rm -rf -- "$test_root"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

mkdir -p -- "$fixture_ops"
cp "$repo_root/ops/check-integration-delivery.sh" \
  "$fixture_ops/check-integration-delivery.sh"
printf '%s\n' \
  '#!/usr/bin/env bash' \
  'set -Eeuo pipefail' \
  'cat "$BATON_TEST_INTEGRATION_METRICS_FILE"' \
  > "$fixture_ops/show-integration-metrics.sh"
chmod 700 "$fixture_ops"/*.sh

write_metrics() {
  local refresh_success="$1"
  local last_refresh="$2"
  local calendar_failed="$3"
  local watch_failed="$4"
  local calendar_expired="$5"
  local watch_expired="$6"

  printf '%s\n' \
    'BATON 연동 지표:' \
    "baton_integration_metrics_refresh_success $refresh_success" \
    "baton_integration_metrics_last_successful_refresh_time_seconds $last_refresh" \
    "baton_integration_delivery_items{integration=\"calendar\",status=\"failed\"} $calendar_failed" \
    "baton_integration_delivery_items{status=\"failed\",integration=\"watch\"} $watch_failed" \
    "baton_integration_delivery_expired_processing_items{integration=\"calendar\"} $calendar_expired" \
    "baton_integration_delivery_expired_processing_items{integration=\"watch\"} $watch_expired" \
    > "$metrics_file"
}

run_check() {
  BATON_TEST_INTEGRATION_METRICS_FILE="$metrics_file" \
  BATON_INTEGRATION_METRICS_MAX_STALENESS_SECONDS=120 \
    "$fixture_ops/check-integration-delivery.sh"
}

assert_failure() {
  local expected="$1"
  local output

  if output="$(run_check 2>&1)"; then
    fail "실패해야 하는 연동 전달 점검이 성공했습니다: $expected"
  fi
  if [[ "$output" != *"$expected"* ]]; then
    fail "예상한 실패 문구를 찾지 못했습니다: expected=$expected output=$output"
  fi
}

now_epoch="$(date -u '+%s')"
write_metrics 1 "$((now_epoch - 10))" 0 0 0 0
run_check >/dev/null || fail "정상 연동 전달 지표를 거부했습니다"

write_metrics 1 "$((now_epoch - 10))" 1 0 0 0
assert_failure 'integration=calendar failed_items=1'

write_metrics 1 "$((now_epoch - 10))" 0 0 0 1
assert_failure 'integration=watch expired_processing_items=1'

write_metrics 0 "$((now_epoch - 10))" 0 0 0 0
assert_failure '최근 갱신이 실패했습니다'

write_metrics 1 "$((now_epoch - 121))" 0 0 0 0
assert_failure '마지막 정상 갱신 시각이 유효하지 않거나 오래됐습니다'

printf 'PASS: BATON 연동 전달 점검은 정상 지표만 허용하고 확정 장애를 거부합니다.\n'
