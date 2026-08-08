#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="$REPOSITORY_ROOT/compose.production.yml"
RECOVERY_COMPOSE_FILE="$SCRIPT_DIR/compose.recovery-rehearsal.yml"
ISOLATED_RECOVERY_COMPOSE="$SCRIPT_DIR/isolated-recovery-compose.sh"
TEMP_BASE="${TMPDIR:-/tmp}"
TEMP_BASE="${TEMP_BASE%/}"
TEMP_BASE="$(CDPATH= cd -- "$TEMP_BASE" && pwd -P)"
RUN_DIR="$(mktemp -d "$TEMP_BASE/baton-production-smoke.XXXXXX")"
chmod 700 "$RUN_DIR"
RUN_SUFFIX="${RUN_DIR##*.}"
RECOVERY_REHEARSAL_RUN_ID="$(printf '%s' "$RUN_SUFFIX" | tr '[:upper:]' '[:lower:]')"
COMPOSE_PROJECT="baton-recovery-rehearsal-$RECOVERY_REHEARSAL_RUN_ID"
INVALID_DATASOURCE_CONTAINER="${COMPOSE_PROJECT}-missing-datasource"
REPORT_DIR="$REPOSITORY_ROOT/build/reports/production-runtime-smoke"
REAL_DOCKER="$(command -v docker || true)"

export BATON_HOST=localhost
export BATON_DB_NAME="baton_runtime_smoke_$RECOVERY_REHEARSAL_RUN_ID"
export BATON_DB_USERNAME=baton_runtime_smoke
export BATON_DB_PASSWORD=runtime-smoke-database-password-0001
export BATON_DB_ROOT_PASSWORD=runtime-smoke-root-password-0000001
export BATON_WORKSPACE_CREATION_KEY=runtime-smoke-creation-key-0000000000000001
export BATON_WORKSPACE_RECOVERY_KEY=runtime-smoke-recovery-key-0000000000000002
export BATON_WATCH_SOURCE_NAMESPACE=runtime-smoke
export BATON_WATCH_EVENT_RECEIVER_ENABLED=true
export BATON_WATCH_EVENT_RECEIVER_BEARER_TOKEN=runtime-smoke-watch-receiver-token-00000001
export BATON_AUTH_OAUTH2_ENABLED=true
export BATON_AUTH_OAUTH2_GOOGLE_CLIENT_ID=runtime-smoke-google-client
export BATON_AUTH_OAUTH2_NAVER_CLIENT_ID=runtime-smoke-naver-client
export BATON_SECRET_GOOGLE_OAUTH_CLIENT_SECRET=runtime-smoke-disabled-google-oauth
export BATON_SECRET_NAVER_OAUTH_CLIENT_SECRET=runtime-smoke-disabled-naver-oauth
export BATON_SECRET_SMTP_PASSWORD=runtime-smoke-disabled-smtp-password
export BATON_SECRET_EMAIL_OUTBOX_ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=
export BATON_SECRET_ROUND_CURRENT_PRIVATE_KEY=runtime-smoke-disabled-round-private
export BATON_SECRET_ROUND_CURRENT_PUBLIC_KEY=runtime-smoke-disabled-round-public
export BATON_SECRET_ROUND_PREVIOUS_PUBLIC_KEY=runtime-smoke-disabled-round-previous-public
export BATON_EFFECTIVE_SMTP_TEST_CONNECTION=false
export BATON_EFFECTIVE_ROUND_PREVIOUS_PUBLIC_KEY_PATH=
export BATON_RECOVERY_REHEARSAL_RUN_ID="$RECOVERY_REHEARSAL_RUN_ID"
export BATON_HTTP_PUBLISH=127.0.0.1::80
export BATON_HTTPS_TCP_PUBLISH=127.0.0.1::443
export BATON_HTTPS_UDP_PUBLISH=127.0.0.1::443/udp
SPOOFED_REQUEST_ID=00000000-0000-0000-0000-000000000000
SPOOFED_ACCESS_KEY=runtime-smoke-access-key-log-redaction
WORKSPACE_CREATE_IDEMPOTENCY_A=runtime-smoke-create-workspace-a-000000000001
WORKSPACE_CREATE_IDEMPOTENCY_B=runtime-smoke-create-workspace-b-000000000002
ACCESS_KEY_ROTATE_IDEMPOTENCY_A=runtime-smoke-rotate-access-key-a-000000000003
SUCCESSOR_IDEMPOTENCY_A=runtime-smoke-create-successor-a-000000000004
POST_BACKUP_MEMBER_IDEMPOTENCY=runtime-smoke-post-backup-member-000000000005
RECOVERY_IDEMPOTENCY_A=runtime-smoke-recover-access-key-a-000000000006
RECOVERY_IDEMPOTENCY_B=runtime-smoke-recover-access-key-b-000000000007
POST_RECOVERY_MEMBER_IDEMPOTENCY=runtime-smoke-post-recovery-member-000000000008
WATCH_EVENT_ID=8cf76651-f98d-4755-b578-1629b0ca2f55
WATCH_EVENT_ATTEMPT_ID=81ccb9da-f9f9-4abc-87fe-cf6193ee5f79
WATCH_EVENT_RESOURCE_ID=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa
WRONG_RECOVERY_KEY=runtime-smoke-wrong-recovery-key-0000000003
RECOVERY_REHEARSAL_TOKEN=""
INITIAL_ACCESS_KEY_A=""
ACTIVE_ACCESS_KEY_A=""
INITIAL_ACCESS_KEY_B=""
RECOVERED_ACCESS_KEY_A=""
RECOVERED_ACCESS_KEY_B=""
COMPOSE_SCOPE_INITIALIZED=false

COMPOSE=(
  "$REAL_DOCKER" compose
  --project-name "$COMPOSE_PROJECT"
  --file "$COMPOSE_FILE"
  --file "$RECOVERY_COMPOSE_FILE"
)

log() {
  printf '[production-runtime-smoke] %s\n' "$*"
}

require_command() {
  local command_name="$1"

  if ! command -v "$command_name" >/dev/null 2>&1; then
    log "필수 명령을 찾지 못했습니다: $command_name"
    return 1
  fi
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
  local logs_file="$RUN_DIR/compose.failure.log"
  local sensitive
  local sensitive_log=false

  mkdir -p "$REPORT_DIR"
  if ! project_resources_owned; then
    printf '%s\n' 'failure diagnostics omitted because the Compose project ownership check failed' \
      >"$REPORT_DIR/compose.log"
    copy_response_artifacts
    log "소유권을 확인할 수 없어 Compose 실패 자료를 수집하지 않았습니다."
    return
  fi

  "${COMPOSE[@]}" ps --all >"$REPORT_DIR/compose-ps.txt" 2>&1 || true
  "${COMPOSE[@]}" logs --no-color >"$logs_file" 2>&1 || true
  for sensitive in \
    "$BATON_DB_PASSWORD" \
    "$BATON_DB_ROOT_PASSWORD" \
    "$BATON_WORKSPACE_CREATION_KEY" \
    "$BATON_WORKSPACE_RECOVERY_KEY" \
    "$BATON_WATCH_EVENT_RECEIVER_BEARER_TOKEN" \
    "$BATON_SECRET_EMAIL_OUTBOX_ENCRYPTION_KEY" \
    "$SPOOFED_ACCESS_KEY" \
    "$WORKSPACE_CREATE_IDEMPOTENCY_A" \
    "$WORKSPACE_CREATE_IDEMPOTENCY_B" \
    "$ACCESS_KEY_ROTATE_IDEMPOTENCY_A" \
    "$SUCCESSOR_IDEMPOTENCY_A" \
    "$POST_BACKUP_MEMBER_IDEMPOTENCY" \
    "$RECOVERY_IDEMPOTENCY_A" \
    "$RECOVERY_IDEMPOTENCY_B" \
    "$POST_RECOVERY_MEMBER_IDEMPOTENCY" \
    "$WATCH_EVENT_ID" \
    "$WATCH_EVENT_ATTEMPT_ID" \
    "$WATCH_EVENT_RESOURCE_ID" \
    "$WRONG_RECOVERY_KEY" \
    "$RECOVERY_REHEARSAL_TOKEN" \
    "$INITIAL_ACCESS_KEY_A" \
    "$ACTIVE_ACCESS_KEY_A" \
    "$INITIAL_ACCESS_KEY_B" \
    "$RECOVERED_ACCESS_KEY_A" \
    "$RECOVERED_ACCESS_KEY_B"; do
    if [[ -n "$sensitive" ]] && grep -Fq -- "$sensitive" "$logs_file"; then
      sensitive_log=true
      break
    fi
  done
  if [[ "$sensitive_log" == true ]]; then
    printf '%s\n' 'failure logs omitted because a protected runtime value was detected' \
      >"$REPORT_DIR/compose.log"
  else
    cp "$logs_file" "$REPORT_DIR/compose.log" || true
  fi
  for service in mysql app web; do
    container_id="$("${COMPOSE[@]}" ps -q "$service" 2>/dev/null || true)"
    if [[ -n "$container_id" ]]; then
      "$REAL_DOCKER" inspect --format \
        $'name={{.Name}}\nimage={{.Config.Image}}\nstatus={{.State.Status}}\nhealth={{if .State.Health}}{{.State.Health.Status}}{{end}}\nlabels={{json .Config.Labels}}\nmounts={{json .Mounts}}\nports={{json .NetworkSettings.Ports}}' \
        "$container_id" >"$REPORT_DIR/$service-inspect.txt" 2>&1 || true
    fi
  done
  copy_response_artifacts
  log "실패 자료를 $REPORT_DIR 에 보존했습니다."
}

