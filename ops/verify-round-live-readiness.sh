#!/usr/bin/env bash

set -Eeuo pipefail
export LC_ALL=C
umask 077

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
temporary_dir=""
temp_base="${TMPDIR:-/tmp}"
temp_prefix="${temp_base%/}/baton-round-live-readiness"
canonical_env_file=""
require_key_overlap=false

log() {
  printf '[round-live-readiness] %s\n' "$1"
}

fail() {
  printf '[round-live-readiness] 실패: %s\n' "$1" >&2
  exit 1
}

cleanup() {
  if [[ -n "$temporary_dir" ]]; then
    case "$temporary_dir" in
      "$temp_prefix".*) rm -rf -- "$temporary_dir" ;;
    esac
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [[ $# -eq 2 && "$1" == --require-key-overlap ]]; then
  require_key_overlap=true
  shift
fi
if [[ $# -ne 1 ]]; then
  printf 'Usage: %s [--require-key-overlap] /absolute/path/to/.env.production\n' "$0" >&2
  exit 1
fi

temporary_dir="$(mktemp -d "$temp_prefix.XXXXXX")"
chmod 700 "$temporary_dir"

log '운영 환경 구성'
if ! "$script_dir/validate-production-env.sh" "$1" \
  > "$temporary_dir/validated-env" \
  2> "$temporary_dir/validation-error"; then
  fail '운영 환경 구성'
fi
if [[ "$(wc -l < "$temporary_dir/validated-env")" -ne 1 ]]; then
  fail '운영 환경 구성'
fi
canonical_env_file="$(< "$temporary_dir/validated-env")"

log '필수 도구'
for required_command in curl openssl jq cmp od; do
  command -v "$required_command" >/dev/null 2>&1 || fail '필수 도구'
done

read_setting() {
  local name="$1"
  local line

  while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ "$line" == "$name="* ]]; then
      printf '%s' "${line#*=}"
      return 0
    fi
  done < "$canonical_env_file"
  return 1
}

baton_host="$(read_setting BATON_HOST)" || fail '운영 환경 구성'
oidc_enabled="$(read_setting BATON_IDENTITY_OIDC_ENABLED)" \
  || fail 'OIDC 활성 상태'
go_enabled="$(read_setting BATON_GO_ENABLED)" || fail 'GO 활성 상태'
round_enabled="$(read_setting BATON_ROUND_GRANT_ENABLED)" \
  || fail 'ROUND 활성 상태'
go_public_base_url="$(read_setting BATON_GO_PUBLIC_BASE_URL)" \
  || fail 'GO 활성 상태'
round_active_kid="$(read_setting BATON_ROUND_GRANT_ACTIVE_KID)" \
  || fail 'ROUND 활성 상태'
round_private_key_file="$(read_setting BATON_ROUND_GRANT_PRIVATE_KEY_FILE)" \
  || fail '로컬 ROUND 서명 키'
round_local_jwk_set_file="$(read_setting BATON_ROUND_GRANT_JWK_SET_FILE)" \
  || fail '로컬 ROUND 서명 키'
round_turn_urls="$(read_setting BATON_ROUND_TURN_URLS)" \
  || fail 'TURN TLS'

log '통합 기능 활성 상태'
[[ "$oidc_enabled" == true ]] || fail '통합 기능 활성 상태'
[[ "$go_enabled" == true ]] || fail '통합 기능 활성 상태'
[[ "$round_enabled" == true ]] || fail '통합 기능 활성 상태'

required_jwk_count=1
if [[ "$require_key_overlap" == true ]]; then
  required_jwk_count=2
fi

normalize_hex_file() {
  local source_file="$1"
  local target_file="$2"

  awk '
    {
      value = tolower($0)
      gsub(/[[:space:]]/, "", value)
      if (value !~ /^[0-9a-f]+$/) {
        invalid = 1
        next
      }
      while (length(value) > 1 && substr(value, 1, 1) == "0") {
        value = substr(value, 2)
      }
      print value
      found += 1
    }
    END {
      if (invalid || found != 1) {
        exit 1
      }
    }
  ' "$source_file" > "$target_file"
}

decode_base64url_to_hex() {
  local source_file="$1"
  local target_file="$2"
  local artifact_name="$3"
  local encoded_value
  local padding=""
  local remainder

  encoded_value="$(< "$source_file")"
  [[ -n "$encoded_value" && "$encoded_value" =~ ^[A-Za-z0-9_-]+$ ]] || return 1
  remainder=$((${#encoded_value} % 4))
  case "$remainder" in
    0) ;;
    2) padding='==' ;;
    3) padding='=' ;;
    *) return 1 ;;
  esac
  if ! printf '%s' "$encoded_value" \
    > "$temporary_dir/$artifact_name.base64url"; then
    return 1
  fi
  if ! tr '_-' '/+' \
    < "$temporary_dir/$artifact_name.base64url" \
    > "$temporary_dir/$artifact_name.base64" \
    2> "$temporary_dir/$artifact_name-tr.error"; then
    return 1
  fi
  if ! printf '%s' "$padding" >> "$temporary_dir/$artifact_name.base64"; then
    return 1
  fi
  if ! openssl base64 \
    -d \
    -A \
    -in "$temporary_dir/$artifact_name.base64" \
    > "$temporary_dir/$artifact_name.bin" \
    2> "$temporary_dir/$artifact_name-base64.error"; then
    return 1
  fi
  [[ -s "$temporary_dir/$artifact_name.bin" ]] || return 1
  if ! od -An -v -tx1 "$temporary_dir/$artifact_name.bin" \
    > "$temporary_dir/$artifact_name-od.output" \
    2> "$temporary_dir/$artifact_name-od.error"; then
    return 1
  fi
  if ! tr -d '[:space:]' \
    < "$temporary_dir/$artifact_name-od.output" \
    > "$temporary_dir/$artifact_name.hex" \
    2> "$temporary_dir/$artifact_name-hex.error"; then
    return 1
  fi
  normalize_hex_file "$temporary_dir/$artifact_name.hex" "$target_file"
}

log '로컬 RSA private key'
if ! openssl rsa \
  -in "$round_private_key_file" \
  -passin pass: \
  -check \
  -noout \
  > "$temporary_dir/private-key-check.output" \
  2> "$temporary_dir/private-key-check.error"; then
  fail '로컬 RSA private key'
fi
if ! openssl rsa \
  -in "$round_private_key_file" \
  -passin pass: \
  -text \
  -noout \
  > "$temporary_dir/private-key-text.output" \
  2> "$temporary_dir/private-key-text.error"; then
  fail '로컬 RSA private key'
fi
if ! private_key_bits="$(awk '
  !found && match($0, /\([0-9][0-9]* bit/) {
    value = substr($0, RSTART + 1, RLENGTH - 5)
    if (value ~ /^[0-9][0-9]*$/) {
      print value
      found = 1
    }
  }
  END {
    if (!found) {
      exit 1
    }
  }
' "$temporary_dir/private-key-text.output")"; then
  fail '로컬 RSA private key'
fi
[[ "$private_key_bits" =~ ^[0-9]+$ ]] || fail '로컬 RSA private key'
(( 10#$private_key_bits >= 2048 )) || fail '로컬 RSA private key'

if ! openssl rsa \
  -in "$round_private_key_file" \
  -passin pass: \
  -modulus \
  -noout \
  > "$temporary_dir/private-modulus.output" \
  2> "$temporary_dir/private-modulus.error"; then
  fail '로컬 RSA private key'
fi
if ! awk -F= '
  $1 == "Modulus" && $2 ~ /^[0-9A-Fa-f]+$/ {
    print $2
    found += 1
  }
  END {
    if (found != 1) {
      exit 1
    }
  }
' "$temporary_dir/private-modulus.output" \
  > "$temporary_dir/private-modulus.hex"; then
  fail '로컬 RSA private key'
fi
if ! normalize_hex_file \
  "$temporary_dir/private-modulus.hex" \
  "$temporary_dir/private-modulus.normalized"; then
  fail '로컬 RSA private key'
fi
if ! awk '
  !found && /publicExponent:/ {
    if (match($0, /\(0x[0-9A-Fa-f][0-9A-Fa-f]*\)/)) {
      value = substr($0, RSTART + 3, RLENGTH - 4)
      print value
      found = 1
      next
    }
    line = $0
    sub(/^.*publicExponent:[[:space:]]*/, "", line)
    split(line, parts, /[[:space:]]+/)
    if (parts[1] ~ /^[0-9]+$/) {
      printf "%x\n", parts[1]
      found = 1
    }
  }
  END {
    if (!found) {
      exit 1
    }
  }
' "$temporary_dir/private-key-text.output" \
  > "$temporary_dir/private-exponent.hex"; then
  fail '로컬 RSA private key'
fi
if ! normalize_hex_file \
  "$temporary_dir/private-exponent.hex" \
  "$temporary_dir/private-exponent.normalized"; then
  fail '로컬 RSA private key'
fi

log '로컬 public JWK Set'
if ! jq -e \
  --arg active_kid "$round_active_kid" \
  --argjson required_key_count "$required_jwk_count" '
  type == "object"
  and (.keys | type == "array")
  and ((.keys | length) as $key_count
    | $key_count >= $required_key_count
    and ([.keys[].kid] | unique | length) == $key_count)
  and ([.keys[] | select(.kid == $active_kid)] | length == 1)
  and all(.keys[];
    .kty == "RSA"
    and .use == "sig"
    and .alg == "RS256"
    and (.kid | type == "string" and length > 0)
    and (.n | type == "string" and length > 0)
    and (.e | type == "string" and length > 0)
    and ((has("d") or has("p") or has("q") or has("dp")
      or has("dq") or has("qi") or has("oth")) | not))
' "$round_local_jwk_set_file" \
  > "$temporary_dir/local-jwks.jq" \
  2> "$temporary_dir/local-jwks-jq.error"; then
  fail '로컬 public JWK Set'
fi
if ! jq -er --arg active_kid "$round_active_kid" '
  .keys[] | select(.kid == $active_kid) | .n
' "$round_local_jwk_set_file" \
  > "$temporary_dir/local-active-n.base64url" \
  2> "$temporary_dir/local-active-n.error"; then
  fail '로컬 public JWK Set'
fi
if ! jq -er --arg active_kid "$round_active_kid" '
  .keys[] | select(.kid == $active_kid) | .e
' "$round_local_jwk_set_file" \
  > "$temporary_dir/local-active-e.base64url" \
  2> "$temporary_dir/local-active-e.error"; then
  fail '로컬 public JWK Set'
fi
log '로컬 RSA와 JWK 일치'
if ! decode_base64url_to_hex \
  "$temporary_dir/local-active-n.base64url" \
  "$temporary_dir/local-active-n.normalized" \
  local-active-n; then
  fail '로컬 JWK modulus 인코딩'
fi
if ! decode_base64url_to_hex \
  "$temporary_dir/local-active-e.base64url" \
  "$temporary_dir/local-active-e.normalized" \
  local-active-e; then
  fail '로컬 JWK exponent 인코딩'
fi
cmp -s \
  "$temporary_dir/private-modulus.normalized" \
  "$temporary_dir/local-active-n.normalized" \
  || fail '로컬 RSA modulus 일치'
cmp -s \
  "$temporary_dir/private-exponent.normalized" \
  "$temporary_dir/local-active-e.normalized" \
  || fail '로컬 RSA exponent 일치'

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

perform_request() {
  local name="$1"
  local protocol="$2"
  local url="$3"
  local method="${4:-GET}"
  local -a curl_arguments

  curl_arguments=(
    --disable
    --silent
    --proto "=$protocol"
    --connect-timeout 5
    --max-time 15
    --max-filesize 65536
  )
  if [[ "$protocol" == https ]]; then
    curl_arguments+=(--tlsv1.2)
  fi
  if [[ "$method" != GET ]]; then
    curl_arguments+=(--request "$method")
  fi
  curl_arguments+=(
    --dump-header "$temporary_dir/$name.headers"
    --output "$temporary_dir/$name.body"
    --write-out '%{http_code}'
    "$url"
  )

  curl "${curl_arguments[@]}" 2> "$temporary_dir/$name.error"
}

query_parameter() {
  local location="$1"
  local expected_name="$2"
  local query
  local pair
  local name
  local value
  local found=false
  local result=""
  local old_ifs="$IFS"
  local -a pairs

  [[ "$location" == *\?* ]] || return 1
  query="${location#*\?}"
  [[ "$query" != *'#'* ]] || return 1
  IFS='&'
  read -r -a pairs <<< "$query"
  IFS="$old_ifs"
  for pair in "${pairs[@]}"; do
    name="${pair%%=*}"
    value="${pair#*=}"
    if [[ "$name" == "$expected_name" ]]; then
      [[ "$found" == false ]] || return 1
      found=true
      result="$value"
    fi
  done
  [[ "$found" == true ]] || return 1
  printf '%s' "$result"
}

baton_https_origin="https://$baton_host"

log 'BATON 공개 HTTPS 상태'
if ! health_status="$(perform_request \
  baton-health \
  https \
  "$baton_https_origin/actuator/health")"; then
  fail 'BATON 공개 HTTPS 상태'
fi
[[ "$health_status" == 200 ]] || fail 'BATON 공개 HTTPS 상태'
if ! jq -e '
  type == "object"
  and .status == "UP"
  and (keys - ["status", "groups"] | length == 0)
' "$temporary_dir/baton-health.body" \
  > "$temporary_dir/baton-health.jq" \
  2> "$temporary_dir/baton-health-jq.error"; then
  fail 'BATON 공개 HTTPS 상태'
fi

log 'HTTP에서 HTTPS 전환'
if ! redirect_status="$(perform_request \
  http-redirect \
  http \
  "http://$baton_host/")"; then
  fail 'HTTP에서 HTTPS 전환'
fi
case "$redirect_status" in
  301|302|307|308) ;;
  *) fail 'HTTP에서 HTTPS 전환' ;;
esac
redirect_location="$(header_value Location "$temporary_dir/http-redirect.headers")"
[[ "$redirect_location" == "$baton_https_origin/" ]] \
  || fail 'HTTP에서 HTTPS 전환'

log 'Google OIDC와 PKCE'
if ! oidc_status="$(perform_request \
  oidc-authorization \
  https \
  "$baton_https_origin/api/v1/auth/oidc/authorization/google")"; then
  fail 'Google OIDC와 PKCE'
fi
case "$oidc_status" in
  302|303) ;;
  *) fail 'Google OIDC와 PKCE' ;;
