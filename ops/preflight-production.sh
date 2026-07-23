#!/usr/bin/env bash

set -Eeuo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(dirname -- "$script_dir")"
env_file="${BATON_PRODUCTION_ENV_FILE:-$repo_root/.env.production}"

fail() {
  printf 'Production preflight failed: %s\n' "$1" >&2
  exit 1
}

if [[ $# -gt 1 ]]; then
  printf 'Usage: %s [/absolute/path/to/.env.production]\n' "$0" >&2
  exit 1
fi
if [[ $# -eq 1 ]]; then
  env_file="$1"
fi
case "$env_file" in
  /*) ;;
  *) fail "production environment file must be an absolute path: $env_file" ;;
esac

if [[ -L "$env_file" ]]; then
  fail "environment file must not be a symbolic link: $env_file"
fi
if [[ ! -f "$env_file" || ! -r "$env_file" ]]; then
  fail "environment file must be a readable regular file: $env_file"
fi
if [[ ! -O "$env_file" ]]; then
  fail "environment file must be owned by the current user: $env_file"
fi

env_dir="$(CDPATH= cd -- "$(dirname -- "$env_file")" && pwd -P)"
env_file="$env_dir/$(basename -- "$env_file")"

file_mode=""
if file_mode="$(stat -f '%Lp' "$env_file" 2>/dev/null)"; then
  :
elif file_mode="$(stat -c '%a' "$env_file" 2>/dev/null)"; then
  :
else
  fail "could not inspect environment file permissions: $env_file"
fi
if [[ ! "$file_mode" =~ ^[0-7]{3,4}$ ]]; then
  fail "environment file permissions are invalid: $file_mode"
fi
file_mode_decimal=$((8#$file_mode))
if (( (file_mode_decimal & 077) != 0 )); then
  fail "environment file must not grant group or other permissions: mode=$file_mode"
fi

command -v git >/dev/null 2>&1 || fail "git is required to verify that the environment file is untracked"
git_command=(
  env
  -u GIT_DIR
  -u GIT_WORK_TREE
  -u GIT_INDEX_FILE
  -u GIT_CEILING_DIRECTORIES
  -u GIT_DISCOVERY_ACROSS_FILESYSTEM
  GIT_LITERAL_PATHSPECS=1
  git
)
env_git_root=""
if env_git_root="$("${git_command[@]}" -C "$env_dir" rev-parse --show-toplevel 2>/dev/null)"; then
  env_git_root="$(CDPATH= cd -- "$env_git_root" && pwd -P)"
  relative_env_file="${env_file#"$env_git_root"/}"
  if [[ "$relative_env_file" == "$env_file" ]]; then
    fail "could not resolve environment file inside its Git worktree: $env_file"
  fi
  if ! tracked_env_file="$("${git_command[@]}" -C "$env_git_root" ls-files -- "$relative_env_file")"; then
    fail "could not inspect whether the environment file is tracked by Git: $env_file"
  fi
  if [[ -n "$tracked_env_file" ]]; then
    fail "environment file must not be tracked by Git: $env_file"
  fi
else
  git_probe_dir="$env_dir"
  while [[ "$git_probe_dir" != "/" ]]; do
    if [[ -e "$git_probe_dir/.git" || -L "$git_probe_dir/.git" ]]; then
      fail "could not inspect the Git worktree containing the environment file: $env_file"
    fi
    git_probe_dir="${git_probe_dir%/*}"
    [[ -n "$git_probe_dir" ]] || git_probe_dir="/"
  done
fi

baton_host=""
baton_db_name=""
baton_db_username=""
baton_db_password=""
baton_db_root_password=""
baton_workspace_creation_key=""
baton_workspace_recovery_key=""
seen_baton_host=false
seen_baton_db_name=false
seen_baton_db_username=false
seen_baton_db_password=false
seen_baton_db_root_password=false
seen_baton_workspace_creation_key=false
seen_baton_workspace_recovery_key=false
line_number=0

while IFS= read -r line || [[ -n "$line" ]]; do
  line_number=$((line_number + 1))
  if [[ "$line" == *$'\r'* ]]; then
    fail "environment file must use LF line endings: line=$line_number"
  fi
  if [[ "$line" =~ ^[[:space:]]*$ || "$line" =~ ^[[:space:]]*# ]]; then
    continue
  fi
  if [[ ! "$line" =~ ^([A-Z][A-Z0-9_]*)=([^[:space:]\"\'\$\`]+)$ ]]; then
    fail "line $line_number must be a simple literal KEY=VALUE without quotes, whitespace, or interpolation"
  fi

  key="${BASH_REMATCH[1]}"
  value="${BASH_REMATCH[2]}"
  case "$key" in
    BATON_HOST)
      [[ "$seen_baton_host" == false ]] || fail "duplicate key: $key"
      seen_baton_host=true
      baton_host="$value"
      ;;
    BATON_DB_NAME)
      [[ "$seen_baton_db_name" == false ]] || fail "duplicate key: $key"
      seen_baton_db_name=true
      baton_db_name="$value"
      ;;
    BATON_DB_USERNAME)
      [[ "$seen_baton_db_username" == false ]] || fail "duplicate key: $key"
      seen_baton_db_username=true
      baton_db_username="$value"
      ;;
    BATON_DB_PASSWORD)
      [[ "$seen_baton_db_password" == false ]] || fail "duplicate key: $key"
      seen_baton_db_password=true
      baton_db_password="$value"
      ;;
    BATON_DB_ROOT_PASSWORD)
      [[ "$seen_baton_db_root_password" == false ]] || fail "duplicate key: $key"
      seen_baton_db_root_password=true
      baton_db_root_password="$value"
      ;;
    BATON_WORKSPACE_CREATION_KEY)
      [[ "$seen_baton_workspace_creation_key" == false ]] || fail "duplicate key: $key"
      seen_baton_workspace_creation_key=true
      baton_workspace_creation_key="$value"
      ;;
    BATON_WORKSPACE_RECOVERY_KEY)
      [[ "$seen_baton_workspace_recovery_key" == false ]] || fail "duplicate key: $key"
      seen_baton_workspace_recovery_key=true
      baton_workspace_recovery_key="$value"
      ;;
    *)
      fail "unknown or unsafe production environment key: $key"
      ;;
  esac
done < "$env_file"

for required_key in \
  BATON_HOST \
  BATON_DB_NAME \
  BATON_DB_USERNAME \
  BATON_DB_PASSWORD \
  BATON_DB_ROOT_PASSWORD \
  BATON_WORKSPACE_CREATION_KEY \
  BATON_WORKSPACE_RECOVERY_KEY; do
  case "$required_key" in
    BATON_HOST) seen="$seen_baton_host" ;;
    BATON_DB_NAME) seen="$seen_baton_db_name" ;;
    BATON_DB_USERNAME) seen="$seen_baton_db_username" ;;
    BATON_DB_PASSWORD) seen="$seen_baton_db_password" ;;
    BATON_DB_ROOT_PASSWORD) seen="$seen_baton_db_root_password" ;;
    BATON_WORKSPACE_CREATION_KEY) seen="$seen_baton_workspace_creation_key" ;;
    BATON_WORKSPACE_RECOVERY_KEY) seen="$seen_baton_workspace_recovery_key" ;;
  esac
  [[ "$seen" == true ]] || fail "required key is missing: $required_key"
done

validate_hostname() {
  local hostname="$1"
  local label
  local old_ifs
  local labels

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
}

validate_secret() {
  local name="$1"
  local value="$2"

  if [[ ${#value} -lt 32 || ${#value} -gt 200 || ! "$value" =~ ^[A-Za-z0-9._~-]+$ ]]; then
    fail "$name must be 32-200 URL-safe ASCII characters"
  fi
}

validate_hostname "$baton_host" \
  || fail "BATON_HOST must be a public DNS hostname without scheme, port, path, localhost, or IP"
if [[ ${#baton_db_name} -gt 64 || ! "$baton_db_name" =~ ^[A-Za-z0-9_]+$ ]]; then
  fail "BATON_DB_NAME must be 1-64 letters, digits, or underscores"
fi
if [[ ${#baton_db_username} -gt 32 \
  || ! "$baton_db_username" =~ ^[A-Za-z0-9_]+$ \
  || "$baton_db_username" == "root" ]]; then
  fail "BATON_DB_USERNAME must be a non-root 1-32 character identifier"
fi

validate_secret BATON_DB_PASSWORD "$baton_db_password"
validate_secret BATON_DB_ROOT_PASSWORD "$baton_db_root_password"
validate_secret BATON_WORKSPACE_CREATION_KEY "$baton_workspace_creation_key"
validate_secret BATON_WORKSPACE_RECOVERY_KEY "$baton_workspace_recovery_key"

secrets=(
  "$baton_db_password"
  "$baton_db_root_password"
  "$baton_workspace_creation_key"
  "$baton_workspace_recovery_key"
)
for ((left = 0; left < ${#secrets[@]}; left += 1)); do
  for ((right = left + 1; right < ${#secrets[@]}; right += 1)); do
    if [[ "${secrets[$left]}" == "${secrets[$right]}" ]]; then
      fail "database passwords and workspace keys must all be independently generated"
    fi
  done
done

command -v docker >/dev/null 2>&1 || fail "docker is required"
docker info >/dev/null 2>&1 || fail "Docker daemon is not available to the current user"
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is required"

if ! BATON_PRODUCTION_ENV_FILE="$env_file" \
  "$script_dir/production-compose.sh" config --quiet; then
  fail "production Compose configuration is invalid"
fi

printf 'Production preflight passed: env=%s compose=%s\n' \
  "$env_file" "$repo_root/compose.production.yml"
