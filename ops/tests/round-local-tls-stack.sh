#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPOSITORY_ROOT="$(dirname -- "$(dirname -- "$SCRIPT_DIR")")"
BASE_COMPOSE_FILE="$REPOSITORY_ROOT/compose.production.yml"
OIDC_COMPOSE_FILE="$REPOSITORY_ROOT/ops/compose.production-oidc.yml"
ROUND_COMPOSE_FILE="$REPOSITORY_ROOT/ops/compose.production-round.yml"
LOCAL_COMPOSE_FILE="$SCRIPT_DIR/compose.round-local-tls.yml"
if [[ -n "${BATON_ROUND_LOCAL_TEMP_BASE:-}" ]]; then
  TEMP_BASE_INPUT="$BATON_ROUND_LOCAL_TEMP_BASE"
elif [[ "$(uname -s)" == Darwin ]]; then
  TEMP_BASE_INPUT=/private/tmp
else
  TEMP_BASE_INPUT="${TMPDIR:-/tmp}"
fi
ROUND_REPOSITORY_ROOT="${ROUND_REPOSITORY_ROOT:-}"
COMMAND="${1:-}"
MINIMUM_COMPOSE_VERSION="2.24.4"
CURRENT_DOCKER_CONTEXT=""
CURRENT_DOCKER_DAEMON_ID=""

log() {
  printf '[round-local-tls] %s\n' "$*"
}

fail() {
  log "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "필수 명령을 찾지 못했습니다: $1"
}

