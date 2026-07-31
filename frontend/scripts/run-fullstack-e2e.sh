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
OIDC_PORT="${BATON_FULLSTACK_OIDC_PORT:-19090}"
CREATION_KEY="${BATON_FULLSTACK_CREATION_KEY:-fullstack-creation-key-0000000000000001}"
RECOVERY_KEY="${BATON_FULLSTACK_RECOVERY_KEY:-fullstack-recovery-key-0000000000000002}"
IDENTITY_BOOTSTRAP_KEY="${BATON_FULLSTACK_IDENTITY_BOOTSTRAP_KEY:-fullstack-identity-bootstrap-key-00000001}"
IDENTITY_INVITATION_SECRET="${BATON_FULLSTACK_IDENTITY_INVITATION_SECRET:-fullstack-identity-invitation-secret-000001}"
OIDC_CLIENT_ID="${BATON_FULLSTACK_OIDC_CLIENT_ID:-baton-fullstack-e2e}"
OIDC_CLIENT_SECRET="${BATON_FULLSTACK_OIDC_CLIENT_SECRET:-baton-fullstack-e2e-client-secret}"
ROUND_GRANT_KID="baton-round-fullstack-e2e"
DATABASE_PASSWORD="fullstack-database-password"
TEMP_BASE="${TMPDIR:-/tmp}"
TEMP_BASE="${TEMP_BASE%/}"
RUN_DIR="$(mktemp -d "$TEMP_BASE/baton-fullstack-e2e.XXXXXX")"
BACKEND_LOG="$RUN_DIR/backend.log"
VITE_LOG="$RUN_DIR/vite.log"
OIDC_LOG="$RUN_DIR/mock-oidc.log"
PLAYWRIGHT_LOG="$RUN_DIR/playwright.log"
MYSQL_LOG="$RUN_DIR/mysql.log"
BACKEND_PID=""
VITE_PID=""
OIDC_PID=""
ROUND_KEY_DIRECTORY="$RUN_DIR/round-key-material"
ROUND_PRIVATE_KEY_PATH="$ROUND_KEY_DIRECTORY/round-signing-key.pem"
ROUND_JWK_SET_PATH="$ROUND_KEY_DIRECTORY/round-jwks.json"
BACKEND_BASE_URL="http://localhost:$BACKEND_PORT"
OIDC_BASE_URL="http://127.0.0.1:$OIDC_PORT"
OIDC_REDIRECT_URI="$BACKEND_BASE_URL/api/v1/auth/oidc/callback/google"
COMPOSE=(docker compose --project-name "$COMPOSE_PROJECT" --file "$COMPOSE_FILE")

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
  if ! mkdir -p "$artifact_dir"; then
    log "실패 로그 디렉터리를 만들지 못했습니다: $artifact_dir"
    return 0
  fi

  "${COMPOSE[@]}" logs --no-color mysql >"$MYSQL_LOG" 2>&1 || true
  preserve_log_if_safe "$BACKEND_LOG" "$artifact_dir/backend.log"
  preserve_log_if_safe "$VITE_LOG" "$artifact_dir/vite.log"
  preserve_log_if_safe "$OIDC_LOG" "$artifact_dir/mock-oidc.log"
  preserve_log_if_safe "$MYSQL_LOG" "$artifact_dir/mysql.log"
  preserve_log_if_safe "$PLAYWRIGHT_LOG" "$artifact_dir/playwright.log"
  log "실패 로그를 $artifact_dir 에 보존했습니다."
}

