#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="$REPOSITORY_ROOT/compose.production.yml"
RESTORE_ACCESS_KEY_SQL="$REPOSITORY_ROOT/ops/sql/invalidate-restored-access-keys.sql"
COMPOSE_PROJECT="baton-production-smoke-$$-$RANDOM"
INVALID_DATASOURCE_CONTAINER="${COMPOSE_PROJECT}-missing-datasource"
TEMP_BASE="${TMPDIR:-/tmp}"
TEMP_BASE="${TEMP_BASE%/}"
RUN_DIR="$(mktemp -d "$TEMP_BASE/baton-production-smoke.XXXXXX")"
REPORT_DIR="$REPOSITORY_ROOT/build/reports/production-runtime-smoke"

export BATON_HOST=localhost
export BATON_DB_NAME=baton_runtime_smoke
export BATON_DB_USERNAME=baton_runtime_smoke
export BATON_DB_PASSWORD=runtime-smoke-database-password-0001
export BATON_DB_ROOT_PASSWORD=runtime-smoke-root-password-0000001
export BATON_WORKSPACE_CREATION_KEY=runtime-smoke-creation-key-0000000000000001
export BATON_WORKSPACE_RECOVERY_KEY=runtime-smoke-recovery-key-0000000000000002
export BATON_HTTP_PUBLISH=127.0.0.1::80
export BATON_HTTPS_TCP_PUBLISH=127.0.0.1::443
export BATON_HTTPS_UDP_PUBLISH=127.0.0.1::443/udp
SPOOFED_REQUEST_ID=00000000-0000-0000-0000-000000000000
SPOOFED_ACCESS_KEY=runtime-smoke-access-key-log-redaction

COMPOSE=(docker compose --project-name "$COMPOSE_PROJECT" --file "$COMPOSE_FILE")

log() {
  printf '[production-runtime-smoke] %s\n' "$*"
}

copy_response_artifacts() {
  local artifact

  for artifact in \
    http.headers \
    root.headers root.body \
    spa.headers spa.body \
    health.headers health.body \
    status.headers status.body \
    edge-413.headers edge-413.body \
    edge-502.headers edge-502.body; do
    if [[ -f "$RUN_DIR/$artifact" ]]; then
      cp "$RUN_DIR/$artifact" "$REPORT_DIR/$artifact" || true
    fi
  done
}

preserve_failure_logs() {
  local service
  local container_id

  mkdir -p "$REPORT_DIR"
  "${COMPOSE[@]}" ps --all >"$REPORT_DIR/compose-ps.txt" 2>&1 || true
  "${COMPOSE[@]}" logs --no-color >"$REPORT_DIR/compose.log" 2>&1 || true
  for service in mysql app web; do
    container_id="$("${COMPOSE[@]}" ps -q "$service" 2>/dev/null || true)"
    if [[ -n "$container_id" ]]; then
      docker inspect "$container_id" >"$REPORT_DIR/$service-inspect.json" 2>&1 || true
    fi
  done
  copy_response_artifacts
  log "실패 자료를 $REPORT_DIR 에 보존했습니다."
}

cleanup() {
  local exit_status="${1:-1}"
  local down_status

  trap - EXIT INT TERM
  if [[ "$exit_status" -ne 0 ]]; then
    preserve_failure_logs
  fi

  set +e
  docker rm --force "$INVALID_DATASOURCE_CONTAINER" >/dev/null 2>&1
  "${COMPOSE[@]}" down --volumes --remove-orphans --rmi local --timeout 10
  down_status=$?
  set -e

  if [[ "$down_status" -ne 0 ]]; then
    log "격리된 Compose project 정리에 실패했습니다: $COMPOSE_PROJECT"
    preserve_failure_logs
    if [[ "$exit_status" -eq 0 ]]; then
      exit_status="$down_status"
    fi
  fi

  case "$RUN_DIR" in
    "$TEMP_BASE"/baton-production-smoke.*) rm -rf -- "$RUN_DIR" ;;
  esac
  exit "$exit_status"
}

