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

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(dirname -- "$script_dir")"
env_file="${BATON_PRODUCTION_ENV_FILE:-${BATON_ENV_FILE:-$repo_root/.env.production}}"
state_dir="${BATON_BACKUP_STATE_DIR:-}"
access_key_revocation_sql="$script_dir/sql/invalidate-restored-access-keys.sql"

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

if ! command -v flock >/dev/null 2>&1; then
  printf 'flock is required to exclude backup cycles during restore.\n' >&2
  exit 1
fi
if [[ ! -f "$access_key_revocation_sql" || ! -r "$access_key_revocation_sql" ]]; then
  printf 'Access-key revocation SQL is not readable: %s\n' "$access_key_revocation_sql" >&2
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
recovery_targets_path="$state_dir/last-restore-recovery-targets.tsv"
if [[ -L "$lock_file" || ( -e "$lock_file" && ( ! -f "$lock_file" || ! -O "$lock_file" ) ) ]]; then
  printf 'Backup lock must be a regular file owned by the service user: %s\n' "$lock_file" >&2
  exit 1
fi
if [[ -L "$recovery_targets_path" \
  || ( -e "$recovery_targets_path" \
    && ( ! -f "$recovery_targets_path" || ! -O "$recovery_targets_path" ) ) ]]; then
  printf 'Restore recovery targets must be a regular file owned by the service user: %s\n' \
    "$recovery_targets_path" >&2
  exit 1
fi
exec 9>"$lock_file"
if ! flock -n 9; then
  printf 'Restore refused because a backup cycle is running.\n' >&2
  exit 1
fi

if [[ ! -r "$env_file" ]]; then
  printf 'Production env file is not readable: %s\n' "$env_file" >&2
  exit 1
fi
if ! env_file="$("$script_dir/validate-production-env.sh" "$env_file")"; then
  exit 1
fi

# The restore owns the same canonical lifecycle lock that production Compose mutations use.
# shellcheck source=ops/production-lifecycle-lock.sh
source "$script_dir/production-lifecycle-lock.sh"
acquire_production_lifecycle_lock || exit $?

"$script_dir/verify-backup.sh" --require-checksum "$backup_path" >/dev/null

restore_sql_path="$(mktemp "${TMPDIR:-/tmp}/baton-restore.XXXXXX")"
compose_output_path="$(mktemp "${TMPDIR:-/tmp}/baton-restore-compose.XXXXXX")"
recovery_targets_tmp=""
cleanup() {
  rm -f -- "$restore_sql_path" "$compose_output_path"
  if [[ -n "$recovery_targets_tmp" ]]; then
    rm -f -- "$recovery_targets_tmp"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

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

compose=(
  env
  BATON_PRODUCTION_ENV_FILE="$env_file"
  BATON_PRODUCTION_LIFECYCLE_LOCK_FD="$BATON_PRODUCTION_LIFECYCLE_LOCK_FD"
  "$script_dir/production-compose.sh"
)
docker_command=(
  env
  -u DOCKER_HOST
  -u DOCKER_CONTEXT
  -u DOCKER_CONFIG
  -u DOCKER_TLS_VERIFY
  -u DOCKER_CERT_PATH
  -u DOCKER_API_VERSION
  -u DOCKER_DEFAULT_PLATFORM
  docker
  --host unix:///var/run/docker.sock
)

"${compose[@]}" ps --all -q app web round-web round-signaling > "$compose_output_path"
service_ids="$(< "$compose_output_path")"
unsafe_containers=""
while IFS= read -r container_id; do
  if [[ -z "$container_id" ]]; then
    continue
  fi
  container_state="$(
    "${docker_command[@]}" inspect --format '{{.Name}} {{.State.Status}}' "$container_id"
  )"
  if [[ "${container_state##* }" != "exited" ]]; then
    unsafe_containers+="${container_state#/}"$'\n'
  fi
done <<< "$service_ids"

if [[ -n "$unsafe_containers" ]]; then
  printf 'Restore refused unless app, web, and ROUND containers are stopped (exited). Unsafe state:\n%s' \
    "$unsafe_containers" >&2
  exit 1
fi

rm -f -- "$recovery_targets_path"

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

"${compose[@]}" exec -T mysql sh -ec '
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
  export MYSQL_PWD
  exec mysql --user=root --batch --skip-column-names "$MYSQL_DATABASE" --execute="
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name IN ('\''flyway_schema_history'\'', '\''teams'\'', '\''seasons'\'')
  "
' > "$compose_output_path"
restored_marker_count="$(< "$compose_output_path")"
if [[ "$restored_marker_count" != "3" ]]; then
  printf 'Restore failed BATON schema verification; expected 3 marker tables, found %s.\n' \
    "$restored_marker_count" >&2
  exit 1
fi

"${compose[@]}" exec -T mysql sh -ec '
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
  export MYSQL_PWD
  exec mysql --user=root --batch --skip-column-names "$MYSQL_DATABASE" --execute="
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = '\''teams'\''
      AND column_name IN (
        '\''last_access_key_change_idempotency_hash'\'',
        '\''version'\''
      )
  "
