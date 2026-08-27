#!/usr/bin/env bash

set -Eeuo pipefail
export LC_ALL=C

if [[ $# -ne 0 ]]; then
  printf '사용법: %s\n' "$0" >&2
  exit 1
fi

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
max_staleness_seconds="${BATON_INTEGRATION_METRICS_MAX_STALENESS_SECONDS:-120}"

if [[ ! "$max_staleness_seconds" =~ ^[1-9][0-9]{1,3}$ ]] \
  || (( 10#$max_staleness_seconds < 60 || 10#$max_staleness_seconds > 3600 )); then
  printf 'BATON_INTEGRATION_METRICS_MAX_STALENESS_SECONDS는 60~3600의 정수여야 합니다: %s\n' \
    "$max_staleness_seconds" >&2
  exit 1
fi

if ! metrics_output="$("$script_dir/show-integration-metrics.sh")"; then
  printf 'BATON 연동 전달 지표를 읽지 못했습니다.\n' >&2
  exit 1
fi

metric_value() {
  local metric_name="$1"
  local integration="${2:-}"
  local status="${3:-}"

  printf '%s\n' "$metrics_output" | awk \
    -v metric_name="$metric_name" \
    -v integration="$integration" \
    -v status="$status" '
      index($0, metric_name " ") == 1 || index($0, metric_name "{") == 1 {
        if (integration != "" \
            && index($0, "integration=\"" integration "\"") == 0) {
          next
        }
        if (status != "" && index($0, "status=\"" status "\"") == 0) {
          next
        }
        count += 1
        value = $NF
      }
      END {
        if (count != 1) {
          exit 1
        }
        print value
      }
    '
}

require_metric() {
  local metric_name="$1"
  local integration="${2:-}"
  local status="${3:-}"
  local value

  if ! value="$(metric_value "$metric_name" "$integration" "$status")" \
    || [[ ! "$value" =~ ^[0-9]+([.][0-9]+)?([eE][+-]?[0-9]+)?$ ]]; then
    printf '필수 BATON 연동 지표가 없거나 숫자가 아닙니다: metric=%s integration=%s status=%s\n' \
      "$metric_name" "${integration:--}" "${status:--}" >&2
    return 1
  fi
  printf '%s\n' "$value"
}

refresh_success="$(require_metric baton_integration_metrics_refresh_success)" || exit 1
last_refresh="$(require_metric \
  baton_integration_metrics_last_successful_refresh_time_seconds)" || exit 1
now_epoch="$(date -u '+%s')"

if ! awk -v value="$refresh_success" 'BEGIN { exit !(value == 1) }'; then
  printf 'BATON 연동 지표의 최근 갱신이 실패했습니다.\n' >&2
  exit 1
fi
if ! awk \
  -v now="$now_epoch" \
  -v refreshed="$last_refresh" \
  -v maximum="$max_staleness_seconds" \
  'BEGIN {
    age = now - refreshed
    exit !(refreshed > 0 && age >= 0 && age <= maximum)
  }'; then
  printf 'BATON 연동 지표의 마지막 정상 갱신 시각이 유효하지 않거나 오래됐습니다: last_refresh=%s now=%s max_staleness_seconds=%s\n' \
    "$last_refresh" "$now_epoch" "$max_staleness_seconds" >&2
  exit 1
fi

delivery_failure=false
for integration in calendar watch; do
  failed_items="$(require_metric \
    baton_integration_delivery_items "$integration" failed)" || exit 1
  expired_processing_items="$(require_metric \
    baton_integration_delivery_expired_processing_items "$integration")" || exit 1

  if awk -v value="$failed_items" 'BEGIN { exit !(value > 0) }'; then
    printf 'BATON 연동 전달에 영구 실패 항목이 있습니다: integration=%s failed_items=%s\n' \
      "$integration" "$failed_items" >&2
    delivery_failure=true
  fi
  if awk -v value="$expired_processing_items" 'BEGIN { exit !(value > 0) }'; then
    printf 'BATON 연동 전달에 만료된 처리 임대가 있습니다: integration=%s expired_processing_items=%s\n' \
      "$integration" "$expired_processing_items" >&2
    delivery_failure=true
  fi
done

if [[ "$delivery_failure" == true ]]; then
  exit 1
fi

printf 'BATON 연동 전달의 확정 장애가 없습니다: last_refresh=%s\n' "$last_refresh"
