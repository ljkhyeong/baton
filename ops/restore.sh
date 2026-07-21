#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

if [[ "${BATON_RESTORE_CONFIRM:-}" != "RESTORE_BATON_DATABASE" ]]; then
  printf 'Restore refused. Set BATON_RESTORE_CONFIRM=RESTORE_BATON_DATABASE to continue.\n' >&2
  exit 1
fi

if [[ $# -ne 1 || -z "$1" ]]; then
  printf 'Usage: BATON_RESTORE_CONFIRM=RESTORE_BATON_DATABASE %s /absolute/path/to/backup.sql.gz\n' "$0" >&2
  exit 1
fi

backup_path="$1"
case "$backup_path" in
  /*) ;;
  *)
    printf 'Backup path must be absolute: %s\n' "$backup_path" >&2
    exit 1
    ;;
esac

if [[ ! -f "$backup_path" || ! -r "$backup_path" ]]; then
  printf 'Backup file is not readable: %s\n' "$backup_path" >&2
  exit 1
fi

case "$backup_path" in
  *.sql.gz) ;;
  *)
    printf 'Backup file must end with .sql.gz: %s\n' "$backup_path" >&2
    exit 1
    ;;
esac

restore_sql_path="$(mktemp "${TMPDIR:-/tmp}/baton-restore.XXXXXX")"
cleanup() {
  rm -f -- "$restore_sql_path"
}
trap cleanup EXIT INT TERM

gzip -cd -- "$backup_path" > "$restore_sql_path"
if [[ ! -s "$restore_sql_path" ]]; then
  printf 'Restore refused because the backup contains no SQL.\n' >&2
  exit 1
fi

for marker in 'CREATE TABLE `flyway_schema_history`' 'CREATE TABLE `teams`' 'CREATE TABLE `seasons`'; do
  if ! grep -Fq -- "$marker" "$restore_sql_path"; then
    printf 'Restore refused because the backup is missing BATON schema marker: %s\n' "$marker" >&2
    exit 1
  fi
done

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(dirname -- "$script_dir")"
compose_file="$repo_root/compose.production.yml"
env_file="${BATON_ENV_FILE:-$repo_root/.env.production}"

if [[ ! -r "$env_file" ]]; then
  printf 'Production env file is not readable: %s\n' "$env_file" >&2
  exit 1
fi

compose=(docker compose --project-directory "$repo_root" --env-file "$env_file" -f "$compose_file")

service_ids="$("${compose[@]}" ps --all -q app web)"
unsafe_containers=""
while IFS= read -r container_id; do
  if [[ -z "$container_id" ]]; then
    continue
  fi
  container_state="$(docker inspect --format '{{.Name}} {{.State.Status}}' "$container_id")"
  if [[ "${container_state##* }" != "exited" ]]; then
    unsafe_containers+="${container_state#/}"$'\n'
  fi
done <<< "$service_ids"

if [[ -n "$unsafe_containers" ]]; then
  printf 'Restore refused unless app and web containers are stopped (exited). Unsafe state:\n%s' \
    "$unsafe_containers" >&2
  exit 1
fi

"${compose[@]}" exec -T mysql sh -ec '
  case "$MYSQL_DATABASE" in
    ""|*[!A-Za-z0-9_]*)
      printf "Unsafe MYSQL_DATABASE identifier: %s\n" "$MYSQL_DATABASE" >&2
      exit 1
      ;;
  esac
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
  export MYSQL_PWD
  exec mysql --user=root --execute="DROP DATABASE IF EXISTS \`$MYSQL_DATABASE\`; CREATE DATABASE \`$MYSQL_DATABASE\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
'

"${compose[@]}" exec -T mysql sh -ec \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; export MYSQL_PWD; exec mysql --user=root "$MYSQL_DATABASE"' \
  < "$restore_sql_path"

restored_marker_count="$("${compose[@]}" exec -T mysql sh -ec '
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
  export MYSQL_PWD
  exec mysql --user=root --batch --skip-column-names "$MYSQL_DATABASE" --execute="
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name IN ('\''flyway_schema_history'\'', '\''teams'\'', '\''seasons'\'')
  "
')"
if [[ "$restored_marker_count" != "3" ]]; then
  printf 'Restore failed BATON schema verification; expected 3 marker tables, found %s.\n' \
    "$restored_marker_count" >&2
  exit 1
fi

rm -f -- "$restore_sql_path"
trap - EXIT INT TERM
printf 'Restore completed from: %s\n' "$backup_path"