esac
oidc_location="$(header_value Location "$temporary_dir/oidc-authorization.headers")"
oidc_google_prefix='https://accounts.google.com/o/oauth2/v2/auth?'
[[ "$oidc_location" == "$oidc_google_prefix"* ]] || fail 'Google OIDC와 PKCE'
if ! oidc_response_type="$(query_parameter "$oidc_location" response_type)" \
  || ! oidc_client_id="$(query_parameter "$oidc_location" client_id)" \
  || ! oidc_state="$(query_parameter "$oidc_location" state)" \
  || ! oidc_challenge_method="$(query_parameter "$oidc_location" code_challenge_method)" \
  || ! oidc_challenge="$(query_parameter "$oidc_location" code_challenge)" \
  || ! oidc_redirect_uri="$(query_parameter "$oidc_location" redirect_uri)"; then
  fail 'Google OIDC와 PKCE'
fi
[[ "$oidc_response_type" == code ]] || fail 'Google OIDC와 PKCE'
[[ -n "$oidc_client_id" && "$oidc_client_id" != *[[:space:]]* ]] \
  || fail 'Google OIDC와 PKCE'
[[ -n "$oidc_state" && "$oidc_state" != *[[:space:]]* ]] \
  || fail 'Google OIDC와 PKCE'
[[ "$oidc_challenge_method" == S256 ]] || fail 'Google OIDC와 PKCE'
[[ "$oidc_challenge" =~ ^[A-Za-z0-9_-]{43,128}$ ]] \
  || fail 'Google OIDC와 PKCE'
