#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

SCRIPT_DIRECTORY="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPOSITORY_ROOT="$(dirname -- "$(dirname -- "$SCRIPT_DIRECTORY")")"
BASE_COMPOSE_FILE="$REPOSITORY_ROOT/compose.production.yml"
OIDC_COMPOSE_FILE="$REPOSITORY_ROOT/ops/compose.production-oidc.yml"
ROUND_COMPOSE_FILE="$REPOSITORY_ROOT/ops/compose.production-round.yml"
TEST_COMPOSE_FILE="$SCRIPT_DIRECTORY/compose.round-edge-tls-e2e.yml"
FRONTEND_DIRECTORY="$REPOSITORY_ROOT/frontend"
TEMPORARY_BASE="${TMPDIR:-/tmp}"
ROUND_REPOSITORY_ROOT="${ROUND_REPOSITORY_ROOT:-}"
RUN_DIRECTORY=""
ENV_FILE=""
KEY_DIRECTORY=""
PLAYWRIGHT_LOG=""
COMPOSE_UP_LOG=""
RUN_TOKEN=""
PROJECT_NAME=""
ROUND_WEB_IMAGE=""
ROUND_SIGNALING_IMAGE=""
TLS_PORT=""
HTTP_PORT=""
OIDC_PORT=""
DOCKER_CONTEXT=""
DOCKER_ENDPOINT=""
DOCKER_DAEMON_SIGNATURE=""
STACK_CREATED=false

readonly -a PROTECTED_ENVIRONMENT_NAMES=(
  BATON_HOST
  BATON_DB_NAME
  BATON_DB_USERNAME
  BATON_DB_PASSWORD
  BATON_DB_ROOT_PASSWORD
  BATON_WORKSPACE_CREATION_KEY
  BATON_WORKSPACE_RECOVERY_KEY
  BATON_IDENTITY_BOOTSTRAP_KEY
  BATON_IDENTITY_INVITATION_HMAC_SECRET
  BATON_IDENTITY_BOOTSTRAP_INVITATION_TTL
  BATON_IDENTITY_MEMBER_INVITATION_TTL
  BATON_IDENTITY_OIDC_ENABLED
  SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID
  SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET
  SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_REDIRECT_URI
  BATON_GO_ENABLED
  BATON_GO_BASE_URL
  BATON_GO_PUBLIC_BASE_URL
  BATON_GO_MANAGEMENT_TOKEN
  BATON_ROUND_PUBLIC_BASE_URL
  BATON_ROUND_GRANT_ENABLED
  BATON_ROUND_GRANT_ACTIVE_KID
  BATON_ROUND_GRANT_PRIVATE_KEY_FILE
  BATON_ROUND_GRANT_JWK_SET_FILE
  BATON_ROUND_WEB_IMAGE
  BATON_ROUND_SIGNALING_IMAGE
  BATON_ROUND_TURN_URLS
  BATON_ROUND_TURN_SHARED_SECRET
  BATON_HTTP_PUBLISH
  BATON_HTTPS_TCP_PUBLISH
  BATON_HTTPS_UDP_PUBLISH
  BATON_ROUND_EDGE_RUN_TOKEN
  BATON_ROUND_EDGE_TLS_PORT
  BATON_ROUND_EDGE_OIDC_PORT
  ROUND_REPOSITORY_ROOT
  NODE_IMAGE
  CADDY_IMAGE
  JAVA_BUILD_IMAGE
  JAVA_RUNTIME_IMAGE
  COTURN_IMAGE
  COMPOSE_FILE
  COMPOSE_ENV_FILES
  COMPOSE_PROJECT_NAME
  COMPOSE_PROFILES
)

log() {
  printf '[round-edge-tls-e2e] %s\n' "$*"
}

fail() {
  log "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "필수 명령을 찾지 못했습니다: $1"
}

write_env() {
  local name="$1"
  local value="$2"
  [[ "$name" =~ ^[A-Z0-9_]+$ ]] || fail "환경 변수 이름이 올바르지 않습니다: $name"
  [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] \
    || fail "$name 값에 줄바꿈을 사용할 수 없습니다"
  printf '%s=%s\n' "$name" "$value" >>"$ENV_FILE"
}