trap 'cleanup $?' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

assert_matches() {
  local pattern="$1"
  local file="$2"
  local message="$3"

  if ! grep -Eqi "$pattern" "$file"; then
    log "$message"
    return 1
  fi
}

header_value() {
  local header_name="$1"
  local file="$2"

  awk -v expected_name="$header_name" '
    {
      line = $0
      sub(/\r$/, "", line)
      separator = index(line, ":")
      if (separator == 0) {
        next
      }
      name = substr(line, 1, separator - 1)
      if (tolower(name) != tolower(expected_name)) {
        next
      }
      value = substr(line, separator + 1)
      sub(/^[[:space:]]+/, "", value)
      sub(/[[:space:]]+$/, "", value)
      print value
      exit
    }
  ' "$file"
}

published_port() {
  local private_port="$1"
  local endpoint

  endpoint="$("${COMPOSE[@]}" port --protocol tcp web "$private_port")"
  if [[ ! "$endpoint" =~ :([0-9]+)$ ]]; then
    log "web의 $private_port/tcp 임시 host port를 확인하지 못했습니다: $endpoint"
    return 1
  fi
  printf '%s\n' "${BASH_REMATCH[1]}"
}

assert_no_published_ports() {
  local service="$1"
  local container_id
  local published_bindings

  container_id="$("${COMPOSE[@]}" ps -q "$service")"
  published_bindings="$(docker inspect \
    --format '{{range $port, $bindings := .HostConfig.PortBindings}}{{if $bindings}}{{$port}} {{end}}{{end}}' \
    "$container_id")"
  if [[ -n "$published_bindings" ]]; then
    log "${service}의 port가 host에 게시됐습니다: $published_bindings"
    return 1
  fi
}

wait_for_public_health() {
  local base_url="$1"
  local https_port="$2"
  local web_container_id

  for ((attempt = 1; attempt <= 120; attempt += 1)); do
    if curl --insecure --fail --silent --show-error \
      --resolve "localhost:$https_port:127.0.0.1" \
      --dump-header "$RUN_DIR/health.headers" \
      --output "$RUN_DIR/health.body" \
      "$base_url/actuator/health" >/dev/null 2>&1 \
      && grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' "$RUN_DIR/health.body"; then
      return 0
    fi

    web_container_id="$("${COMPOSE[@]}" ps -q web 2>/dev/null || true)"
    if [[ -n "$web_container_id" ]] \
      && [[ "$(docker inspect --format '{{.State.Running}}' "$web_container_id" 2>/dev/null || true)" != "true" ]]; then
      log "Caddy 컨테이너가 public health 준비 전에 종료됐습니다."
      "${COMPOSE[@]}" logs --no-color web || true
      return 1
    fi
    sleep 1
  done

  log "Caddy HTTPS public health 준비 시간이 초과됐습니다: $base_url"
  return 1
}

command -v docker >/dev/null
command -v curl >/dev/null
command -v grep >/dev/null
docker info >/dev/null
test -f "$COMPOSE_FILE"
test -f "$RESTORE_ACCESS_KEY_SQL"

case "$COMPOSE_PROJECT" in
  baton-production-smoke-?*) ;;
  *)
    log "안전하지 않은 Compose project 이름입니다: $COMPOSE_PROJECT"
    exit 1
    ;;
esac
if [[ "$COMPOSE_PROJECT" == "baton-production" ]]; then
  log "운영 Compose project는 스모크에 사용할 수 없습니다."
  exit 1
fi

mkdir -p "$REPORT_DIR"
rm -f \
  "$REPORT_DIR/compose-ps.txt" \
  "$REPORT_DIR/compose.log" \
  "$REPORT_DIR/mysql-inspect.json" \
  "$REPORT_DIR/app-inspect.json" \
  "$REPORT_DIR/web-inspect.json" \
  "$REPORT_DIR/http.headers" \
  "$REPORT_DIR/root.headers" "$REPORT_DIR/root.body" \
  "$REPORT_DIR/spa.headers" "$REPORT_DIR/spa.body" \
  "$REPORT_DIR/health.headers" "$REPORT_DIR/health.body" \
  "$REPORT_DIR/status.headers" "$REPORT_DIR/status.body" \
  "$REPORT_DIR/edge-413.headers" "$REPORT_DIR/edge-413.body" \
  "$REPORT_DIR/edge-502.headers" "$REPORT_DIR/edge-502.body"

