#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
FRONTEND_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd -P)"
REPOSITORY_ROOT="$(CDPATH= cd -- "$FRONTEND_DIR/.." && pwd -P)"
COMPOSE_FILE="$REPOSITORY_ROOT/compose.round-fullstack-e2e.yml"
OPENSSL_EXTENSIONS="$REPOSITORY_ROOT/ops/tests/round-fullstack/openssl-extensions.cnf"
ROUND_ROOT="${ROUND_REPOSITORY_ROOT:-}"
EDGE_PORT="${BATON_ROUND_EDGE_PORT:-}"
CREATION_KEY="round-fullstack-creation-key-000000000001"
RECOVERY_KEY="round-fullstack-recovery-key-000000000002"
PUBLIC_HOST="baton.fullstack.test"
PUBLIC_ORIGIN=""
ACCOUNT_EMAIL="round.fullstack@example.test"
ACCOUNT_PASSWORD="Round-Fullstack-Password-2026!"
ROUND_KID="baton-round-edge-fullstack-e2e"
TEMP_BASE="${TMPDIR:-/tmp}"
TEMP_BASE="${TEMP_BASE%/}"
RUN_DIR="$(mktemp -d "$TEMP_BASE/baton-round-edge-e2e.XXXXXX")"
PORT_LOCK_DIR=""
COMPOSE_PROJECT="baton-round-edge-e2e-$$-$RANDOM"
COMPOSE=(docker compose --project-name "$COMPOSE_PROJECT" --file "$COMPOSE_FILE")

log() {
  printf '[round-edge-e2e] %s\n' "$*"
}

fail() {
  log "$*" >&2
  exit 1
}

preserve_failure_logs() {
  local artifact_dir="$FRONTEND_DIR/test-results/round-edge-runtime"
  if ! mkdir -p "$artifact_dir"; then
    log "실패 로그 디렉터리를 만들지 못했습니다: $artifact_dir" >&2
    return
  fi
  if "${COMPOSE[@]}" logs --no-color >"$artifact_dir/compose.log" 2>&1; then
    log "실패 로그를 $artifact_dir 에 보존했습니다."
  else
    log "Compose 실패 로그를 보존하지 못했습니다: $artifact_dir" >&2
  fi
}

cleanup() {
  local exit_status=$?
  local teardown_status=0
  trap - EXIT INT TERM
  set +e

  if [[ "$exit_status" -ne 0 ]]; then
    preserve_failure_logs
  fi
  if ! "${COMPOSE[@]}" down --volumes --remove-orphans --rmi local --timeout 10 >/dev/null 2>&1; then
    teardown_status=1
    log "Compose 환경을 정리하지 못했습니다: $COMPOSE_PROJECT" >&2
  fi

  if [[ -n "$PORT_LOCK_DIR" ]]; then
    case "$PORT_LOCK_DIR" in
      "$TEMP_BASE"/baton-round-edge-e2e-port-*.lock)
        if ! rmdir -- "$PORT_LOCK_DIR"; then
          teardown_status=1
          log "공개 TLS 포트 lock을 정리하지 못했습니다: $PORT_LOCK_DIR" >&2
        fi
        ;;
    esac
  fi

  if [[ "$teardown_status" -eq 0 ]]; then
    case "$RUN_DIR" in
      "$TEMP_BASE"/baton-round-edge-e2e.*) rm -rf -- "$RUN_DIR" ;;
    esac
  else
    log "수동 정리를 위해 임시 key 디렉터리를 보존했습니다: $RUN_DIR" >&2
  fi
  if [[ "$exit_status" -eq 0 && "$teardown_status" -ne 0 ]]; then
    exit_status="$teardown_status"
  fi
  exit "$exit_status"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

require_available_port() {
  local port="$1"

  if [[ ! "$port" =~ ^[0-9]+$ ]] || ((port < 1 || port > 65535)); then
    fail "공개 TLS 포트가 올바르지 않습니다: $port"
  fi
  if (echo >/dev/tcp/127.0.0.1/"$port") 2>/dev/null; then
    fail "공개 TLS 포트를 이미 다른 프로세스가 사용 중입니다: $port"
  fi
}