contains_sensitive_material() {
  local log_file="$1"
  local grep_status
  local secret
  if [[ ! -f "$log_file" || ! -r "$log_file" ]]; then
    return 2
  fi
  for secret in \
    "$CREATION_KEY" \
    "$RECOVERY_KEY" \
    "$IDENTITY_BOOTSTRAP_KEY" \
    "$IDENTITY_INVITATION_SECRET" \
    "$OIDC_CLIENT_SECRET" \
    "$DATABASE_PASSWORD"; do
    if [[ -n "$secret" ]]; then
      if grep --fixed-strings --quiet -- "$secret" "$log_file"; then
        return 0
      else
        grep_status=$?
        if [[ "$grep_status" -ne 1 ]]; then
          return 2
        fi
      fi
    fi
  done

  if grep --ignore-case --extended-regexp --quiet -- \
    '#accessKey=|(__Host-baton_session|__Secure-round_access|JSESSIONID|baton_session)[[:space:]]*[=:]|[?&](code|state|nonce|code_verifier|code_challenge)=|(Authorization|Idempotency-Key|X-Baton-(Creation|Access|Identity-Bootstrap)-Key|X-CSRF-TOKEN)[[:space:]]*:|"?(accessKey|csrfToken|token)"?[[:space:]]*:[[:space:]]*"?[[:alnum:]_.-]{20,}|-----BEGIN (RSA )?PRIVATE KEY-----|mi1_[[:alnum:]_-]+|[[:alnum:]_-]{40,64}|[[:alnum:]_-]{20,}\.[[:alnum:]_-]{20,}\.[[:alnum:]_-]{20,}' \
    "$log_file"; then
    return 0
  else
    grep_status=$?
    if [[ "$grep_status" -eq 1 ]]; then
      return 1
    fi
    return 2
  fi
}

preserve_log_if_safe() {
  local source_file="$1"
  local destination_file="$2"
  if [[ ! -f "$source_file" ]]; then
    return
  fi
  rm -f -- "$destination_file"
  local scan_status
  if contains_sensitive_material "$source_file"; then
    scan_status=0
  else
    scan_status=$?
  fi
  case "$scan_status" in
    0)
      log "민감정보 패턴을 감지해 $(basename "$source_file") 보존을 건너뜁니다."
      ;;
    1)
      if ! cp "$source_file" "$destination_file" 2>/dev/null; then
        log "$(basename "$source_file") 보존에 실패해 해당 아티팩트를 남기지 않습니다."
      fi
      ;;
    *)
      log "$(basename "$source_file") 안전 검사가 실패해 보존을 건너뜁니다."
      ;;
  esac
}

print_log_tail_if_safe() {
  local source_file="$1"
  local label="$2"
  local scan_status
  if contains_sensitive_material "$source_file"; then
    scan_status=0
  else
    scan_status=$?
  fi
  case "$scan_status" in
    0) log "$label 로그에서 민감정보 패턴을 감지해 콘솔 출력을 차단했습니다." ;;
    1) tail -n 120 "$source_file" 2>/dev/null || log "$label 로그를 읽지 못했습니다." ;;
    *) log "$label 로그 안전 검사가 실패해 콘솔 출력을 차단했습니다." ;;
  esac
}

cleanup() {
  local exit_status=$?
  local cleanup_status=0
  local remaining_containers=""
  local remaining_networks=""
  local remaining_volumes=""
  trap - EXIT INT TERM

  terminate_process "$VITE_PID"
  terminate_process "$BACKEND_PID"
  terminate_process "$OIDC_PID"
  if [[ "$exit_status" -ne 0 ]]; then
    preserve_failure_logs || true
  fi
  if ! "${COMPOSE[@]}" down --volumes --remove-orphans --timeout 10 >/dev/null 2>&1; then
    log "Compose 리소스 정리 명령에 실패했습니다."
    cleanup_status=1
  fi
  if ! remaining_containers="$(docker ps --all --quiet \
    --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" 2>/dev/null)"; then
    cleanup_status=1
  fi
  if ! remaining_volumes="$(docker volume ls --quiet \
    --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" 2>/dev/null)"; then
    cleanup_status=1
  fi
  if ! remaining_networks="$(docker network ls --quiet \
    --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" 2>/dev/null)"; then
    cleanup_status=1
  fi
  if [[ -n "$remaining_containers" || -n "$remaining_volumes" || -n "$remaining_networks" ]]; then
    log "전용 Compose container, volume 또는 network가 남아 있습니다."
    cleanup_status=1
  fi

  case "$RUN_DIR" in
    "$TEMP_BASE"/baton-fullstack-e2e.*)
      if ! rm -rf -- "$RUN_DIR"; then
        log "임시 실행 디렉터리 정리에 실패했습니다."
        cleanup_status=1
      fi
      ;;
  esac
  if [[ "$exit_status" -eq 0 && "$cleanup_status" -ne 0 ]]; then
    exit 1
  fi
  if [[ "$cleanup_status" -ne 0 ]]; then
    log "원래 실패 상태를 유지하지만 일부 테스트 리소스 정리에 실패했습니다."
  fi
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
      wait "$pid" 2>/dev/null || true
      print_log_tail_if_safe "$log_file" "$label"
      return 1
    fi
    if curl --fail --silent --show-error "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done

  log "$label 준비 시간이 초과됐습니다: $url"
  terminate_process "$pid"
  print_log_tail_if_safe "$log_file" "$label"
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
command -v node >/dev/null
docker info >/dev/null
test -x "$REPOSITORY_ROOT/gradlew"
test -x "$FRONTEND_DIR/node_modules/.bin/vite"
test -x "$FRONTEND_DIR/node_modules/.bin/playwright"
rm -rf -- \
  "$FRONTEND_DIR/playwright-report/fullstack" \
  "$FRONTEND_DIR/test-results/fullstack" \
  "$FRONTEND_DIR/test-results/fullstack-runtime"
