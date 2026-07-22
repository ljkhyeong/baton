#!/usr/bin/env bash

set -Eeuo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
backup_dir="${BATON_BACKUP_DIR:-$script_dir/backups}"
state_dir="${BATON_BACKUP_STATE_DIR:-$backup_dir/.state}"
max_age_hours="${BATON_BACKUP_MAX_AGE_HOURS:-36}"
success_file="$state_dir/last-success"

if [[ ! "$max_age_hours" =~ ^[1-9][0-9]*$ ]]; then
  printf 'BATON_BACKUP_MAX_AGE_HOURS must be a positive integer: %s\n' "$max_age_hours" >&2
  exit 1
fi
if [[ ! -f "$success_file" || ! -r "$success_file" ]]; then
  printf 'No successful off-site backup state found: %s\n' "$success_file" >&2
  exit 1
fi

backup_epoch=""
backup_name=""
backup_hash=""
verified_time=""
success_remote=""
IFS= read -r backup_epoch < "$success_file" || true
backup_name="$(sed -n '2p' "$success_file")"
backup_hash="$(sed -n '3p' "$success_file")"
verified_time="$(sed -n '4p' "$success_file")"
success_remote="$(sed -n '5p' "$success_file")"
extra_line="$(sed -n '6p' "$success_file")"

if [[ ! "$backup_epoch" =~ ^[0-9]+$ \
  || ! "$backup_name" =~ ^baton-[0-9]{8}T[0-9]{6}Z-[A-Za-z0-9]+\.sql\.gz$ \
  || ! "$backup_hash" =~ ^[0-9a-f]{64}$ \
  || -z "$verified_time" \
  || -z "$success_remote" \
  || -n "$extra_line" ]]; then
  printf 'Successful backup state is invalid: %s\n' "$success_file" >&2
  exit 1
fi

now_epoch="$(date -u '+%s')"
age_seconds=$((now_epoch - backup_epoch))
max_age_seconds=$((max_age_hours * 60 * 60))
if [[ $age_seconds -lt 0 || $age_seconds -gt $max_age_seconds ]]; then
  printf 'Latest off-site backup is stale: snapshot=%s, verified=%s, remote=%s, age_seconds=%d\n' \
    "$backup_name" "$verified_time" "$success_remote" "$age_seconds" >&2
  exit 1
fi

printf 'Latest off-site backup is fresh: snapshot=%s, verified=%s, remote=%s, age_seconds=%d\n' \
  "$backup_name" "$verified_time" "$success_remote" "$age_seconds"