project_resources_owned() {
  local resource_id
  local resource_ids

  [[ "$REAL_DOCKER" == /* && -x "$REAL_DOCKER" ]] || return 1

  resource_ids="$("$REAL_DOCKER" ps -aq \
    --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" 2>/dev/null)" || return 1
  while IFS= read -r resource_id; do
    [[ -z "$resource_id" ]] && continue
    [[ "$("$REAL_DOCKER" inspect --format \
      '{{ index .Config.Labels "com.personal.baton.recovery-rehearsal" }}' \
      "$resource_id" 2>/dev/null)" == "$RECOVERY_REHEARSAL_RUN_ID" ]] || return 1
  done <<< "$resource_ids"

  resource_ids="$("$REAL_DOCKER" volume ls -q \
    --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" 2>/dev/null)" || return 1
  while IFS= read -r resource_id; do
    [[ -z "$resource_id" ]] && continue
    [[ "$("$REAL_DOCKER" volume inspect --format \
      '{{ index .Labels "com.personal.baton.recovery-rehearsal" }}' \
      "$resource_id" 2>/dev/null)" == "$RECOVERY_REHEARSAL_RUN_ID" ]] || return 1
  done <<< "$resource_ids"

  resource_ids="$("$REAL_DOCKER" network ls -q \
    --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" 2>/dev/null)" || return 1
  while IFS= read -r resource_id; do
    [[ -z "$resource_id" ]] && continue
    [[ "$("$REAL_DOCKER" network inspect --format \
      '{{ index .Labels "com.personal.baton.recovery-rehearsal" }}' \
      "$resource_id" 2>/dev/null)" == "$RECOVERY_REHEARSAL_RUN_ID" ]] || return 1
  done <<< "$resource_ids"
}

project_resource_count() {
  local containers
  local networks
  local volumes

  containers="$("$REAL_DOCKER" ps -aq \
    --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" 2>/dev/null)" \
    || return 1
  volumes="$("$REAL_DOCKER" volume ls -q \
    --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" 2>/dev/null)" \
    || return 1
  networks="$("$REAL_DOCKER" network ls -q \
    --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" 2>/dev/null)" \
    || return 1
  printf '%s\n%s\n%s\n' "$containers" "$volumes" "$networks" | grep -c . || true
}

cleanup() {
  local exit_status="${1:-1}"
  local down_status
  local invalid_container_label
  local remaining_resource_count

  trap - EXIT INT TERM
  if [[ "$exit_status" -ne 0 && "$COMPOSE_SCOPE_INITIALIZED" == true ]]; then
    preserve_failure_logs
  fi

  if [[ "$COMPOSE_SCOPE_INITIALIZED" != true ]]; then
    case "$RUN_DIR" in
      "$TEMP_BASE"/baton-production-smoke.*) rm -rf -- "$RUN_DIR" ;;
    esac
    exit "$exit_status"
  fi

  set +e
  invalid_container_label="$("$REAL_DOCKER" inspect --format \
    '{{ index .Config.Labels "com.personal.baton.recovery-rehearsal" }}' \
    "$INVALID_DATASOURCE_CONTAINER" 2>/dev/null)"
  if [[ "$invalid_container_label" == "$RECOVERY_REHEARSAL_RUN_ID" ]]; then
    "$REAL_DOCKER" rm --force "$INVALID_DATASOURCE_CONTAINER" >/dev/null 2>&1
  fi
  if project_resources_owned; then
    "${COMPOSE[@]}" down --volumes --remove-orphans --rmi local --timeout 10
    down_status=$?
  else
    log "격리 project의 소유권 label이 일치하지 않아 삭제를 거부합니다."
    down_status=1
  fi
  if ! remaining_resource_count="$(project_resource_count)"; then
    log "격리 project resource의 정리 결과를 조회하지 못했습니다."
    down_status=1
  elif [[ "$remaining_resource_count" != "0" ]]; then
    log "격리 project resource가 정리 뒤에도 남았습니다: $remaining_resource_count"
    down_status=1
  fi
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

extract_json_string() {
  local field="$1"
  local file="$2"
  local match
  local value

  [[ "$field" =~ ^[A-Za-z][A-Za-z0-9]*$ ]] \
    || {
      log "안전하지 않은 JSON field 이름입니다: $field"
      return 1
    }
  match="$(grep -Eo "\"$field\"[[:space:]]*:[[:space:]]*\"[^\"]+\"" "$file" || true)"
  if [[ -z "$match" || "$match" == *$'\n'* ]]; then
    log "응답에서 단일 $field 문자열을 찾지 못했습니다."
    return 1
  fi
  value="${match#*:}"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value#\"}"
  value="${value%\"}"
  [[ -n "$value" ]] || {
    log "응답의 $field 문자열이 비어 있습니다."
    return 1
  }
  printf '%s\n' "$value"
}

assert_uuid() {
  local value="$1"
  local label="$2"

  if [[ ! "$value" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]]; then
    log "$label UUID가 올바르지 않습니다."
    return 1
  fi
}

mode_of() {
  local target="$1"
  local mode

  if mode="$(stat -f '%Lp' "$target" 2>/dev/null)"; then
    :
  elif mode="$(stat -c '%a' "$target" 2>/dev/null)"; then
    :
  else
    log "파일 권한을 확인하지 못했습니다: $target"
    return 1
  fi
  printf '%s\n' "$mode"
}

run_isolated_recovery_operation() {
  local operation="$1"
  shift

  env \
    BATON_RECOVERY_REHEARSAL_DOCKER="$REAL_DOCKER" \
    BATON_RECOVERY_REHEARSAL_ROOT="$RUN_DIR" \
    BATON_RECOVERY_REHEARSAL_RUN_ID="$RECOVERY_REHEARSAL_RUN_ID" \
    BATON_RECOVERY_REHEARSAL_TOKEN="$RECOVERY_REHEARSAL_TOKEN" \
    BATON_RECOVERY_REHEARSAL_TOKEN_FILE="$RECOVERY_TOKEN_FILE" \
    BATON_RECOVERY_REHEARSAL_PROJECT="$COMPOSE_PROJECT" \
    BATON_RECOVERY_REHEARSAL_OPERATION="$operation" \
    BATON_RECOVERY_REHEARSAL_REPOSITORY_ROOT="$REPOSITORY_ROOT" \
    BATON_RECOVERY_REHEARSAL_DAEMON_ID="$RECOVERY_REHEARSAL_DAEMON_ID" \
    BATON_RECOVERY_REHEARSAL_DOCKER_CONTEXT="$RECOVERY_REHEARSAL_DOCKER_CONTEXT" \
    BATON_RECOVERY_REHEARSAL_DB_NAME="$BATON_DB_NAME" \
    BATON_PRODUCTION_ENV_FILE="$RECOVERY_ENV_FILE" \
    BATON_BACKUP_DIR="$RECOVERY_BACKUP_DIR" \
    BATON_BACKUP_STATE_DIR="$RECOVERY_STATE_DIR" \
    BATON_RESTORE_CONFIRM=RESTORE_BATON_DATABASE \
    TMPDIR="$RECOVERY_TEMP_DIR" \
    "$@"
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
  published_bindings="$("$REAL_DOCKER" inspect \
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
      && [[ "$("$REAL_DOCKER" inspect --format '{{.State.Running}}' \
        "$web_container_id" 2>/dev/null || true)" != "true" ]]; then
      log "Caddy 컨테이너가 public health 준비 전에 종료됐습니다."
      "${COMPOSE[@]}" logs --no-color web || true
      return 1
    fi
    sleep 1
  done

  log "Caddy HTTPS public health 준비 시간이 초과됐습니다: $base_url"
  return 1
}

if [[ "$REAL_DOCKER" != /* || ! -x "$REAL_DOCKER" ]]; then
  log "실행 가능한 Docker CLI 절대 경로를 찾지 못했습니다."
  exit 1
fi
require_command curl
require_command grep
require_command flock
require_command openssl
require_command cmp
"$REAL_DOCKER" info >/dev/null
if [[ ! -f "$COMPOSE_FILE" || ! -f "$RECOVERY_COMPOSE_FILE" \
  || ! -x "$ISOLATED_RECOVERY_COMPOSE" ]]; then
  log "production 또는 복구 리허설 Compose 자산을 찾지 못했습니다."
  exit 1
fi

case "$COMPOSE_PROJECT" in
  baton-recovery-rehearsal-?*) ;;
  *)
    log "안전하지 않은 Compose project 이름입니다: $COMPOSE_PROJECT"
    exit 1
    ;;
esac
if [[ "$COMPOSE_PROJECT" == "baton-production" ]]; then
  log "운영 Compose project는 스모크에 사용할 수 없습니다."
  exit 1
fi

RECOVERY_REHEARSAL_DAEMON_ID="$("$REAL_DOCKER" info --format '{{.ID}}')"
RECOVERY_REHEARSAL_DOCKER_CONTEXT="$("$REAL_DOCKER" context show)"
[[ -n "$RECOVERY_REHEARSAL_DAEMON_ID" && -n "$RECOVERY_REHEARSAL_DOCKER_CONTEXT" ]]

if ! PRODUCTION_CONTAINERS="$("$REAL_DOCKER" ps -aq \
  --filter label=com.docker.compose.project=baton-production)" \
  || ! PRODUCTION_VOLUMES="$("$REAL_DOCKER" volume ls -q \
    --filter label=com.docker.compose.project=baton-production)" \
  || ! PRODUCTION_NETWORKS="$("$REAL_DOCKER" network ls -q \
    --filter label=com.docker.compose.project=baton-production)"; then
  log "baton-production resource 경계를 조회하지 못했습니다."
  exit 1
fi
PRODUCTION_RESOURCE_COUNT="$(
  printf '%s\n%s\n%s\n' \
    "$PRODUCTION_CONTAINERS" \
    "$PRODUCTION_VOLUMES" \
    "$PRODUCTION_NETWORKS" \
    | grep -c . || true
)"
if [[ "$PRODUCTION_RESOURCE_COUNT" != "0" ]]; then
  log "같은 Docker daemon에 baton-production resource가 있어 파괴적 복구 리허설을 거부합니다."
  exit 1
fi

if ! EXISTING_REHEARSAL_RESOURCE_COUNT="$(project_resource_count)"; then
  log "격리 project의 사전 resource 경계를 조회하지 못했습니다."
  exit 1
fi
if [[ "$EXISTING_REHEARSAL_RESOURCE_COUNT" != "0" ]]; then
  log "같은 이름의 격리 project resource가 이미 존재합니다."
  exit 1
fi
for rehearsal_volume in \
  "${COMPOSE_PROJECT}_baton_mysql_data" \
  "${COMPOSE_PROJECT}_baton_caddy_data" \
  "${COMPOSE_PROJECT}_baton_caddy_config"; do
  if "$REAL_DOCKER" volume inspect "$rehearsal_volume" >/dev/null 2>&1; then
    log "같은 이름의 기존 Docker volume이 있어 리허설을 거부합니다: $rehearsal_volume"
    exit 1
  fi
done
for rehearsal_network in \
  "${COMPOSE_PROJECT}_data" \
  "${COMPOSE_PROJECT}_edge"; do
  if "$REAL_DOCKER" network inspect "$rehearsal_network" >/dev/null 2>&1; then
    log "같은 이름의 기존 Docker network가 있어 리허설을 거부합니다: $rehearsal_network"
    exit 1
  fi
done
for rehearsal_image in \
  "${COMPOSE_PROJECT}-app:latest" \
  "${COMPOSE_PROJECT}-web:latest"; do
  if "$REAL_DOCKER" image inspect "$rehearsal_image" >/dev/null 2>&1; then
    log "같은 tag의 기존 Docker image가 있어 리허설을 거부합니다: $rehearsal_image"
    exit 1
  fi
done

RECOVERY_OPS_DIR="$RUN_DIR/recovery-ops"
RECOVERY_BACKUP_DIR="$RUN_DIR/backups"
RECOVERY_STATE_DIR="$RUN_DIR/state"
RECOVERY_TEMP_DIR="$RUN_DIR/tmp"
RECOVERY_ENV_FILE="$RUN_DIR/rehearsal.env"
RECOVERY_TOKEN_FILE="$RUN_DIR/rehearsal.token"
RECOVERY_EMAIL_OUTBOX_KEY_FILE="$RUN_DIR/rehearsal-email-outbox-key.base64"
mkdir -p \
  "$RECOVERY_OPS_DIR/sql" \
  "$RECOVERY_BACKUP_DIR" \
  "$RECOVERY_STATE_DIR" \
  "$RECOVERY_TEMP_DIR"
chmod 700 \
  "$RECOVERY_OPS_DIR" \
  "$RECOVERY_OPS_DIR/sql" \
  "$RECOVERY_BACKUP_DIR" \
  "$RECOVERY_STATE_DIR" \
  "$RECOVERY_TEMP_DIR"
cp "$REPOSITORY_ROOT/ops/backup.sh" "$RECOVERY_OPS_DIR/backup.sh"
cp "$REPOSITORY_ROOT/ops/restore.sh" "$RECOVERY_OPS_DIR/restore.sh"
cp "$REPOSITORY_ROOT/ops/verify-backup.sh" "$RECOVERY_OPS_DIR/verify-backup.sh"
cp "$REPOSITORY_ROOT/ops/validate-production-env.sh" \
  "$RECOVERY_OPS_DIR/validate-production-env.sh"
cp "$REPOSITORY_ROOT/ops/validate-production-auth-secrets.sh" \
  "$RECOVERY_OPS_DIR/validate-production-auth-secrets.sh"
cp "$REPOSITORY_ROOT/ops/sql/invalidate-restored-access-keys.sql" \
  "$RECOVERY_OPS_DIR/sql/invalidate-restored-access-keys.sql"
cp "$ISOLATED_RECOVERY_COMPOSE" "$RECOVERY_OPS_DIR/production-compose.sh"
cp "$RECOVERY_COMPOSE_FILE" "$RECOVERY_OPS_DIR/compose.recovery-rehearsal.yml"
cmp -s "$REPOSITORY_ROOT/ops/backup.sh" "$RECOVERY_OPS_DIR/backup.sh"
cmp -s "$REPOSITORY_ROOT/ops/restore.sh" "$RECOVERY_OPS_DIR/restore.sh"
cmp -s "$REPOSITORY_ROOT/ops/verify-backup.sh" "$RECOVERY_OPS_DIR/verify-backup.sh"
cmp -s "$REPOSITORY_ROOT/ops/validate-production-auth-secrets.sh" \
  "$RECOVERY_OPS_DIR/validate-production-auth-secrets.sh"
cmp -s "$REPOSITORY_ROOT/ops/sql/invalidate-restored-access-keys.sql" \
  "$RECOVERY_OPS_DIR/sql/invalidate-restored-access-keys.sql"
chmod 700 \
  "$RECOVERY_OPS_DIR/backup.sh" \
  "$RECOVERY_OPS_DIR/restore.sh" \
  "$RECOVERY_OPS_DIR/verify-backup.sh" \
  "$RECOVERY_OPS_DIR/validate-production-env.sh" \
  "$RECOVERY_OPS_DIR/validate-production-auth-secrets.sh" \
  "$RECOVERY_OPS_DIR/production-compose.sh"

printf '%s\n' \
  'BATON_HOST=runtime-smoke.baton.invalid' \
  "BATON_DB_NAME=$BATON_DB_NAME" \
  "BATON_DB_USERNAME=$BATON_DB_USERNAME" \
  "BATON_DB_PASSWORD=$BATON_DB_PASSWORD" \
  "BATON_DB_ROOT_PASSWORD=$BATON_DB_ROOT_PASSWORD" \
  "BATON_WORKSPACE_CREATION_KEY=$BATON_WORKSPACE_CREATION_KEY" \
  "BATON_WORKSPACE_RECOVERY_KEY=$BATON_WORKSPACE_RECOVERY_KEY" \
  "BATON_EMAIL_OUTBOX_ENCRYPTION_KEY_FILE=$RECOVERY_EMAIL_OUTBOX_KEY_FILE" \
  >"$RECOVERY_ENV_FILE"
chmod 600 "$RECOVERY_ENV_FILE"
printf '%s' "$BATON_SECRET_EMAIL_OUTBOX_ENCRYPTION_KEY" \
  > "$RECOVERY_EMAIL_OUTBOX_KEY_FILE"
chmod 600 "$RECOVERY_EMAIL_OUTBOX_KEY_FILE"
RECOVERY_REHEARSAL_TOKEN="$(openssl rand -hex 32)"
printf '%s\n' "$RECOVERY_REHEARSAL_TOKEN" >"$RECOVERY_TOKEN_FILE"
chmod 600 "$RECOVERY_TOKEN_FILE"

mkdir -p "$REPORT_DIR"
rm -f \
  "$REPORT_DIR/compose-ps.txt" \
  "$REPORT_DIR/compose.log" \
  "$REPORT_DIR/mysql-inspect.json" \
  "$REPORT_DIR/app-inspect.json" \
  "$REPORT_DIR/web-inspect.json" \
  "$REPORT_DIR/mysql-inspect.txt" \
  "$REPORT_DIR/app-inspect.txt" \
  "$REPORT_DIR/web-inspect.txt" \
  "$REPORT_DIR/http.headers" \
  "$REPORT_DIR/root.headers" "$REPORT_DIR/root.body" \
  "$REPORT_DIR/spa.headers" "$REPORT_DIR/spa.body" \
  "$REPORT_DIR/health.headers" "$REPORT_DIR/health.body" \
  "$REPORT_DIR/status.headers" "$REPORT_DIR/status.body" \
  "$REPORT_DIR/edge-413.headers" "$REPORT_DIR/edge-413.body" \
  "$REPORT_DIR/edge-502.headers" "$REPORT_DIR/edge-502.body"

log "고유 Compose project를 검증합니다: $COMPOSE_PROJECT"
COMPOSE_SCOPE_INITIALIZED=true
"${COMPOSE[@]}" config --quiet

log "production app·web 이미지를 빌드합니다."
"${COMPOSE[@]}" build app web

APP_IMAGE="${COMPOSE_PROJECT}-app:latest"
if ! "$REAL_DOCKER" image inspect "$APP_IMAGE" >/dev/null 2>&1; then
  log "빌드한 production app 이미지 식별자를 찾지 못했습니다."
  exit 1
fi

log "production app 이미지가 DB 설정 누락을 context 구성 전에 거절하는지 검증합니다."
if MISSING_DATASOURCE_OUTPUT="$("$REAL_DOCKER" run --rm \
  --name "$INVALID_DATASOURCE_CONTAINER" \
  --label "com.personal.baton.recovery-rehearsal=$RECOVERY_REHEARSAL_RUN_ID" \
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

log "Caddy가 정규화한 HTTPS host로 OAuth callback과 동일 출처 인증 경계를 검증합니다."
curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --dump-header "$RUN_DIR/oauth-google.headers" \
  --output /dev/null \
  "$HTTPS_BASE_URL/oauth2/authorization/google"
assert_matches '^HTTP/[0-9.]+[[:space:]]+30[237]' "$RUN_DIR/oauth-google.headers" \
  "Google OAuth 시작 경로가 authorization redirect를 반환하지 않았습니다."
if ! grep -Eqi \
  'location:.*redirect_uri=(https://localhost/login/oauth2/code/google|https%3A%2F%2Flocalhost%2Flogin%2Foauth2%2Fcode%2Fgoogle)(&|[[:space:]]|$)' \
  "$RUN_DIR/oauth-google.headers"; then
  log "Google OAuth callback이 Caddy의 canonical HTTPS host를 사용하지 않았습니다."
  grep -Eio 'redirect_uri=[^&[:space:]]+' "$RUN_DIR/oauth-google.headers" >&2 || true
  exit 1
fi

curl --insecure --fail --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --cookie-jar "$RUN_DIR/auth.cookies" \
  --output "$RUN_DIR/auth-csrf.body" \
  "$HTTPS_BASE_URL/api/v1/auth/csrf"
AUTH_CSRF_TOKEN="$(extract_json_string csrfToken "$RUN_DIR/auth-csrf.body")"
AUTH_REGISTRATION_STATUS="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --request POST \
  --cookie "$RUN_DIR/auth.cookies" \
  --header "Origin: https://localhost" \
  --header "Sec-Fetch-Site: same-origin" \
  --header "X-CSRF-TOKEN: $AUTH_CSRF_TOKEN" \
  --header "Content-Type: application/json" \
  --data-binary '{"email":"runtime-smoke@example.com","displayName":"Runtime Smoke"}' \
  --output "$RUN_DIR/auth-registration.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/auth/local/registrations")"
if [[ "$AUTH_REGISTRATION_STATUS" != "503" ]]; then
  log "canonical forwarded origin이 auth filter를 통과하지 못했습니다: $AUTH_REGISTRATION_STATUS"
  exit 1
fi
assert_matches '"code"[[:space:]]*:[[:space:]]*"EMAIL_VERIFICATION_UNAVAILABLE"' \
  "$RUN_DIR/auth-registration.body" \
  "비활성 local registration의 일반화된 503 응답을 찾지 못했습니다."

log "Caddy HTTPS를 통한 WATCH 이벤트 인증·멱등 수신과 MySQL 저장을 검증합니다."
WATCH_UNAUTHORIZED_STATUS="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --request POST \
  --header "Content-Type: application/json" \
  --data-binary '{not-json' \
  --dump-header "$RUN_DIR/watch-unauthorized.headers" \
  --output "$RUN_DIR/watch-unauthorized.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/internal/resource-health-events")"
if [[ "$WATCH_UNAUTHORIZED_STATUS" != "401" ]]; then
  log "인증 없는 WATCH 이벤트가 본문 파싱 전에 401로 거부되지 않았습니다: $WATCH_UNAUTHORIZED_STATUS"
  exit 1
fi
assert_matches '"code"[[:space:]]*:[[:space:]]*"UNAUTHORIZED"' \
  "$RUN_DIR/watch-unauthorized.body" \
  "WATCH 이벤트 401 응답 코드가 올바르지 않습니다."

printf '{"eventId":"%s","eventType":"RESOURCE_HEALTH_CHANGED","resourceReference":"baton-manager:%s:role-resource:%s","sourceRevision":7,"attemptId":"%s","previousHealth":"DEGRADED","currentHealth":"BROKEN","changedAt":"2026-08-02T03:04:05.123456789Z"}\n' \
  "$WATCH_EVENT_ID" \
  "$BATON_WATCH_SOURCE_NAMESPACE" \
  "$WATCH_EVENT_RESOURCE_ID" \
  "$WATCH_EVENT_ATTEMPT_ID" \
  >"$RUN_DIR/watch-event.json"

WATCH_ACCEPT_STATUS_A="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --request POST \
  --header "Authorization: Bearer $BATON_WATCH_EVENT_RECEIVER_BEARER_TOKEN" \
  --header "Content-Type: application/json" \
  --header "Idempotency-Key: $WATCH_EVENT_ID" \
  --data-binary "@$RUN_DIR/watch-event.json" \
  --dump-header "$RUN_DIR/watch-accept-a.headers" \
  --output "$RUN_DIR/watch-accept-a.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/internal/resource-health-events")"
WATCH_ACCEPT_STATUS_B="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --request POST \
  --header "Authorization: Bearer $BATON_WATCH_EVENT_RECEIVER_BEARER_TOKEN" \
  --header "Content-Type: application/json" \
  --header "Idempotency-Key: $WATCH_EVENT_ID" \
  --data-binary "@$RUN_DIR/watch-event.json" \
  --dump-header "$RUN_DIR/watch-accept-b.headers" \
  --output "$RUN_DIR/watch-accept-b.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/internal/resource-health-events")"
if [[ "$WATCH_ACCEPT_STATUS_A" != "202" || "$WATCH_ACCEPT_STATUS_B" != "202" ]]; then
  log "WATCH 이벤트 최초 수신 또는 정확 재전송이 202가 아닙니다: first=$WATCH_ACCEPT_STATUS_A replay=$WATCH_ACCEPT_STATUS_B"
  exit 1
fi
if ! cmp -s "$RUN_DIR/watch-accept-a.body" "$RUN_DIR/watch-accept-b.body"; then
  log "WATCH 이벤트 정확 재전송이 최초 접수 receipt를 재사용하지 않았습니다."
  exit 1
fi
WATCH_RECEIPT_EVENT_ID="$(extract_json_string eventId "$RUN_DIR/watch-accept-a.body")"
WATCH_RECEIPT_ACCEPTED_AT="$(extract_json_string acceptedAt "$RUN_DIR/watch-accept-a.body")"
if [[ "$WATCH_RECEIPT_EVENT_ID" != "$WATCH_EVENT_ID" \
  || ! "$WATCH_RECEIPT_ACCEPTED_AT" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T ]]; then
  log "WATCH 이벤트 접수 receipt가 eventId와 acceptedAt을 보존하지 않았습니다."
  exit 1
fi

WATCH_EVENT_ROW_COUNT="$("${COMPOSE[@]}" exec -T \
  -e MYSQL_PWD="$BATON_DB_ROOT_PASSWORD" \
  mysql mysql --protocol=socket --user=root --batch --skip-column-names \
  --execute="SELECT COUNT(*) FROM ${BATON_DB_NAME}.watch_health_event_inbox WHERE event_id = UUID_TO_BIN('$WATCH_EVENT_ID');" \
  | tr -d '[:space:]')"
if [[ "$WATCH_EVENT_ROW_COUNT" != "1" ]]; then
  log "WATCH 이벤트 정확 재전송 뒤 inbox 행 수가 1이 아닙니다: $WATCH_EVENT_ROW_COUNT"
  exit 1
fi

sed 's/"currentHealth":"BROKEN"/"currentHealth":"HEALTHY"/' \
  "$RUN_DIR/watch-event.json" >"$RUN_DIR/watch-event-conflict.json"
WATCH_CONFLICT_STATUS="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --request POST \
  --header "Authorization: Bearer $BATON_WATCH_EVENT_RECEIVER_BEARER_TOKEN" \
  --header "Content-Type: application/json" \
  --header "Idempotency-Key: $WATCH_EVENT_ID" \
  --data-binary "@$RUN_DIR/watch-event-conflict.json" \
  --dump-header "$RUN_DIR/watch-conflict.headers" \
  --output "$RUN_DIR/watch-conflict.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/internal/resource-health-events")"
if [[ "$WATCH_CONFLICT_STATUS" != "409" ]]; then
  log "같은 eventId의 다른 WATCH envelope가 409로 거부되지 않았습니다: $WATCH_CONFLICT_STATUS"
  exit 1
fi
assert_matches '"code"[[:space:]]*:[[:space:]]*"WATCH_EVENT_ID_CONFLICT"' \
  "$RUN_DIR/watch-conflict.body" \
  "WATCH 이벤트 충돌 응답 코드가 올바르지 않습니다."
WATCH_STORED_HEALTH="$("${COMPOSE[@]}" exec -T \
  -e MYSQL_PWD="$BATON_DB_ROOT_PASSWORD" \
  mysql mysql --protocol=socket --user=root --batch --skip-column-names \
  --execute="SELECT current_health FROM ${BATON_DB_NAME}.watch_health_event_inbox WHERE event_id = UUID_TO_BIN('$WATCH_EVENT_ID');" \
  | tr -d '[:space:]')"
if [[ "$WATCH_STORED_HEALTH" != "BROKEN" ]]; then
  log "WATCH 이벤트 충돌이 최초 inbox envelope를 변경했습니다: $WATCH_STORED_HEALTH"
  exit 1
fi

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
  || [[ "$WEB_ACCESS_LOGS" == *"$SPOOFED_REQUEST_ID"* ]] \
  || [[ "$WEB_ACCESS_LOGS" == *"$BATON_WATCH_EVENT_RECEIVER_BEARER_TOKEN"* ]] \
  || [[ "$WEB_ACCESS_LOGS" == *"$WATCH_EVENT_ID"* ]] \
  || [[ "$WEB_ACCESS_LOGS" == *"$WATCH_EVENT_ATTEMPT_ID"* ]] \
  || [[ "$WEB_ACCESS_LOGS" == *"$WATCH_EVENT_RESOURCE_ID"* ]]; then
  log "Caddy access log에 제품 API 또는 WATCH credential, 멱등 키, payload가 노출됐습니다."
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

log "실제 API 데이터로 격리된 백업·복구 리허설을 준비합니다."
CREATE_STATUS_A="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --request POST \
  --header "Content-Type: application/json" \
  --header "Idempotency-Key: $WORKSPACE_CREATE_IDEMPOTENCY_A" \
  --header "X-Baton-Creation-Key: $BATON_WORKSPACE_CREATION_KEY" \
  --data '{"teamName":"복구 리허설 A","seasonName":"원본 시즌 A","startDate":"2026-07-01","endDate":"2026-12-31","memberNames":["복구원본A"]}' \
  --dump-header "$RUN_DIR/recovery-create-a.headers" \
  --output "$RUN_DIR/recovery-create-a.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/workspaces")"
if [[ "$CREATE_STATUS_A" != "201" ]]; then
  log "첫 번째 복구 리허설 workspace 생성이 실패했습니다: $CREATE_STATUS_A"
  exit 1
fi
TEAM_ID_A="$(extract_json_string teamId "$RUN_DIR/recovery-create-a.body")"
SEASON_ID_A="$(extract_json_string seasonId "$RUN_DIR/recovery-create-a.body")"
INITIAL_ACCESS_KEY_A="$(extract_json_string accessKey "$RUN_DIR/recovery-create-a.body")"
assert_uuid "$TEAM_ID_A" "첫 번째 team"
assert_uuid "$SEASON_ID_A" "첫 번째 season"

CREATE_STATUS_B="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --request POST \
  --header "Content-Type: application/json" \
  --header "Idempotency-Key: $WORKSPACE_CREATE_IDEMPOTENCY_B" \
  --header "X-Baton-Creation-Key: $BATON_WORKSPACE_CREATION_KEY" \
  --data '{"teamName":"복구 리허설 B","seasonName":"원본 시즌 B","startDate":"2026-07-01","endDate":"2026-12-31","memberNames":["복구원본B"]}' \
  --dump-header "$RUN_DIR/recovery-create-b.headers" \
  --output "$RUN_DIR/recovery-create-b.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/workspaces")"
if [[ "$CREATE_STATUS_B" != "201" ]]; then
  log "두 번째 복구 리허설 workspace 생성이 실패했습니다: $CREATE_STATUS_B"
  exit 1
fi
TEAM_ID_B="$(extract_json_string teamId "$RUN_DIR/recovery-create-b.body")"
SEASON_ID_B="$(extract_json_string seasonId "$RUN_DIR/recovery-create-b.body")"
INITIAL_ACCESS_KEY_B="$(extract_json_string accessKey "$RUN_DIR/recovery-create-b.body")"
assert_uuid "$TEAM_ID_B" "두 번째 team"
assert_uuid "$SEASON_ID_B" "두 번째 season"

ROTATE_STATUS_A="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --request POST \
  --header "Idempotency-Key: $ACCESS_KEY_ROTATE_IDEMPOTENCY_A" \
  --header "X-Baton-Access-Key: $INITIAL_ACCESS_KEY_A" \
  --dump-header "$RUN_DIR/recovery-rotate-a.headers" \
  --output "$RUN_DIR/recovery-rotate-a.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/teams/$TEAM_ID_A/seasons/$SEASON_ID_A/access-key/rotate")"
if [[ "$ROTATE_STATUS_A" != "200" ]]; then
  log "복구 전 접근 키 회전이 실패했습니다: $ROTATE_STATUS_A"
  exit 1
fi
ACTIVE_ACCESS_KEY_A="$(extract_json_string accessKey "$RUN_DIR/recovery-rotate-a.body")"
if [[ "$ACTIVE_ACCESS_KEY_A" == "$INITIAL_ACCESS_KEY_A" ]]; then
  log "접근 키 회전이 새 키를 발급하지 않았습니다."
  exit 1
fi

ROTATED_OLD_KEY_STATUS_A="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --header "X-Baton-Access-Key: $INITIAL_ACCESS_KEY_A" \
  --output "$RUN_DIR/recovery-rotated-old-key-a.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/teams/$TEAM_ID_A/seasons/$SEASON_ID_A/workspace")"
if [[ "$ROTATED_OLD_KEY_STATUS_A" != "403" ]]; then
  log "회전 직후 첫 번째 팀의 최초 접근 키가 폐기되지 않았습니다."
  exit 1
fi

SUCCESSOR_STATUS_A="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --request POST \
  --header "Content-Type: application/json" \
  --header "Idempotency-Key: $SUCCESSOR_IDEMPOTENCY_A" \
  --header "X-Baton-Access-Key: $ACTIVE_ACCESS_KEY_A" \
  --data '{"name":"대표 시즌 A","startDate":"2027-01-01","endDate":"2027-06-30","copyRoleIds":[],"copyRoutineIds":[]}' \
  --dump-header "$RUN_DIR/recovery-successor-a.headers" \
  --output "$RUN_DIR/recovery-successor-a.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/teams/$TEAM_ID_A/seasons/$SEASON_ID_A/successor")"
if [[ "$SUCCESSOR_STATUS_A" != "201" ]]; then
  log "최신 복구 대표 시즌을 만들지 못했습니다: $SUCCESSOR_STATUS_A"
  exit 1
fi
SUCCESSOR_LOCATION_A="$(header_value "Location" "$RUN_DIR/recovery-successor-a.headers")"
SUCCESSOR_PREFIX_A="/api/v1/teams/$TEAM_ID_A/seasons/"
case "$SUCCESSOR_LOCATION_A" in
  "$SUCCESSOR_PREFIX_A"*/workspace)
    LATEST_SEASON_ID_A="${SUCCESSOR_LOCATION_A#"$SUCCESSOR_PREFIX_A"}"
    LATEST_SEASON_ID_A="${LATEST_SEASON_ID_A%/workspace}"
    ;;
  *)
    log "다음 시즌 Location header가 workspace 경계를 가리키지 않습니다."
    exit 1
    ;;
esac
assert_uuid "$LATEST_SEASON_ID_A" "첫 번째 팀의 최신 season"
if [[ "$LATEST_SEASON_ID_A" == "$SEASON_ID_A" ]]; then
  log "다음 시즌이 새 식별자를 발급하지 않았습니다."
  exit 1
fi

PRE_BACKUP_STATUS_A="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --header "X-Baton-Access-Key: $ACTIVE_ACCESS_KEY_A" \
  --output "$RUN_DIR/recovery-before-backup-a.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/teams/$TEAM_ID_A/seasons/$LATEST_SEASON_ID_A/workspace")"
PRE_BACKUP_STATUS_B="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --header "X-Baton-Access-Key: $INITIAL_ACCESS_KEY_B" \
  --output "$RUN_DIR/recovery-before-backup-b.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/teams/$TEAM_ID_B/seasons/$SEASON_ID_B/workspace")"
if [[ "$PRE_BACKUP_STATUS_A" != "200" || "$PRE_BACKUP_STATUS_B" != "200" ]]; then
  log "백업 직전 두 workspace의 유효한 접근 키를 확인하지 못했습니다."
  exit 1
fi

log "실제 backup.sh로 리허설 snapshot과 checksum을 생성합니다."
BACKUP_PATH="$(run_isolated_recovery_operation \
  backup "$RECOVERY_OPS_DIR/backup.sh" --print-path)"
case "$BACKUP_PATH" in
  "$RECOVERY_BACKUP_DIR"/baton-*.sql.gz) ;;
  *)
    log "backup.sh가 격리 경계 밖의 경로를 반환했습니다."
    exit 1
    ;;