require_available_port "$BACKEND_PORT" "Spring Boot"
require_available_port "$FRONTEND_PORT" "Vite"
require_available_port "$OIDC_PORT" "mock OIDC"

log "풀스택 Playwright TypeScript를 검사합니다."
(
  cd "$FRONTEND_DIR"
  npm run typecheck:fullstack
)

log "임시 ROUND RS256 서명키와 공개 JWK Set을 만듭니다."
node "$FRONTEND_DIR/tests/support/generate-round-key-material.mjs" \
  "$ROUND_KEY_DIRECTORY" \
  "$ROUND_GRANT_KID" >/dev/null
test -s "$ROUND_PRIVATE_KEY_PATH"
test -s "$ROUND_JWK_SET_PATH"

log "loopback mock OIDC 공급자를 127.0.0.1:$OIDC_PORT 에서 시작합니다."
BATON_MOCK_OIDC_PORT="$OIDC_PORT" \
BATON_MOCK_OIDC_CLIENT_ID="$OIDC_CLIENT_ID" \
BATON_MOCK_OIDC_CLIENT_SECRET="$OIDC_CLIENT_SECRET" \
BATON_MOCK_OIDC_REDIRECT_URI="$OIDC_REDIRECT_URI" \
BATON_MOCK_OIDC_ISSUER=https://accounts.google.com \
BATON_MOCK_OIDC_SUBJECT=owner \
node "$FRONTEND_DIR/tests/support/mock-oidc-provider.mjs" \
  >"$OIDC_LOG" 2>&1 &
OIDC_PID=$!
wait_for_process_url \
  "$OIDC_BASE_URL/health" \
  "$OIDC_PID" \
  "mock OIDC" \
  "$OIDC_LOG"

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
"${COMPOSE[@]}" up -d mysql
MYSQL_CONTAINER_ID="$("${COMPOSE[@]}" ps -q mysql)"
if [[ -z "$MYSQL_CONTAINER_ID" ]]; then
  log "MySQL 컨테이너 식별자를 찾지 못했습니다."
  exit 1
fi

for ((attempt = 1; attempt <= 90; attempt += 1)); do
  MYSQL_HEALTH="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$MYSQL_CONTAINER_ID")"
  if [[ "$MYSQL_HEALTH" == "healthy" ]]; then
    break
  fi
  if [[ "$MYSQL_HEALTH" == "unhealthy" ]] || ! docker inspect --format '{{.State.Running}}' "$MYSQL_CONTAINER_ID" | grep -q true; then
    log "MySQL이 정상적으로 준비되지 않았습니다: $MYSQL_HEALTH"
    "${COMPOSE[@]}" logs --no-color mysql >"$MYSQL_LOG" 2>&1 || true
    print_log_tail_if_safe "$MYSQL_LOG" "MySQL"
    exit 1
  fi
  sleep 1