canonicalize_paths() {
  local requested_state_directory
  local requested_state_parent
  local canonical_state_parent

  [[ -d "$TEMP_BASE_INPUT" ]] || fail "임시 루트를 찾지 못했습니다: $TEMP_BASE_INPUT"
  TEMP_BASE="$(CDPATH= cd -- "$TEMP_BASE_INPUT" && pwd -P)"
  requested_state_directory="${BATON_ROUND_LOCAL_STATE_DIRECTORY:-$TEMP_BASE/baton-round-local-tls-${UID}}"
  [[ "$requested_state_directory" == /* ]] || fail "상태 디렉터리는 절대 경로여야 합니다"

  requested_state_parent="$(dirname -- "$requested_state_directory")"
  [[ -d "$requested_state_parent" ]] \
    || fail "상태 디렉터리의 상위 임시 루트를 찾지 못했습니다: $requested_state_parent"
  canonical_state_parent="$(CDPATH= cd -- "$requested_state_parent" && pwd -P)"
  [[ "$canonical_state_parent" == "$TEMP_BASE" ]] \
    || fail "상태 디렉터리는 정규화된 임시 루트의 직계 자식이어야 합니다"

  STATE_BASENAME="$(basename -- "$requested_state_directory")"
  [[ "$STATE_BASENAME" =~ ^baton-round-local-tls-${UID}(-[a-z0-9_-]+)?$ ]] \
    || fail "상태 디렉터리 이름이 현재 사용자용 안전 패턴과 일치하지 않습니다"
  [[ ${#STATE_BASENAME} -le 63 ]] || fail "상태 디렉터리 이름이 너무 깁니다"

  STATE_DIRECTORY="$canonical_state_parent/$STATE_BASENAME"
  ENV_FILE="$STATE_DIRECTORY/compose.env"
  KEY_DIRECTORY="$STATE_DIRECTORY/round-keys"
  TURN_SECRET_FILE="$STATE_DIRECTORY/turn-shared-secret"
  STATE_SENTINEL="$STATE_DIRECTORY/.round-local-tls-state"
  PROJECT_NAME="$STATE_BASENAME"
}

canonicalize_paths

require_safe_state_directory() {
  [[ "$(dirname -- "$STATE_DIRECTORY")" == "$TEMP_BASE" ]] \
    || fail "상태 디렉터리의 정규화된 상위 경계가 바뀌었습니다"
  [[ "$(basename -- "$STATE_DIRECTORY")" == "$STATE_BASENAME" ]] \
    || fail "상태 디렉터리 이름 경계가 바뀌었습니다"
  [[ ! -L "$STATE_DIRECTORY" ]] || fail "상태 디렉터리 심볼릭 링크는 허용하지 않습니다"
}

load_docker_identity() {
  CURRENT_DOCKER_CONTEXT="$(docker context show)"
  CURRENT_DOCKER_DAEMON_ID="$(docker info --format '{{.ID}}')"
  [[ -n "$CURRENT_DOCKER_CONTEXT" && -n "$CURRENT_DOCKER_DAEMON_ID" ]] \
    || fail "Docker context 또는 daemon ID를 확인하지 못했습니다"
  [[ "$CURRENT_DOCKER_CONTEXT" != *$'\n'* && "$CURRENT_DOCKER_CONTEXT" != *$'\r'* ]] \
    || fail "Docker context 이름에 줄바꿈을 사용할 수 없습니다"
  [[ "$CURRENT_DOCKER_DAEMON_ID" != *$'\n'* && "$CURRENT_DOCKER_DAEMON_ID" != *$'\r'* ]] \
    || fail "Docker daemon ID에 줄바꿈을 사용할 수 없습니다"
}

sentinel_payload() {
  printf 'version=1\nuid=%s\nproject=%s\ndocker_context=%s\ndocker_daemon_id=%s\n' \
    "$UID" \
    "$PROJECT_NAME" \
    "$CURRENT_DOCKER_CONTEXT" \
    "$CURRENT_DOCKER_DAEMON_ID"
}

state_directory_mode() {
  if stat -f '%Lp' "$STATE_DIRECTORY" >/dev/null 2>&1; then
    stat -f '%Lp' "$STATE_DIRECTORY"
    return
  fi
  stat -c '%a' "$STATE_DIRECTORY"
}

managed_state_directory_is_valid() {
  local mode
  local actual_sentinel
  local expected_sentinel

  [[ "$(dirname -- "$STATE_DIRECTORY")" == "$TEMP_BASE" ]] || return 1
  [[ "$(basename -- "$STATE_DIRECTORY")" == "$STATE_BASENAME" ]] || return 1
  [[ -d "$STATE_DIRECTORY" && ! -L "$STATE_DIRECTORY" && -O "$STATE_DIRECTORY" ]] || return 1
  mode="$(state_directory_mode)" || return 1
  (( (8#$mode & 8#077) == 0 )) || return 1
  [[ -f "$STATE_SENTINEL" && ! -L "$STATE_SENTINEL" && -O "$STATE_SENTINEL" ]] || return 1
  actual_sentinel="$(<"$STATE_SENTINEL")" || return 1
  expected_sentinel="$(sentinel_payload)"
  [[ "$actual_sentinel" == "$expected_sentinel" ]]
}

require_managed_state_directory() {
  require_safe_state_directory
  managed_state_directory_is_valid \
    || fail "상태 디렉터리의 소유권, 권한, sentinel 또는 Docker daemon 경계가 일치하지 않습니다"
}

require_ready_state_directory() {
  require_managed_state_directory
  [[ -f "$ENV_FILE" && ! -L "$ENV_FILE" && -O "$ENV_FILE" ]] \
    || fail "관리되는 Compose 환경 파일을 찾지 못했습니다"
}

COMPOSE_UNSET_VARIABLES=(
  BATON_DB_NAME
  BATON_DB_PASSWORD
  BATON_DB_ROOT_PASSWORD
  BATON_DB_USERNAME
  BATON_GO_BASE_URL
  BATON_GO_ENABLED
  BATON_GO_MANAGEMENT_TOKEN
  BATON_GO_PUBLIC_BASE_URL
  BATON_HOST
  BATON_HTTPS_TCP_PUBLISH
  BATON_HTTPS_UDP_PUBLISH
  BATON_HTTP_PUBLISH
  BATON_IDENTITY_BOOTSTRAP_INVITATION_TTL
  BATON_IDENTITY_BOOTSTRAP_KEY
  BATON_IDENTITY_INVITATION_HMAC_SECRET
  BATON_IDENTITY_MEMBER_INVITATION_TTL
  BATON_IDENTITY_OIDC_ENABLED
  BATON_ROUND_GRANT_ACTIVE_KID
  BATON_ROUND_GRANT_JWK_SET_FILE
  BATON_ROUND_GRANT_PRIVATE_KEY_FILE
  BATON_ROUND_LOCAL_TEMP_BASE
  BATON_ROUND_PUBLIC_BASE_URL
  BATON_ROUND_SIGNALING_IMAGE
  BATON_ROUND_TURN_SHARED_SECRET
  BATON_ROUND_TURN_URLS
  BATON_ROUND_WEB_IMAGE
  BATON_WORKSPACE_CREATION_KEY
  BATON_WORKSPACE_RECOVERY_KEY
  CADDY_IMAGE
  COMPOSE_DISABLE_ENV_FILE
  COMPOSE_ENV_FILES
  COMPOSE_FILE
  COMPOSE_PROFILES
  COMPOSE_PROJECT_NAME
  COTURN_IMAGE
  JAVA_BUILD_IMAGE
  JAVA_RUNTIME_IMAGE
  MYSQL_DATABASE
  MYSQL_PASSWORD
  MYSQL_USER
  NODE_IMAGE
  ROUND_LOCAL_TURN_IMAGE
  ROUND_LOCAL_TURN_SHARED_SECRET_FILE
  ROUND_REPOSITORY_ROOT
  SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID
  SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET
  SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_REDIRECT_URI
)

compose() {
  local name
  local -a compose_command=(env)
  for name in "${COMPOSE_UNSET_VARIABLES[@]}"; do
    compose_command+=(-u "$name")
  done
  compose_command+=(
    docker compose
    --project-directory "$REPOSITORY_ROOT"
    --project-name "$PROJECT_NAME"
    --env-file "$ENV_FILE"
    --file "$BASE_COMPOSE_FILE"
    --file "$OIDC_COMPOSE_FILE"
    --file "$ROUND_COMPOSE_FILE"
    --file "$LOCAL_COMPOSE_FILE"
  )
  "${compose_command[@]}" "$@"
}

write_env() {
  local name="$1"
  local value="$2"
  [[ "$name" =~ ^[A-Z0-9_]+$ ]] || fail "환경 변수 이름이 올바르지 않습니다: $name"
  [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] \
    || fail "$name 값에 줄바꿈을 사용할 수 없습니다"
  value="${value//\\/\\\\}"
  value="${value//\'/\\\'}"
  printf "%s='%s'\n" "$name" "$value" >>"$ENV_FILE"
}

random_hex() {
  openssl rand -hex "$1"
}

compose_version_at_least() {
  local version="$1"
  local required="$2"
  local major minor patch required_major required_minor required_patch

  [[ "$version" =~ ^v?([0-9]+)\.([0-9]+)\.([0-9]+) ]] || return 1
  major=$((10#${BASH_REMATCH[1]}))
  minor=$((10#${BASH_REMATCH[2]}))
  patch=$((10#${BASH_REMATCH[3]}))
  IFS=. read -r required_major required_minor required_patch <<<"$required"

  (( major > required_major )) && return 0
  (( major < required_major )) && return 1
  (( minor > required_minor )) && return 0
  (( minor < required_minor )) && return 1
  (( patch >= required_patch ))
}

require_supported_compose_version() {
  local compose_version
  compose_version="$(docker compose version --short)"
  compose_version_at_least "$compose_version" "$MINIMUM_COMPOSE_VERSION" \
    || fail "Docker Compose $MINIMUM_COMPOSE_VERSION 이상이 필요합니다: ${compose_version:-unknown}"
}

project_resources_remain() {
  local containers
  local volumes
  local networks
  local resource_name

  containers="$(docker ps --all --quiet \
    --filter "label=com.docker.compose.project=$PROJECT_NAME")" || return 2
  volumes="$(docker volume ls --quiet \
    --filter "label=com.docker.compose.project=$PROJECT_NAME")" || return 2
  networks="$(docker network ls --quiet \
    --filter "label=com.docker.compose.project=$PROJECT_NAME")" || return 2
  [[ -z "$containers$volumes$networks" ]] || return 0

  for resource_name in \
    "${PROJECT_NAME}_baton_mysql_data" \
    "${PROJECT_NAME}_baton_caddy_data" \
    "${PROJECT_NAME}_baton_caddy_config" \
    "${PROJECT_NAME}_round_local_truststore"; do
    if docker volume inspect "$resource_name" >/dev/null 2>&1; then
      return 0
    fi
  done
  for resource_name in \
    "${PROJECT_NAME}_data" \
    "${PROJECT_NAME}_edge" \
    "${PROJECT_NAME}_round"; do
    if docker network inspect "$resource_name" >/dev/null 2>&1; then
      return 0
    fi
  done
  return 1
}

require_no_project_resources() {
  local status
  if project_resources_remain; then
    fail "동일한 Compose project의 container, volume 또는 network가 남아 있습니다: $PROJECT_NAME"
  else
    status=$?
    [[ $status -eq 1 ]] || fail "Compose project 리소스 상태를 확인하지 못했습니다"
  fi
}

port_in_use() {
  local protocol="$1"
  local port="$2"
  if [[ "$protocol" == TCP ]]; then
    lsof -nP "-iTCP:$port" -sTCP:LISTEN 2>/dev/null | grep -q .
    return
  fi
  lsof -nP "-iUDP:$port" 2>/dev/null | grep -q .
}

require_free_ports() {
  local port
  for port in 443 18080 19090; do
    if port_in_use TCP "$port"; then
      fail "TCP $port 포트를 다른 프로세스가 사용 중입니다"
    fi
  done
  for port in 3478 49160 49161 49162 49163 49164 49165 49166 49167 49168 49169; do
    if port_in_use UDP "$port"; then
      fail "UDP $port 포트를 다른 프로세스가 사용 중입니다"
    fi
  done
}

wait_for_url() {
  local url="$1"
  local label="$2"
  local insecure="${3:-false}"
  local -a curl_options=(--fail --silent --show-error --max-time 3)
  if [[ "$insecure" == true ]]; then
    curl_options+=(--insecure)
  fi
  for ((attempt = 1; attempt <= 120; attempt += 1)); do
    if curl "${curl_options[@]}" "$url" >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done
  fail "$label 준비 시간이 초과됐습니다: $url"
}

wait_for_service_health() {
  local service="$1"
  local label="$2"
  local container_id=""
  local health=""
  for ((attempt = 1; attempt <= 180; attempt += 1)); do
    container_id="$(compose ps --all -q "$service")"
    if [[ -n "$container_id" ]]; then
      health="$(docker inspect \
        --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
        "$container_id")"
      if [[ "$health" == healthy ]]; then
        return
      fi
      if [[ "$health" == unhealthy || "$health" == exited || "$health" == dead ]]; then
        fail "$label 서비스가 준비되지 않았습니다: $health"
      fi
    fi
    sleep 1
  done
  fail "$label 서비스 준비 시간이 초과됐습니다: ${health:-missing}"
}

log_recovery_command() {
  log "복구 상태를 보존했습니다: $STATE_DIRECTORY"
  log "재시도: BATON_ROUND_LOCAL_TEMP_BASE='$TEMP_BASE' BATON_ROUND_LOCAL_STATE_DIRECTORY='$STATE_DIRECTORY' '$0' down"
}

remove_state_after_verified_cleanup() {
  local status
  if project_resources_remain; then
    log_recovery_command
    return 1
  else
    status=$?
    if [[ $status -ne 1 ]]; then
      log "Compose project 리소스의 정리 후 상태를 확인하지 못했습니다."
      log_recovery_command
      return 1
    fi
  fi
  if managed_state_directory_is_valid; then
    rm -rf -- "$STATE_DIRECTORY"
    return
  fi
  log "상태 sentinel 검증에 실패해 디렉터리를 삭제하지 않습니다."
  log_recovery_command
  return 1
}

down_stack() {
  require_command docker
  require_safe_state_directory
  if [[ ! -e "$STATE_DIRECTORY" ]]; then
    log "실행 중인 로컬 TLS 스택 상태를 찾지 못했습니다."
    return
  fi
  docker info >/dev/null
  load_docker_identity
  require_supported_compose_version
  require_ready_state_directory

  if ! compose down --volumes --remove-orphans --timeout 15; then
    log_recovery_command
    fail "Compose 정리에 실패했습니다. 상태 파일을 보존했습니다"
  fi
  remove_state_after_verified_cleanup \
    || fail "Compose 리소스가 남아 있어 상태 파일을 보존했습니다"
  log "로컬 TLS 스택과 전용 volume을 정리했습니다."
}

assert_secret_absent_from_runtime_metadata() {
  local secret="$1"
  local service
  local container_id
  local runtime_metadata
  for service in round-signaling round-turn; do
    container_id="$(compose ps --all -q "$service")"
    [[ -n "$container_id" ]] || fail "$service container를 찾지 못했습니다"
    runtime_metadata="$(docker inspect \
      --format '{{json .Config.Cmd}} {{json .Config.Entrypoint}} {{json .Config.Env}}' \
      "$container_id")"
    [[ "$runtime_metadata" != *"$secret"* ]] \
      || fail "$service runtime metadata에 TURN shared secret이 노출됐습니다"
    runtime_metadata="$(docker logs "$container_id" 2>&1)"
    [[ "$runtime_metadata" != *"$secret"* ]] \
      || fail "$service log에 TURN shared secret이 노출됐습니다"
  done
}

up_stack() {
  local database_password
  local database_root_password
  local creation_key
  local recovery_key
  local bootstrap_key
  local invitation_secret
  local oidc_client_secret
  local turn_secret
  local up_complete=false
  local compose_invoked=false
  local state_creation_started=false

  require_command curl
  require_command docker
  require_command env
  require_command grep
  require_command lsof
  require_command node
  require_command openssl
  require_command stat
  require_safe_state_directory

  [[ -n "$ROUND_REPOSITORY_ROOT" ]] \
    || fail "ROUND_REPOSITORY_ROOT 환경 변수로 ROUND 저장소 절대 경로를 지정하세요"
  [[ "$ROUND_REPOSITORY_ROOT" == /* && -d "$ROUND_REPOSITORY_ROOT" ]] \
    || fail "ROUND_REPOSITORY_ROOT는 존재하는 절대 디렉터리여야 합니다"
  ROUND_REPOSITORY_ROOT="$(CDPATH= cd -- "$ROUND_REPOSITORY_ROOT" && pwd -P)"
  [[ -f "$ROUND_REPOSITORY_ROOT/Dockerfile" && ! -L "$ROUND_REPOSITORY_ROOT/Dockerfile" ]] \
    || fail "ROUND_REPOSITORY_ROOT에서 Dockerfile을 찾지 못했습니다"
  [[ ! -e "$STATE_DIRECTORY" ]] \
    || fail "기존 상태 디렉터리가 있습니다. 먼저 down을 실행하세요: $STATE_DIRECTORY"

  docker info >/dev/null
  load_docker_identity
  require_supported_compose_version
  require_no_project_resources
  require_free_ports

  cleanup_failed_up() {
    local status=$?
    local down_succeeded=true
    trap - EXIT INT TERM
    if [[ "$up_complete" != true && "$state_creation_started" == true ]]; then
      log "기동에 실패해 전용 Compose 리소스를 정리합니다."
      if [[ "$compose_invoked" == true && -f "$ENV_FILE" && ! -L "$ENV_FILE" ]]; then
        if ! compose down --volumes --remove-orphans --timeout 10 >/dev/null 2>&1; then
          down_succeeded=false
        fi
      fi
      if [[ "$down_succeeded" == true ]]; then
        remove_state_after_verified_cleanup || true
      else
        log "Compose down이 실패해 상태와 비밀 파일을 삭제하지 않습니다."
        log_recovery_command
      fi
    fi
    exit "$status"
  }
  trap cleanup_failed_up EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM

  state_creation_started=true
  mkdir -m 0700 -- "$STATE_DIRECTORY"
  sentinel_payload >"$STATE_SENTINEL"
  chmod 0600 "$STATE_SENTINEL"
  mkdir -m 0700 -- "$KEY_DIRECTORY"
  node "$REPOSITORY_ROOT/frontend/tests/support/generate-round-key-material.mjs" \
    "$KEY_DIRECTORY" \
    baton-round-local-tls >/dev/null

  database_password="$(random_hex 32)"
  database_root_password="$(random_hex 32)"
  creation_key="$(random_hex 32)"
  recovery_key="$(random_hex 32)"
  bootstrap_key="$(random_hex 32)"
  invitation_secret="$(random_hex 32)"
  oidc_client_secret="$(random_hex 32)"
  turn_secret="$(random_hex 32)"

  printf '%s\n' "$turn_secret" >"$TURN_SECRET_FILE"
  chmod 0600 "$TURN_SECRET_FILE"
  : >"$ENV_FILE"
  chmod 0600 "$ENV_FILE"
  write_env BATON_HOST baton.localhost
  write_env BATON_DB_NAME baton_round_local_tls
  write_env BATON_DB_USERNAME baton_round_local_tls
  write_env BATON_DB_PASSWORD "$database_password"
  write_env BATON_DB_ROOT_PASSWORD "$database_root_password"
  write_env BATON_WORKSPACE_CREATION_KEY "$creation_key"
  write_env BATON_WORKSPACE_RECOVERY_KEY "$recovery_key"
  write_env BATON_IDENTITY_BOOTSTRAP_KEY "$bootstrap_key"
  write_env BATON_IDENTITY_INVITATION_HMAC_SECRET "$invitation_secret"
  write_env BATON_IDENTITY_OIDC_ENABLED true
  write_env SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID baton-round-local-tls
  write_env SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET "$oidc_client_secret"
  write_env SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_REDIRECT_URI \
    https://baton.localhost/api/v1/auth/oidc/callback/google
  write_env BATON_GO_ENABLED false
  write_env BATON_ROUND_PUBLIC_BASE_URL https://baton.localhost
  write_env BATON_ROUND_GRANT_ENABLED true
  write_env BATON_ROUND_GRANT_ACTIVE_KID baton-round-local-tls
  write_env BATON_ROUND_GRANT_PRIVATE_KEY_FILE \
    "$KEY_DIRECTORY/round-signing-key.pem"
  write_env BATON_ROUND_GRANT_JWK_SET_FILE "$KEY_DIRECTORY/round-jwks.json"
  write_env BATON_ROUND_WEB_IMAGE "baton-round-local-web:$PROJECT_NAME"
  write_env BATON_ROUND_SIGNALING_IMAGE "baton-round-local-signaling:$PROJECT_NAME"
  write_env ROUND_LOCAL_TURN_IMAGE "baton-round-local-turn:$PROJECT_NAME"
  write_env BATON_ROUND_TURN_URLS 'turn:127.0.0.1:3478?transport=udp'
  write_env BATON_ROUND_TURN_SHARED_SECRET local-secret-mounted-from-file
  write_env ROUND_LOCAL_TURN_SHARED_SECRET_FILE "$TURN_SECRET_FILE"
  write_env BATON_HTTP_PUBLISH 127.0.0.1:18081:80
  write_env BATON_HTTPS_TCP_PUBLISH 127.0.0.1:443:443
  write_env BATON_HTTPS_UDP_PUBLISH 127.0.0.1:14443:443/udp
  write_env ROUND_REPOSITORY_ROOT "$ROUND_REPOSITORY_ROOT"

  log "Compose 계약을 검사합니다."
  compose config --quiet
  log "BATON production edge와 ROUND relay-only 이미지를 빌드하고 시작합니다."
  compose_invoked=true
  compose up --build --detach

  wait_for_url http://127.0.0.1:19090/health "mock OIDC"
  wait_for_service_health mysql MySQL
  wait_for_service_health app "BATON Spring"
  wait_for_service_health round-web "ROUND web"
  wait_for_service_health round-signaling "ROUND signaling"
  wait_for_service_health round-turn coturn
  wait_for_service_health web "BATON Caddy"
  wait_for_url https://baton.localhost/actuator/health "BATON HTTPS edge" true
  wait_for_url https://baton.localhost/.well-known/jwks.json "BATON public JWKS" true
  compose exec -T web caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null

  local auth_mode
  auth_mode="$(compose exec -T round-web cat /srv/.round-auth-mode)"
  [[ "$auth_mode" == baton ]] || fail "ROUND web image가 BATON 모드가 아닙니다"
  assert_secret_absent_from_runtime_metadata "$turn_secret"

  up_complete=true
  trap - EXIT INT TERM
  log "로컬 production-like 스택이 준비됐습니다."
  log "공개 origin: https://baton.localhost"
  log "운영자 bootstrap loopback origin: http://127.0.0.1:18080"
  log "mock OIDC browser origin: http://127.0.0.1:19090"
  log "정리: BATON_ROUND_LOCAL_TEMP_BASE='$TEMP_BASE' BATON_ROUND_LOCAL_STATE_DIRECTORY='$STATE_DIRECTORY' '$0' down"
}

status_stack() {
  require_command docker
  require_safe_state_directory
  [[ -e "$STATE_DIRECTORY" ]] || fail "실행 중인 로컬 TLS 스택 상태를 찾지 못했습니다"
  docker info >/dev/null
  load_docker_identity
  require_supported_compose_version
  require_ready_state_directory
  compose ps
}

case "$COMMAND" in
  up) up_stack ;;
  down) down_stack ;;
  status) status_stack ;;
  *)
    printf 'Usage: ROUND_REPOSITORY_ROOT=/absolute/path %s {up|down|status}\n' "$0" >&2
    exit 2
    ;;
esac
