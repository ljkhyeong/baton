#!/usr/bin/env bash

set -Eeuo pipefail

state_dir="${BATON_BACKUP_STATE_DIR:-}"
max_age_hours="${BATON_BACKUP_MAX_AGE_HOURS:-36}"
success_file="$state_dir/last-success"

backup_epoch_from_name() {
  local backup_name="$1"
  local timestamp
  local formatted

  if [[ ! "$backup_name" =~ ^baton-([0-9]{8}T[0-9]{6}Z)-[A-Za-z0-9]+\.sql\.gz$ ]]; then
    return 1
  fi
  timestamp="${BASH_REMATCH[1]}"
  formatted="${timestamp:0:4}-${timestamp:4:2}-${timestamp:6:2} ${timestamp:9:2}:${timestamp:11:2}:${timestamp:13:2} UTC"
  if date --version >/dev/null 2>&1; then
    date -u -d "$formatted" '+%s'
  elif date -j -u -f '%Y%m%dT%H%M%SZ' "$timestamp" '+%s' >/dev/null 2>&1; then
    date -j -u -f '%Y%m%dT%H%M%SZ' "$timestamp" '+%s'
  else
    return 1
  fi
}

verified_epoch_from_time() {
  local verified_time="$1"

  if [[ ! "$verified_time" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]]; then
    return 1
  fi
  if date --version >/dev/null 2>&1; then
    date -u -d "$verified_time" '+%s'
  elif date -j -u -f '%Y-%m-%dT%H:%M:%SZ' "$verified_time" '+%s' >/dev/null 2>&1; then
    date -j -u -f '%Y-%m-%dT%H:%M:%SZ' "$verified_time" '+%s'
  else
    return 1
  fi
}

permission_mode() {
  local target="$1"
  local mode

  if mode="$(stat -f '%Lp' "$target" 2>/dev/null)"; then
    :
  elif mode="$(stat -c '%a' "$target" 2>/dev/null)"; then
    :
  else
    return 1
  fi
  [[ "$mode" =~ ^[0-7]{3,4}$ ]] || return 1
  printf '%s\n' "$mode"
}

if [[ ! "$max_age_hours" =~ ^[1-9][0-9]{0,3}$ ]] \
  || (( 10#$max_age_hours > 8760 )); then
  printf 'BATON_BACKUP_MAX_AGE_HOURS must be an integer from 1 to 8760: %s\n' \
    "$max_age_hours" >&2
  exit 1
fi
case "$state_dir" in
  /*) ;;
  *)
    printf 'BATON_BACKUP_STATE_DIR must be an absolute path: %s\n' "$state_dir" >&2
    exit 1
    ;;
esac
if [[ "$state_dir" == "/" \
  || -L "$state_dir" \
  || ! -d "$state_dir" \
  || ! -r "$state_dir" \
  || ! -x "$state_dir" \
  || ! -O "$state_dir" ]]; then
  printf 'BATON_BACKUP_STATE_DIR must be an owner-readable directory, not root or a symlink: %s\n' \
    "$state_dir" >&2
  exit 1
fi
if ! state_dir_mode="$(permission_mode "$state_dir")"; then
  printf 'BATON_BACKUP_STATE_DIR permissions could not be inspected: %s\n' \
    "$state_dir" >&2
  exit 1
fi
if (( (8#$state_dir_mode & 077) != 0 )); then
  printf 'BATON_BACKUP_STATE_DIR must not grant group or other permissions: %s\n' \
    "$state_dir" >&2
  exit 1
fi
if [[ -L "$success_file" || ! -f "$success_file" || ! -r "$success_file" || ! -O "$success_file" ]]; then
  printf 'No successful off-site backup state found: %s\n' "$success_file" >&2
  exit 1
fi
if ! success_file_mode="$(permission_mode "$success_file")"; then
  printf 'Successful backup state permissions could not be inspected: %s\n' \
    "$success_file" >&2
  exit 1
fi
if (( (8#$success_file_mode & 077) != 0 )); then
  printf 'Successful backup state must not grant group or other permissions: %s\n' \
    "$success_file" >&2
  exit 1
fi

backup_epoch=""
backup_name=""
backup_hash=""
verified_time=""
success_remote=""
name_epoch=""
verified_epoch=""
extra_line=""
has_extra_line=false
if ! exec 3< "$success_file"; then
  printf 'Successful backup state could not be opened: %s\n' "$success_file" >&2
  exit 1
fi
IFS= read -r backup_epoch <&3 || true
IFS= read -r backup_name <&3 || true
IFS= read -r backup_hash <&3 || true
IFS= read -r verified_time <&3 || true
IFS= read -r success_remote <&3 || true
if IFS= read -r extra_line <&3 || [[ -n "$extra_line" ]]; then
  has_extra_line=true
fi
exec 3<&-

if [[ ! "$backup_epoch" =~ ^[0-9]{1,11}$ \
  || ! "$backup_hash" =~ ^[0-9a-f]{64}$ \
  || ! "$success_remote" =~ ^[A-Za-z0-9_]+:[^[:space:]]+$ \
  || "$has_extra_line" == true ]]; then
  printf 'Successful backup state is invalid: %s\n' "$success_file" >&2
  exit 1
fi
if ! name_epoch="$(backup_epoch_from_name "$backup_name")" \
  || [[ "$name_epoch" != "$backup_epoch" ]]; then
  printf 'Successful backup state timestamp does not match its snapshot name: %s\n' \
    "$success_file" >&2
  exit 1
fi
if ! verified_epoch="$(verified_epoch_from_time "$verified_time")"; then
  printf 'Successful backup verification time is invalid: %s\n' "$success_file" >&2
  exit 1
fi

now_epoch="$(date -u '+%s')"
if [[ "$verified_epoch" -lt "$backup_epoch" || "$verified_epoch" -gt "$now_epoch" ]]; then
  printf 'Successful backup verification time is outside the valid snapshot interval: %s\n' \
    "$success_file" >&2
  exit 1
fi
age_seconds=$((now_epoch - backup_epoch))
max_age_seconds=$((max_age_hours * 60 * 60))
if [[ $age_seconds -lt 0 || $age_seconds -gt $max_age_seconds ]]; then
  printf 'Latest off-site backup is stale: snapshot=%s, verified=%s, remote=%s, age_seconds=%d\n' \
    "$backup_name" "$verified_time" "$success_remote" "$age_seconds" >&2
  exit 1
fi

printf 'Latest off-site backup is fresh: snapshot=%s, verified=%s, remote=%s, age_seconds=%d\n' \
  "$backup_name" "$verified_time" "$success_remote" "$age_seconds"