log "고유 Compose project를 검증합니다: $COMPOSE_PROJECT"
"${COMPOSE[@]}" config --quiet

log "production app·web 이미지를 빌드합니다."
"${COMPOSE[@]}" build app web

APP_IMAGE="${COMPOSE_PROJECT}-app:latest"
if ! docker image inspect "$APP_IMAGE" >/dev/null 2>&1; then
  log "빌드한 production app 이미지 식별자를 찾지 못했습니다."
  exit 1
fi

log "production app 이미지가 DB 설정 누락을 context 구성 전에 거절하는지 검증합니다."
if MISSING_DATASOURCE_OUTPUT="$(docker run --rm \
  --name "$INVALID_DATASOURCE_CONTAINER" \
  --network none \
  --env SPRING_PROFILES_ACTIVE=production \
  --env BATON_WORKSPACE_CREATION_KEY="$BATON_WORKSPACE_CREATION_KEY" \
  --env BATON_WORKSPACE_RECOVERY_KEY="$BATON_WORKSPACE_RECOVERY_KEY" \
  "$APP_IMAGE" 2>&1)"; then
  log "DB 설정이 없는 production app 이미지가 시작됐습니다."
  exit 1
fi
if [[ "$MISSING_DATASOURCE_OUTPUT" != *"production 프로필에는 DB_URL 설정이 필요합니다"* ]]; then
  log "production DB fail-closed 오류를 확인하지 못했습니다."
  printf '%s\n' "$MISSING_DATASOURCE_OUTPUT" >&2
  exit 1
fi

log "빌드한 이미지와 격리된 MySQL·Caddy volume을 기동합니다."
"${COMPOSE[@]}" up --detach --no-build --wait --wait-timeout 300

HTTP_PORT="$(published_port 80)"
HTTPS_PORT="$(published_port 443)"
HTTP_BASE_URL="http://localhost:$HTTP_PORT"
HTTPS_BASE_URL="https://localhost:$HTTPS_PORT"

assert_no_published_ports mysql
assert_no_published_ports app
wait_for_public_health "$HTTPS_BASE_URL" "$HTTPS_PORT"

log "Caddy HTTPS, 정적 화면, SPA fallback과 reverse proxy를 검증합니다."
curl --silent --show-error \
  --resolve "localhost:$HTTP_PORT:127.0.0.1" \
  --dump-header "$RUN_DIR/http.headers" \
  --output /dev/null \
  "$HTTP_BASE_URL/"
assert_matches '^HTTP/[0-9.]+[[:space:]]+308' "$RUN_DIR/http.headers" \
  "Caddy가 HTTP 요청을 HTTPS로 전환하지 않았습니다."
assert_matches '^location:[[:space:]]*https://localhost' "$RUN_DIR/http.headers" \
  "Caddy의 HTTPS redirect 위치가 올바르지 않습니다."

curl --insecure --fail --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --dump-header "$RUN_DIR/root.headers" \
  --output "$RUN_DIR/root.body" \
  "$HTTPS_BASE_URL/"
assert_matches '<div[[:space:]]+id="root"></div>' "$RUN_DIR/root.body" \
  "production 프런트엔드 root 문서를 찾지 못했습니다."
assert_matches '^content-security-policy:' "$RUN_DIR/root.headers" \
  "Caddy CSP header가 없습니다."
assert_matches '^strict-transport-security:' "$RUN_DIR/root.headers" \
  "Caddy HSTS header가 없습니다."
assert_matches '^x-content-type-options:[[:space:]]*nosniff' "$RUN_DIR/root.headers" \
  "Caddy MIME sniffing 방지 header가 없습니다."