random_hex() {
  openssl rand -hex "$1"
}

contains_sensitive_material() {
  local source_file="$1"
  local name=""
  local value=""
  local grep_status=0
  [[ -f "$source_file" && -r "$source_file" ]] || return 2

  while IFS='=' read -r name value; do
    case "$name" in
      BATON_DB_PASSWORD|BATON_DB_ROOT_PASSWORD|BATON_WORKSPACE_CREATION_KEY|\
      BATON_WORKSPACE_RECOVERY_KEY|BATON_IDENTITY_BOOTSTRAP_KEY|\
      BATON_IDENTITY_INVITATION_HMAC_SECRET|\
      SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET|\
      BATON_ROUND_TURN_SHARED_SECRET)
        if [[ -n "$value" ]]; then
          if grep --fixed-strings --quiet -- "$value" "$source_file"; then
            return 0
          else
            grep_status=$?
            [[ "$grep_status" -eq 1 ]] || return 2
          fi
        fi
        ;;
    esac
  done <"$ENV_FILE"

  if grep --ignore-case --extended-regexp --quiet -- \
    '#accessKey=|(__Host-baton_session|__Secure-round_access|JSESSIONID|baton_session)[[:space:]]*[=:]|[?&](code|state|nonce|code_verifier|code_challenge)=|(Authorization|Idempotency-Key|X-Baton-(Creation|Access|Identity-Bootstrap)-Key|X-CSRF-TOKEN)[[:space:]]*:|"?(accessKey|csrfToken|token)"?[[:space:]]*:[[:space:]]*"?[[:alnum:]_.-]{20,}|-----BEGIN (RSA )?PRIVATE KEY-----|mi1_[[:alnum:]_-]+|[[:alnum:]_-]{40,64}|[[:alnum:]_-]{20,}\.[[:alnum:]_-]{20,}\.[[:alnum:]_-]{20,}' \
    "$source_file"; then
    return 0
  else
    grep_status=$?
    [[ "$grep_status" -eq 1 ]] && return 1
    return 2
  fi
}

print_log_tail_if_safe() {
  local source_file="$1"
  local label="$2"
  local scan_status=0
  if contains_sensitive_material "$source_file"; then
    scan_status=0
  else
    scan_status=$?
  fi
  case "$scan_status" in
    0) log "$label 로그에서 민감정보 패턴을 감지해 콘솔 출력을 차단했습니다" ;;
    1) tail -n 160 "$source_file" 2>/dev/null \
      || log "$label 로그를 읽지 못했습니다" ;;
    *) log "$label 로그 안전 검사가 실패해 콘솔 출력을 차단했습니다" ;;
  esac
}

print_safe_playwright_diagnostic() {
  local last_stage=""
  local request_summary=""
  local response_summary=""
  local transport_summary=""
  local failure_location=""
  last_stage="$(grep --extended-regexp '^\[round-tls-stage\] [a-z-]+$' \
    "$PLAYWRIGHT_LOG" 2>/dev/null | tail -n 1 || true)"
  failure_location="$(grep --only-matching --extended-regexp \
    'identity-round-transport\.spec\.ts:[0-9]+:[0-9]+' \
    "$PLAYWRIGHT_LOG" 2>/dev/null | tail -n 1 || true)"
  transport_summary="$(grep --extended-regexp \
    '^\[round-tls-transport\] protected=[0-9]+ standalone=[0-9]+ grant=(none|[0-9,]+) turn=(none|[0-9,]+) wss=[0-9]+$' \
    "$PLAYWRIGHT_LOG" 2>/dev/null | tail -n 1 || true)"
  response_summary="$(grep --extended-regexp \
    '^\[round-tls-response\] grant status=[0-9]{3} content=(absent|json|other) length=(absent|zero|positive|invalid)$' \
    "$PLAYWRIGHT_LOG" 2>/dev/null | tail -n 1 || true)"
  request_summary="$(grep --extended-regexp \
    '^\[round-tls-request\] method=[A-Z]+ query=(absent|present) cookies=[0-9]+ session=[0-9]+ grant=[0-9]+ other=[0-9]+ cookieShape=(valid|invalid) origin=(match|mismatch|absent) fetchSite=(same-origin|other|absent) csrf=(present|absent) content=(json|other) body=(empty|present)$' \
    "$PLAYWRIGHT_LOG" 2>/dev/null | tail -n 1 || true)"
  if [[ -n "$last_stage" ]]; then
    log "마지막 안전 단계: ${last_stage#\[round-tls-stage\] }"
  else
    log "첫 안전 단계 이전에 브라우저 검증이 실패했습니다"
  fi
  if [[ -n "$failure_location" ]]; then
    log "실패 위치: $failure_location"
  fi
  if [[ -n "$transport_summary" ]]; then
    log "안전 전송 요약: ${transport_summary#\[round-tls-transport\] }"
  fi
  if [[ -n "$response_summary" ]]; then
    log "안전 응답 요약: ${response_summary#\[round-tls-response\] }"
  fi
  if [[ -n "$request_summary" ]]; then
    log "안전 요청 요약: ${request_summary#\[round-tls-request\] }"
  fi
}

