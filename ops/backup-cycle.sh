#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
backup_dir="${BATON_BACKUP_DIR:-$script_dir/backups}"
state_dir="${BATON_BACKUP_STATE_DIR:-}"

if ! command -v flock >/dev/null 2>&1; then
  printf 'flock is required to prevent overlapping backup cycles.\n' >&2
  exit 1
fi

if [[ -z "$state_dir" ]]; then
  printf 'BATON_BACKUP_STATE_DIR is required for the shared backup and restore lock.\n' >&2
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
if [[ -L "$state_dir" ]]; then
  printf 'BATON_BACKUP_STATE_DIR must not be a symbolic link: %s\n' "$state_dir" >&2
  exit 1
fi
mkdir -p -- "$state_dir"
if [[ ! -O "$state_dir" ]]; then
  printf 'BATON_BACKUP_STATE_DIR must be owned by the service user: %s\n' "$state_dir" >&2
  exit 1
fi
chmod 700 "$state_dir"
lock_file="$state_dir/backup-cycle.lock"
if [[ -L "$lock_file" || ( -e "$lock_file" && ( ! -f "$lock_file" || ! -O "$lock_file" ) ) ]]; then
  printf 'Backup lock must be a regular file owned by the service user: %s\n' "$lock_file" >&2
  exit 1
fi

exec 9>"$lock_file"
if ! flock -n 9; then
  printf 'Backup cycle is already running; this invocation will exit without starting another cycle.\n'
  exit 75
fi

retry_pending=false
for pending_backup in "$backup_dir"/baton-*.sql.gz; do
  if [[ ! -f "$pending_backup" ]]; then
    continue
  fi
  if [[ ! -f "$pending_backup.uploaded" ]]; then
    retry_pending=true
    break
  fi
done
if [[ "$retry_pending" == true ]]; then
  "$script_dir/sync-backups.sh" --defer-cycle-finalization
fi

backup_path="$("$script_dir/backup.sh" --print-path)"
printf 'Backup created for synchronization: %s\n' "$backup_path"
"$script_dir/sync-backups.sh"
