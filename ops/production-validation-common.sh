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
# shellcheck disable=SC2034
PRODUCTION_VALIDATION_VALUE=""

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

production_validation_read_env_value() {
  local index
  local wanted_key="$1"

  PRODUCTION_VALIDATION_ERROR=""
  PRODUCTION_VALIDATION_VALUE=""
  if [[ ! "$wanted_key" =~ ^[A-Z][A-Z0-9_]*$ ]]; then
    # shellcheck disable=SC2034  # 함수 반환 후 호출자가 이 결과를 읽는다.
    PRODUCTION_VALIDATION_ERROR="environment lookup key is invalid"
    return 1
  fi

  for ((index = 0; index < ${#PRODUCTION_VALIDATION_ENV_KEYS[@]}; index += 1)); do
    if [[ "${PRODUCTION_VALIDATION_ENV_KEYS[$index]}" == "$wanted_key" ]]; then
      # shellcheck disable=SC2034  # 함수 반환 후 호출자가 이 결과를 읽는다.
      PRODUCTION_VALIDATION_VALUE="${PRODUCTION_VALIDATION_ENV_VALUES[$index]}"
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