pick_tcp_port() {
  node -e '
    const net = require("node:net");
    const server = net.createServer();
    server.unref();
    server.on("error", () => process.exit(1));
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (!address || typeof address === "string") process.exit(1);
      process.stdout.write(String(address.port));
      server.close();
    });
  '
}

pick_tcp_udp_port() {
  node -e '
    const dgram = require("node:dgram");
    const net = require("node:net");

    function attempt(remaining) {
      const tcp = net.createServer();
      tcp.unref();
      tcp.once("error", () => {
        if (remaining > 1) attempt(remaining - 1);
        else process.exit(1);
      });
      tcp.listen(0, "127.0.0.1", () => {
        const address = tcp.address();
        if (!address || typeof address === "string") process.exit(1);
        const udp = dgram.createSocket("udp4");
        udp.unref();
        udp.once("error", () => {
          tcp.close(() => {
            if (remaining > 1) attempt(remaining - 1);
            else process.exit(1);
          });
        });
        udp.bind(address.port, "127.0.0.1", () => {
          process.stdout.write(String(address.port));
          udp.close();
          tcp.close();
        });
      });
    }

    attempt(20);
  '
}

replace_env_value() {
  local name="$1"
  local value="$2"
  local next_env="$RUN_DIRECTORY/compose.env.next"
  [[ "$name" =~ ^[A-Z0-9_]+$ ]] || fail "환경 변수 이름이 올바르지 않습니다: $name"
  [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] \
    || fail "$name 값에 줄바꿈을 사용할 수 없습니다"
  awk -v key="$name" -v replacement="$name=$value" '
    index($0, key "=") == 1 {
      if (found == 1) {
        exit 3
      }
      print replacement
      found = 1
      next
    }
    { print }
    END {
      if (found != 1) {
        exit 2
      }
    }
  ' "$ENV_FILE" >"$next_env" || fail "$name 실행값을 안전하게 갱신하지 못했습니다"
  chmod 0600 "$next_env"
  mv -f -- "$next_env" "$ENV_FILE"
}

configure_runtime_ports() {
  local selected=false
  for ((attempt = 1; attempt <= 20; attempt += 1)); do
    TLS_PORT="$(pick_tcp_udp_port)"
    HTTP_PORT="$(pick_tcp_port)"
    OIDC_PORT="$(pick_tcp_port)"
    if [[ "$TLS_PORT" != "$HTTP_PORT" && "$TLS_PORT" != "$OIDC_PORT" \
      && "$HTTP_PORT" != "$OIDC_PORT" ]]; then
      selected=true
      break
    fi
  done
  [[ "$selected" == true ]] || fail "서로 다른 로컬 실행 포트를 선택하지 못했습니다"

  replace_env_value SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_REDIRECT_URI \
    "https://baton.localhost:$TLS_PORT/api/v1/auth/oidc/callback/google"
  replace_env_value BATON_ROUND_PUBLIC_BASE_URL "https://baton.localhost:$TLS_PORT"
  replace_env_value BATON_HTTP_PUBLISH "127.0.0.1:$HTTP_PORT:80"
  replace_env_value BATON_HTTPS_TCP_PUBLISH "127.0.0.1:$TLS_PORT:443"
  replace_env_value BATON_HTTPS_UDP_PUBLISH "127.0.0.1:$TLS_PORT:443/udp"
  replace_env_value BATON_ROUND_EDGE_TLS_PORT "$TLS_PORT"
  replace_env_value BATON_ROUND_EDGE_OIDC_PORT "$OIDC_PORT"
}

