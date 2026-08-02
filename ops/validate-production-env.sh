#!/usr/bin/env bash

set -Eeuo pipefail
export LC_ALL=C

fail() {
  printf 'Production environment validation failed: %s\n' "$1" >&2
  exit 1
}

if [[ $# -ne 1 ]]; then
  printf 'Usage: %s /absolute/path/to/.env.production\n' "$0" >&2
  exit 1
fi
env_file="$1"
if [[ "$env_file" == *$'\n'* || "$env_file" == *$'\r'* ]]; then
  fail "production environment file path must not contain line breaks"
fi
case "$env_file" in
  /*) ;;
  *) fail "production environment file must be an absolute path: $env_file" ;;
esac

validate_file_boundary() {
  local target="$1"

  if [[ -L "$target" ]]; then
    fail "environment file must not be a symbolic link: $target"
  fi
  if [[ ! -f "$target" || ! -r "$target" ]]; then
    fail "environment file must be a readable regular file: $target"
  fi
  if [[ ! -O "$target" ]]; then
    fail "environment file must be owned by the current user: $target"
  fi
}

validate_file_boundary "$env_file"
if ! env_dir="$(CDPATH= cd -- "$(dirname -- "$env_file")" && pwd -P)"; then
  fail "environment file parent directory could not be resolved: $env_file"
fi
env_file="$env_dir/$(basename -- "$env_file")"
validate_file_boundary "$env_file"

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
baton_watch_enabled="false"
baton_watch_monitoring_enabled="true"
baton_watch_base_url=""
baton_watch_bearer_token=""
baton_watch_source_namespace=""
baton_watch_event_receiver_enabled="false"
baton_watch_event_receiver_bearer_token=""
seen_baton_host=false
seen_baton_db_name=false
seen_baton_db_username=false
seen_baton_db_password=false
seen_baton_db_root_password=false
seen_baton_workspace_creation_key=false
seen_baton_workspace_recovery_key=false
seen_baton_watch_enabled=false
seen_baton_watch_monitoring_enabled=false
seen_baton_watch_base_url=false
seen_baton_watch_bearer_token=false
seen_baton_watch_source_namespace=false
seen_baton_watch_event_receiver_enabled=false
seen_baton_watch_event_receiver_bearer_token=false
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
    BATON_WATCH_ENABLED)
      [[ "$seen_baton_watch_enabled" == false ]] || fail "duplicate key: $key"
      seen_baton_watch_enabled=true
      baton_watch_enabled="$value"
      ;;
    BATON_WATCH_MONITORING_ENABLED)
      [[ "$seen_baton_watch_monitoring_enabled" == false ]] \
        || fail "duplicate key: $key"
      seen_baton_watch_monitoring_enabled=true
      baton_watch_monitoring_enabled="$value"
      ;;
    BATON_WATCH_BASE_URL)
      [[ "$seen_baton_watch_base_url" == false ]] || fail "duplicate key: $key"
      seen_baton_watch_base_url=true
      baton_watch_base_url="$value"
      ;;
    BATON_WATCH_BEARER_TOKEN)
      [[ "$seen_baton_watch_bearer_token" == false ]] || fail "duplicate key: $key"
      seen_baton_watch_bearer_token=true
      baton_watch_bearer_token="$value"
      ;;
    BATON_WATCH_SOURCE_NAMESPACE)
      [[ "$seen_baton_watch_source_namespace" == false ]] || fail "duplicate key: $key"
      seen_baton_watch_source_namespace=true
      baton_watch_source_namespace="$value"
      ;;
    BATON_WATCH_EVENT_RECEIVER_ENABLED)
      [[ "$seen_baton_watch_event_receiver_enabled" == false ]] \
        || fail "duplicate key: $key"
      seen_baton_watch_event_receiver_enabled=true
      baton_watch_event_receiver_enabled="$value"
      ;;
    BATON_WATCH_EVENT_RECEIVER_BEARER_TOKEN)
      [[ "$seen_baton_watch_event_receiver_bearer_token" == false ]] \
        || fail "duplicate key: $key"
      seen_baton_watch_event_receiver_bearer_token=true
      baton_watch_event_receiver_bearer_token="$value"
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
  return 0
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

if [[ "$baton_watch_enabled" != "true" && "$baton_watch_enabled" != "false" ]]; then
  fail "BATON_WATCH_ENABLED must be exactly true or false"
fi
if [[ "$baton_watch_monitoring_enabled" != "true" \
  && "$baton_watch_monitoring_enabled" != "false" ]]; then
  fail "BATON_WATCH_MONITORING_ENABLED must be exactly true or false"
fi
if [[ "$baton_watch_event_receiver_enabled" != "true" \
  && "$baton_watch_event_receiver_enabled" != "false" ]]; then
  fail "BATON_WATCH_EVENT_RECEIVER_ENABLED must be exactly true or false"
fi
if [[ "$baton_watch_enabled" == "true" ]]; then
  [[ "$seen_baton_watch_base_url" == true ]] \
    || fail "BATON_WATCH_BASE_URL is required when WATCH is enabled"
  [[ "$seen_baton_watch_bearer_token" == true ]] \
    || fail "BATON_WATCH_BEARER_TOKEN is required when WATCH is enabled"
  [[ "$seen_baton_watch_source_namespace" == true ]] \
    || fail "BATON_WATCH_SOURCE_NAMESPACE is required when WATCH is enabled"
fi
if [[ "$baton_watch_event_receiver_enabled" == "true" ]]; then
  [[ "$seen_baton_watch_event_receiver_bearer_token" == true ]] \
    || fail "BATON_WATCH_EVENT_RECEIVER_BEARER_TOKEN is required when the WATCH event receiver is enabled"
  [[ "$seen_baton_watch_source_namespace" == true ]] \
    || fail "BATON_WATCH_SOURCE_NAMESPACE is required when the WATCH event receiver is enabled"
fi
if [[ -n "$baton_watch_base_url" \
  && ! "$baton_watch_base_url" =~ ^https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:[0-9]{1,5})?/?$ ]]; then
  fail "BATON_WATCH_BASE_URL must be an absolute HTTPS origin without user info, path, query, or fragment"
fi
if [[ -n "$baton_watch_bearer_token" ]]; then
  validate_secret BATON_WATCH_BEARER_TOKEN "$baton_watch_bearer_token"
fi
if [[ -n "$baton_watch_event_receiver_bearer_token" ]]; then
  validate_secret \
    BATON_WATCH_EVENT_RECEIVER_BEARER_TOKEN \
    "$baton_watch_event_receiver_bearer_token"
fi
if [[ -n "$baton_watch_source_namespace" \
  && ( ${#baton_watch_source_namespace} -gt 63 \
    || ! "$baton_watch_source_namespace" =~ ^[A-Za-z0-9._-]+$ ) ]]; then
  fail "BATON_WATCH_SOURCE_NAMESPACE must be 1-63 letters, digits, dots, underscores, or hyphens"
fi

secrets=(
  "$baton_db_password"
  "$baton_db_root_password"
  "$baton_workspace_creation_key"
  "$baton_workspace_recovery_key"
)
optional_secrets=(
  "$baton_watch_bearer_token"
  "$baton_watch_event_receiver_bearer_token"
)
for optional_secret in "${optional_secrets[@]}"; do
  if [[ -n "$optional_secret" ]]; then
    secrets+=("$optional_secret")
  fi
done
for ((left = 0; left < ${#secrets[@]}; left += 1)); do
  for ((right = left + 1; right < ${#secrets[@]}; right += 1)); do
    if [[ "${secrets[$left]}" == "${secrets[$right]}" ]]; then
      fail "production secrets must all be independently generated"
    fi
  done
done

printf '%s\n' "$env_file"
