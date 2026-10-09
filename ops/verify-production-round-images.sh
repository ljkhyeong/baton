#!/usr/bin/env bash

set -Eeuo pipefail
export LC_ALL=C

# 이 검증기는 프로덕션 환경 설정 파일 전체를 검사하므로 자격 증명이 포함된 줄을 절대 추적하지 않는다.
case "$-" in
  *x*) set +x ;;
esac

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/production-validation-common.sh
source "$script_dir/production-validation-common.sh"

fail() {
  printf 'Production ROUND image verification failed: %s\n' "$1" >&2
  exit 1
}

if [[ $# -ne 1 ]]; then
  printf 'Usage: %s /absolute/path/to/.env.production\n' "$0" >&2
  exit 1
fi
if ! env_file="$("$script_dir/validate-production-env.sh" "$1")"; then
  exit 1
fi
if ! production_validation_parse_literal_env "$env_file"; then
  fail "$PRODUCTION_VALIDATION_ERROR"
fi

round_runtime_enabled="$(production_validation_env_value BATON_ROUND_RUNTIME_ENABLED)"
round_runtime_enabled="${round_runtime_enabled:-false}"
if [[ "$round_runtime_enabled" != "true" ]]; then
  exit 0
fi

round_web_image="$(production_validation_env_value BATON_ROUND_WEB_IMAGE)"
round_signaling_image="$(production_validation_env_value BATON_ROUND_SIGNALING_IMAGE)"
round_release_revision="$(production_validation_env_value BATON_ROUND_RELEASE_REVISION)"

command -v docker >/dev/null 2>&1 || fail "docker is required"

for image in "$round_web_image" "$round_signaling_image"; do
  resolved_repo_digests=""
  "${PRODUCTION_DOCKER_COMMAND[@]}" pull --quiet "$image" >/dev/null \
    || fail "could not pull an exact configured ROUND image digest"
  if ! resolved_repo_digests="$(
    "${PRODUCTION_DOCKER_COMMAND[@]}" image inspect \
      --format '{{ join .RepoDigests "\n" }}' \
      "$image"
  )"; then
    fail "could not inspect the pulled ROUND image repository digests"
  fi
  case $'\n'"$resolved_repo_digests"$'\n' in
    *$'\n'"$image"$'\n'*) ;;
    *) fail "pulled ROUND image RepoDigests do not include the exact configured digest" ;;
  esac
done

inspect_label() {
  local image="$1"
  local label="$2"
  local value

  if ! value="$(
    "${PRODUCTION_DOCKER_COMMAND[@]}" image inspect \
      --format "{{ index .Config.Labels \"$label\" }}" \
      "$image"
  )"; then
    fail "could not inspect ROUND image label: $label"
  fi
  printf '%s' "$value"
}

round_web_auth_mode="$(inspect_label "$round_web_image" io.round.auth-mode)"
if [[ "$round_web_auth_mode" != "baton" ]]; then
  fail "BATON_ROUND_WEB_IMAGE must declare io.round.auth-mode=baton"
fi

round_web_revision="$(
  inspect_label "$round_web_image" org.opencontainers.image.revision
)"
round_signaling_revision="$(
  inspect_label "$round_signaling_image" org.opencontainers.image.revision
)"
if [[ "$round_web_revision" != "$round_release_revision" ]]; then
  fail "BATON_ROUND_WEB_IMAGE revision label does not match BATON_ROUND_RELEASE_REVISION"
fi
if [[ "$round_signaling_revision" != "$round_release_revision" ]]; then
  fail "BATON_ROUND_SIGNALING_IMAGE revision label does not match BATON_ROUND_RELEASE_REVISION"
fi

round_web_tag_object="$(
  inspect_label "$round_web_image" io.round.release.tag-object
)"
round_signaling_tag_object="$(
  inspect_label "$round_signaling_image" io.round.release.tag-object
)"
if [[ ! "$round_web_tag_object" =~ ^[0-9a-f]{40}$ \
  || ! "$round_signaling_tag_object" =~ ^[0-9a-f]{40}$ ]]; then
  fail "ROUND images must declare io.round.release.tag-object as exactly 40 lowercase hexadecimal characters"
fi
if [[ "$round_web_tag_object" != "$round_signaling_tag_object" ]]; then
  fail "ROUND images must declare the same io.round.release.tag-object label"
fi

printf 'Production ROUND images verified: revision=%s\n' "$round_release_revision"
