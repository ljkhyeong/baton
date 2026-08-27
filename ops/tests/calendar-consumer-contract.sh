#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPOSITORY_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd -P)"
COMMON_GIT_DIRECTORY="$(git -C "$REPOSITORY_ROOT" rev-parse --path-format=absolute --git-common-dir)"
DEFAULT_CAL_ROOT="$(dirname -- "$(dirname -- "$COMMON_GIT_DIRECTORY")")/baton-cal"
CAL_ROOT="${BATON_CAL_REPOSITORY_ROOT:-$DEFAULT_CAL_ROOT}"
CAL_IMAGE_OVERRIDE="${BATON_CAL_IMAGE:-}"
CAL_CONTRACT_VERSION='1.0.0'
CAL_TOKEN='calendar-consumer-contract-token-000000000001'
CAL_GENERATION='40000000-0000-0000-0000-000000000001'
COMPOSE_PROJECT_NAME="baton-cal-consumer-$$"

log() {
  printf '[calendar-consumer-contract] %s\n' "$*"
}

fail() {
  log "$*" >&2
  exit 1
}

cleanup() {
  docker compose \
    --project-name "$COMPOSE_PROJECT_NAME" \
    --file "$CAL_ROOT/compose.smoke.yml" \
    down --volumes --remove-orphans >/dev/null 2>&1 || true
}

trap cleanup EXIT

command -v docker >/dev/null 2>&1 || fail 'Docker 실행 파일을 찾지 못했습니다'
command -v curl >/dev/null 2>&1 || fail 'curl 실행 파일을 찾지 못했습니다'
command -v git >/dev/null 2>&1 || fail 'Git 실행 파일을 찾지 못했습니다'
[[ -x "$REPOSITORY_ROOT/gradlew" ]] || fail 'BATON Gradle Wrapper를 찾지 못했습니다'
[[ "$CAL_ROOT" == /* ]] || fail 'BATON_CAL_REPOSITORY_ROOT는 절대 경로여야 합니다'
[[ -x "$CAL_ROOT/gradlew" && -f "$CAL_ROOT/compose.smoke.yml" ]] \
  || fail "BATON CAL 저장소를 찾지 못했습니다: $CAL_ROOT"
CAL_ROOT="$(CDPATH= cd -- "$CAL_ROOT" && pwd -P)"

actual_contract_version="$(<"$CAL_ROOT/contracts/VERSION")"
[[ "$actual_contract_version" == "$CAL_CONTRACT_VERSION" ]] \
  || fail "CAL 계약 버전이 다릅니다: $actual_contract_version"

if [[ -n "$CAL_IMAGE_OVERRIDE" ]]; then
  CAL_IMAGE="$CAL_IMAGE_OVERRIDE"
else
  CAL_REVISION="$(git -C "$CAL_ROOT" rev-parse --short=12 HEAD)"
  CAL_IMAGE="baton-cal:consumer-$CAL_REVISION"
  log "CAL $CAL_CONTRACT_VERSION 이미지를 현재 소스에서 빌드합니다."
  (
    cd "$CAL_ROOT"
    ./gradlew --no-daemon bootBuildImage "--imageName=$CAL_IMAGE"
  )
fi

export BATON_CAL_IMAGE="$CAL_IMAGE"
export BATON_CAL_INTERNAL_TOKEN="$CAL_TOKEN"
export BATON_CAL_SUBSCRIPTION_GENERATION="$CAL_GENERATION"

log "CAL 컨테이너를 시작합니다: $CAL_IMAGE"
docker compose \
  --project-name "$COMPOSE_PROJECT_NAME" \
  --file "$CAL_ROOT/compose.smoke.yml" \
  up --detach --wait --wait-timeout 120

CAL_PORT="$(docker compose \
  --project-name "$COMPOSE_PROJECT_NAME" \
  --file "$CAL_ROOT/compose.smoke.yml" \
  port app 8080)"
CAL_BASE_URL="http://$CAL_PORT"
if ! curl --fail --silent --retry 20 --retry-all-errors --retry-delay 1 \
  "$CAL_BASE_URL/actuator/health/readiness" >/dev/null; then
  fail 'CAL 준비 상태를 확인하지 못했습니다'
fi

log 'BATON 운영용 직렬화와 응답 분류를 실제 CAL에 검증합니다.'
(
  cd "$REPOSITORY_ROOT"
  BATON_CAL_LIVE_BASE_URL="$CAL_BASE_URL" \
  BATON_CAL_LIVE_BEARER_TOKEN="$CAL_TOKEN" \
    ./gradlew --no-daemon :adapter-out-external:calendarConsumerContractTest
)

log 'BATON → CAL 생성·변경·취소·중복·역순 전달 계약이 통과했습니다.'