expected_callback="$baton_https_origin/api/v1/auth/oidc/callback/google"
if ! expected_encoded_callback="$(jq -rn --arg value "$expected_callback" '$value | @uri' \
  2> "$temporary_dir/callback-encoding.error")"; then
  fail 'Google OIDC와 PKCE'
fi
[[ "$oidc_redirect_uri" == "$expected_encoded_callback" ]] \
  || fail 'Google OIDC와 PKCE'

if ! awk '
  BEGIN {
    found = 0
    invalid = 0
  }
  {
    line = $0
    sub(/\r$/, "", line)
    separator = index(line, ":")
    if (separator == 0) {
      next
    }
    header_name = substr(line, 1, separator - 1)
    if (tolower(header_name) != "set-cookie") {
      next
    }
    cookie = substr(line, separator + 1)
    sub(/^[[:space:]]+/, "", cookie)
    attribute_count = split(cookie, attributes, ";")
    first = attributes[1]
    sub(/^[[:space:]]+/, "", first)
    sub(/[[:space:]]+$/, "", first)
    if (first !~ /^__Host-baton_session=[^;,[:space:]]+$/) {
      next
    }
    if (found == 1) {
      invalid = 1
      next
    }
    found = 1
    secure = 0
    http_only = 0
    same_site = 0
    root_path = 0
    domain = 0
    for (attribute_index = 2; attribute_index <= attribute_count; attribute_index += 1) {
      attribute = attributes[attribute_index]
      sub(/^[[:space:]]+/, "", attribute)
      sub(/[[:space:]]+$/, "", attribute)
      attribute = tolower(attribute)
      if (attribute == "secure") {
        secure = 1
      } else if (attribute == "httponly") {
        http_only = 1
      } else if (attribute == "samesite=lax") {
        same_site = 1
      } else if (attribute == "path=/") {
        root_path = 1
      } else if (attribute ~ /^domain=/) {
        domain = 1
      }
    }
    if (!secure || !http_only || !same_site || !root_path || domain) {
      invalid = 1
    }
  }
  END {
    exit(found == 1 && invalid == 0 ? 0 : 1)
  }