is_port_binding_conflict() {
  grep --ignore-case --extended-regexp --quiet -- \
    'port is already allocated|failed to bind (host )?port|address already in use' \
    "$COMPOSE_UP_LOG"
}

compose() {
  local -a unset_arguments=()
  local name
  for name in "${PROTECTED_ENVIRONMENT_NAMES[@]}"; do
    unset_arguments+=(-u "$name")
  done
  env "${unset_arguments[@]}" COMPOSE_DISABLE_ENV_FILE=1 \
    docker compose \
      --project-directory "$REPOSITORY_ROOT" \
      --project-name "$PROJECT_NAME" \
      --env-file "$ENV_FILE" \
      --file "$BASE_COMPOSE_FILE" \
      --file "$OIDC_COMPOSE_FILE" \
      --file "$ROUND_COMPOSE_FILE" \
      --file "$TEST_COMPOSE_FILE" \
      "$@"
}

current_docker_context() {
  docker context show
}

current_docker_endpoint() {
  docker context inspect --format '{{.Endpoints.docker.Host}}' "$(current_docker_context)"
}

current_docker_daemon_signature() {
  docker info --format '{{.ServerVersion}}|{{.OSType}}|{{.Architecture}}|{{.DockerRootDir}}'
}

require_same_docker_boundary() {
  if [[ "$(current_docker_context)" != "$DOCKER_CONTEXT" ]]; then
    log "Docker context가 실행 중 변경되어 자동 정리를 중단합니다" >&2
    return 1
  fi
  if [[ "$(current_docker_endpoint)" != "$DOCKER_ENDPOINT" ]]; then
    log "Docker endpoint가 실행 중 변경되어 자동 정리를 중단합니다" >&2
    return 1
  fi
  if [[ "$(current_docker_daemon_signature)" != "$DOCKER_DAEMON_SIGNATURE" ]]; then
    log "Docker daemon이 실행 중 변경되어 자동 정리를 중단합니다" >&2
    return 1
  fi
}

assert_resource_run_label() {
  local resource_type="$1"
  local resource_id="$2"
  local actual_label=""
  case "$resource_type" in
    container)
      actual_label="$(docker inspect \
        --format '{{index .Config.Labels "io.baton.test.run"}}' "$resource_id")"
      ;;
    network)
      actual_label="$(docker network inspect \
        --format '{{index .Labels "io.baton.test.run"}}' "$resource_id")"
      ;;
    volume)
      actual_label="$(docker volume inspect \
        --format '{{index .Labels "io.baton.test.run"}}' "$resource_id")"
      ;;
    *)
      log "알 수 없는 Docker 리소스 유형입니다: $resource_type" >&2
      return 1
      ;;
  esac
  if [[ "$actual_label" != "$RUN_TOKEN" ]]; then
    log "전용 실행 label이 없는 $resource_type 리소스 정리를 거부합니다" >&2
    return 1
  fi
}

assert_project_ownership() {
  local resource_id
  while IFS= read -r resource_id; do
    [[ -z "$resource_id" ]] || assert_resource_run_label container "$resource_id"
  done < <(docker ps --all --quiet \
    --filter "label=com.docker.compose.project=$PROJECT_NAME")
  while IFS= read -r resource_id; do
    [[ -z "$resource_id" ]] || assert_resource_run_label network "$resource_id"
  done < <(docker network ls --quiet \
    --filter "label=com.docker.compose.project=$PROJECT_NAME")
  while IFS= read -r resource_id; do
    [[ -z "$resource_id" ]] || assert_resource_run_label volume "$resource_id"
  done < <(docker volume ls --quiet \
    --filter "label=com.docker.compose.project=$PROJECT_NAME")
}

