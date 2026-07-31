#!/usr/bin/env bash

set -Eeuo pipefail
export LC_ALL=C
umask 077

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(dirname -- "$(dirname -- "$script_dir")")"
test_base="${TMPDIR:-/tmp}"
test_prefix="${test_base%/}/baton-round-live-readiness-test"
test_root="$(mktemp -d "$test_prefix.XXXXXX")"

cleanup() {
  case "$test_root" in
    "$test_prefix".*) rm -rf -- "$test_root" ;;
  esac
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

assert_contains() {
  local expected="$1"
  local actual="$2"
  local label="$3"

  [[ "$actual" == *"$expected"* ]] || fail "$label"
}

assert_not_contains() {
  local unexpected="$1"
  local actual="$2"
  local label="$3"

  [[ "$actual" != *"$unexpected"* ]] || fail "$label"
}

fake_bin="$test_root/fakebin"
mkdir -p -- "$fake_bin"
real_openssl="$(command -v openssl)" || fail 'OpenSSL is required for RSA fixtures'

cat > "$fake_bin/curl" <<'SCRIPT'
#!/usr/bin/env bash
set -Eeuo pipefail

headers_file=""
body_file=""
url=""
method=GET

while [[ $# -gt 0 ]]; do
  case "$1" in
    --disable|--silent|--tlsv1.2)
      shift
      ;;
    --proto|--connect-timeout|--max-time|--max-filesize|--dump-header|--output|--write-out|--request)
      option="$1"
      value="$2"
      case "$option" in
        --dump-header) headers_file="$value" ;;
        --output) body_file="$value" ;;
        --request) method="$value" ;;
      esac
      shift 2
      ;;
    http://*|https://*)
      url="$1"
      shift
      ;;
    *)
      exit 80
      ;;
  esac
done

[[ -n "$headers_file" && -n "$body_file" && -n "$url" ]] || exit 81
status=""
: > "$headers_file"
: > "$body_file"