' "$temporary_dir/oidc-authorization.headers"; then
  fail 'Google OIDC와 PKCE'
fi

log '공개 JWK 회전 상태'
if ! jwks_status="$(perform_request \
  round-jwks \
  https \
  "$baton_https_origin/.well-known/jwks.json")"; then
  fail '공개 JWK 회전 상태'
fi
[[ "$jwks_status" == 200 ]] || fail '공개 JWK 회전 상태'
jwks_content_type="$(header_value Content-Type "$temporary_dir/round-jwks.headers")"
jwks_content_type="$(printf '%s' "$jwks_content_type" | tr '[:upper:]' '[:lower:]')"
case "$jwks_content_type" in
  application/json|application/json\;*) ;;
  *) fail '공개 JWK 회전 상태' ;;
esac
jwks_cache_control="$(header_value Cache-Control "$temporary_dir/round-jwks.headers")"
jwks_cache_control="$(printf '%s' "$jwks_cache_control" \
  | tr '[:upper:]' '[:lower:]' \
  | tr -d '[:space:]')"
case ",$jwks_cache_control," in
  *,public,*) ;;
  *) fail '공개 JWK 회전 상태' ;;
esac
case ",$jwks_cache_control," in
  *,max-age=60,*) ;;
  *) fail '공개 JWK 회전 상태' ;;
