#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FRONTEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPOSITORY_ROOT="$(cd "$FRONTEND_DIR/.." && pwd)"
COMPOSE_FILE="$REPOSITORY_ROOT/compose.fullstack-e2e.yml"
COMPOSE_PROJECT="baton-fullstack-e2e-$$-$RANDOM"
BACKEND_PORT="${BATON_FULLSTACK_BACKEND_PORT:-18080}"
FRONTEND_PORT="${BATON_FULLSTACK_FRONTEND_PORT:-3200}"
CREATION_KEY="${BATON_FULLSTACK_CREATION_KEY:-fullstack-creation-key-0000000000000001}"
RECOVERY_KEY="${BATON_FULLSTACK_RECOVERY_KEY:-fullstack-recovery-key-0000000000000002}"
TEMP_BASE="${TMPDIR:-/tmp}"
TEMP_BASE="${TEMP_BASE%/}"
RUN_DIR="$(mktemp -d "$TEMP_BASE/baton-fullstack-e2e.XXXXXX")"
BACKEND_LOG="$RUN_DIR/backend.log"
VITE_LOG="$RUN_DIR/vite.log"
ROUND_PRIVATE_KEY="$RUN_DIR/round-private.pem"
ROUND_PUBLIC_KEY="$RUN_DIR/round-public.pem"
BACKEND_PID=""
VITE_PID=""
COMPOSE=(docker compose --project-name "$COMPOSE_PROJECT" --file "$COMPOSE_FILE")
ACCOUNT_EMAIL="round.fullstack@example.test"
ACCOUNT_PASSWORD="Round-Fullstack-Password-2026!"
ROUND_ISSUER="https://baton.fullstack.test"
ROUND_KID="baton-round-fullstack-e2e"

log() {
  printf '[fullstack-e2e] %s\n' "$*"
}

terminate_process() {
  local pid="${1:-}"
  if [[ -z "$pid" ]] || ! kill -0 "$pid" 2>/dev/null; then
    return
  fi

  kill "$pid" 2>/dev/null || true
  for ((attempt = 0; attempt < 50; attempt += 1)); do
    if ! kill -0 "$pid" 2>/dev/null; then
      wait "$pid" 2>/dev/null || true
      return
    fi
    sleep 0.2
  done
  kill -KILL "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
}

preserve_failure_logs() {
  local artifact_dir="$FRONTEND_DIR/test-results/fullstack-runtime"
  mkdir -p "$artifact_dir"
  cp "$BACKEND_LOG" "$artifact_dir/backend.log" 2>/dev/null || true
  cp "$VITE_LOG" "$artifact_dir/vite.log" 2>/dev/null || true
  "${COMPOSE[@]}" logs --no-color mysql >"$artifact_dir/mysql.log" 2>&1 || true
  log "실패 로그를 $artifact_dir 에 보존했습니다."
}

cleanup() {
  local exit_status=$?
  trap - EXIT INT TERM

  terminate_process "$VITE_PID"
  terminate_process "$BACKEND_PID"
  if [[ "$exit_status" -ne 0 ]]; then
    preserve_failure_logs
  fi
  "${COMPOSE[@]}" down --volumes --remove-orphans --timeout 10 >/dev/null 2>&1 || true

  case "$RUN_DIR" in
    "$TEMP_BASE"/baton-fullstack-e2e.*) rm -rf -- "$RUN_DIR" ;;
  esac
  exit "$exit_status"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

wait_for_process_url() {
  local url="$1"
  local pid="$2"
  local label="$3"
  local log_file="$4"

  for ((attempt = 1; attempt <= 120; attempt += 1)); do
    if ! kill -0 "$pid" 2>/dev/null; then
      log "$label 프로세스가 준비 전에 종료됐습니다."
      tail -n 120 "$log_file" 2>/dev/null || true
      return 1
    fi
    if curl --fail --silent --show-error "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done

  log "$label 준비 시간이 초과됐습니다: $url"
  tail -n 120 "$log_file" 2>/dev/null || true
  return 1
}

require_available_port() {
  local port="$1"
  local label="$2"

  if [[ ! "$port" =~ ^[0-9]+$ ]] || ((port < 1 || port > 65535)); then
    log "$label 포트가 올바르지 않습니다: $port"
    return 1
  fi
  if (echo >/dev/tcp/127.0.0.1/"$port") 2>/dev/null; then
    log "$label 포트를 이미 다른 프로세스가 사용 중입니다: $port"
    return 1
  fi
}

command -v docker >/dev/null
command -v curl >/dev/null
command -v java >/dev/null
command -v openssl >/dev/null
docker info >/dev/null
test -x "$REPOSITORY_ROOT/gradlew"
test -x "$FRONTEND_DIR/node_modules/.bin/vite"
test -x "$FRONTEND_DIR/node_modules/.bin/playwright"
require_available_port "$BACKEND_PORT" "Spring Boot"
require_available_port "$FRONTEND_PORT" "Vite"

log "폐기 가능한 ROUND RSA key pair를 만듭니다."
openssl genpkey \
  -algorithm RSA \
  -pkeyopt rsa_keygen_bits:2048 \
  -out "$ROUND_PRIVATE_KEY" >/dev/null 2>&1
