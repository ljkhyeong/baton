#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

print_path_only=false
if [[ $# -gt 1 ]]; then
  printf 'Usage: %s [--print-path]\n' "$0" >&2
  exit 1
fi
if [[ $# -eq 1 ]]; then
  if [[ "$1" != "--print-path" ]]; then
    printf 'Usage: %s [--print-path]\n' "$0" >&2
    exit 1
  fi
  print_path_only=true
fi

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(dirname -- "$script_dir")"
compose_file="$repo_root/compose.production.yml"
env_file="${BATON_ENV_FILE:-$repo_root/.env.production}"
backup_dir="${BATON_BACKUP_DIR:-$script_dir/backups}"

if [[ ! -r "$env_file" ]]; then
  printf 'Production env file is not readable: %s\n' "$env_file" >&2
  exit 1
fi

mkdir -p -- "$backup_dir"

partial_path=""
finalizing_path=""
cleanup() {
  if [[ -n "$partial_path" ]]; then
    rm -f -- "$partial_path" "$partial_path.sha256"
  fi
  if [[ -n "$finalizing_path" ]]; then
    rm -f -- "$finalizing_path" "$finalizing_path.sha256"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

timestamp="$(date -u '+%Y%m%dT%H%M%SZ')"
partial_path="$(mktemp "$backup_dir/.baton-$timestamp.XXXXXX")"
unique_suffix="${partial_path##*.}"
backup_path="$backup_dir/baton-$timestamp-$unique_suffix.sql.gz"
compose=(docker compose --project-directory "$repo_root" --env-file "$env_file" -f "$compose_file")

"${compose[@]}" exec -T mysql sh -ec \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; export MYSQL_PWD; exec mysqldump --user=root --single-transaction --quick --routines --triggers --events --hex-blob --no-tablespaces --set-gtid-purged=OFF "$MYSQL_DATABASE"' \
  | gzip -c > "$partial_path"

finalizing_path="$backup_path"
backup_name="$(basename -- "$backup_path")"
"$script_dir/verify-backup.sh" "$partial_path" >/dev/null
"$script_dir/verify-backup.sh" --write-checksum --require-checksum --checksum-name "$backup_name" "$partial_path" >/dev/null
mv -- "$partial_path.sha256" "$backup_path.sha256"
mv -- "$partial_path" "$backup_path"
partial_path=""
"$script_dir/verify-backup.sh" --require-checksum "$backup_path" >/dev/null
finalizing_path=""
trap - EXIT INT TERM

if [[ "$print_path_only" == true ]]; then
  printf '%s\n' "$backup_path"
else
  printf 'Backup created: %s\n' "$backup_path"
fi