project_resources_exist() {
  [[ -n "$(docker ps --all --quiet \
    --filter "label=com.docker.compose.project=$PROJECT_NAME")" \
    || -n "$(docker network ls --quiet \
      --filter "label=com.docker.compose.project=$PROJECT_NAME")" \
    || -n "$(docker volume ls --quiet \
      --filter "label=com.docker.compose.project=$PROJECT_NAME")" ]]
}

remove_run_directory() {
  [[ -n "$RUN_DIRECTORY" && -n "$TEMPORARY_BASE" ]] || return
  case "$RUN_DIRECTORY" in
    "$TEMPORARY_BASE"/baton-round-edge-tls.*) ;;
    *) fail "예상하지 못한 임시 실행 디렉터리 정리를 거부합니다" ;;
  esac
  [[ ! -L "$RUN_DIRECTORY" ]] || fail "임시 실행 디렉터리 심볼릭 링크 정리를 거부합니다"
  rm -rf -- "$RUN_DIRECTORY"
}

cleanup() {
  local exit_status=$?
  local cleanup_status=0
  trap - EXIT INT TERM

  if [[ "$STACK_CREATED" == true ]]; then
    if require_same_docker_boundary && assert_project_ownership; then
      compose down --volumes --remove-orphans --timeout 10 --rmi local \
        >/dev/null 2>&1 || cleanup_status=1
      local image
      for image in "$ROUND_WEB_IMAGE" "$ROUND_SIGNALING_IMAGE"; do
        if docker image inspect "$image" >/dev/null 2>&1; then
          docker image rm "$image" >/dev/null 2>&1 || cleanup_status=1
        fi
      done
      if project_resources_exist; then
        log "전용 Compose 리소스 일부가 남아 있습니다"
        cleanup_status=1
      fi
    else
      cleanup_status=1
    fi
  fi

  if [[ "$cleanup_status" -eq 0 ]]; then
    remove_run_directory || cleanup_status=1
  else
    log "안전한 재확인을 위해 실행 상태를 보존합니다: $RUN_DIRECTORY"
  fi

  if [[ "$exit_status" -eq 0 && "$cleanup_status" -ne 0 ]]; then
    exit 1
  fi
  exit "$exit_status"
}

wait_for_service_health() {
  local service="$1"
  local label="$2"
  local container_id=""
  local state=""
  for ((attempt = 1; attempt <= 180; attempt += 1)); do
    container_id="$(compose ps --all --quiet "$service")"
    if [[ -n "$container_id" ]]; then
      state="$(docker inspect \
        --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
        "$container_id")"
      if [[ "$state" == healthy ]]; then
        return
      fi
      if [[ "$state" == unhealthy || "$state" == exited || "$state" == dead ]]; then
        fail "$label 서비스가 준비되지 않았습니다: $state"
      fi
    fi
    sleep 1
  done
  fail "$label 서비스 준비 시간이 초과됐습니다: ${state:-missing}"
}

wait_for_https() {
  local url="$1"
  for ((attempt = 1; attempt <= 120; attempt += 1)); do
    if curl --fail --silent --show-error --insecure --max-time 3 \
      "$url" >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done
  fail "로컬 HTTPS edge 준비 시간이 초과됐습니다"
}

