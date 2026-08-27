#!/usr/bin/env bash

set -Eeuo pipefail

if [[ $# -ne 0 ]]; then
  printf '사용법: %s\n' "$0" >&2
  exit 1
fi

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(dirname -- "$script_dir")"
env_file="${BATON_PRODUCTION_ENV_FILE:-$repo_root/.env.production}"
compose=(
  env
  BATON_PRODUCTION_ENV_FILE="$env_file"
  "$script_dir/production-compose.sh"
)

if ! metrics_output="$(
  "${compose[@]}" exec -T app sh -ec \
    'exec wget -q -O - http://127.0.0.1:8080/actuator/prometheus'
)"; then
  printf 'BATON 애플리케이션 컨테이너에서 연동 지표를 읽지 못했습니다.\n' >&2
  exit 1
fi

if ! integration_metrics="$(
  printf '%s\n' "$metrics_output" | grep -E '^baton_integration_'
)"; then
  printf 'Prometheus 응답에 BATON 연동 지표가 없습니다.\n' >&2
  exit 1
fi

printf 'BATON 연동 지표:\n%s\n' "$integration_metrics"