esac
case ",$jwks_cache_control," in
  *,must-revalidate,*) ;;
  *) fail '공개 JWK 회전 상태' ;;
esac
if ! jq -e \
  --arg active_kid "$round_active_kid" \
  --argjson required_key_count "$required_jwk_count" '
  type == "object"
  and (.keys | type == "array")
  and ((.keys | length) as $key_count
    | $key_count >= $required_key_count
    and ([.keys[].kid] | unique | length) == $key_count)
  and ([.keys[] | select(.kid == $active_kid)] | length == 1)
  and all(.keys[];
    .kty == "RSA"
    and .use == "sig"
    and .alg == "RS256"
    and (.kid | type == "string" and length > 0)
    and (.n | type == "string" and length > 0)
    and (.e | type == "string" and length > 0)
    and ((has("d") or has("p") or has("q") or has("dp")
      or has("dq") or has("qi") or has("oth")) | not))
' "$temporary_dir/round-jwks.body" \
  > "$temporary_dir/round-jwks.jq" \
  2> "$temporary_dir/round-jwks-jq.error"; then
  fail '공개 JWK 회전 상태'
fi
log '배포 JWK와 로컬 key 일치'
if ! jq -er --arg active_kid "$round_active_kid" '
  .keys[] | select(.kid == $active_kid) | .n