openssl pkey \
  -in "$ROUND_PRIVATE_KEY" \
  -pubout \
  -out "$ROUND_PUBLIC_KEY" >/dev/null 2>&1

log "실행 가능한 Spring Boot jar를 만듭니다."
(
  cd "$REPOSITORY_ROOT"
  ./gradlew --no-daemon :bootstrap:clean :bootstrap:bootJar
)

BOOT_JAR=""
for candidate in "$REPOSITORY_ROOT"/bootstrap/build/libs/*.jar; do
  if [[ -f "$candidate" && "$candidate" != *-plain.jar ]]; then
    if [[ -n "$BOOT_JAR" ]]; then
      log "실행 가능한 Spring Boot jar가 둘 이상입니다."
      exit 1
    fi
    BOOT_JAR="$candidate"
  fi
done
if [[ -z "$BOOT_JAR" ]]; then
  log "실행 가능한 Spring Boot jar를 찾지 못했습니다."
  exit 1
fi

log "격리된 MySQL을 시작합니다: $COMPOSE_PROJECT"
if ! "${COMPOSE[@]}" up -d --wait --wait-timeout 90 mysql; then
  log "MySQL 준비 시간이 초과됐습니다."
  "${COMPOSE[@]}" logs --no-color mysql
  exit 1
fi

MYSQL_ENDPOINT="$("${COMPOSE[@]}" port mysql 3306)"
MYSQL_PORT="${MYSQL_ENDPOINT##*:}"
if [[ ! "$MYSQL_PORT" =~ ^[0-9]+$ ]]; then
  log "MySQL 임시 포트를 확인하지 못했습니다: $MYSQL_ENDPOINT"
  exit 1
fi

log "Spring Boot를 127.0.0.1:$BACKEND_PORT 에서 시작합니다."
SPRING_PROFILES_ACTIVE=local \
SPRING_DEVTOOLS_RESTART_ENABLED=false \
SPRING_FLYWAY_LOCATIONS="classpath:db/migration,filesystem:$FRONTEND_DIR/tests/fullstack/db" \
DB_URL="jdbc:mysql://127.0.0.1:$MYSQL_PORT/baton_fullstack_e2e?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8" \
DB_USERNAME=baton_fullstack_e2e \
DB_PASSWORD=fullstack-database-password \
SERVER_ADDRESS=127.0.0.1 \
BATON_SERVER_PORT="$BACKEND_PORT" \
BATON_WORKSPACE_CREATION_KEY="$CREATION_KEY" \
BATON_WORKSPACE_RECOVERY_KEY="$RECOVERY_KEY" \
BATON_ROUND_PARTICIPATION_GRANT_ENABLED=true \
BATON_ROUND_PARTICIPATION_GRANT_ISSUER="$ROUND_ISSUER" \
BATON_ROUND_PARTICIPATION_GRANT_AUDIENCE=round \
BATON_ROUND_PARTICIPATION_GRANT_CURRENT_KID="$ROUND_KID" \
BATON_ROUND_PARTICIPATION_GRANT_PRIVATE_KEY_PATH="$ROUND_PRIVATE_KEY" \
BATON_ROUND_PARTICIPATION_GRANT_PUBLIC_KEY_PATH="$ROUND_PUBLIC_KEY" \
java -jar "$BOOT_JAR" >"$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!
wait_for_process_url "http://127.0.0.1:$BACKEND_PORT/actuator/health" "$BACKEND_PID" "Spring Boot" "$BACKEND_LOG"

log "Vite를 127.0.0.1:$FRONTEND_PORT 에서 시작합니다."
BATON_API_PROXY_TARGET="http://127.0.0.1:$BACKEND_PORT" \
VITE_WORKSPACE_SYNC_INTERVAL_MS=1000 \
"$FRONTEND_DIR/node_modules/.bin/vite" \
  "$FRONTEND_DIR" \
  --host 127.0.0.1 \
  --port "$FRONTEND_PORT" \
  --strictPort >"$VITE_LOG" 2>&1 &
VITE_PID=$!
wait_for_process_url "http://127.0.0.1:$FRONTEND_PORT" "$VITE_PID" "Vite" "$VITE_LOG"

log "실제 브라우저 → Vite → Spring → MySQL 흐름을 검증합니다."
(
  cd "$FRONTEND_DIR"
  BATON_FULLSTACK_BASE_URL="http://127.0.0.1:$FRONTEND_PORT" \
  BATON_FULLSTACK_CREATION_KEY="$CREATION_KEY" \
  BATON_FULLSTACK_ACCOUNT_EMAIL="$ACCOUNT_EMAIL" \
  BATON_FULLSTACK_ACCOUNT_PASSWORD="$ACCOUNT_PASSWORD" \
  BATON_FULLSTACK_ROUND_ISSUER="$ROUND_ISSUER" \
  BATON_FULLSTACK_ROUND_KID="$ROUND_KID" \
  npm run e2e:fullstack:test -- "$@"
)
