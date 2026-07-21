#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

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
cleanup() {
  if [[ -n "$partial_path" ]]; then
    rm -f -- "$partial_path"
  fi
}
trap cleanup EXIT INT TERM

timestamp="$(date -u '+%Y%m%dT%H%M%SZ')"
partial_path="$(mktemp "$backup_dir/.baton-$timestamp.XXXXXX")"
unique_suffix="${partial_path##*.}"
backup_path="$backup_dir/baton-$timestamp-$unique_suffix.sql.gz"
compose=(docker compose --project-directory "$repo_root" --env-file "$env_file" -f "$compose_file")

"${compose[@]}" exec -T mysql sh -ec \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; export MYSQL_PWD; exec mysqldump --user=root --single-transaction --quick --routines --triggers --events --hex-blob --no-tablespaces --set-gtid-purged=OFF "$MYSQL_DATABASE"' \
  | gzip -c > "$partial_path"

gzip -t -- "$partial_path"
mv -- "$partial_path" "$backup_path"
partial_path=""
trap - EXIT INT TERM

printf 'Backup created: %s\n' "$backup_path"
