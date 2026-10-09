#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops/production-validation-common.sh
source "$script_dir/production-validation-common.sh"
backup_dir="${BATON_BACKUP_DIR:-$script_dir/backups}"
state_dir="${BATON_BACKUP_STATE_DIR:-}"

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

if ! command -v flock >/dev/null 2>&1; then
  printf 'flock is required to prevent overlapping backup cycles.\n' >&2
  exit 1
fi

lock_file="$state_dir/backup-cycle.lock"
production_validation_prepare_backup_state_dir fail "$state_dir" "$lock_file"

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

# 새 덤프의 원격 검증이 끝난 뒤에만 완료 신호를 보낸다.
heartbeat_url_file="${BATON_BACKUP_HEARTBEAT_URL_FILE:-}"
if [[ -n "$heartbeat_url_file" ]]; then
  heartbeat_url=""
  if [[ -r "$heartbeat_url_file" ]]; then
    IFS= read -r heartbeat_url < "$heartbeat_url_file" || true
  fi
  if [[ "$heartbeat_url" =~ ^https://uptime[.]betterstack[.]com/api/v1/heartbeat/[A-Za-z0-9_-]+$ ]] \
    && printf 'url = "%s"\n' "$heartbeat_url" | curl -q --config - \
      --proto '=https' --fail --silent --output /dev/null \
      --connect-timeout 5 --max-time 10 --retry 2 --retry-delay 2; then
    printf '백업 완료 신호를 외부 감시에 전달했습니다.\n'
  else
    # 통보 장애로 성공한 백업을 다시 만들지 않는다. 누락 알림은 외부 감시가 맡는다.
    printf '백업은 완료했지만 외부 감시에 완료 신호를 전달하지 못했습니다.\n' >&2
  fi
fi