' "$temporary_dir/round-jwks.body" \
  > "$temporary_dir/remote-active-n.base64url" \
  2> "$temporary_dir/remote-active-n.error"; then
  fail '배포 JWK와 로컬 key 일치'
fi
if ! jq -er --arg active_kid "$round_active_kid" '
  .keys[] | select(.kid == $active_kid) | .e
' "$temporary_dir/round-jwks.body" \
  > "$temporary_dir/remote-active-e.base64url" \
  2> "$temporary_dir/remote-active-e.error"; then
  fail '배포 JWK와 로컬 key 일치'
fi
if ! decode_base64url_to_hex \
  "$temporary_dir/remote-active-n.base64url" \
  "$temporary_dir/remote-active-n.normalized" \
  remote-active-n; then
  fail '배포 JWK와 로컬 key 일치'
fi
if ! decode_base64url_to_hex \
  "$temporary_dir/remote-active-e.base64url" \
  "$temporary_dir/remote-active-e.normalized" \
  remote-active-e; then
  fail '배포 JWK와 로컬 key 일치'
fi
cmp -s \
  "$temporary_dir/remote-active-n.normalized" \
  "$temporary_dir/local-active-n.normalized" \
  || fail '배포 JWK와 로컬 key 일치'
cmp -s \
  "$temporary_dir/remote-active-n.normalized" \
  "$temporary_dir/private-modulus.normalized" \
  || fail '배포 JWK와 로컬 key 일치'
cmp -s \
  "$temporary_dir/remote-active-e.normalized" \
  "$temporary_dir/local-active-e.normalized" \
  || fail '배포 JWK와 로컬 key 일치'
cmp -s \
  "$temporary_dir/remote-active-e.normalized" \
  "$temporary_dir/private-exponent.normalized" \
  || fail '배포 JWK와 로컬 key 일치'

canonical_dummy_room='abcd-efgh-jkmn'

log 'ROUND 공개 화면 보안 헤더'
if ! round_page_status="$(perform_request \
  round-page \
  https \
  "$baton_https_origin/room/$canonical_dummy_room")"; then
  fail 'ROUND 공개 화면 보안 헤더'
fi
[[ "$round_page_status" == 200 ]] || fail 'ROUND 공개 화면 보안 헤더'
round_csp="$(header_value Content-Security-Policy "$temporary_dir/round-page.headers")"
round_permissions="$(header_value Permissions-Policy "$temporary_dir/round-page.headers")"
round_referrer="$(header_value Referrer-Policy "$temporary_dir/round-page.headers")"
round_frame="$(header_value X-Frame-Options "$temporary_dir/round-page.headers")"
round_hsts="$(header_value Strict-Transport-Security "$temporary_dir/round-page.headers")"
[[ "$round_csp" == *"default-src 'self'"* \
  && "$round_csp" == *"connect-src 'self' wss://$baton_host"* \
  && "$round_csp" == *"frame-ancestors 'none'"* ]] \
  || fail 'ROUND 공개 화면 보안 헤더'
round_permissions="$(printf '%s' "$round_permissions" | tr -d '[:space:]')"
[[ "$round_permissions" == *'camera=(self)'* \
  && "$round_permissions" == *'microphone=(self)'* \
  && "$round_permissions" == *'geolocation=()'* ]] \
  || fail 'ROUND 공개 화면 보안 헤더'
round_referrer="$(printf '%s' "$round_referrer" | tr '[:upper:]' '[:lower:]')"
round_frame="$(printf '%s' "$round_frame" | tr '[:upper:]' '[:lower:]')"
round_hsts="$(printf '%s' "$round_hsts" | tr '[:upper:]' '[:lower:]')"
[[ "$round_referrer" == no-referrer ]] || fail 'ROUND 공개 화면 보안 헤더'
[[ "$round_frame" == deny ]] || fail 'ROUND 공개 화면 보안 헤더'
[[ "$round_hsts" == *'max-age=31536000'* ]] \
  || fail 'ROUND 공개 화면 보안 헤더'

