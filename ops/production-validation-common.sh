#!/usr/bin/env bash

# 공유 프로덕션 검증 기본 요소는 해석한 프로덕션 값을 절대 추적하지 않는다.
case "$-" in
  *x*) set +x ;;
esac

# Bash 3.2에는 이름 참조가 없으므로 호출자에게 보이는 이 전역 변수가 헬퍼의 결과 API다.
# shellcheck disable=SC2034
PRODUCTION_VALIDATION_ERROR=""
PRODUCTION_VALIDATION_ENV_KEYS=()
PRODUCTION_VALIDATION_ENV_VALUES=()

# 운영 Docker 명령은 주변 Docker·BuildKit 설정을 무시하고 로컬 데몬 소켓만 사용한다.
# shellcheck disable=SC2034  # 불러온 스크립트가 이 명령 배열을 실행한다.
PRODUCTION_DOCKER_COMMAND=(
  env
  -u DOCKER_HOST
  -u DOCKER_CONTEXT
  -u DOCKER_CONFIG
  -u DOCKER_TLS_VERIFY
  -u DOCKER_CERT_PATH
  -u DOCKER_API_VERSION
  -u DOCKER_DEFAULT_PLATFORM
  -u BUILDX_BUILDER
  -u BUILDX_CONFIG
  -u BUILDKIT_HOST
  -u DOCKER_BUILDKIT
  docker
  --host unix:///var/run/docker.sock
)

# shellcheck disable=SC2034  # 함수 반환 후 호출자가 PRODUCTION_VALIDATION_ERROR를 읽는다.
production_validation_parse_literal_env() {
  local env_file="$1"
  local key
  local line
  local line_number=0
  local seen_keys=$'\n'
  local value

  PRODUCTION_VALIDATION_ERROR=""
  PRODUCTION_VALIDATION_ENV_KEYS=()
  PRODUCTION_VALIDATION_ENV_VALUES=()

  while IFS= read -r line || [[ -n "$line" ]]; do
    line_number=$((line_number + 1))
    if [[ "$line" == *$'\r'* ]]; then
      PRODUCTION_VALIDATION_ERROR="environment file must use LF line endings: line=$line_number"
      return 1
    fi
    if [[ "$line" =~ ^[[:space:]]*$ || "$line" =~ ^[[:space:]]*# ]]; then
      continue
    fi
    if [[ ! "$line" =~ ^([A-Z][A-Z0-9_]*)=([^[:space:]\"\'\$\`]+)$ ]]; then
      PRODUCTION_VALIDATION_ERROR="line $line_number must be a simple literal KEY=VALUE without quotes, whitespace, or interpolation"
      return 1
    fi

    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    case "$seen_keys" in
      *$'\n'"$key"$'\n'*)
        PRODUCTION_VALIDATION_ERROR="duplicate key: $key"
        return 1
        ;;
    esac
    seen_keys+="$key"$'\n'
    PRODUCTION_VALIDATION_ENV_KEYS+=("$key")
    PRODUCTION_VALIDATION_ENV_VALUES+=("$value")
  done < "$env_file"
  return 0
}

# 해석한 환경 파일의 값을 출력한다. 키가 없으면 아무것도 출력하지 않는다.
production_validation_env_value() {
  local index
  local wanted_key="$1"

  for ((index = 0; index < ${#PRODUCTION_VALIDATION_ENV_KEYS[@]}; index += 1)); do
    if [[ "${PRODUCTION_VALIDATION_ENV_KEYS[$index]}" == "$wanted_key" ]]; then
      printf '%s' "${PRODUCTION_VALIDATION_ENV_VALUES[$index]}"
      return 0
    fi
  done
  return 0
}

production_validation_is_dns_hostname() {
  local hostname="$1"
  local label
  local labels
  local old_ifs

  if [[ ${#hostname} -gt 253 \
    || "$hostname" != *.* \
    || "$hostname" == .* \
    || "$hostname" == *. \
    || "$hostname" == *..* \
    || "$hostname" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ \
    || ! "$hostname" =~ ^[A-Za-z0-9.-]+$ ]]; then
    return 1
  fi

  old_ifs="$IFS"
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

production_validation_validate_boolean() {
  local fail_callback="$1"
  local name="$2"
  local value="$3"

  if [[ "$value" != "true" && "$value" != "false" ]]; then
    "$fail_callback" "$name must be exactly true or false"
  fi
}

# 운영 기본 비밀값과 Bearer 토큰이 함께 따르는 32~200자 URL 안전 ASCII 정책이다.
production_validation_is_url_safe_secret() {
  local value="$1"

  [[ ${#value} -ge 32 && ${#value} -le 200 && "$value" =~ ^[A-Za-z0-9._~-]+$ ]]
}

production_validation_portable_mode() {
  local fail_callback="$1"
  local target="$2"
  local mode

  if mode="$(stat -f '%Lp' "$target" 2>/dev/null)"; then
    :
  elif mode="$(stat -c '%a' "$target" 2>/dev/null)"; then
    :
  else
    "$fail_callback" "could not inspect permissions: $target"
  fi
  printf '%s' "$mode"
}

production_validation_canonical_file() {
  local fail_callback="$1"
  local target="$2"
  local directory

  directory="$(CDPATH= cd -- "$(dirname -- "$target")" && pwd -P)" \
    || "$fail_callback" "could not resolve secret parent directory: $target"
  printf '%s/%s' "$directory" "$(basename -- "$target")"
}

production_validation_secret_file() {
  local fail_callback="$1"
  local name="$2"
  local target="$3"
  local canonical_target
  local directory
  local directory_mode
  local file_mode

  [[ "$target" == /* ]] || "$fail_callback" "$name must be an absolute file path"
  [[ ! -L "$target" ]] || "$fail_callback" "$name must not be a symbolic link"
  [[ -f "$target" && -r "$target" ]] \
    || "$fail_callback" "$name must be a readable regular file"
  [[ -O "$target" ]] || "$fail_callback" "$name must be owned by the current user"

  canonical_target="$(production_validation_canonical_file "$fail_callback" "$target")" || exit 1
  [[ ! -L "$canonical_target" && -f "$canonical_target" && -r "$canonical_target" ]] \
    || "$fail_callback" "$name canonical target must be a readable regular file"
  [[ -O "$canonical_target" ]] \
    || "$fail_callback" "$name canonical target must be owned by the current user"

  directory="$(dirname -- "$canonical_target")"
  [[ -O "$directory" ]] || "$fail_callback" "$name parent directory must be owned by the current user"
  directory_mode="$(production_validation_portable_mode "$fail_callback" "$directory")" || exit 1
  file_mode="$(production_validation_portable_mode "$fail_callback" "$canonical_target")" || exit 1
  [[ "$directory_mode" =~ ^[0-7]{3,4}$ && "$file_mode" =~ ^[0-7]{3,4}$ ]] \
    || "$fail_callback" "$name permissions are invalid"
  if (( (8#$directory_mode & 077) != 0 )); then
    "$fail_callback" "$name parent directory must not grant group or other permissions"
  fi
  if (( (8#$file_mode & 077) != 0 )); then
    "$fail_callback" "$name must not grant group or other permissions"
  fi
  printf '%s' "$canonical_target"
}