done
if [[ "${MYSQL_HEALTH:-missing}" != "healthy" ]]; then
  log "MySQL 준비 시간이 초과됐습니다."
  "${COMPOSE[@]}" logs --no-color mysql >"$MYSQL_LOG" 2>&1 || true
  print_log_tail_if_safe "$MYSQL_LOG" "MySQL"
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
SERVER_ADDRESS=127.0.0.1 \
DB_URL="jdbc:mysql://127.0.0.1:$MYSQL_PORT/baton_fullstack_e2e?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8" \
DB_USERNAME=baton_fullstack_e2e \
DB_PASSWORD="$DATABASE_PASSWORD" \
BATON_SERVER_PORT="$BACKEND_PORT" \
BATON_WORKSPACE_CREATION_KEY="$CREATION_KEY" \
BATON_WORKSPACE_RECOVERY_KEY="$RECOVERY_KEY" \
BATON_IDENTITY_BOOTSTRAP_KEY="$IDENTITY_BOOTSTRAP_KEY" \
BATON_IDENTITY_INVITATION_HMAC_SECRET="$IDENTITY_INVITATION_SECRET" \
BATON_IDENTITY_OIDC_ENABLED=true \
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID="$OIDC_CLIENT_ID" \
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET="$OIDC_CLIENT_SECRET" \
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_REDIRECT_URI="$OIDC_REDIRECT_URI" \
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_SCOPE=openid \
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_GOOGLE_AUTHORIZATION_URI="$OIDC_BASE_URL/authorize" \
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_GOOGLE_TOKEN_URI="$OIDC_BASE_URL/token" \
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_GOOGLE_JWK_SET_URI="$OIDC_BASE_URL/jwks" \
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_GOOGLE_USER_INFO_URI="$OIDC_BASE_URL/userinfo" \
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_GOOGLE_USER_NAME_ATTRIBUTE=sub \
BATON_ROUND_GRANT_ENABLED=true \
BATON_ROUND_GRANT_ISSUER="$BACKEND_BASE_URL" \
BATON_ROUND_GRANT_ROUND_PUBLIC_ORIGIN="$BACKEND_BASE_URL" \
BATON_ROUND_GRANT_ACTIVE_KID="$ROUND_GRANT_KID" \
BATON_ROUND_GRANT_PRIVATE_KEY_PATH="$ROUND_PRIVATE_KEY_PATH" \
BATON_ROUND_GRANT_JWK_SET_PATH="$ROUND_JWK_SET_PATH" \
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
set +e
(
  cd "$FRONTEND_DIR"
  BATON_FULLSTACK_BASE_URL="http://127.0.0.1:$FRONTEND_PORT" \
  BATON_FULLSTACK_BACKEND_BASE_URL="$BACKEND_BASE_URL" \
  BATON_FULLSTACK_CREATION_KEY="$CREATION_KEY" \
  BATON_FULLSTACK_IDENTITY_BOOTSTRAP_KEY="$IDENTITY_BOOTSTRAP_KEY" \
  npm run e2e:fullstack:test
) >"$PLAYWRIGHT_LOG" 2>&1
PLAYWRIGHT_STATUS=$?
set -e

if contains_sensitive_material "$PLAYWRIGHT_LOG"; then
  PLAYWRIGHT_SCAN_STATUS=0
else
  PLAYWRIGHT_SCAN_STATUS=$?
fi
case "$PLAYWRIGHT_SCAN_STATUS" in
  0)
    log "Playwright 출력에서 민감정보 패턴을 감지해 출력과 보존을 차단했습니다."
    exit 1
    ;;
  1)
    if [[ ! -r "$PLAYWRIGHT_LOG" ]]; then
      log "Playwright 출력을 읽지 못해 콘솔 출력과 보존을 차단했습니다."
      exit 1
    fi
    PLAYWRIGHT_OUTPUT="$(<"$PLAYWRIGHT_LOG")"
    printf '%s\n' "$PLAYWRIGHT_OUTPUT"
    ;;
  *)
    log "Playwright 출력 안전 검사가 실패해 콘솔 출력과 보존을 차단했습니다."
    exit 1
    ;;
esac
if [[ "$PLAYWRIGHT_STATUS" -ne 0 ]]; then
  exit "$PLAYWRIGHT_STATUS"
fi