try_acquire_port_lock() {
  local port="$1"
  local lock_dir="$TEMP_BASE/baton-round-edge-e2e-port-$port.lock"

  if ! mkdir "$lock_dir" 2>/dev/null; then
    return 1
  fi
  chmod 0700 "$lock_dir"
  PORT_LOCK_DIR="$lock_dir"
}

select_edge_port() {
  local candidate

  if [[ -n "$EDGE_PORT" ]]; then
    require_available_port "$EDGE_PORT"
    try_acquire_port_lock "$EDGE_PORT" \
      || fail "공개 TLS 포트를 다른 ROUND edge E2E가 예약했습니다: $EDGE_PORT"
    return
  fi

  for ((attempt = 1; attempt <= 32; attempt += 1)); do
    candidate=$((20000 + RANDOM % 20000))
    if ! (echo >/dev/tcp/127.0.0.1/"$candidate") 2>/dev/null \
        && try_acquire_port_lock "$candidate"; then
      EDGE_PORT="$candidate"
      return
    fi
  done

  fail '사용 가능한 loopback TLS 포트를 예약하지 못했습니다'
}

[[ -n "$ROUND_ROOT" ]] || fail 'ROUND_REPOSITORY_ROOT 절대 경로가 필요합니다'
[[ "$ROUND_ROOT" == /* ]] || fail 'ROUND_REPOSITORY_ROOT는 절대 경로여야 합니다'
[[ "$ROUND_ROOT" != *$'\n'* && "$ROUND_ROOT" != *$'\r'* ]] \
  || fail 'ROUND_REPOSITORY_ROOT에 줄바꿈을 사용할 수 없습니다'
[[ -d "$ROUND_ROOT" ]] || fail "ROUND 저장소를 찾지 못했습니다: $ROUND_ROOT"
ROUND_ROOT="$(CDPATH= cd -- "$ROUND_ROOT" && pwd -P)"
[[ -f "$ROUND_ROOT/Dockerfile" && -f "$ROUND_ROOT/apps/signaling/build.gradle" ]] \
  || fail "ROUND runtime build context를 찾지 못했습니다: $ROUND_ROOT"

command -v curl >/dev/null
command -v docker >/dev/null
command -v git >/dev/null
command -v keytool >/dev/null
command -v openssl >/dev/null
docker info >/dev/null
test -x "$FRONTEND_DIR/node_modules/.bin/playwright"
test -f "$COMPOSE_FILE"
test -f "$OPENSSL_EXTENSIONS"
select_edge_port
PUBLIC_ORIGIN="https://$PUBLIC_HOST:$EDGE_PORT"
log "공개 TLS loopback 포트: $EDGE_PORT"

ROUND_REVISION="$(git -C "$ROUND_ROOT" rev-parse --verify HEAD)"
[[ "$ROUND_REVISION" =~ ^[0-9a-f]{40}$ ]] || fail 'ROUND Git revision을 확인하지 못했습니다'
if [[ -n "$(git -C "$ROUND_ROOT" status --porcelain)" ]]; then
  ROUND_REVISION="$ROUND_REVISION+dirty"
fi
log "검증할 ROUND revision: $ROUND_REVISION"

log '폐기 가능한 참여권 RSA key와 로컬 TLS chain을 만듭니다.'
openssl genpkey \
  -algorithm RSA \
  -pkeyopt rsa_keygen_bits:2048 \
  -out "$RUN_DIR/round-private.pem" >/dev/null 2>&1
openssl pkey \
  -in "$RUN_DIR/round-private.pem" \
  -pubout \
  -out "$RUN_DIR/round-public.pem" >/dev/null 2>&1
openssl genpkey \
  -algorithm RSA \
  -pkeyopt rsa_keygen_bits:2048 \
  -out "$RUN_DIR/ca.key" >/dev/null 2>&1
openssl req \
  -x509 \
  -new \
  -sha256 \
  -days 2 \
  -key "$RUN_DIR/ca.key" \
  -out "$RUN_DIR/ca.crt" \
  -subj '/CN=BATON ROUND fullstack test CA' \
  -config "$OPENSSL_EXTENSIONS" \
  -extensions ca_cert >/dev/null 2>&1
openssl genpkey \
  -algorithm RSA \
  -pkeyopt rsa_keygen_bits:2048 \
  -out "$RUN_DIR/server.key" >/dev/null 2>&1
openssl req \
  -new \
  -sha256 \
  -key "$RUN_DIR/server.key" \
  -out "$RUN_DIR/server.csr" \
  -subj "/CN=$PUBLIC_HOST" >/dev/null 2>&1
openssl x509 \
  -req \
  -sha256 \
  -days 2 \
  -in "$RUN_DIR/server.csr" \
  -CA "$RUN_DIR/ca.crt" \
  -CAkey "$RUN_DIR/ca.key" \
  -CAcreateserial \
  -out "$RUN_DIR/server.crt" \
  -extfile "$OPENSSL_EXTENSIONS" \
  -extensions server_cert >/dev/null 2>&1
openssl verify -CAfile "$RUN_DIR/ca.crt" "$RUN_DIR/server.crt" >/dev/null
keytool -importcert \
  -noprompt \
  -alias baton-round-fullstack \
  -file "$RUN_DIR/ca.crt" \
  -keystore "$RUN_DIR/truststore.p12" \
  -storetype PKCS12 \
  -storepass round-fullstack-trust >/dev/null 2>&1
chmod 0444 \
  "$RUN_DIR/round-private.pem" \
  "$RUN_DIR/round-public.pem" \
  "$RUN_DIR/server.crt" \
  "$RUN_DIR/server.key" \
  "$RUN_DIR/truststore.p12"

log 'BATON 정적 bundle을 빌드합니다.'
(
  cd "$FRONTEND_DIR"
  npm run build
)

export BATON_ROUND_EDGE_CREATION_KEY="$CREATION_KEY"
export BATON_ROUND_EDGE_PORT="$EDGE_PORT"
export BATON_ROUND_EDGE_PUBLIC_ORIGIN="$PUBLIC_ORIGIN"
export BATON_ROUND_EDGE_RECOVERY_KEY="$RECOVERY_KEY"
export BATON_ROUND_EDGE_RUN_DIR="$RUN_DIR"
export ROUND_REPOSITORY_ROOT="$ROUND_ROOT"

"${COMPOSE[@]}" config --quiet

log 'BATON·ROUND·MySQL과 외부 TLS edge를 빌드하고 시작합니다.'
"${COMPOSE[@]}" up --build --detach

for ((attempt = 1; attempt <= 120; attempt += 1)); do
  if curl \
      --cacert "$RUN_DIR/ca.crt" \
      --fail \
      --noproxy '*' \
      --resolve "$PUBLIC_HOST:$EDGE_PORT:127.0.0.1" \
      --silent \
      --show-error \
      "$PUBLIC_ORIGIN/actuator/health" >/dev/null 2>&1; then
    break
  fi
  if ((attempt == 120)); then
    fail '공개 TLS edge가 준비 시간 안에 정상화되지 않았습니다'
  fi
  sleep 1
done

log '실제 HTTPS browser session → BATON refresh → ROUND TURN credential·WSS 입장을 검증합니다.'
(
  cd "$FRONTEND_DIR"
  BATON_FULLSTACK_ACCOUNT_EMAIL="$ACCOUNT_EMAIL" \
  BATON_FULLSTACK_ACCOUNT_PASSWORD="$ACCOUNT_PASSWORD" \
  BATON_FULLSTACK_BASE_URL="$PUBLIC_ORIGIN" \
  BATON_FULLSTACK_CREATION_KEY="$CREATION_KEY" \
  BATON_FULLSTACK_ROUND_EDGE=true \
  BATON_FULLSTACK_ROUND_ISSUER="$PUBLIC_ORIGIN" \
  BATON_FULLSTACK_ROUND_KID="$ROUND_KID" \
  NO_PROXY="$PUBLIC_HOST,127.0.0.1,localhost" \
  no_proxy="$PUBLIC_HOST,127.0.0.1,localhost" \
  npm run e2e:fullstack:test
)

log 'BATON → TLS edge → ROUND TURN credential 발급·WSS 입장 계약이 통과했습니다.'