if grep -Eqi '^server:' "$RUN_DIR/root.headers"; then
  log "Caddy가 Server header를 제거하지 않았습니다."
  exit 1
fi

curl --insecure --fail --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --dump-header "$RUN_DIR/spa.headers" \
  --output "$RUN_DIR/spa.body" \
  "$HTTPS_BASE_URL/teams/smoke/seasons/smoke"
assert_matches '<div[[:space:]]+id="root"></div>' "$RUN_DIR/spa.body" \
  "동적 route에서 SPA fallback 문서를 찾지 못했습니다."

curl --insecure --fail --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --header "X-Request-ID: $SPOOFED_REQUEST_ID" \
  --header "X-Baton-Access-Key: $SPOOFED_ACCESS_KEY" \
  --header "X-Baton-Recovery-Key: $BATON_WORKSPACE_RECOVERY_KEY" \
  --dump-header "$RUN_DIR/status.headers" \
  --output "$RUN_DIR/status.body" \
  "$HTTPS_BASE_URL/api/v1/system/status"
assert_matches '"service"[[:space:]]*:[[:space:]]*"baton"' "$RUN_DIR/status.body" \
  "Caddy를 통한 시스템 상태 API 응답이 올바르지 않습니다."
assert_matches '^x-request-id:[[:space:]]*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}[[:space:]]*$' \
  "$RUN_DIR/status.headers" \
  "API 응답의 서버 생성 X-Request-ID header가 없습니다."
if grep -Eqi "^x-request-id:[[:space:]]*${SPOOFED_REQUEST_ID}[[:space:]]*$" \
  "$RUN_DIR/status.headers"; then
  log "외부 X-Request-ID가 서버 진단 ID로 그대로 사용됐습니다."
  exit 1
fi
STATUS_REQUEST_ID="$(header_value "X-Request-ID" "$RUN_DIR/status.headers")"
assert_matches '^cache-control:[[:space:]]*no-store' "$RUN_DIR/status.headers" \
  "API 응답의 no-store header가 없습니다."
assert_matches '^cache-control:[[:space:]]*no-store' "$RUN_DIR/health.headers" \
  "health 응답의 no-store header가 없습니다."

log "Caddy가 직접 생성하는 제품 API 오류의 요청 ID와 안전한 access log를 검증합니다."
printf '{"teamName":"' >"$RUN_DIR/oversized.body"
dd if=/dev/zero bs=1048577 count=1 2>/dev/null \
  | tr '\0' 'a' >>"$RUN_DIR/oversized.body"
printf '%s' '","seasonName":"smoke","startDate":"2026-01-01","endDate":"2026-12-31","memberNames":["smoke"]}' \
  >>"$RUN_DIR/oversized.body"
EDGE_413_STATUS="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --request POST \
  --header "Content-Type: application/json" \
  --header "Idempotency-Key: runtime-smoke-idempotency-key" \
  --header "X-Baton-Creation-Key: $BATON_WORKSPACE_CREATION_KEY" \
  --data-binary "@$RUN_DIR/oversized.body" \
  --dump-header "$RUN_DIR/edge-413.headers" \
  --output "$RUN_DIR/edge-413.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/workspaces")"
if [[ "$EDGE_413_STATUS" != "413" ]]; then
  log "1MB를 넘는 제품 API 요청이 413으로 거부되지 않았습니다: $EDGE_413_STATUS"
  exit 1
fi
assert_matches '^x-request-id:[[:space:]]*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}[[:space:]]*$' \
  "$RUN_DIR/edge-413.headers" \
  "Caddy 413 응답의 X-Request-ID header가 없습니다."
assert_matches '^cache-control:[[:space:]]*no-store' "$RUN_DIR/edge-413.headers" \
  "Caddy 413 응답의 no-store header가 없습니다."
EDGE_413_REQUEST_ID="$(header_value "X-Request-ID" "$RUN_DIR/edge-413.headers")"

"${COMPOSE[@]}" stop --timeout 20 app
EDGE_502_STATUS="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --dump-header "$RUN_DIR/edge-502.headers" \
  --output "$RUN_DIR/edge-502.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/system/status")"