esac
if [[ -L "$BACKUP_PATH" || ! -s "$BACKUP_PATH" || ! -f "$BACKUP_PATH.sha256" ]]; then
  log "리허설 backup 본문 또는 checksum sidecar가 올바르지 않습니다."
  exit 1
fi
"$RECOVERY_OPS_DIR/verify-backup.sh" --require-checksum "$BACKUP_PATH" >/dev/null

POST_BACKUP_MEMBER_STATUS="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --request POST \
  --header "Content-Type: application/json" \
  --header "Idempotency-Key: $POST_BACKUP_MEMBER_IDEMPOTENCY" \
  --header "X-Baton-Access-Key: $ACTIVE_ACCESS_KEY_A" \
  --data '{"name":"백업후삭제"}' \
  --output "$RUN_DIR/recovery-post-backup-member.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/teams/$TEAM_ID_A/seasons/$LATEST_SEASON_ID_A/members")"
if [[ "$POST_BACKUP_MEMBER_STATUS" != "201" ]]; then
  log "복원으로 제거할 백업 이후 sentinel 생성이 실패했습니다: $POST_BACKUP_MEMBER_STATUS"
  exit 1
fi

log "app·web을 중지하고 실제 restore.sh로 snapshot을 복원합니다."
"${COMPOSE[@]}" stop --timeout 20 app web
RESTORE_OUTPUT="$(run_isolated_recovery_operation \
  restore "$RECOVERY_OPS_DIR/restore.sh" "$BACKUP_PATH")"
