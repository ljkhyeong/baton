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

# 일일 주기보다 짧게 잡아 다음 날 주기는 새 덤프를 만들고, 같은 날 재시작은 덤프를 늘리지 않는다.
recent_dump_window_seconds=$((20 * 60 * 60))

# 복사로 수정 시각이 바뀔 수 있으므로 파일 이름의 UTC 스냅샷 시각으로 판단한다.
recent_verified_dump_exists() {
  local candidate
  local candidate_age
  local candidate_epoch
  local now_epoch

  now_epoch="$(date -u '+%s')"
  for candidate in "$backup_dir"/baton-*.sql.gz; do
    [[ -f "$candidate" ]] || continue
    candidate_epoch="$(
      production_validation_backup_epoch_from_name "$(basename -- "$candidate")"
    )" || continue
    candidate_age=$((now_epoch - candidate_epoch))
    if (( candidate_age >= 0 && candidate_age < recent_dump_window_seconds )) \
      && "$script_dir/verify-backup.sh" --require-checksum "$candidate" >/dev/null 2>&1; then
      return 0
    fi
  done
  return 1
}

lock_file="$state_dir/backup-cycle.lock"
production_validation_prepare_backup_state_dir fail "$state_dir" "$lock_file"

exec 9>"$lock_file"
if ! flock -n 9; then
  printf 'Backup cycle is already running; this invocation will exit without starting another cycle.\n'
  exit 75
fi

# 강제 종료가 남긴 임시 파일은 평문 덤프일 수 있으므로 잠금을 잡은 뒤 지운다. 쓰는 중인 파일은
# 수정 시각이 갱신되고 주기는 systemd 제한인 2시간 안에 끝나므로 3시간이 지난 파일만 잔존물로 본다.
if [[ -d "$backup_dir" ]]; then
  find "$backup_dir" -maxdepth 1 -type f -name '.baton-*' -mmin +180 -print -delete
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
retry_failed=false
if [[ "$retry_pending" == true ]] \
  && ! "$script_dir/sync-backups.sh" --defer-cycle-finalization; then
  retry_failed=true
  printf 'Retry of pending backups failed; the cycle will still end as failed.\n' >&2
fi

if [[ "$retry_failed" == true ]] && recent_verified_dump_exists; then
  printf 'A verified dump from the last 20 hours exists; skipping a new dump.\n' >&2
  exit 1
fi

backup_path="$("$script_dir/backup.sh" --print-path)"
printf 'Backup created for synchronization: %s\n' "$backup_path"
"$script_dir/sync-backups.sh"

if [[ "$retry_failed" == true ]]; then
  exit 1
fi

# 새 덤프의 원격 검증과 로컬 보존 정리가 끝난 뒤에만 완료 신호를 보낸다.
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