if [[ "$EDGE_502_STATUS" != "502" && "$EDGE_502_STATUS" != "503" ]]; then
  log "중지된 upstream의 제품 API 요청이 502/503으로 종료되지 않았습니다: $EDGE_502_STATUS"
  exit 1
fi
assert_matches '^x-request-id:[[:space:]]*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}[[:space:]]*$' \
  "$RUN_DIR/edge-502.headers" \
  "Caddy upstream 오류 응답의 X-Request-ID header가 없습니다."
assert_matches '^cache-control:[[:space:]]*no-store' "$RUN_DIR/edge-502.headers" \
  "Caddy upstream 오류 응답의 no-store header가 없습니다."
EDGE_502_REQUEST_ID="$(header_value "X-Request-ID" "$RUN_DIR/edge-502.headers")"

WEB_ACCESS_LOGS="$("${COMPOSE[@]}" logs --no-color web)"
STATUS_LOG_FIELD="\"X-Request-Id\":[\"$STATUS_REQUEST_ID\"]"
EDGE_413_LOG_FIELD="\"X-Request-Id\":[\"$EDGE_413_REQUEST_ID\"]"
EDGE_502_LOG_FIELD="\"X-Request-Id\":[\"$EDGE_502_REQUEST_ID\"]"
EDGE_413_UUID_FIELD="\"uuid\":\"$EDGE_413_REQUEST_ID\""
EDGE_502_UUID_FIELD="\"uuid\":\"$EDGE_502_REQUEST_ID\""
if [[ "$WEB_ACCESS_LOGS" != *"$STATUS_LOG_FIELD"* ]] \
  || [[ "$WEB_ACCESS_LOGS" != *"$EDGE_413_LOG_FIELD"* ]] \
  || [[ "$WEB_ACCESS_LOGS" != *"$EDGE_502_LOG_FIELD"* ]] \
  || [[ "$WEB_ACCESS_LOGS" != *"$EDGE_413_UUID_FIELD"* ]] \
  || [[ "$WEB_ACCESS_LOGS" != *"$EDGE_502_UUID_FIELD"* ]]; then
  log "Caddy access log의 최종 응답 헤더 또는 edge uuid가 제품 API 응답 ID와 일치하지 않습니다."
  exit 1
fi
if [[ "$WEB_ACCESS_LOGS" == *"$BATON_WORKSPACE_CREATION_KEY"* ]] \
  || [[ "$WEB_ACCESS_LOGS" == *"$BATON_WORKSPACE_RECOVERY_KEY"* ]] \
  || [[ "$WEB_ACCESS_LOGS" == *"$SPOOFED_ACCESS_KEY"* ]] \
  || [[ "$WEB_ACCESS_LOGS" == *"runtime-smoke-idempotency-key"* ]] \
  || [[ "$WEB_ACCESS_LOGS" == *"$SPOOFED_REQUEST_ID"* ]]; then
  log "Caddy access log에 제품 API credential, 멱등 키 또는 외부 요청 ID가 노출됐습니다."
  exit 1
fi

"${COMPOSE[@]}" start app
wait_for_public_health "$HTTPS_BASE_URL" "$HTTPS_PORT"

log "애플리케이션 JDBC 연결이 MySQL TLS를 사용하는지 검증합니다."
TLS_CONNECTION_COUNT="$("${COMPOSE[@]}" exec -T \
  -e MYSQL_PWD="$BATON_DB_ROOT_PASSWORD" \
  mysql mysql --protocol=socket --user=root --batch --skip-column-names \
  --execute="SELECT COUNT(*) FROM performance_schema.status_by_thread AS s JOIN performance_schema.threads AS t ON t.THREAD_ID = s.THREAD_ID WHERE t.PROCESSLIST_USER = '$BATON_DB_USERNAME' AND t.PROCESSLIST_HOST NOT LIKE 'localhost%' AND s.VARIABLE_NAME = 'Ssl_cipher' AND s.VARIABLE_VALUE <> '';" \
  | tr -d '[:space:]')"
