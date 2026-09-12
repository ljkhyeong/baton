#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

defer_cycle_finalization=false
if [[ $# -gt 1 ]]; then
  printf 'Usage: %s [--defer-cycle-finalization]\n' "$0" >&2
  exit 1
fi
if [[ $# -eq 1 ]]; then
  if [[ "$1" != "--defer-cycle-finalization" ]]; then
    printf 'Usage: %s [--defer-cycle-finalization]\n' "$0" >&2
    exit 1
  fi
  defer_cycle_finalization=true
fi

temporary_path=""
cleanup() {
  if [[ -n "$temporary_path" ]]; then
    rm -f -- "$temporary_path"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

backup_epoch_from_name() {
  local backup_name="$1"
  local timestamp
  local formatted
  if [[ ! "$backup_name" =~ ^baton-([0-9]{8}T[0-9]{6}Z)-[A-Za-z0-9]+\.sql\.gz$ ]]; then
    printf 'Backup filename does not contain a valid UTC snapshot timestamp: %s\n' "$backup_name" >&2
    return 1
  fi
  timestamp="${BASH_REMATCH[1]}"
  formatted="${timestamp:0:4}-${timestamp:4:2}-${timestamp:6:2} ${timestamp:9:2}:${timestamp:11:2}:${timestamp:13:2} UTC"
  if date --version >/dev/null 2>&1; then
    date -u -d "$formatted" '+%s'
  elif date -j -u -f '%Y%m%dT%H%M%SZ' "$timestamp" '+%s' >/dev/null 2>&1; then
    date -j -u -f '%Y%m%dT%H%M%SZ' "$timestamp" '+%s'
  else
    printf 'Unable to parse backup UTC snapshot timestamp: %s\n' "$backup_name" >&2
    return 1
  fi
}

encryption_is_disabled() {
  case "${1:-}" in
    ""|false|False|FALSE|0|no|No|NO|off|Off|OFF)
      return 1
      ;;
    *)
      return 0
      ;;
  esac
}

verify_remote_objects() {
  local backup_path="$1"
  local backup_name="$2"

  "$script_dir/verify-backup.sh" --require-checksum "$backup_path" >/dev/null
  printf '%s\n' "$backup_name" "$backup_name.sha256" | \
    rclone check "$backup_dir" "$remote" --download --one-way --files-from-raw - \
      --retries 5 --retries-sleep 30s
}

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
backup_dir="${BATON_BACKUP_DIR:-$script_dir/backups}"
remote="${BATON_RCLONE_REMOTE:-}"
retention_days="${BATON_BACKUP_LOCAL_RETENTION_DAYS:-14}"
state_dir="${BATON_BACKUP_STATE_DIR:-$backup_dir/.state}"

case "$backup_dir" in
  /*) ;;
  *)
    printf 'BATON_BACKUP_DIR must be an absolute path: %s\n' "$backup_dir" >&2
    exit 1
    ;;
esac
if [[ "$backup_dir" == "/" ]]; then
  printf 'BATON_BACKUP_DIR must not be the filesystem root.\n' >&2
  exit 1
fi
if [[ ! -d "$backup_dir" ]]; then
  printf 'Backup directory does not exist: %s\n' "$backup_dir" >&2
  exit 1
fi

case "$state_dir" in
  /*) ;;
  *)
    printf 'BATON_BACKUP_STATE_DIR must be an absolute path: %s\n' "$state_dir" >&2
    exit 1
    ;;
esac
if [[ "$state_dir" == "/" ]]; then
  printf 'BATON_BACKUP_STATE_DIR must not be the filesystem root.\n' >&2
  exit 1
fi

if [[ ! "$remote" =~ ^[A-Za-z0-9_]+:.+ ]]; then
  printf 'BATON_RCLONE_REMOTE must use an environment-safe named remote and dedicated path, for example baton_crypt:daily.\n' >&2
  exit 1
fi
remote="${remote%/}"
remote_path="${remote#*:}"
if [[ -z "$remote_path" || "$remote_path" == "/" ]]; then
  printf 'BATON_RCLONE_REMOTE must not target a remote root.\n' >&2
  exit 1
fi

if [[ ! "$retention_days" =~ ^[1-9][0-9]*$ ]]; then
  printf 'BATON_BACKUP_LOCAL_RETENTION_DAYS must be a positive integer: %s\n' "$retention_days" >&2
  exit 1
fi
if ! command -v rclone >/dev/null 2>&1; then
  printf 'rclone is required for off-site backup synchronization.\n' >&2
  exit 1
fi

remote_root="${remote%%:*}:"
if ! rclone backend encode "$remote_root" baton-backup-probe >/dev/null 2>&1; then
  printf 'BATON_RCLONE_REMOTE must reference an rclone crypt remote: %s\n' "$remote_root" >&2
  exit 1
fi

remote_name="${remote%%:*}"
if ! crypt_config="$(rclone config redacted "$remote_name" 2>/dev/null)"; then
  printf 'Unable to inspect the rclone remote configuration; rclone 1.64 or newer is required.\n' >&2
  exit 1
fi
if ! printf '%s\n' "$crypt_config" | awk -F '=' '
  {
    key = $1
    value = $2
    gsub(/^[[:space:]]+|[[:space:]]+$/, "", key)
    gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
    if (tolower(key) == "type" && tolower(value) == "crypt") found = 1
  }
  END { exit !found }
'; then
  printf 'BATON_RCLONE_REMOTE must be configured as type=crypt: %s\n' "$remote_name" >&2
  exit 1
fi
configured_no_data_encryption="$(printf '%s\n' "$crypt_config" | awk -F '=' '
  {
    key = $1
    value = $2
    gsub(/^[[:space:]]+|[[:space:]]+$/, "", key)
    gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
    if (tolower(key) == "no_data_encryption") print value
  }
' | tail -n 1)"
remote_env_suffix="$(printf '%s' "$remote_name" | tr '[:lower:]' '[:upper:]')"
remote_no_data_env="RCLONE_CONFIG_${remote_env_suffix}_NO_DATA_ENCRYPTION"
if encryption_is_disabled "$configured_no_data_encryption" \
  || encryption_is_disabled "${RCLONE_CRYPT_NO_DATA_ENCRYPTION:-}" \
  || encryption_is_disabled "${RCLONE_NO_DATA_ENCRYPTION:-}" \
  || encryption_is_disabled "${!remote_no_data_env:-}"; then
  printf 'BATON backups require rclone crypt data encryption; no_data_encryption must be false.\n' >&2
  exit 1
fi

backups=("$backup_dir"/baton-*.sql.gz)

if [[ ! -f "${backups[0]}" ]]; then
  printf 'No BATON backups to synchronize in: %s\n' "$backup_dir"
  exit 0
fi

uploaded=0
already_verified=0
latest_backup_epoch=-1
latest_backup_name=""
latest_backup_hash=""
for backup_path in "${backups[@]}"; do
  "$script_dir/verify-backup.sh" --require-checksum "$backup_path" >/dev/null
  backup_name="$(basename -- "$backup_path")"
  checksum_path="$backup_path.sha256"
  verified_path="$backup_path.uploaded"
  checksum_line=""
  IFS= read -r checksum_line < "$checksum_path"
  expected_backup_hash="${checksum_line%% *}"

  backup_is_verified=false
  if [[ -f "$verified_path" && -r "$verified_path" ]]; then
    verified_hash=""
    verified_remote=""
    IFS= read -r verified_hash < "$verified_path" || true
    verified_remote="$(sed -n '2p' "$verified_path")"
    if [[ "$verified_hash" == "$expected_backup_hash" && "$verified_remote" == "$remote" ]]; then
      already_verified=$((already_verified + 1))
      backup_is_verified=true
    fi
  fi

  if [[ "$backup_is_verified" == false ]]; then
    rclone copyto "$backup_path" "$remote/$backup_name" --immutable --retries 5 --retries-sleep 30s
    rclone copyto "$checksum_path" "$remote/$backup_name.sha256" --immutable --retries 5 --retries-sleep 30s
    verify_remote_objects "$backup_path" "$backup_name"

    temporary_path="$(mktemp "$backup_dir/.baton-uploaded.XXXXXX")"
    printf '%s\n%s\n' "$expected_backup_hash" "$remote" > "$temporary_path"
    mv -- "$temporary_path" "$verified_path"
    temporary_path=""
    uploaded=$((uploaded + 1))
  fi

  backup_epoch="$(backup_epoch_from_name "$backup_name")"
  if [[ "$backup_epoch" -gt "$latest_backup_epoch" ]]; then
    latest_backup_epoch="$backup_epoch"
    latest_backup_name="$backup_name"
    latest_backup_hash="$expected_backup_hash"
  fi
done

minimum_local_backups=3
pruned=0
if [[ "$defer_cycle_finalization" == false ]]; then
  recent_start=0
  if [[ ${#backups[@]} -gt $minimum_local_backups ]]; then
    recent_start=$((${#backups[@]} - minimum_local_backups))
  fi
  recent_backups=("${backups[@]:recent_start}")

  while IFS= read -r -d '' expired_backup; do
    keep_backup=false
    for recent_backup in "${recent_backups[@]}"; do
      if [[ "$expired_backup" == "$recent_backup" ]]; then
        keep_backup=true
        break
      fi
    done
    if [[ "$keep_backup" == true ]]; then
      continue
    fi
    expired_name="$(basename -- "$expired_backup")"
    verify_remote_objects "$expired_backup" "$expired_name"
    rm -f -- "$expired_backup" "$expired_backup.sha256" "$expired_backup.uploaded"
    pruned=$((pruned + 1))
  done < <(find "$backup_dir" -maxdepth 1 -type f -name 'baton-*.sql.gz' -mtime "+$retention_days" -print0)
fi

if [[ $uploaded -gt 0 && "$defer_cycle_finalization" == false ]]; then
  mkdir -p -- "$state_dir"
  temporary_path="$(mktemp "$state_dir/.last-success.XXXXXX")"
  printf '%s\n%s\n%s\n%s\n%s\n' \
    "$latest_backup_epoch" \
    "$latest_backup_name" \
    "$latest_backup_hash" \
    "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
    "$remote" > "$temporary_path"
  mv -- "$temporary_path" "$state_dir/last-success"
  temporary_path=""
fi

printf 'Off-site backup synchronization completed: uploaded=%d, already_verified=%d, local_pruned=%d, remote=%s\n' \
  "$uploaded" "$already_verified" "$pruned" "$remote"

trap - EXIT INT TERM