' > "$compose_output_path"
team_revision_column_count="$(< "$compose_output_path")"
case "$team_revision_column_count" in
  0)
    "${compose[@]}" exec -T mysql sh -ec '
      MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
      export MYSQL_PWD
      exec mysql --user=root --batch --skip-column-names "$MYSQL_DATABASE"
    ' > "$compose_output_path" <<'SQL'
START TRANSACTION;

UPDATE teams
SET access_key_hash = LOWER(HEX(RANDOM_BYTES(32)));

SELECT
    ROW_COUNT(),
    (SELECT COUNT(*) FROM teams),
    (
        SELECT COUNT(*)
        FROM teams
        WHERE access_key_hash NOT REGEXP '^[0-9a-f]{64}$'
    ),
    0;

COMMIT;
SQL
    access_key_revocation_result="$(< "$compose_output_path")"
    ;;
  2)
    "${compose[@]}" exec -T mysql sh -ec '
      MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
      export MYSQL_PWD
      exec mysql --user=root --batch --skip-column-names "$MYSQL_DATABASE" --execute="
        SELECT COUNT(*)
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = '\''access_key_change_history'\''
      "
    ' > "$compose_output_path"
    access_key_history_table_count="$(< "$compose_output_path")"
    if [[ "$access_key_history_table_count" != "1" ]]; then
      printf 'Restore found access-key revision columns without their history table.\n' >&2
      exit 1
    fi
    "${compose[@]}" exec -T mysql sh -ec '
      MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
      export MYSQL_PWD
      exec mysql --user=root --batch --skip-column-names "$MYSQL_DATABASE"
    ' < "$access_key_revocation_sql" > "$compose_output_path"
    access_key_revocation_result="$(< "$compose_output_path")"
    ;;
  *)
    printf 'Restore found an incomplete teams access-key revision schema: columns=%s.\n' \
      "$team_revision_column_count" >&2
    exit 1
    ;;
esac

access_key_revocation_result="${access_key_revocation_result//$'\r'/}"
if [[ "$access_key_revocation_result" == *$'\n'* ]]; then
  printf 'Restore received an unexpected multi-line access-key revocation result.\n' >&2
  exit 1
fi
IFS=$'\t' read -r \
  invalidated_access_key_count \
  restored_team_count \
  invalid_access_key_hash_count \
  retained_last_change_marker_count \
  unexpected_revocation_result \
  <<< "$access_key_revocation_result"
for numeric_result in \
  "$invalidated_access_key_count" \
  "$restored_team_count" \
  "$invalid_access_key_hash_count" \
  "$retained_last_change_marker_count"; do
  if [[ ! "$numeric_result" =~ ^[0-9]+$ ]]; then
    printf 'Restore received an invalid access-key revocation count: %s.\n' \
      "$access_key_revocation_result" >&2
    exit 1
  fi
done
if [[ -n "$unexpected_revocation_result" \
  || "$invalidated_access_key_count" != "$restored_team_count" \
  || "$invalid_access_key_hash_count" != "0" \
  || "$retained_last_change_marker_count" != "0" ]]; then
  printf 'Restore failed to invalidate every workspace access key: %s.\n' \
    "$access_key_revocation_result" >&2
  exit 1
fi

recovery_targets_tmp="$(mktemp "$state_dir/restore-recovery-targets.XXXXXX")"
chmod 600 "$recovery_targets_tmp"
"${compose[@]}" exec -T mysql sh -ec '
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
  export MYSQL_PWD
  exec mysql --user=root --batch --skip-column-names "$MYSQL_DATABASE" --execute="
    SELECT
      LOWER(BIN_TO_UUID(team.id)),
      LOWER(BIN_TO_UUID((
        SELECT season.id
        FROM seasons AS season
        WHERE season.team_id = team.id
        ORDER BY season.start_date DESC, season.id DESC
        LIMIT 1
      )))
    FROM teams AS team
    ORDER BY HEX(team.id)
  "
' > "$recovery_targets_tmp"

recovery_target_count=0
uuid_pattern='^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
while IFS=$'\t' read -r target_team_id target_season_id unexpected_target; do
  if [[ ! "$target_team_id" =~ $uuid_pattern \
    || ! "$target_season_id" =~ $uuid_pattern \
    || -n "$unexpected_target" ]]; then
    printf 'Restore produced an invalid workspace recovery target.\n' >&2
    exit 1
  fi
  recovery_target_count=$((recovery_target_count + 1))
done < "$recovery_targets_tmp"
if [[ "$recovery_target_count" != "$restored_team_count" ]]; then
  printf 'Restore expected %s workspace recovery targets, found %s.\n' \
    "$restored_team_count" "$recovery_target_count" >&2
  exit 1
fi

mv -f -- "$recovery_targets_tmp" "$recovery_targets_path"
recovery_targets_tmp=""
rm -f -- "$restore_sql_path"
trap - EXIT INT TERM
printf 'Restore completed from: %s\n' "$backup_path"
printf 'Invalidated workspace access keys: %s\n' "$invalidated_access_key_count"
printf 'Workspace recovery targets: %s\n' "$recovery_targets_path"
printf 'Old shared links cannot be reused. Recover each target before distributing new links.\n'