if [[ "$RESTORE_OUTPUT" != *"Invalidated workspace access keys: 2"* \
  || "$RESTORE_OUTPUT" != *"Old shared links cannot be reused"* ]]; then
  log "restore.sh가 두 팀의 접근 키 무효화를 완료했다고 보고하지 않았습니다."
  exit 1
fi

RECOVERY_TARGETS="$RECOVERY_STATE_DIR/last-restore-recovery-targets.tsv"
if [[ ! -f "$RECOVERY_TARGETS" || -L "$RECOVERY_TARGETS" ]]; then
  log "restore.sh가 복구 대상 TSV를 안전한 regular file로 만들지 않았습니다."
  exit 1
fi
RECOVERY_TARGET_MODE="$(mode_of "$RECOVERY_TARGETS")"
if (( (8#$RECOVERY_TARGET_MODE) != 0600 )); then
  log "복구 대상 TSV의 권한이 0600이 아닙니다: $RECOVERY_TARGET_MODE"
  exit 1
fi
RECOVERY_TARGET_COUNT="$(wc -l <"$RECOVERY_TARGETS" | tr -d '[:space:]')"
if [[ "$RECOVERY_TARGET_COUNT" != "2" ]]; then
  log "복구 대상 TSV의 팀 수가 올바르지 않습니다: $RECOVERY_TARGET_COUNT"
  exit 1
fi
grep -Fxq -- "$TEAM_ID_A"$'\t'"$LATEST_SEASON_ID_A" "$RECOVERY_TARGETS" \
  || {
    log "첫 번째 팀의 복구 대상이 TSV에 없습니다."
    exit 1
  }
if grep -Fxq -- "$TEAM_ID_A"$'\t'"$SEASON_ID_A" "$RECOVERY_TARGETS"; then
  log "첫 번째 팀의 과거 시즌이 최신 복구 대표 시즌으로 잘못 선택됐습니다."
  exit 1
fi
grep -Fxq -- "$TEAM_ID_B"$'\t'"$SEASON_ID_B" "$RECOVERY_TARGETS" \
  || {
    log "두 번째 팀의 복구 대상이 TSV에 없습니다."
    exit 1
  }

"${COMPOSE[@]}" start app web
HTTP_PORT="$(published_port 80)"
HTTPS_PORT="$(published_port 443)"
HTTP_BASE_URL="http://localhost:$HTTP_PORT"
HTTPS_BASE_URL="https://localhost:$HTTPS_PORT"
wait_for_public_health "$HTTPS_BASE_URL" "$HTTPS_PORT"

OLD_KEY_STATUS_A="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --header "X-Baton-Access-Key: $ACTIVE_ACCESS_KEY_A" \
  --output "$RUN_DIR/recovery-old-key-a.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/teams/$TEAM_ID_A/seasons/$LATEST_SEASON_ID_A/workspace")"
INITIAL_KEY_STATUS_A="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --header "X-Baton-Access-Key: $INITIAL_ACCESS_KEY_A" \
  --output "$RUN_DIR/recovery-initial-key-a.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/teams/$TEAM_ID_A/seasons/$LATEST_SEASON_ID_A/workspace")"
OLD_KEY_STATUS_B="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --header "X-Baton-Access-Key: $INITIAL_ACCESS_KEY_B" \
  --output "$RUN_DIR/recovery-old-key-b.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/teams/$TEAM_ID_B/seasons/$SEASON_ID_B/workspace")"
if [[ "$OLD_KEY_STATUS_A" != "403" || "$INITIAL_KEY_STATUS_A" != "403" \
  || "$OLD_KEY_STATUS_B" != "403" ]]; then
  log "restore 뒤 기존 공유 키가 모두 403으로 폐기되지 않았습니다."
  exit 1
fi
assert_matches '"code"[[:space:]]*:[[:space:]]*"WORKSPACE_ACCESS_DENIED"' \
  "$RUN_DIR/recovery-old-key-a.body" \
  "첫 번째 기존 키의 오류 code가 올바르지 않습니다."
assert_matches '"code"[[:space:]]*:[[:space:]]*"WORKSPACE_ACCESS_DENIED"' \
  "$RUN_DIR/recovery-initial-key-a.body" \
  "첫 번째 최초 키의 오류 code가 올바르지 않습니다."
assert_matches '"code"[[:space:]]*:[[:space:]]*"WORKSPACE_ACCESS_DENIED"' \
  "$RUN_DIR/recovery-old-key-b.body" \
  "두 번째 기존 키의 오류 code가 올바르지 않습니다."

ROTATE_REPLAY_STATUS_A="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --request POST \
  --header "Idempotency-Key: $ACCESS_KEY_ROTATE_IDEMPOTENCY_A" \
  --header "X-Baton-Access-Key: $INITIAL_ACCESS_KEY_A" \
  --output "$RUN_DIR/recovery-rotate-expired.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/teams/$TEAM_ID_A/seasons/$LATEST_SEASON_ID_A/access-key/rotate")"
if [[ "$ROTATE_REPLAY_STATUS_A" != "409" ]]; then
  log "restore 뒤 과거 접근 키 회전 replay가 만료되지 않았습니다."
  exit 1
fi
assert_matches '"code"[[:space:]]*:[[:space:]]*"IDEMPOTENCY_REPLAY_EXPIRED"' \
  "$RUN_DIR/recovery-rotate-expired.body" \
  "과거 접근 키 회전 replay의 오류 code가 올바르지 않습니다."

CREATE_REPLAY_STATUS_A="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --request POST \
  --header "Content-Type: application/json" \
  --header "Idempotency-Key: $WORKSPACE_CREATE_IDEMPOTENCY_A" \
  --header "X-Baton-Creation-Key: $BATON_WORKSPACE_CREATION_KEY" \
  --data '{"teamName":"복구 리허설 A","seasonName":"원본 시즌 A","startDate":"2026-07-01","endDate":"2026-12-31","memberNames":["복구원본A"]}' \
  --output "$RUN_DIR/recovery-create-expired.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/workspaces")"
if [[ "$CREATE_REPLAY_STATUS_A" != "409" ]]; then
  log "restore 뒤 과거 workspace 생성 replay가 옛 접근 키를 재발급할 수 있습니다."
  exit 1
fi
assert_matches '"code"[[:space:]]*:[[:space:]]*"IDEMPOTENCY_REPLAY_EXPIRED"' \
  "$RUN_DIR/recovery-create-expired.body" \
  "과거 workspace 생성 replay의 오류 code가 올바르지 않습니다."

log "TSV의 두 팀을 운영자 복구 API로 다시 열고 멱등 재생을 검증합니다."
RECOVER_STATUS_A="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --request POST \
  --header "Idempotency-Key: $RECOVERY_IDEMPOTENCY_A" \
  --header "X-Baton-Recovery-Key: $BATON_WORKSPACE_RECOVERY_KEY" \
  --dump-header "$RUN_DIR/recovery-recover-a.headers" \
  --output "$RUN_DIR/recovery-recover-a.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/teams/$TEAM_ID_A/seasons/$LATEST_SEASON_ID_A/access-key/recover")"
if [[ "$RECOVER_STATUS_A" != "200" ]]; then
  log "첫 번째 팀의 운영자 접근 키 복구가 실패했습니다: $RECOVER_STATUS_A"
  exit 1
fi
assert_matches '^cache-control:[[:space:]]*no-store' \
  "$RUN_DIR/recovery-recover-a.headers" \
  "첫 번째 복구 응답의 no-store header가 없습니다."
RECOVERED_ACCESS_KEY_A="$(extract_json_string accessKey "$RUN_DIR/recovery-recover-a.body")"

WRONG_RECOVERY_STATUS="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --request POST \
  --header "Idempotency-Key: $RECOVERY_IDEMPOTENCY_A" \
  --header "X-Baton-Recovery-Key: $WRONG_RECOVERY_KEY" \
  --output "$RUN_DIR/recovery-wrong-key.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/teams/$TEAM_ID_A/seasons/$LATEST_SEASON_ID_A/access-key/recover")"
if [[ "$WRONG_RECOVERY_STATUS" != "403" ]]; then
  log "잘못된 운영자 키가 복구 멱등 재생에서 거부되지 않았습니다."
  exit 1
fi
assert_matches '"code"[[:space:]]*:[[:space:]]*"WORKSPACE_RECOVERY_DENIED"' \
  "$RUN_DIR/recovery-wrong-key.body" \
  "잘못된 운영자 키의 오류 code가 올바르지 않습니다."

RECOVER_REPLAY_STATUS_A="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --request POST \
  --header "Idempotency-Key: $RECOVERY_IDEMPOTENCY_A" \
  --header "X-Baton-Recovery-Key: $BATON_WORKSPACE_RECOVERY_KEY" \
  --output "$RUN_DIR/recovery-recover-a-replay.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/teams/$TEAM_ID_A/seasons/$LATEST_SEASON_ID_A/access-key/recover")"
if [[ "$RECOVER_REPLAY_STATUS_A" != "200" \
  || "$(extract_json_string accessKey "$RUN_DIR/recovery-recover-a-replay.body")" \
    != "$RECOVERED_ACCESS_KEY_A" ]]; then
  log "첫 번째 팀의 복구 멱등 재생이 같은 새 키를 반환하지 않았습니다."
  exit 1
fi

RECOVER_STATUS_B="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --request POST \
  --header "Idempotency-Key: $RECOVERY_IDEMPOTENCY_B" \
  --header "X-Baton-Recovery-Key: $BATON_WORKSPACE_RECOVERY_KEY" \
  --dump-header "$RUN_DIR/recovery-recover-b.headers" \
  --output "$RUN_DIR/recovery-recover-b.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/teams/$TEAM_ID_B/seasons/$SEASON_ID_B/access-key/recover")"
if [[ "$RECOVER_STATUS_B" != "200" ]]; then
  log "두 번째 팀의 운영자 접근 키 복구가 실패했습니다: $RECOVER_STATUS_B"
  exit 1
fi
assert_matches '^cache-control:[[:space:]]*no-store' \
  "$RUN_DIR/recovery-recover-b.headers" \
  "두 번째 복구 응답의 no-store header가 없습니다."
RECOVERED_ACCESS_KEY_B="$(extract_json_string accessKey "$RUN_DIR/recovery-recover-b.body")"
RECOVER_REPLAY_STATUS_B="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --request POST \
  --header "Idempotency-Key: $RECOVERY_IDEMPOTENCY_B" \
  --header "X-Baton-Recovery-Key: $BATON_WORKSPACE_RECOVERY_KEY" \
  --output "$RUN_DIR/recovery-recover-b-replay.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/teams/$TEAM_ID_B/seasons/$SEASON_ID_B/access-key/recover")"
if [[ "$RECOVER_REPLAY_STATUS_B" != "200" \
  || "$(extract_json_string accessKey "$RUN_DIR/recovery-recover-b-replay.body")" \
    != "$RECOVERED_ACCESS_KEY_B" ]]; then
  log "두 번째 팀의 복구 멱등 재생이 같은 새 키를 반환하지 않았습니다."
  exit 1
fi
if [[ "$RECOVERED_ACCESS_KEY_A" == "$INITIAL_ACCESS_KEY_A" \
  || "$RECOVERED_ACCESS_KEY_A" == "$ACTIVE_ACCESS_KEY_A" \
  || "$RECOVERED_ACCESS_KEY_B" == "$INITIAL_ACCESS_KEY_B" \
  || "$RECOVERED_ACCESS_KEY_A" == "$RECOVERED_ACCESS_KEY_B" ]]; then
  log "복구 API가 팀별로 새로운 접근 키를 발급하지 않았습니다."
  exit 1
fi

RECOVERED_STATUS_A="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --header "X-Baton-Access-Key: $RECOVERED_ACCESS_KEY_A" \
  --output "$RUN_DIR/recovery-workspace-a.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/teams/$TEAM_ID_A/seasons/$LATEST_SEASON_ID_A/workspace")"
RECOVERED_STATUS_B="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --header "X-Baton-Access-Key: $RECOVERED_ACCESS_KEY_B" \
  --output "$RUN_DIR/recovery-workspace-b.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/teams/$TEAM_ID_B/seasons/$SEASON_ID_B/workspace")"
if [[ "$RECOVERED_STATUS_A" != "200" || "$RECOVERED_STATUS_B" != "200" ]]; then
  log "새 접근 키로 복원된 두 workspace를 조회하지 못했습니다."
  exit 1
fi
assert_matches '"name"[[:space:]]*:[[:space:]]*"복구원본A"' \
  "$RUN_DIR/recovery-workspace-a.body" \
  "백업 snapshot의 첫 번째 원본 구성원이 보존되지 않았습니다."
assert_matches '"name"[[:space:]]*:[[:space:]]*"복구원본B"' \
  "$RUN_DIR/recovery-workspace-b.body" \
  "백업 snapshot의 두 번째 원본 구성원이 보존되지 않았습니다."
if grep -Fq '"백업후삭제"' "$RUN_DIR/recovery-workspace-a.body"; then
  log "백업 이후 sentinel이 restore 뒤에도 남아 실제 import를 증명하지 못했습니다."
  exit 1
fi

CROSS_KEY_STATUS="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --header "X-Baton-Access-Key: $RECOVERED_ACCESS_KEY_A" \
  --output "$RUN_DIR/recovery-cross-key.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/teams/$TEAM_ID_B/seasons/$SEASON_ID_B/workspace")"
if [[ "$CROSS_KEY_STATUS" != "403" ]]; then
  log "첫 번째 팀의 새 키가 두 번째 팀 경계에서 거부되지 않았습니다."
  exit 1
fi
assert_matches '"code"[[:space:]]*:[[:space:]]*"WORKSPACE_ACCESS_DENIED"' \
  "$RUN_DIR/recovery-cross-key.body" \
  "팀 간 접근 키 오용의 오류 code가 올바르지 않습니다."

OLD_KEY_AFTER_RECOVERY_STATUS="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --header "X-Baton-Access-Key: $ACTIVE_ACCESS_KEY_A" \
  --output "$RUN_DIR/recovery-old-key-after-recovery.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/teams/$TEAM_ID_A/seasons/$LATEST_SEASON_ID_A/workspace")"
INITIAL_KEY_AFTER_RECOVERY_STATUS="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --header "X-Baton-Access-Key: $INITIAL_ACCESS_KEY_A" \
  --output "$RUN_DIR/recovery-initial-key-after-recovery.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/teams/$TEAM_ID_A/seasons/$LATEST_SEASON_ID_A/workspace")"
if [[ "$OLD_KEY_AFTER_RECOVERY_STATUS" != "403" \
  || "$INITIAL_KEY_AFTER_RECOVERY_STATUS" != "403" ]]; then
  log "운영자 복구 뒤 과거 공유 키가 다시 유효해졌습니다."
  exit 1
fi

POST_RECOVERY_MEMBER_STATUS="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --request POST \
  --header "Content-Type: application/json" \
  --header "Idempotency-Key: $POST_RECOVERY_MEMBER_IDEMPOTENCY" \
  --header "X-Baton-Access-Key: $RECOVERED_ACCESS_KEY_A" \
  --data '{"name":"복구후유지"}' \
  --output "$RUN_DIR/recovery-post-recovery-member.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/teams/$TEAM_ID_A/seasons/$LATEST_SEASON_ID_A/members")"
if [[ "$POST_RECOVERY_MEMBER_STATUS" != "201" ]]; then
  log "새 접근 키를 사용한 변경이 실패했습니다: $POST_RECOVERY_MEMBER_STATUS"
  exit 1
fi

POST_RECOVERY_QUERY_STATUS="$(curl --insecure --silent --show-error \
  --resolve "localhost:$HTTPS_PORT:127.0.0.1" \
  --header "X-Baton-Access-Key: $RECOVERED_ACCESS_KEY_A" \
  --output "$RUN_DIR/recovery-post-recovery-workspace.body" \
  --write-out '%{http_code}' \
  "$HTTPS_BASE_URL/api/v1/teams/$TEAM_ID_A/seasons/$LATEST_SEASON_ID_A/workspace")"
if [[ "$POST_RECOVERY_QUERY_STATUS" != "200" ]]; then
  log "복구 뒤 변경한 workspace 재조회가 실패했습니다."
  exit 1
fi
assert_matches '"name"[[:space:]]*:[[:space:]]*"복구후유지"' \
  "$RUN_DIR/recovery-post-recovery-workspace.body" \
  "복구 뒤 새 접근 키로 만든 구성원이 조회되지 않습니다."

log "복구 완료 상태를 실제 backup.sh로 다시 보존합니다."
POST_RECOVERY_BACKUP_PATH="$(run_isolated_recovery_operation \
  backup "$RECOVERY_OPS_DIR/backup.sh" --print-path)"
if [[ "$POST_RECOVERY_BACKUP_PATH" == "$BACKUP_PATH" \
  || "$POST_RECOVERY_BACKUP_PATH" != "$RECOVERY_BACKUP_DIR"/baton-*.sql.gz \
  || ! -s "$POST_RECOVERY_BACKUP_PATH" \
  || ! -f "$POST_RECOVERY_BACKUP_PATH.sha256" ]]; then
  log "복구 완료 상태의 새 backup과 checksum을 만들지 못했습니다."
  exit 1
fi
"$RECOVERY_OPS_DIR/verify-backup.sh" \
  --require-checksum "$POST_RECOVERY_BACKUP_PATH" >/dev/null

FINAL_WEB_LOGS="$("${COMPOSE[@]}" logs --no-color web)"
FINAL_APP_LOGS="$("${COMPOSE[@]}" logs --no-color app)"
for protected_value in \
  "$BATON_WORKSPACE_CREATION_KEY" \
  "$BATON_WORKSPACE_RECOVERY_KEY" \
  "$BATON_WATCH_EVENT_RECEIVER_BEARER_TOKEN" \
  "$INITIAL_ACCESS_KEY_A" \
  "$ACTIVE_ACCESS_KEY_A" \
  "$INITIAL_ACCESS_KEY_B" \
  "$RECOVERED_ACCESS_KEY_A" \
  "$RECOVERED_ACCESS_KEY_B" \
  "$WORKSPACE_CREATE_IDEMPOTENCY_A" \
  "$WORKSPACE_CREATE_IDEMPOTENCY_B" \
  "$ACCESS_KEY_ROTATE_IDEMPOTENCY_A" \
  "$SUCCESSOR_IDEMPOTENCY_A" \
  "$POST_BACKUP_MEMBER_IDEMPOTENCY" \
  "$RECOVERY_IDEMPOTENCY_A" \
  "$RECOVERY_IDEMPOTENCY_B" \
  "$POST_RECOVERY_MEMBER_IDEMPOTENCY" \
  "$WATCH_EVENT_ID" \
  "$WATCH_EVENT_ATTEMPT_ID" \
  "$WATCH_EVENT_RESOURCE_ID" \
  "$WRONG_RECOVERY_KEY" \
  "$RECOVERY_REHEARSAL_TOKEN"; do
  if [[ "$FINAL_WEB_LOGS" == *"$protected_value"* \
    || "$FINAL_APP_LOGS" == *"$protected_value"* ]]; then
    log "복구 리허설 credential 또는 멱등 키가 runtime log에 노출됐습니다."
    exit 1
  fi
done

log "production runtime과 격리 복구 리허설이 통과했습니다: $HTTPS_BASE_URL"
