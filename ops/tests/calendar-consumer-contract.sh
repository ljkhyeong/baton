#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPOSITORY_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd -P)"
COMMON_GIT_DIRECTORY="$(git -C "$REPOSITORY_ROOT" rev-parse --path-format=absolute --git-common-dir)"
DEFAULT_CAL_ROOT="$(dirname -- "$(dirname -- "$COMMON_GIT_DIRECTORY")")/baton-cal"
CAL_ROOT="${BATON_CAL_REPOSITORY_ROOT:-$DEFAULT_CAL_ROOT}"
CAL_IMAGE_OVERRIDE="${BATON_CAL_IMAGE:-}"
CAL_CONTRACT_VERSION="${BATON_CAL_CONTRACT_VERSION:-1.1.0-rc.2}"
CAL_MANAGEMENT_PORT=8081
CONTRACT_TEST_OPTIONS=(
  :adapter-out-external:calendarConsumerContractTest
  :application:calendarMetadataOutboxContractTest
)
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

[[ $# -eq 0 ]] || fail '사용법: calendar-consumer-contract.sh'

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
case "$CAL_CONTRACT_VERSION" in
  1.1.0-rc.1) log '게시된 1.1.0-rc.1 사전 릴리스 계약을 검증합니다.' ;;
  1.1.0-rc.2)
    log '1.1.0-rc.2의 구독·복구 진단 계약을 검증합니다.'
    CONTRACT_TEST_OPTIONS+=( :application:calendarSubscriptionContractTest )
    ;;
  *) fail "지원하지 않는 CAL 계약 버전입니다: $CAL_CONTRACT_VERSION" ;;
esac
export BATON_CAL_CONTRACT_VERSION="$CAL_CONTRACT_VERSION"
log "CAL 소스 커밋: $(git -C "$CAL_ROOT" rev-parse HEAD)"

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
export BATON_CAL_RECOVERY_MODE=false

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
CAL_HEALTH_PORT="$(docker compose \
  --project-name "$COMPOSE_PROJECT_NAME" \
  --file "$CAL_ROOT/compose.smoke.yml" \
  port app "$CAL_MANAGEMENT_PORT")"
if ! curl --fail --silent --connect-timeout 1 --max-time 2 \
  --retry 60 --retry-all-errors --retry-delay 1 --retry-max-time 180 \
  "http://$CAL_HEALTH_PORT/actuator/health/readiness" >/dev/null; then
  docker compose --project-name "$COMPOSE_PROJECT_NAME" \
    --file "$CAL_ROOT/compose.smoke.yml" logs --no-color --tail 100 app >&2 || true
  fail 'CAL 준비 상태를 확인하지 못했습니다'
fi

log 'BATON 운영용 직렬화와 응답 분류를 실제 CAL에 검증합니다.'
(
  cd "$REPOSITORY_ROOT"
  BATON_CAL_LIVE_BASE_URL="$CAL_BASE_URL" \
  BATON_CAL_LIVE_BEARER_TOKEN="$CAL_TOKEN" \
  BATON_CAL_LIVE_COMPOSE_PROJECT="$COMPOSE_PROJECT_NAME" \
  BATON_CAL_LIVE_COMPOSE_FILE="$CAL_ROOT/compose.smoke.yml" \
    ./gradlew --no-daemon "${CONTRACT_TEST_OPTIONS[@]}"
)

log 'BATON → CAL 생성·변경·취소·중복·역순 전달 계약이 통과했습니다.'
log '시즌 이름 전달과 실제 CAL 백업 복원 뒤 같은 개정 번호의 최신 이름 재전달이 통과했습니다.'
log 'CAL을 새 복구 모드로 다시 시작합니다.'
docker compose \
  --project-name "$COMPOSE_PROJECT_NAME" \
  --file "$CAL_ROOT/compose.smoke.yml" \
  down --volumes --remove-orphans >/dev/null
export BATON_CAL_RECOVERY_MODE=true
docker compose \
  --project-name "$COMPOSE_PROJECT_NAME" \
  --file "$CAL_ROOT/compose.smoke.yml" \
  up --detach --wait --wait-timeout 120

CAL_PORT="$(docker compose \
  --project-name "$COMPOSE_PROJECT_NAME" \
  --file "$CAL_ROOT/compose.smoke.yml" \
  port app 8080)"
CAL_BASE_URL="http://$CAL_PORT"
CAL_HEALTH_PORT="$(docker compose \
  --project-name "$COMPOSE_PROJECT_NAME" \
  --file "$CAL_ROOT/compose.smoke.yml" \
  port app "$CAL_MANAGEMENT_PORT")"
if ! curl --fail --silent --connect-timeout 1 --max-time 2 \
  --retry 60 --retry-all-errors --retry-delay 1 --retry-max-time 180 \
  "http://$CAL_HEALTH_PORT/actuator/health/readiness" >/dev/null; then
  docker compose --project-name "$COMPOSE_PROJECT_NAME" \
    --file "$CAL_ROOT/compose.smoke.yml" logs --no-color --tail 100 app >&2 || true
  fail '복구 모드 CAL 준비 상태를 확인하지 못했습니다'
fi

(
  cd "$REPOSITORY_ROOT"
  BATON_CAL_LIVE_BASE_URL="$CAL_BASE_URL" \
  BATON_CAL_LIVE_BEARER_TOKEN="$CAL_TOKEN" \
    ./gradlew --no-daemon :adapter-out-external:calendarRecoveryConsumerContractTest
)
log '시즌별 상태 검증과 전체 복구 완료 계약이 통과했습니다.'
