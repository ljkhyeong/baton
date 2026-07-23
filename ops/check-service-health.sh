#!/usr/bin/env bash

set -Eeuo pipefail

health_url="${BATON_HEALTH_URL:-}"
connect_timeout_seconds="${BATON_HEALTH_CONNECT_TIMEOUT_SECONDS:-5}"
timeout_seconds="${BATON_HEALTH_TIMEOUT_SECONDS:-15}"
temporary_dir=""
temp_base="${TMPDIR:-/tmp}"
temp_base="${temp_base%/}"

fail() {
  printf 'BATON public service health check failed: %s\n' "$1" >&2
  exit 1
}

cleanup() {
  if [[ -n "$temporary_dir" ]]; then
    case "$temporary_dir" in
      "$temp_base"/baton-service-health.*) rm -rf -- "$temporary_dir" ;;
    esac
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [[ ! "$connect_timeout_seconds" =~ ^[1-9][0-9]?$ ]] \
  || (( 10#$connect_timeout_seconds > 60 )); then
  fail "BATON_HEALTH_CONNECT_TIMEOUT_SECONDS must be an integer from 1 to 60"
fi
if [[ ! "$timeout_seconds" =~ ^[1-9][0-9]{0,2}$ ]] \
  || (( 10#$timeout_seconds > 120 )) \
  || (( 10#$timeout_seconds < 10#$connect_timeout_seconds )); then
  fail "BATON_HEALTH_TIMEOUT_SECONDS must be an integer from the connect timeout through 120"
fi

case "$health_url" in
  https://*/actuator/health) ;;
  *) fail "BATON_HEALTH_URL must be an HTTPS URL ending exactly in /actuator/health" ;;
esac
authority="${health_url#https://}"
authority="${authority%/actuator/health}"
if [[ "$authority" == *"/"* \
  || "$authority" == *"@"* \
  || "$authority" == *"?"* \
  || "$authority" == *"#"* \
  || "$authority" == *"["* \
  || "$authority" == *"]"* ]]; then
  fail "BATON_HEALTH_URL must not contain credentials, query, fragment, or an extra path"
fi

health_host="$authority"
if [[ "$authority" == *:* ]]; then
  health_port="${authority##*:}"
  health_host="${authority%:*}"
  if [[ ! "$health_port" =~ ^[1-9][0-9]{0,4}$ ]] \
    || (( 10#$health_port > 65535 )); then
    fail "BATON_HEALTH_URL contains an invalid port"
  fi
fi
if [[ "$health_host" != *.* \
  || ${#health_host} -gt 253 \
  || "$health_host" == .* \
  || "$health_host" == *. \
  || "$health_host" == *..* \
  || "$health_host" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ \
  || ! "$health_host" =~ ^[A-Za-z0-9.-]+$ ]]; then
  fail "BATON_HEALTH_URL must use a public DNS hostname"
fi
old_ifs="$IFS"
IFS='.'
read -r -a health_labels <<< "$health_host"
IFS="$old_ifs"
for health_label in "${health_labels[@]}"; do
  if [[ ${#health_label} -gt 63 \
    || ! "$health_label" =~ ^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?$ ]]; then
    fail "BATON_HEALTH_URL must use a public DNS hostname"
  fi
done

command -v curl >/dev/null 2>&1 || fail "curl is required"

temporary_dir="$(mktemp -d "$temp_base/baton-service-health.XXXXXX")"
headers_file="$temporary_dir/headers"
body_file="$temporary_dir/body"

http_status=""
if ! http_status="$(curl \
  --disable \
  --fail \
  --silent \
  --show-error \
  --proto '=https' \
  --tlsv1.2 \
  --connect-timeout "$connect_timeout_seconds" \
  --max-time "$timeout_seconds" \
  --max-filesize 65536 \
  --dump-header "$headers_file" \
  --output "$body_file" \
  --write-out '%{http_code}' \
  "$health_url")"; then
  fail "HTTPS request did not complete with a trusted certificate and successful response"
fi
if [[ "$http_status" != "200" ]]; then
  fail "expected HTTP 200 without redirects, received $http_status"
fi

health_body="$(< "$body_file")"
aggregate_up_pattern='^[[:space:]]*\{"status":"UP"(,"groups":\[("[A-Za-z0-9._-]+"(,"[A-Za-z0-9._-]+")*)?\])?\}[[:space:]]*$'
if [[ ! "$health_body" =~ $aggregate_up_pattern ]]; then
  fail "health response was not the aggregate UP document"
fi

printf 'BATON public service is healthy: url=%s status=%s\n' "$health_url" "$http_status"