if [[ ! "$TLS_CONNECTION_COUNT" =~ ^[0-9]+$ ]] || ((TLS_CONNECTION_COUNT < 1)); then
  log "TLS를 사용하는 애플리케이션 MySQL session을 찾지 못했습니다: ${TLS_CONNECTION_COUNT:-없음}"
  exit 1
fi

log "복원된 공유 키 무효화 SQL을 실제 MySQL에서 검증합니다."
RESTORE_TEAM_ID="11111111-1111-4111-8111-111111111111"
RESTORE_HISTORY_ID="22222222-2222-4222-8222-222222222222"
"${COMPOSE[@]}" exec -T \
  -e MYSQL_PWD="$BATON_DB_ROOT_PASSWORD" \
  mysql mysql --protocol=socket --user=root "$BATON_DB_NAME" \
  --execute="
    INSERT INTO teams (
      id,
      name,
      access_key_hash,
      last_access_key_change_idempotency_hash,
      version
    ) VALUES (
      UNHEX(REPLACE('$RESTORE_TEAM_ID', '-', '')),
      'restore security smoke',
      REPEAT('a', 64),
      REPEAT('b', 64),
      7
    );
    INSERT INTO access_key_change_history (id, team_id, idempotency_hash)
    VALUES (
      UNHEX(REPLACE('$RESTORE_HISTORY_ID', '-', '')),
      UNHEX(REPLACE('$RESTORE_TEAM_ID', '-', '')),
      REPEAT('b', 64)
    );
  "

RESTORE_REVOCATION_RESULT="$(
  "${COMPOSE[@]}" exec -T \
    -e MYSQL_PWD="$BATON_DB_ROOT_PASSWORD" \
    mysql mysql --protocol=socket --user=root \
    --batch --skip-column-names "$BATON_DB_NAME" \
    < "$RESTORE_ACCESS_KEY_SQL" \
    | tr -d '\r'
)"
if [[ "$RESTORE_REVOCATION_RESULT" != $'1\t1\t0\t0' ]]; then
  log "복원 접근 키 무효화 결과가 올바르지 않습니다: $RESTORE_REVOCATION_RESULT"
  exit 1
fi

RESTORE_REVOCATION_MATCH_COUNT="$(
  "${COMPOSE[@]}" exec -T \
    -e MYSQL_PWD="$BATON_DB_ROOT_PASSWORD" \
    mysql mysql --protocol=socket --user=root \
    --batch --skip-column-names "$BATON_DB_NAME" \
    --execute="
      SELECT COUNT(*)
      FROM teams AS team
      WHERE team.id = UNHEX(REPLACE('$RESTORE_TEAM_ID', '-', ''))
        AND team.access_key_hash <> REPEAT('a', 64)
        AND team.access_key_hash REGEXP '^[0-9a-f]{64}$'
        AND team.last_access_key_change_idempotency_hash IS NULL
        AND team.version = 8
        AND (
          SELECT COUNT(*)
          FROM access_key_change_history AS history
          WHERE history.team_id = team.id
            AND history.idempotency_hash = REPEAT('b', 64)
        ) = 1;
    " \
    | tr -d '[:space:]'
)"
if [[ "$RESTORE_REVOCATION_MATCH_COUNT" != "1" ]]; then
  log "복원 접근 키·멱등 tombstone·version 상태가 올바르지 않습니다."
  exit 1
fi

"${COMPOSE[@]}" exec -T \
  -e MYSQL_PWD="$BATON_DB_ROOT_PASSWORD" \
  mysql mysql --protocol=socket --user=root "$BATON_DB_NAME" \
  --execute="
    DELETE FROM access_key_change_history
    WHERE team_id = UNHEX(REPLACE('$RESTORE_TEAM_ID', '-', ''));
    DELETE FROM teams
    WHERE id = UNHEX(REPLACE('$RESTORE_TEAM_ID', '-', ''));
  "

log "production runtime smoke가 통과했습니다: $HTTPS_BASE_URL"