prepare() {
  require_command curl
  require_command docker
  require_command grep
  require_command node
  require_command npm
  require_command openssl
  require_command tail
  docker compose version >/dev/null
  docker info >/dev/null
  [[ -x "$FRONTEND_DIRECTORY/node_modules/.bin/playwright" ]] \
    || fail "frontend Playwright 의존성이 설치되어 있지 않습니다"

  [[ -n "$ROUND_REPOSITORY_ROOT" && "$ROUND_REPOSITORY_ROOT" == /* ]] \
    || fail "ROUND_REPOSITORY_ROOT로 ROUND 저장소 절대 경로를 지정하세요"
  ROUND_REPOSITORY_ROOT="$(CDPATH= cd -- "$ROUND_REPOSITORY_ROOT" && pwd -P)"
  [[ -f "$ROUND_REPOSITORY_ROOT/Dockerfile" && ! -L "$ROUND_REPOSITORY_ROOT/Dockerfile" ]] \
    || fail "ROUND_REPOSITORY_ROOT에서 일반 Dockerfile을 찾지 못했습니다"

  TEMPORARY_BASE="$(CDPATH= cd -- "$TEMPORARY_BASE" && pwd -P)"
  RUN_DIRECTORY="$(mktemp -d "$TEMPORARY_BASE/baton-round-edge-tls.XXXXXX")"
  [[ ! -L "$RUN_DIRECTORY" ]] || fail "임시 실행 디렉터리는 심볼릭 링크일 수 없습니다"
  ENV_FILE="$RUN_DIRECTORY/compose.env"
  KEY_DIRECTORY="$RUN_DIRECTORY/round-keys"
  PLAYWRIGHT_LOG="$RUN_DIRECTORY/playwright.log"
  COMPOSE_UP_LOG="$RUN_DIRECTORY/compose-up.log"
  mkdir -p "$KEY_DIRECTORY"
  chmod 0700 "$RUN_DIRECTORY" "$KEY_DIRECTORY"

  RUN_TOKEN="$(random_hex 8)"
  PROJECT_NAME="baton-round-edge-tls-$RUN_TOKEN"
  ROUND_WEB_IMAGE="baton-round-edge-web:$RUN_TOKEN"
  ROUND_SIGNALING_IMAGE="baton-round-edge-signaling:$RUN_TOKEN"
  # Build-only placeholders are replaced immediately before the first container bind.
  TLS_PORT=4443
  HTTP_PORT=8080
  OIDC_PORT=19090

  DOCKER_CONTEXT="$(current_docker_context)"
  DOCKER_ENDPOINT="$(current_docker_endpoint)"
  DOCKER_DAEMON_SIGNATURE="$(current_docker_daemon_signature)"
  project_resources_exist && fail "고유 Compose project 리소스가 이미 존재합니다"
  local image
  for image in "$ROUND_WEB_IMAGE" "$ROUND_SIGNALING_IMAGE"; do
    if docker image inspect "$image" >/dev/null 2>&1; then
      fail "고유 ROUND 테스트 image tag가 이미 존재합니다"
    fi
  done

  node "$FRONTEND_DIRECTORY/tests/support/generate-round-key-material.mjs" \
    "$KEY_DIRECTORY" \
    baton-round-edge-tls >/dev/null

  : >"$ENV_FILE"
  chmod 0600 "$ENV_FILE"
  write_env BATON_HOST baton.localhost
  write_env BATON_DB_NAME baton_round_edge_tls
  write_env BATON_DB_USERNAME baton_round_edge_tls
  write_env BATON_DB_PASSWORD "$(random_hex 32)"
  write_env BATON_DB_ROOT_PASSWORD "$(random_hex 32)"
  write_env BATON_WORKSPACE_CREATION_KEY "$(random_hex 32)"
  write_env BATON_WORKSPACE_RECOVERY_KEY "$(random_hex 32)"
  write_env BATON_IDENTITY_BOOTSTRAP_KEY "$(random_hex 32)"
  write_env BATON_IDENTITY_INVITATION_HMAC_SECRET "$(random_hex 32)"
  write_env BATON_IDENTITY_OIDC_ENABLED true
  write_env SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID baton-round-edge-tls
  write_env SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET "$(random_hex 32)"
  write_env SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_REDIRECT_URI \
    "https://baton.localhost:$TLS_PORT/api/v1/auth/oidc/callback/google"
  write_env BATON_GO_ENABLED false
  write_env BATON_ROUND_PUBLIC_BASE_URL "https://baton.localhost:$TLS_PORT"
  write_env BATON_ROUND_GRANT_ENABLED true
  write_env BATON_ROUND_GRANT_ACTIVE_KID baton-round-edge-tls
  write_env BATON_ROUND_GRANT_PRIVATE_KEY_FILE "$KEY_DIRECTORY/round-signing-key.pem"
  write_env BATON_ROUND_GRANT_JWK_SET_FILE "$KEY_DIRECTORY/round-jwks.json"
  write_env BATON_ROUND_WEB_IMAGE "$ROUND_WEB_IMAGE"
  write_env BATON_ROUND_SIGNALING_IMAGE "$ROUND_SIGNALING_IMAGE"
  write_env BATON_ROUND_TURN_URLS 'turn:127.0.0.1:9?transport=udp'
  write_env BATON_ROUND_TURN_SHARED_SECRET local-edge-test-only-shared-secret-not-for-relay
  write_env BATON_HTTP_PUBLISH "127.0.0.1:$HTTP_PORT:80"
  write_env BATON_HTTPS_TCP_PUBLISH "127.0.0.1:$TLS_PORT:443"
  write_env BATON_HTTPS_UDP_PUBLISH "127.0.0.1:$TLS_PORT:443/udp"
  write_env BATON_ROUND_EDGE_RUN_TOKEN "$RUN_TOKEN"
  write_env BATON_ROUND_EDGE_TLS_PORT "$TLS_PORT"
  write_env BATON_ROUND_EDGE_OIDC_PORT "$OIDC_PORT"
  write_env ROUND_REPOSITORY_ROOT "$ROUND_REPOSITORY_ROOT"
}

run_test() {
  log "Compose 계약을 검사합니다"
  compose config --quiet
  STACK_CREATED=true
  log "BATON production edge와 ROUND BATON-mode 이미지를 빌드합니다"
  compose build

  local compose_up_status=0
  local started=false
  for ((attempt = 1; attempt <= 3; attempt += 1)); do
    configure_runtime_ports
    compose config --quiet
    : >"$COMPOSE_UP_LOG"
    set +e
    compose up --no-build --detach >"$COMPOSE_UP_LOG" 2>&1
    compose_up_status=$?
    set -e
    if [[ "$compose_up_status" -eq 0 ]]; then
      started=true
      break
    fi
    if ! is_port_binding_conflict || [[ "$attempt" -eq 3 ]]; then
      print_log_tail_if_safe "$COMPOSE_UP_LOG" Compose
      fail "BATON production edge와 ROUND 컨테이너를 기동하지 못했습니다"
    fi
    log "로컬 publish 포트가 경합해 새 포트로 다시 시도합니다 ($attempt/3)"
    if ! require_same_docker_boundary || ! assert_project_ownership; then
      fail "포트 경합 뒤 전용 Compose 소유권을 확인하지 못했습니다"
    fi
    compose down --volumes --remove-orphans --timeout 10 >/dev/null 2>&1 \
      || fail "포트 경합 뒤 전용 Compose 리소스를 정리하지 못했습니다"
  done
  [[ "$started" == true ]] || fail "BATON production edge 기동 재시도를 소진했습니다"
  assert_project_ownership

  wait_for_service_health mysql MySQL
  wait_for_service_health mock-oidc "mock OIDC"
  wait_for_service_health app "BATON Spring"
  wait_for_service_health round-web "ROUND web"
  wait_for_service_health web "BATON Caddy"
  wait_for_service_health round-signaling "ROUND signaling"
  wait_for_https "https://baton.localhost:$TLS_PORT/actuator/health"
  wait_for_https "https://baton.localhost:$TLS_PORT/.well-known/jwks.json"

  local auth_mode
  auth_mode="$(compose exec -T round-web cat /srv/.round-auth-mode)"
  [[ "$auth_mode" == baton ]] || fail "ROUND web image가 BATON 모드가 아닙니다"

  log "로컬 HTTPS OIDC → grant → TURN credential → WSS 흐름을 검증합니다"
  (
    cd "$FRONTEND_DIRECTORY"
    npm run typecheck:round-tls
  )
  set +e
  (
    cd "$FRONTEND_DIRECTORY"
    BATON_ROUND_EDGE_BASE_URL="https://baton.localhost:$TLS_PORT" \
      BATON_ROUND_EDGE_OUTPUT_DIRECTORY="$RUN_DIRECTORY/playwright-results" \
      npm run e2e:round-tls:test
  ) >"$PLAYWRIGHT_LOG" 2>&1
  local playwright_status=$?
  set -e
  if [[ "$playwright_status" -ne 0 ]]; then
    print_safe_playwright_diagnostic
    print_log_tail_if_safe "$PLAYWRIGHT_LOG" Playwright
    fail "브라우저 검증이 실패했습니다. 인증정보 보호를 위해 원문 출력을 표시하지 않습니다"
  fi
  log "브라우저 검증을 통과했습니다"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

prepare
run_test