case "$url" in
  https://baton.example.com/actuator/health)
    status=200
    printf 'HTTP/2 200\ncontent-type: application/json\n\n' > "$headers_file"
    printf '{"status":"UP"}\n' > "$body_file"
    ;;
  http://baton.example.com/)
    status=308
    printf 'HTTP/1.1 308 Permanent Redirect\nlocation: https://baton.example.com/\n\n' \
      > "$headers_file"
    ;;
  https://baton.example.com/api/v1/auth/oidc/authorization/google)
    status=302
    callback='https%3A%2F%2Fbaton.example.com%2Fapi%2Fv1%2Fauth%2Foidc%2Fcallback%2Fgoogle'
    if [[ "${FAKE_CURL_MODE:-healthy}" == oidc-mismatch ]]; then
      callback='https%3A%2F%2Fother.example.com%2Fapi%2Fv1%2Fauth%2Foidc%2Fcallback%2Fgoogle'
    fi
    oidc_state="$LIVE_TEST_SENTINEL"
    oidc_client_id=test-client
    oidc_cookie='Path=/; Secure; HttpOnly; SameSite=Lax'
    if [[ "${FAKE_CURL_MODE:-healthy}" == oidc-empty-state ]]; then
      oidc_state=''
    fi
    if [[ "${FAKE_CURL_MODE:-healthy}" == oidc-empty-client ]]; then
      oidc_client_id=''
    fi
    if [[ "${FAKE_CURL_MODE:-healthy}" == oidc-domain-cookie ]]; then
      oidc_cookie='Path=/; Domain=baton.example.com; Secure; HttpOnly; SameSite=Lax'
    fi
    printf 'HTTP/2 302\nlocation: https://accounts.google.com/o/oauth2/v2/auth?response_type=code&client_id=%s&redirect_uri=%s&state=%s&code_challenge=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa&code_challenge_method=S256\nset-cookie: __Host-baton_session=%s; %s\n\n' \
      "$oidc_client_id" "$callback" "$oidc_state" "$LIVE_TEST_SENTINEL" "$oidc_cookie" \
      > "$headers_file"
    ;;
  https://baton.example.com/.well-known/jwks.json)
    status=200
    printf 'HTTP/2 200\ncontent-type: application/json;charset=UTF-8\ncache-control: public, max-age=60, must-revalidate\n\n' \
      > "$headers_file"
    remote_active_modulus="$LIVE_TEST_ACTIVE_MODULUS"
    remote_active_exponent=AQAB
    if [[ "${FAKE_CURL_MODE:-healthy}" == remote-modulus-mismatch ]]; then
      remote_active_modulus="$LIVE_TEST_PREVIOUS_MODULUS"
    fi
    if [[ "${FAKE_CURL_MODE:-healthy}" == remote-exponent-mismatch ]]; then
      remote_active_exponent=Aw
    fi
    case "${FAKE_CURL_MODE:-healthy}" in
      single-jwk)
        printf '{"keys":[{"kty":"RSA","kid":"round-active","use":"sig","alg":"RS256","n":"%s","e":"%s"}]}\n' \
          "$remote_active_modulus" "$remote_active_exponent" > "$body_file"
        ;;
      private-jwk)
        printf '{"keys":[{"kty":"RSA","kid":"round-active","use":"sig","alg":"RS256","n":"%s","e":"%s","d":"%s"},{"kty":"RSA","kid":"round-previous","use":"sig","alg":"RS256","n":"%s","e":"AQAB"}]}\n' \
          "$remote_active_modulus" "$remote_active_exponent" "$LIVE_TEST_SENTINEL" \
          "$LIVE_TEST_PREVIOUS_MODULUS" > "$body_file"
        ;;
      *)
        printf '{"keys":[{"kty":"RSA","kid":"round-active","use":"sig","alg":"RS256","n":"%s","e":"%s"},{"kty":"RSA","kid":"round-previous","use":"sig","alg":"RS256","n":"%s","e":"AQAB"}]}\n' \
          "$remote_active_modulus" "$remote_active_exponent" \
          "$LIVE_TEST_PREVIOUS_MODULUS" > "$body_file"
        ;;
    esac
    ;;
  https://baton.example.com/room/abcd-efgh-jkmn)
    status=200
    printf "HTTP/2 200\ncontent-security-policy: default-src 'self'; frame-ancestors 'none'; connect-src 'self' wss://baton.example.com\npermissions-policy: camera=(self), geolocation=(), microphone=(self)\nreferrer-policy: no-referrer\nx-frame-options: DENY\nstrict-transport-security: max-age=31536000; includeSubDomains\n\n" \
      > "$headers_file"
    printf '<!doctype html>\n' > "$body_file"
    ;;
  https://baton.example.com/round/rooms/abcd-efgh-jkmn/turn-credentials)
    [[ "$method" == POST ]] || exit 82
    status=404
    printf 'HTTP/2 404\ncache-control: no-store\n\n' > "$headers_file"
    ;;
  https://go.example.com/)
    if [[ "${FAKE_CURL_MODE:-healthy}" == go-5xx ]]; then
      status=503
      printf 'HTTP/2 503\ncontent-type: application/json\n\n' > "$headers_file"
    else
      status=404
      printf 'HTTP/2 404\ncontent-type: application/json\n\n' > "$headers_file"
    fi
    ;;
  *)
    exit 83
    ;;
esac

printf '%s' "$status"
SCRIPT

cat > "$fake_bin/openssl" <<'SCRIPT'
#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "${1:-}" != s_client ]]; then
  [[ -n "${REAL_OPENSSL:-}" ]] || exit 90
  exec "$REAL_OPENSSL" "$@"
fi
[[ " $* " == *' -verify_hostname turn.example.com '* ]] || exit 91
[[ " $* " == *' -servername turn.example.com '* ]] || exit 92
[[ " $* " == *' -verify_return_error '* ]] || exit 93
[[ "${FAKE_OPENSSL_MODE:-healthy}" != failure ]]
SCRIPT