log 'ROUND 무자격 보호 경계'
if ! protected_status="$(perform_request \
  round-protected \
  https \
  "$baton_https_origin/round/rooms/$canonical_dummy_room/turn-credentials" \
  POST)"; then
  fail 'ROUND 무자격 보호 경계'
fi
[[ "$protected_status" == 404 ]] || fail 'ROUND 무자격 보호 경계'
if header_value Set-Cookie "$temporary_dir/round-protected.headers" \
  > "$temporary_dir/unexpected-cookie"; then
  [[ ! -s "$temporary_dir/unexpected-cookie" ]] || fail 'ROUND 무자격 보호 경계'
fi

log 'GO 공개 HTTPS TLS'
go_https_origin="${go_public_base_url%/}"
if ! go_status="$(perform_request go-public https "$go_https_origin/")"; then
  fail 'GO 공개 HTTPS TLS'
fi
[[ "$go_status" =~ ^[1-4][0-9]{2}$ ]] || fail 'GO 공개 HTTPS TLS'

validate_public_hostname() {
  local hostname="$1"
  local label
  local old_ifs="$IFS"
  local -a labels

  if [[ ${#hostname} -gt 253 \
    || "$hostname" != *.* \
    || "$hostname" == .* \
    || "$hostname" == *. \
    || "$hostname" == *..* \
    || "$hostname" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ \
    || ! "$hostname" =~ ^[A-Za-z0-9.-]+$ ]]; then
    return 1
  fi
  IFS='.'
  read -r -a labels <<< "$hostname"
  IFS="$old_ifs"
  for label in "${labels[@]}"; do
    if [[ ${#label} -gt 63 \
      || ! "$label" =~ ^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?$ ]]; then
      return 1
    fi
  done
  return 0
}

log '외부 TURN TLS'
old_ifs="$IFS"
IFS=','
read -r -a turn_entries <<< "$round_turn_urls"
IFS="$old_ifs"
turns_candidate_count=0
turns_tls_verified=false
for turn_entry in "${turn_entries[@]}"; do
  [[ "$turn_entry" == turns:* ]] || continue
  turns_candidate_count=$((turns_candidate_count + 1))
  turn_authority="${turn_entry#turns:}"
  [[ "$turn_authority" != *'@'* \
    && "$turn_authority" != *'/'* \
    && "$turn_authority" != *'#'* ]] || continue
  turn_query=""
  if [[ "$turn_authority" == *\?* ]]; then
    turn_query="${turn_authority#*\?}"
    turn_authority="${turn_authority%%\?*}"
  fi
  [[ -z "$turn_query" || "$turn_query" == transport=tcp ]] || continue
  turn_host="$turn_authority"
  turn_port=5349
  if [[ "$turn_authority" == *:* ]]; then
    turn_host="${turn_authority%:*}"
    turn_port="${turn_authority##*:}"
  fi
  validate_public_hostname "$turn_host" || continue
  normalized_turn_host="$(printf '%s' "$turn_host" | tr '[:upper:]' '[:lower:]')"
  normalized_baton_host="$(printf '%s' "$baton_host" | tr '[:upper:]' '[:lower:]')"
  [[ "$normalized_turn_host" != "$normalized_baton_host" ]] || continue
  [[ "$turn_port" =~ ^[1-9][0-9]{0,4}$ ]] || continue
  (( 10#$turn_port <= 65535 )) || continue

  if openssl s_client \
    -connect "$turn_host:$turn_port" \
    -servername "$turn_host" \
    -verify_hostname "$turn_host" \
    -verify_return_error \
    < /dev/null \
    > "$temporary_dir/turn-tls.output" \
    2> "$temporary_dir/turn-tls.error"; then
    turns_tls_verified=true
    break
  fi
done
(( turns_candidate_count > 0 )) || fail '외부 TURN TLS'
[[ "$turns_tls_verified" == true ]] || fail '외부 TURN TLS'

log '전체 사전점검 통과'
