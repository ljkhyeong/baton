#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPOSITORY_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd -P)"
DEFAULT_ROUND_ROOT="$(dirname -- "$REPOSITORY_ROOT")/webRTC"
ROUND_ROOT_OVERRIDE="${ROUND_REPOSITORY_ROOT:-}"
ROUND_ROOT="${ROUND_REPOSITORY_ROOT:-$DEFAULT_ROUND_ROOT}"
SIGNALING_JAR="${ROUND_SIGNALING_JAR:-}"

log() {
  printf '[round-consumer-contract] %s\n' "$*"
}

fail() {
  log "$*" >&2
  exit 1
}

canonical_file() {
  local target="$1"
  local parent

  parent="$(CDPATH= cd -- "$(dirname -- "$target")" && pwd -P)"
  printf '%s/%s\n' "$parent" "$(basename -- "$target")"
}

require_safe_path_text() {
  local label="$1"
  local value="$2"

  [[ -n "$value" ]] || fail "$label 값이 비어 있습니다"
  [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] \
    || fail "$label 값에 줄바꿈을 사용할 수 없습니다"
}

command -v java >/dev/null 2>&1 || fail 'Java 21 실행 파일을 찾지 못했습니다'
command -v git >/dev/null 2>&1 || fail 'Git 실행 파일을 찾지 못했습니다'
[[ -x "$REPOSITORY_ROOT/gradlew" ]] || fail 'BATON Gradle Wrapper를 찾지 못했습니다'
if [[ -n "$ROUND_ROOT_OVERRIDE" && -n "$SIGNALING_JAR" ]]; then
  fail 'ROUND_REPOSITORY_ROOT와 ROUND_SIGNALING_JAR는 동시에 지정할 수 없습니다'
fi

if [[ -z "$SIGNALING_JAR" ]]; then
  require_safe_path_text ROUND_REPOSITORY_ROOT "$ROUND_ROOT"
  [[ "$ROUND_ROOT" == /* ]] || fail 'ROUND_REPOSITORY_ROOT는 절대 경로여야 합니다'
  [[ -d "$ROUND_ROOT" ]] || fail "ROUND 저장소를 찾지 못했습니다: $ROUND_ROOT"
  ROUND_ROOT="$(CDPATH= cd -- "$ROUND_ROOT" && pwd -P)"
  [[ -x "$ROUND_ROOT/gradlew" && -f "$ROUND_ROOT/apps/signaling/build.gradle" ]] \
    || fail "ROUND signaling Gradle 프로젝트를 찾지 못했습니다: $ROUND_ROOT"

  ROUND_REVISION="$(git -C "$ROUND_ROOT" rev-parse --verify HEAD)"
  [[ "$ROUND_REVISION" =~ ^[0-9a-f]{40}$ ]] \
    || fail 'ROUND Git revision을 확인하지 못했습니다'
  if [[ -n "$(git -C "$ROUND_ROOT" status --porcelain)" ]]; then
    ROUND_REVISION="$ROUND_REVISION+dirty"
  fi
  log "검증할 ROUND revision: $ROUND_REVISION"

  ROUND_VERSION="$(
    cd "$ROUND_ROOT"
    ./gradlew -q :apps:signaling:properties | sed -n 's/^version: //p'
  )"
  [[ "$ROUND_VERSION" =~ ^[A-Za-z0-9][A-Za-z0-9._+-]*$ ]] \
    || fail "ROUND signaling version을 안전하게 확인하지 못했습니다: $ROUND_VERSION"

  log '외부 ROUND signaling bootJar를 현재 소스에서 빌드합니다.'
  (
    cd "$ROUND_ROOT"
    ./gradlew --no-daemon :apps:signaling:bootJar
  )
  SIGNALING_JAR="$ROUND_ROOT/apps/signaling/build/libs/signaling-$ROUND_VERSION.jar"
fi

require_safe_path_text ROUND_SIGNALING_JAR "$SIGNALING_JAR"
[[ "$SIGNALING_JAR" == /* ]] || fail 'ROUND_SIGNALING_JAR는 절대 경로여야 합니다'
[[ -f "$SIGNALING_JAR" && -r "$SIGNALING_JAR" ]] \
  || fail "읽을 수 있는 ROUND signaling JAR를 찾지 못했습니다: $SIGNALING_JAR"
SIGNALING_JAR="$(canonical_file "$SIGNALING_JAR")"
log "검증할 ROUND signaling JAR: $SIGNALING_JAR"

log 'BATON 실제 서명 결과를 ROUND TURN·WebSocket 소비 경계에서 검증합니다.'
(
  cd "$REPOSITORY_ROOT"
  ./gradlew --no-daemon \
    :adapter-out-external:roundConsumerContractTest \
    "-ProundSignalingJar=$SIGNALING_JAR"
)

log 'BATON → ROUND participation grant 소비자 계약이 통과했습니다.'