chmod +x "$fake_bin/curl" "$fake_bin/openssl"

live_test_sentinel='live-secret-sentinel-never-print'
db_password='1111111111111111111111111111111111111111111111111111111111111111'
root_password='2222222222222222222222222222222222222222222222222222222222222222'
creation_key='3333333333333333333333333333333333333333333333333333333333333333'
recovery_key='4444444444444444444444444444444444444444444444444444444444444444'
identity_bootstrap_key='5555555555555555555555555555555555555555555555555555555555555555'
identity_invitation_secret='6666666666666666666666666666666666666666666666666666666666666666'
google_client_secret='7777777777777777777777777777777777777777777777777777777777777777'
go_management_token='8888888888888888888888888888888888888888888888888888888888888888'
turn_shared_secret='9999999999999999999999999999999999999999999999999999999999999999'

generate_rsa_key() {
  local target="$1"
  local bits="$2"
  local artifact="$3"

  if ! "$real_openssl" genrsa -out "$target" "$bits" \
    > "$test_root/$artifact-generate.output" \
    2> "$test_root/$artifact-generate.error"; then
    fail 'RSA fixture generation failed'
  fi
  chmod 600 "$target"
}

hex_to_binary() {
  local hex_value="$1"
  local offset=0
  local byte

  [[ "$hex_value" =~ ^[0-9A-Fa-f]+$ ]] || return 1
  (( ${#hex_value} % 2 == 0 )) || return 1
  while (( offset < ${#hex_value} )); do
    byte="${hex_value:offset:2}"
    printf '%b' "\\x$byte"
    offset=$((offset + 2))
  done
}

derive_modulus_base64url() {
  local private_key="$1"
  local target="$2"
  local artifact="$3"
  local modulus_line
  local modulus_hex

  if ! "$real_openssl" rsa \
    -in "$private_key" \
    -passin pass: \
    -modulus \
    -noout \
    > "$test_root/$artifact-modulus.output" \
    2> "$test_root/$artifact-modulus.error"; then
    fail 'RSA modulus fixture extraction failed'
  fi
  modulus_line="$(< "$test_root/$artifact-modulus.output")"
  modulus_hex="${modulus_line#Modulus=}"
  [[ "$modulus_line" == Modulus=* && "$modulus_hex" =~ ^[0-9A-Fa-f]+$ ]] \
    || fail 'RSA modulus fixture format was unexpected'
  if ! hex_to_binary "$modulus_hex" > "$test_root/$artifact-modulus.bin"; then
    fail 'RSA modulus fixture conversion failed'
  fi
  if ! "$real_openssl" base64 \
    -A \
    -in "$test_root/$artifact-modulus.bin" \
    > "$test_root/$artifact-modulus.base64" \
    2> "$test_root/$artifact-base64.error"; then
    fail 'RSA modulus fixture encoding failed'
  fi
  tr '+/' '-_' < "$test_root/$artifact-modulus.base64" \
    | tr -d '=\r\n' > "$target"
  [[ -s "$target" ]] || fail 'RSA modulus fixture was empty'
}

active_private_key_file="$test_root/round-active-private.pem"
previous_private_key_file="$test_root/round-previous-private.pem"
mismatched_private_key_file="$test_root/round-mismatched-private.pem"
short_private_key_file="$test_root/round-short-private.pem"
generate_rsa_key "$active_private_key_file" 2048 active
generate_rsa_key "$previous_private_key_file" 2048 previous
generate_rsa_key "$mismatched_private_key_file" 2048 mismatched
generate_rsa_key "$short_private_key_file" 1024 short

active_modulus_file="$test_root/round-active-n.txt"
previous_modulus_file="$test_root/round-previous-n.txt"
short_modulus_file="$test_root/round-short-n.txt"
derive_modulus_base64url "$active_private_key_file" "$active_modulus_file" active
derive_modulus_base64url "$previous_private_key_file" "$previous_modulus_file" previous
derive_modulus_base64url "$short_private_key_file" "$short_modulus_file" short
active_modulus="$(< "$active_modulus_file")"
previous_modulus="$(< "$previous_modulus_file")"
short_modulus="$(< "$short_modulus_file")"
active_modulus_hex="$(< "$test_root/active-modulus.output")"
active_modulus_hex="${active_modulus_hex#Modulus=}"

single_local_jwk_set_file="$test_root/round-single-jwks.json"
overlap_local_jwk_set_file="$test_root/round-overlap-jwks.json"
short_local_jwk_set_file="$test_root/round-short-jwks.json"
private_local_jwk_set_file="$test_root/round-private-jwks.json"
malformed_local_jwk_set_file="$test_root/round-malformed-jwks.json"
wrong_exponent_jwk_set_file="$test_root/round-wrong-exponent-jwks.json"
printf '{"keys":[{"kty":"RSA","kid":"round-active","use":"sig","alg":"RS256","n":"%s","e":"AQAB"}]}\n' \
  "$active_modulus" > "$single_local_jwk_set_file"
printf '{"keys":[{"kty":"RSA","kid":"round-active","use":"sig","alg":"RS256","n":"%s","e":"AQAB"},{"kty":"RSA","kid":"round-previous","use":"sig","alg":"RS256","n":"%s","e":"AQAB"}]}\n' \
  "$active_modulus" "$previous_modulus" > "$overlap_local_jwk_set_file"
printf '{"keys":[{"kty":"RSA","kid":"round-active","use":"sig","alg":"RS256","n":"%s","e":"AQAB"}]}\n' \
  "$short_modulus" > "$short_local_jwk_set_file"
printf '{"keys":[{"kty":"RSA","kid":"round-active","use":"sig","alg":"RS256","n":"%s","e":"AQAB","d":"%s"}]}\n' \
  "$active_modulus" "$live_test_sentinel" > "$private_local_jwk_set_file"
printf '{"keys":[' > "$malformed_local_jwk_set_file"
printf '{"keys":[{"kty":"RSA","kid":"round-active","use":"sig","alg":"RS256","n":"%s","e":"Aw"}]}\n' \
  "$active_modulus" > "$wrong_exponent_jwk_set_file"
chmod 600 \
  "$single_local_jwk_set_file" \
  "$overlap_local_jwk_set_file" \
  "$short_local_jwk_set_file" \
  "$private_local_jwk_set_file" \
  "$malformed_local_jwk_set_file" \
  "$wrong_exponent_jwk_set_file"

write_valid_env() {
  local target="$1"
  local private_key_file="${2:-$active_private_key_file}"
  local jwk_set_file="${3:-$overlap_local_jwk_set_file}"

  printf '%s\n' \
    'BATON_HOST=baton.example.com' \
    'BATON_DB_NAME=baton' \
    'BATON_DB_USERNAME=baton' \
    "BATON_DB_PASSWORD=$db_password" \
    "BATON_DB_ROOT_PASSWORD=$root_password" \
    "BATON_WORKSPACE_CREATION_KEY=$creation_key" \
    "BATON_WORKSPACE_RECOVERY_KEY=$recovery_key" \
    "BATON_IDENTITY_BOOTSTRAP_KEY=$identity_bootstrap_key" \
    "BATON_IDENTITY_INVITATION_HMAC_SECRET=$identity_invitation_secret" \
    'BATON_IDENTITY_BOOTSTRAP_INVITATION_TTL=PT1H' \
    'BATON_IDENTITY_MEMBER_INVITATION_TTL=PT24H' \
    'BATON_IDENTITY_OIDC_ENABLED=true' \
    'SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID=google-client.apps.googleusercontent.com' \
    "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET=$google_client_secret" \
    'SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_REDIRECT_URI={baseUrl}/api/v1/auth/oidc/callback/{registrationId}' \
    'BATON_GO_ENABLED=true' \
    'BATON_GO_BASE_URL=https://go.example.com' \
    'BATON_GO_PUBLIC_BASE_URL=https://go.example.com' \
    "BATON_GO_MANAGEMENT_TOKEN=$go_management_token" \
    'BATON_ROUND_PUBLIC_BASE_URL=https://baton.example.com' \
    'BATON_ROUND_GRANT_ENABLED=true' \
    'BATON_ROUND_GRANT_ACTIVE_KID=round-active' \
    "BATON_ROUND_GRANT_PRIVATE_KEY_FILE=$private_key_file" \
    "BATON_ROUND_GRANT_JWK_SET_FILE=$jwk_set_file" \
    'BATON_ROUND_WEB_IMAGE=ghcr.io/example/round-web@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
    'BATON_ROUND_SIGNALING_IMAGE=ghcr.io/example/round-signaling@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' \
    'BATON_ROUND_TURN_URLS=turns:turn.example.com:5349?transport=tcp' \
    "BATON_ROUND_TURN_SHARED_SECRET=$turn_shared_secret" \
    > "$target"
  chmod 600 "$target"
}

run_readiness() {
  local env_file="$1"
  local curl_mode="${2:-healthy}"
  local require_overlap="${3:-false}"
  local -a readiness_command

  readiness_command=("$repo_root/ops/verify-round-live-readiness.sh")
  if [[ "$require_overlap" == true ]]; then
    readiness_command+=(--require-key-overlap)
  fi
  readiness_command+=("$env_file")

  PATH="$fake_bin:$PATH" \
  REAL_OPENSSL="$real_openssl" \
  LIVE_TEST_SENTINEL="$live_test_sentinel" \
  LIVE_TEST_ACTIVE_MODULUS="$active_modulus" \
  LIVE_TEST_PREVIOUS_MODULUS="$previous_modulus" \
  FAKE_CURL_MODE="$curl_mode" \
  FAKE_OPENSSL_MODE="${FAKE_OPENSSL_MODE:-healthy}" \
  "${readiness_command[@]}" 2>&1
}

assert_redacted() {
  local output="$1"
  local label="$2"

  assert_not_contains "$live_test_sentinel" "$output" "$label sentinel exposure"
  assert_not_contains "$google_client_secret" "$output" "$label OIDC secret exposure"
  assert_not_contains "$turn_shared_secret" "$output" "$label TURN secret exposure"
  assert_not_contains "$test_root" "$output" "$label key path exposure"
  assert_not_contains "$active_modulus" "$output" "$label RSA modulus exposure"
  assert_not_contains "$previous_modulus" "$output" "$label previous RSA modulus exposure"
  assert_not_contains "$active_modulus_hex" "$output" "$label RSA modulus hex exposure"
  assert_not_contains 'BEGIN PRIVATE KEY' "$output" "$label private key exposure"
  assert_not_contains 'BEGIN RSA PRIVATE KEY' "$output" "$label RSA private key exposure"
  assert_not_contains 'https://' "$output" "$label URL exposure"
  assert_not_contains 'Set-Cookie' "$output" "$label header exposure"
}

expect_failure() {
  local label="$1"
  local env_file="$2"
  local curl_mode="${3:-healthy}"
  local require_overlap="${4:-false}"
  local output

  if output="$(run_readiness "$env_file" "$curl_mode" "$require_overlap")"; then
    fail "$label unexpectedly passed"
  fi
  assert_redacted "$output" "$label"
}

valid_env="$test_root/valid.env"
write_valid_env "$valid_env"
if ! success_output="$(run_readiness "$valid_env")"; then
  printf '%s\n' "$success_output" | grep '^\[round-live-readiness\]' >&2 || true
  fail 'valid readiness fixture failed'
fi
assert_contains '[round-live-readiness] 전체 사전점검 통과' \
  "$success_output" 'success output'
assert_redacted "$success_output" 'success output'

oidc_off_env="$test_root/oidc-off.env"
cp "$valid_env" "$oidc_off_env"
sed 's/^BATON_IDENTITY_OIDC_ENABLED=true$/BATON_IDENTITY_OIDC_ENABLED=false/' \
  "$oidc_off_env" > "$test_root/oidc-off.tmp"
mv "$test_root/oidc-off.tmp" "$oidc_off_env"
chmod 600 "$oidc_off_env"
expect_failure 'disabled OIDC' "$oidc_off_env"

single_local_env="$test_root/single-local.env"
write_valid_env \
  "$single_local_env" \
  "$active_private_key_file" \
  "$single_local_jwk_set_file"
single_jwk_output="$(run_readiness "$single_local_env" single-jwk)" \
  || fail 'single active JWK after retirement was rejected'
assert_contains '[round-live-readiness] 전체 사전점검 통과' \
  "$single_jwk_output" 'single active JWK output'
assert_redacted "$single_jwk_output" 'single active JWK output'
expect_failure 'missing local JWK rotation overlap' "$single_local_env" healthy true
expect_failure 'missing remote JWK rotation overlap' "$valid_env" single-jwk true
overlap_output="$(run_readiness "$valid_env" healthy true)" \
  || fail 'valid JWK rotation overlap was rejected'
assert_contains '[round-live-readiness] 전체 사전점검 통과' \
  "$overlap_output" 'JWK overlap output'
assert_redacted "$overlap_output" 'JWK overlap output'
expect_failure 'private JWK material' "$valid_env" private-jwk
expect_failure 'remote active RSA modulus mismatch' "$valid_env" remote-modulus-mismatch
expect_failure 'remote active RSA exponent mismatch' "$valid_env" remote-exponent-mismatch

mismatched_key_env="$test_root/mismatched-key.env"
write_valid_env \
  "$mismatched_key_env" \
  "$mismatched_private_key_file" \
  "$overlap_local_jwk_set_file"
expect_failure 'mismatched local RSA key pair' "$mismatched_key_env"

short_key_env="$test_root/short-key.env"
write_valid_env \
  "$short_key_env" \
  "$short_private_key_file" \
  "$short_local_jwk_set_file"
expect_failure 'RSA private key below 2048 bits' "$short_key_env"

malformed_local_jwk_env="$test_root/malformed-local-jwk.env"
write_valid_env \
  "$malformed_local_jwk_env" \
  "$active_private_key_file" \
  "$malformed_local_jwk_set_file"
expect_failure 'malformed local JWK Set' "$malformed_local_jwk_env"

private_local_jwk_env="$test_root/private-local-jwk.env"
write_valid_env \
  "$private_local_jwk_env" \
  "$active_private_key_file" \
  "$private_local_jwk_set_file"
expect_failure 'private local JWK material' "$private_local_jwk_env"

wrong_exponent_env="$test_root/wrong-exponent.env"
write_valid_env \
  "$wrong_exponent_env" \
  "$active_private_key_file" \
  "$wrong_exponent_jwk_set_file"
expect_failure 'mismatched local RSA exponent' "$wrong_exponent_env"

missing_turns_env="$test_root/missing-turns.env"
cp "$valid_env" "$missing_turns_env"
sed 's#^BATON_ROUND_TURN_URLS=.*$#BATON_ROUND_TURN_URLS=turn:turn.example.com:3478?transport=udp#' \
  "$missing_turns_env" > "$test_root/missing-turns.tmp"
mv "$test_root/missing-turns.tmp" "$missing_turns_env"
chmod 600 "$missing_turns_env"
expect_failure 'missing TURN TLS URI' "$missing_turns_env"

expect_failure 'OIDC callback mismatch' "$valid_env" oidc-mismatch
expect_failure 'empty OIDC state' "$valid_env" oidc-empty-state
expect_failure 'empty OIDC client id' "$valid_env" oidc-empty-client
expect_failure 'OIDC cookie Domain attribute' "$valid_env" oidc-domain-cookie
expect_failure 'GO server error' "$valid_env" go-5xx

if FAKE_OPENSSL_MODE=failure run_readiness "$valid_env" >/dev/null; then
  fail 'TURN TLS handshake failure unexpectedly passed'
fi

printf 'ROUND live readiness fail-closed and redaction checks passed.\n'
