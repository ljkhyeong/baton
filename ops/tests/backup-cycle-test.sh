#!/usr/bin/env bash

set -Eeuo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(dirname -- "$(dirname -- "$script_dir")")"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/baton-backup-test.XXXXXX")"

cleanup() {
  rm -rf -- "$test_root"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

assert_file() {
  [[ -f "$1" ]] || fail "expected file: $1"
}

assert_no_file() {
  [[ ! -e "$1" ]] || fail "expected no file: $1"
}

assert_count() {
  local expected="$1"
  local actual="$2"
  local label="$3"
  [[ "$actual" == "$expected" ]] || fail "$label: expected=$expected actual=$actual"
}

write_valid_production_env() {
  local target="$1"

  printf '%s\n' \
    'BATON_HOST=baton.example.com' \
    'BATON_DB_NAME=baton' \
    'BATON_DB_USERNAME=baton' \
    'BATON_DB_PASSWORD=1111111111111111111111111111111111111111111111111111111111111111' \
    'BATON_DB_ROOT_PASSWORD=2222222222222222222222222222222222222222222222222222222222222222' \
    'BATON_WORKSPACE_CREATION_KEY=3333333333333333333333333333333333333333333333333333333333333333' \
    'BATON_WORKSPACE_RECOVERY_KEY=4444444444444444444444444444444444444444444444444444444444444444' \
    > "$target"
  chmod 600 "$target"
}

write_valid_dump() {
  local target="$1"
  printf '%s\n' \
    '-- BATON test dump' \
    'CREATE TABLE `flyway_schema_history` (`installed_rank` int);' \
    'CREATE TABLE `teams` (`id` binary(16));' \
    'CREATE TABLE `seasons` (`id` binary(16));' \
    | gzip -c > "$target"
}

write_valid_backup() {
  local target="$1"
  write_valid_dump "$target"
  "$repo_root/ops/verify-backup.sh" --write-checksum --require-checksum "$target" >/dev/null
}

fake_bin="$test_root/fakebin"
mkdir -p -- "$fake_bin"

cat > "$fake_bin/flock" <<'SCRIPT'
#!/usr/bin/env bash
exit "${FAKE_FLOCK_EXIT:-0}"
SCRIPT

cat > "$fake_bin/docker" <<'SCRIPT'
#!/usr/bin/env bash
if [[ -n "${FAKE_DOCKER_LOG:-}" ]]; then
  printf '%s\n' "$*" >> "$FAKE_DOCKER_LOG"
fi

if [[ "${FAKE_DOCKER_MODE:-valid}" == "restore" ]]; then
  docker_arguments="$*"
  if [[ "$docker_arguments" == *" ps --all -q app web"* ]]; then
    exit 0
  fi
  if [[ "$docker_arguments" == *"information_schema.tables"* \
    && "$docker_arguments" == *"access_key_change_history"* ]]; then
    printf '%s\n' '1'
    exit 0
  fi
  if [[ "$docker_arguments" == *"information_schema.tables"* ]]; then
    printf '%s\n' '3'
    exit 0
  fi
  if [[ "$docker_arguments" == *"information_schema.columns"* ]]; then
    printf '%s\n' "${FAKE_RESTORE_TEAM_REVISION_COLUMNS:-2}"
    exit 0
  fi
  if [[ "$docker_arguments" == *"BIN_TO_UUID"* ]]; then
    printf '%s\n' \
      $'11111111-1111-4111-8111-111111111111\t22222222-2222-4222-8222-222222222222' \
      $'33333333-3333-4333-8333-333333333333\t44444444-4444-4444-8444-444444444444'
    exit 0
  fi
  if [[ "$docker_arguments" == *"DROP DATABASE"* ]]; then
    exit 0
  fi
  if [[ "$docker_arguments" == *"--batch --skip-column-names"* \
    && "$docker_arguments" != *"--execute="* ]]; then
    if [[ -n "${FAKE_RESTORE_SQL_LOG:-}" ]]; then
      cat > "$FAKE_RESTORE_SQL_LOG"
    else
      cat >/dev/null
    fi
    printf '%b\n' "${FAKE_RESTORE_REVOCATION_RESULT:-2\\t2\\t0\\t0}"
    exit 0
  fi
  if [[ "$docker_arguments" == *" exec -T mysql "* ]]; then
    cat >/dev/null || true
    exit 0
  fi
  printf 'Unexpected fake restore docker command: %s\n' "$docker_arguments" >&2
  exit 46
fi

if [[ "${FAKE_DOCKER_MODE:-valid}" == "fail" ]]; then
  printf '%s\n' 'partial dump'
  exit 23
fi

printf '%s\n' \
  '-- BATON fake mysqldump' \
  'CREATE TABLE `flyway_schema_history` (`installed_rank` int);' \
  'CREATE TABLE `teams` (`id` binary(16));'

if [[ "${FAKE_DOCKER_MODE:-valid}" == "valid" ]]; then
  printf '%s\n' 'CREATE TABLE `seasons` (`id` binary(16));'
fi
SCRIPT

cat > "$fake_bin/mv" <<'SCRIPT'
#!/usr/bin/env bash
set -Eeuo pipefail

if [[ -n "${FAKE_MV_KILL_PARENT_AT:-}" ]]; then
  count=0
  if [[ -f "$FAKE_MV_COUNT_FILE" ]]; then
    IFS= read -r count < "$FAKE_MV_COUNT_FILE"
  fi
  count=$((count + 1))
  printf '%s\n' "$count" > "$FAKE_MV_COUNT_FILE"
  if [[ "$count" == "$FAKE_MV_KILL_PARENT_AT" ]]; then
    kill -KILL "$PPID"
    exit 137
  fi
fi

exec /bin/mv "$@"
SCRIPT

cat > "$fake_bin/rclone" <<'SCRIPT'
#!/usr/bin/env bash
set -Eeuo pipefail

command_name="${1:-}"
case "$command_name" in
  config)
    if [[ "${2:-}" != "redacted" ]]; then
      exit 45
    fi
    printf '%s\n' \
      '[fake]' \
      'type = crypt' \
      'remote = local:/encrypted-baton' \
      "no_data_encryption = ${FAKE_RCLONE_NO_DATA_ENCRYPTION:-false}"
    ;;
  backend)
    if [[ "${FAKE_RCLONE_NON_CRYPT:-0}" == "1" ]]; then
      exit 9
    fi
    exit 0
    ;;
  copyto)
    count=0
    if [[ -f "$FAKE_RCLONE_COUNT_FILE" ]]; then
      IFS= read -r count < "$FAKE_RCLONE_COUNT_FILE"
    fi
    count=$((count + 1))
    printf '%s\n' "$count" > "$FAKE_RCLONE_COUNT_FILE"
    if [[ "${FAKE_RCLONE_FAIL_AT:-0}" == "$count" ]]; then
      exit 42
    fi

    source_path="$2"
    destination="$3"
    remote_relative="${destination#*:}"
    remote_path="$FAKE_REMOTE_ROOT/$remote_relative"
    mkdir -p -- "$(dirname -- "$remote_path")"
    if [[ -e "$remote_path" ]]; then
      cmp -s -- "$source_path" "$remote_path" || exit 43
      exit 0
    fi
    cp -p -- "$source_path" "$remote_path"
    ;;
  cat)
    cat_count_file="${FAKE_RCLONE_CAT_COUNT_FILE:-$FAKE_RCLONE_COUNT_FILE.cat}"
    cat_count=0
    if [[ -f "$cat_count_file" ]]; then
      IFS= read -r cat_count < "$cat_count_file"
    fi
    cat_count=$((cat_count + 1))
    printf '%s\n' "$cat_count" > "$cat_count_file"
    if [[ "${FAKE_RCLONE_CORRUPT_CAT_AT:-0}" == "$cat_count" ]]; then
      printf '%s\n' 'corrupted remote readback'
      exit 0
    fi
    remote_relative="${2#*:}"
    cat -- "$FAKE_REMOTE_ROOT/$remote_relative"
    ;;
  *)
    printf 'Unexpected fake rclone command: %s\n' "$command_name" >&2
    exit 44
    ;;
esac
SCRIPT

chmod +x "$fake_bin/flock" "$fake_bin/docker" "$fake_bin/mv" "$fake_bin/rclone"

run_cycle() {
  local scenario_root="$1"
  PATH="$fake_bin:$PATH" \
  BATON_PRODUCTION_ENV_FILE="$scenario_root/production.env" \
  BATON_BACKUP_DIR="$scenario_root/backups" \
  BATON_BACKUP_STATE_DIR="$scenario_root/state" \
  BATON_BACKUP_LOCAL_RETENTION_DAYS=1 \
  BATON_RCLONE_REMOTE=fake:daily \
  FAKE_REMOTE_ROOT="$scenario_root/remote" \
  FAKE_RCLONE_COUNT_FILE="$scenario_root/rclone-count" \
  FAKE_RCLONE_CAT_COUNT_FILE="$scenario_root/rclone-cat-count" \
  FAKE_MV_COUNT_FILE="$scenario_root/mv-count" \
  "$repo_root/ops/backup-cycle.sh"
}

success_root="$test_root/success"
mkdir -p -- "$success_root/backups" "$success_root/remote"
write_valid_production_env "$success_root/production.env"
for day in 01 02 03 04; do
  backup="$success_root/backups/baton-202001${day}T000000Z-old${day}.sql.gz"
  write_valid_backup "$backup"
  touch -t "202001${day}0000" "$backup"
done
printf 'keep me\n' > "$success_root/backups/unrelated.txt"
touch -t 202001010000 "$success_root/backups/unrelated.txt"

run_cycle "$success_root" >/dev/null

local_backup_count="$(find "$success_root/backups" -maxdepth 1 -type f -name 'baton-*.sql.gz' | wc -l | tr -d ' ')"
remote_backup_count="$(find "$success_root/remote/daily" -maxdepth 1 -type f -name 'baton-*.sql.gz' | wc -l | tr -d ' ')"
remote_checksum_count="$(find "$success_root/remote/daily" -maxdepth 1 -type f -name 'baton-*.sql.gz.sha256' | wc -l | tr -d ' ')"
local_verified_count="$(find "$success_root/backups" -maxdepth 1 -type f -name 'baton-*.sql.gz.uploaded' | wc -l | tr -d ' ')"
local_checksum_count="$(find "$success_root/backups" -maxdepth 1 -type f -name 'baton-*.sql.gz.sha256' | wc -l | tr -d ' ')"
assert_count 3 "$local_backup_count" 'minimum local backup retention'
assert_count 3 "$local_verified_count" 'local verified marker count'
assert_count 3 "$local_checksum_count" 'local checksum retention count'
assert_count 5 "$remote_backup_count" 'append-only remote backup count'
assert_count 5 "$remote_checksum_count" 'remote checksum count'
assert_file "$success_root/backups/unrelated.txt"
assert_file "$success_root/state/last-success"
assert_no_file "$success_root/backups/baton-20200101T000000Z-old01.sql.gz"
assert_no_file "$success_root/backups/baton-20200102T000000Z-old02.sql.gz"
assert_no_file "$success_root/backups/baton-20200101T000000Z-old01.sql.gz.sha256"
assert_no_file "$success_root/backups/baton-20200101T000000Z-old01.sql.gz.uploaded"
assert_no_file "$success_root/backups/baton-20200102T000000Z-old02.sql.gz.sha256"
assert_no_file "$success_root/backups/baton-20200102T000000Z-old02.sql.gz.uploaded"
assert_file "$success_root/backups/baton-20200103T000000Z-old03.sql.gz"
assert_file "$success_root/backups/baton-20200104T000000Z-old04.sql.gz"

new_backup="$(find "$success_root/backups" -maxdepth 1 -type f -name 'baton-*.sql.gz' ! -name '*-old*.sql.gz' -print)"
assert_file "$new_backup"
state_backup_name="$(sed -n '2p' "$success_root/state/last-success")"
[[ "$state_backup_name" == "$(basename -- "$new_backup")" ]] \
  || fail 'freshness state does not identify the newest snapshot'

for backup in "$success_root"/backups/baton-*.sql.gz; do
  "$repo_root/ops/verify-backup.sh" --require-checksum "$backup" >/dev/null
done

PATH="$fake_bin:$PATH" \
BATON_BACKUP_STATE_DIR="$success_root/state" \
BATON_BACKUP_MAX_AGE_HOURS=36 \
"$repo_root/ops/check-backup-freshness.sh" >/dev/null

invalid_root="$test_root/invalid-schema"
mkdir -p -- "$invalid_root/backups" "$invalid_root/remote"
write_valid_production_env "$invalid_root/production.env"
if FAKE_DOCKER_MODE=invalid run_cycle "$invalid_root" >/dev/null 2>&1; then
  fail 'invalid schema backup unexpectedly succeeded'
fi
invalid_backup_count="$(find "$invalid_root/backups" -maxdepth 1 -type f | wc -l | tr -d ' ')"
invalid_remote_count="$(find "$invalid_root/remote" -type f | wc -l | tr -d ' ')"
assert_count 0 "$invalid_backup_count" 'invalid schema local files'
assert_count 0 "$invalid_remote_count" 'invalid schema remote files'

dump_failure_root="$test_root/dump-failure"
mkdir -p -- "$dump_failure_root/backups" "$dump_failure_root/remote"
write_valid_production_env "$dump_failure_root/production.env"
if FAKE_DOCKER_MODE=fail run_cycle "$dump_failure_root" >/dev/null 2>&1; then
  fail 'interrupted database dump unexpectedly succeeded'
fi
dump_failure_local_count="$(find "$dump_failure_root/backups" -maxdepth 1 -type f | wc -l | tr -d ' ')"
dump_failure_remote_count="$(find "$dump_failure_root/remote" -type f | wc -l | tr -d ' ')"
assert_count 0 "$dump_failure_local_count" 'interrupted dump local files'
assert_count 0 "$dump_failure_remote_count" 'interrupted dump remote files'

crash_publish_root="$test_root/crash-publish"
mkdir -p -- "$crash_publish_root/backups" "$crash_publish_root/remote"
write_valid_production_env "$crash_publish_root/production.env"
if FAKE_MV_KILL_PARENT_AT=3 run_cycle "$crash_publish_root" >/dev/null 2>&1; then
  fail 'forced crash before body publication unexpectedly succeeded'
fi
crash_published_body_count="$(find "$crash_publish_root/backups" -maxdepth 1 -type f -name 'baton-*.sql.gz' | wc -l | tr -d ' ')"
crash_published_sidecar_count="$(find "$crash_publish_root/backups" -maxdepth 1 -type f -name 'baton-*.sql.gz.sha256' | wc -l | tr -d ' ')"
assert_count 0 "$crash_published_body_count" 'crash-safe body publication'
assert_count 1 "$crash_published_sidecar_count" 'crash-safe orphan sidecar'
run_cycle "$crash_publish_root" >/dev/null
assert_file "$crash_publish_root/state/last-success"

direct_backup_root="$test_root/direct-backup"
mkdir -p -- "$direct_backup_root/backups"
write_valid_production_env "$direct_backup_root/production.env"
direct_backup_path="$(
  PATH="$fake_bin:$PATH" \
  BATON_PRODUCTION_ENV_FILE="$direct_backup_root/production.env" \
  BATON_BACKUP_DIR="$direct_backup_root/backups" \
  "$repo_root/ops/backup.sh" --print-path
)"
assert_file "$direct_backup_path"
assert_file "$direct_backup_path.sha256"
"$repo_root/ops/verify-backup.sh" --require-checksum "$direct_backup_path" >/dev/null

invalid_env_backup_root="$test_root/direct-backup-invalid-env"
mkdir -p -- "$invalid_env_backup_root/backups"
write_valid_production_env "$invalid_env_backup_root/production.env"
printf '%s\n' 'BATON_HTTP_PUBLISH=127.0.0.1:80' \
  >> "$invalid_env_backup_root/production.env"

invalid_env_docker_log="$invalid_env_backup_root/docker.log"
if invalid_env_backup_output="$(
  PATH="$fake_bin:$PATH" \
  BATON_PRODUCTION_ENV_FILE="$invalid_env_backup_root/production.env" \
  BATON_BACKUP_DIR="$invalid_env_backup_root/backups" \
  FAKE_DOCKER_LOG="$invalid_env_docker_log" \
  "$repo_root/ops/backup.sh" --print-path 2>&1
)"; then
  fail 'backup with invalid production environment unexpectedly succeeded'
fi
[[ "$invalid_env_backup_output" == *'unknown or unsafe production environment key'* ]] \
  || fail 'invalid production environment failure reason was missing'
assert_no_file "$invalid_env_docker_log"
invalid_env_backup_artifact_count="$(
  find "$invalid_env_backup_root/backups" -mindepth 1 -maxdepth 1 -print \
    | wc -l | tr -d ' '
)"
assert_count 0 "$invalid_env_backup_artifact_count" \
  'invalid production environment backup artifacts'

missing_checksum_root="$test_root/missing-checksum-restore"
mkdir -p -- "$missing_checksum_root"
missing_checksum_backup="$missing_checksum_root/baton-20200101T000000Z-missing.sql.gz"
write_valid_dump "$missing_checksum_backup"
if PATH="$fake_bin:$PATH" \
  BATON_RESTORE_CONFIRM=RESTORE_BATON_DATABASE \
  BATON_BACKUP_STATE_DIR="$missing_checksum_root/state" \
  "$repo_root/ops/restore.sh" "$missing_checksum_backup" >/dev/null 2>&1; then
  fail 'restore without checksum unexpectedly succeeded'
fi

restore_security_root="$test_root/restore-security"
mkdir -p -- "$restore_security_root"
write_valid_production_env "$restore_security_root/production.env"
restore_security_backup="$restore_security_root/baton-20200101T000000Z-restore.sql.gz"
write_valid_backup "$restore_security_backup"
restore_security_docker_log="$restore_security_root/docker.log"
restore_security_output="$(
  PATH="$fake_bin:$PATH" \
  BATON_PRODUCTION_ENV_FILE="$restore_security_root/production.env" \
  BATON_RESTORE_CONFIRM=RESTORE_BATON_DATABASE \
  BATON_BACKUP_STATE_DIR="$restore_security_root/state" \
  FAKE_DOCKER_LOG="$restore_security_docker_log" \
  FAKE_DOCKER_MODE=restore \
  FAKE_RESTORE_SQL_LOG="$restore_security_root/revocation.sql" \
  "$repo_root/ops/restore.sh" "$restore_security_backup"
)"
[[ "$restore_security_output" == *'Invalidated workspace access keys: 2'* ]] \
  || fail 'restore did not report invalidated workspace access keys'
[[ "$restore_security_output" == *'Old shared links cannot be reused'* ]] \
  || fail 'restore did not report the shared-link recovery boundary'
restore_recovery_targets="$restore_security_root/state/last-restore-recovery-targets.tsv"
assert_file "$restore_recovery_targets"
assert_count 2 "$(wc -l < "$restore_recovery_targets" | tr -d ' ')" \
  'restore recovery target count'
grep -Fq '11111111-1111-4111-8111-111111111111' "$restore_recovery_targets" \
  || fail 'first restore recovery target was missing'
grep -Fq '33333333-3333-4333-8333-333333333333' "$restore_recovery_targets" \
  || fail 'second restore recovery target was missing'
grep -Fq 'ORDER BY season.start_date DESC, season.id DESC' "$restore_security_docker_log" \
  || fail 'restore did not select the latest season as the recovery target'
grep -Fq 'RANDOM_BYTES(32)' "$restore_security_root/revocation.sql" \
  || fail 'restore did not use a cryptographically random access-key revocation value'
grep -Fq 'last_access_key_change_idempotency_hash = NULL' \
  "$restore_security_root/revocation.sql" \
  || fail 'restore did not expire prior access-key change replays'
grep -Fq 'version = version + 1' "$restore_security_root/revocation.sql" \
  || fail 'restore did not advance the team optimistic-lock version'

restore_count_mismatch_root="$test_root/restore-count-mismatch"
mkdir -p -- "$restore_count_mismatch_root/state"
write_valid_production_env "$restore_count_mismatch_root/production.env"
restore_count_mismatch_backup="$restore_count_mismatch_root/baton-20200101T000000Z-mismatch.sql.gz"
write_valid_backup "$restore_count_mismatch_backup"
printf '%s\n' 'stale recovery target' \
  > "$restore_count_mismatch_root/state/last-restore-recovery-targets.tsv"
if PATH="$fake_bin:$PATH" \
  BATON_PRODUCTION_ENV_FILE="$restore_count_mismatch_root/production.env" \
  BATON_RESTORE_CONFIRM=RESTORE_BATON_DATABASE \
  BATON_BACKUP_STATE_DIR="$restore_count_mismatch_root/state" \
  FAKE_DOCKER_MODE=restore \
  FAKE_RESTORE_REVOCATION_RESULT='1\t2\t0\t0' \
  "$repo_root/ops/restore.sh" "$restore_count_mismatch_backup" >/dev/null 2>&1; then
  fail 'restore with incomplete access-key invalidation unexpectedly succeeded'
fi
assert_no_file "$restore_count_mismatch_root/state/last-restore-recovery-targets.tsv"

legacy_restore_root="$test_root/legacy-restore-security"
mkdir -p -- "$legacy_restore_root"
write_valid_production_env "$legacy_restore_root/production.env"
legacy_restore_backup="$legacy_restore_root/baton-20200101T000000Z-legacy.sql.gz"
write_valid_backup "$legacy_restore_backup"
PATH="$fake_bin:$PATH" \
BATON_PRODUCTION_ENV_FILE="$legacy_restore_root/production.env" \
BATON_RESTORE_CONFIRM=RESTORE_BATON_DATABASE \
BATON_BACKUP_STATE_DIR="$legacy_restore_root/state" \
FAKE_DOCKER_MODE=restore \
FAKE_RESTORE_TEAM_REVISION_COLUMNS=0 \
FAKE_RESTORE_SQL_LOG="$legacy_restore_root/revocation.sql" \
"$repo_root/ops/restore.sh" "$legacy_restore_backup" >/dev/null
grep -Fq 'RANDOM_BYTES(32)' "$legacy_restore_root/revocation.sql" \
  || fail 'legacy restore did not invalidate existing access keys'
if grep -Fq 'last_access_key_change_idempotency_hash' "$legacy_restore_root/revocation.sql"; then
  fail 'legacy restore referenced access-key revision columns before migration'
fi

stale_retry_root="$test_root/stale-retry"
mkdir -p -- "$stale_retry_root/backups" "$stale_retry_root/remote"
write_valid_production_env "$stale_retry_root/production.env"
for day in 01 02 03 04; do
  stale_retry_backup="$stale_retry_root/backups/baton-202001${day}T000000Z-pending${day}.sql.gz"
  write_valid_backup "$stale_retry_backup"
  touch -t "202001${day}0000" "$stale_retry_backup"
done
if FAKE_DOCKER_MODE=invalid run_cycle "$stale_retry_root" >/dev/null 2>&1; then
  fail 'new invalid dump after pending retry unexpectedly succeeded'
fi
stale_retry_backup_count="$(find "$stale_retry_root/backups" -maxdepth 1 -type f -name 'baton-*.sql.gz' | wc -l | tr -d ' ')"
stale_retry_checksum_count="$(find "$stale_retry_root/backups" -maxdepth 1 -type f -name 'baton-*.sql.gz.sha256' | wc -l | tr -d ' ')"
stale_retry_verified_count="$(find "$stale_retry_root/backups" -maxdepth 1 -type f -name 'baton-*.sql.gz.uploaded' | wc -l | tr -d ' ')"
assert_count 4 "$stale_retry_backup_count" 'deferred retry backup retention'
assert_count 4 "$stale_retry_checksum_count" 'deferred retry checksum retention'
assert_count 4 "$stale_retry_verified_count" 'deferred retry verified marker retention'
assert_no_file "$stale_retry_root/state/last-success"

stale_sync_root="$test_root/stale-sync"
mkdir -p -- "$stale_sync_root/backups" "$stale_sync_root/remote"
stale_sync_backup="$stale_sync_root/backups/baton-20200101T000000Z-stale.sql.gz"
write_valid_backup "$stale_sync_backup"
# A copied old dump can have a fresh mtime; freshness must still use the UTC filename.
touch "$stale_sync_backup"
PATH="$fake_bin:$PATH" \
BATON_BACKUP_DIR="$stale_sync_root/backups" \
BATON_BACKUP_STATE_DIR="$stale_sync_root/state" \
BATON_BACKUP_LOCAL_RETENTION_DAYS=14 \
BATON_RCLONE_REMOTE=fake:daily \
FAKE_REMOTE_ROOT="$stale_sync_root/remote" \
FAKE_RCLONE_COUNT_FILE="$stale_sync_root/rclone-count" \
FAKE_RCLONE_CAT_COUNT_FILE="$stale_sync_root/rclone-cat-count" \
"$repo_root/ops/sync-backups.sh" >/dev/null
if PATH="$fake_bin:$PATH" \
  BATON_BACKUP_STATE_DIR="$stale_sync_root/state" \
  BATON_BACKUP_MAX_AGE_HOURS=36 \
  "$repo_root/ops/check-backup-freshness.sh" >/dev/null 2>&1; then
  fail 'old snapshot upload unexpectedly passed freshness check'
fi

prune_audit_root="$test_root/prune-audit-failure"
mkdir -p -- "$prune_audit_root/backups" "$prune_audit_root/remote"
write_valid_production_env "$prune_audit_root/production.env"
for day in 01 02 03 04; do
  prune_audit_backup="$prune_audit_root/backups/baton-202001${day}T000000Z-pending${day}.sql.gz"
  write_valid_backup "$prune_audit_backup"
  touch -t "202001${day}0000" "$prune_audit_backup"
done
if FAKE_RCLONE_CORRUPT_CAT_AT=11 run_cycle "$prune_audit_root" >/dev/null 2>&1; then
  fail 'remote prune audit mismatch unexpectedly succeeded'
fi
prune_audit_backup_count="$(find "$prune_audit_root/backups" -maxdepth 1 -type f -name 'baton-*.sql.gz' | wc -l | tr -d ' ')"
assert_count 5 "$prune_audit_backup_count" 'failed remote prune audit local snapshots'
assert_no_file "$prune_audit_root/state/last-success"

failure_root="$test_root/upload-failure"
mkdir -p -- "$failure_root/backups" "$failure_root/remote"
write_valid_production_env "$failure_root/production.env"
for day in 01 02 03 04; do
  failure_backup="$failure_root/backups/baton-202001${day}T000000Z-pending${day}.sql.gz"
  write_valid_backup "$failure_backup"
  touch -t "202001${day}0000" "$failure_backup"
done
failure_backup="$failure_root/backups/baton-20200101T000000Z-pending01.sql.gz"
if FAKE_RCLONE_FAIL_AT=2 run_cycle "$failure_root" >/dev/null 2>&1; then
  fail 'checksum upload failure unexpectedly succeeded'
fi
assert_file "$failure_backup"
assert_file "$failure_backup.sha256"
assert_no_file "$failure_backup.uploaded"
assert_file "$failure_root/remote/daily/$(basename -- "$failure_backup")"
assert_no_file "$failure_root/remote/daily/$(basename -- "$failure_backup").sha256"
assert_no_file "$failure_root/state/last-success"
failure_backup_count="$(find "$failure_root/backups" -maxdepth 1 -type f -name 'baton-*.sql.gz' | wc -l | tr -d ' ')"
assert_count 4 "$failure_backup_count" 'failed upload must not run local retention'

new_upload_failure_root="$test_root/new-upload-failure"
mkdir -p -- "$new_upload_failure_root/backups" "$new_upload_failure_root/remote"
write_valid_production_env "$new_upload_failure_root/production.env"
if FAKE_RCLONE_FAIL_AT=1 run_cycle "$new_upload_failure_root" >/dev/null 2>&1; then
  fail 'new snapshot upload failure unexpectedly succeeded'
fi
new_upload_failure_backup_count="$(find "$new_upload_failure_root/backups" -maxdepth 1 -type f -name 'baton-*.sql.gz' | wc -l | tr -d ' ')"
new_upload_failure_remote_count="$(find "$new_upload_failure_root/remote" -type f | wc -l | tr -d ' ')"
assert_count 1 "$new_upload_failure_backup_count" 'new failed upload local snapshot'
assert_count 0 "$new_upload_failure_remote_count" 'new failed upload remote files'
assert_no_file "$new_upload_failure_root/state/last-success"

readback_mismatch_root="$test_root/readback-mismatch"
mkdir -p -- "$readback_mismatch_root/backups" "$readback_mismatch_root/remote"
write_valid_production_env "$readback_mismatch_root/production.env"
if FAKE_RCLONE_CORRUPT_CAT_AT=1 run_cycle "$readback_mismatch_root" >/dev/null 2>&1; then
  fail 'remote backup readback mismatch unexpectedly succeeded'
fi
readback_backup="$(find "$readback_mismatch_root/backups" -maxdepth 1 -type f -name 'baton-*.sql.gz' -print)"
assert_file "$readback_backup"
assert_no_file "$readback_backup.uploaded"
assert_no_file "$readback_mismatch_root/state/last-success"

sidecar_readback_root="$test_root/sidecar-readback-mismatch"
mkdir -p -- "$sidecar_readback_root/backups" "$sidecar_readback_root/remote"
write_valid_production_env "$sidecar_readback_root/production.env"
if FAKE_RCLONE_CORRUPT_CAT_AT=2 run_cycle "$sidecar_readback_root" >/dev/null 2>&1; then
  fail 'remote checksum readback mismatch unexpectedly succeeded'
fi
sidecar_readback_backup="$(find "$sidecar_readback_root/backups" -maxdepth 1 -type f -name 'baton-*.sql.gz' -print)"
assert_file "$sidecar_readback_backup"
assert_no_file "$sidecar_readback_backup.uploaded"
assert_no_file "$sidecar_readback_root/state/last-success"

immutable_mismatch_root="$test_root/immutable-mismatch"
mkdir -p -- "$immutable_mismatch_root/backups" "$immutable_mismatch_root/remote/daily"
write_valid_production_env "$immutable_mismatch_root/production.env"
immutable_backup="$immutable_mismatch_root/backups/baton-20200101T000000Z-conflict.sql.gz"
write_valid_backup "$immutable_backup"
printf '%s\n' 'different immutable remote object' > "$immutable_mismatch_root/remote/daily/$(basename -- "$immutable_backup")"
if run_cycle "$immutable_mismatch_root" >/dev/null 2>&1; then
  fail 'immutable remote object mismatch unexpectedly succeeded'
fi
assert_file "$immutable_backup"
assert_no_file "$immutable_backup.uploaded"
assert_no_file "$immutable_mismatch_root/state/last-success"

non_crypt_root="$test_root/non-crypt"
mkdir -p -- "$non_crypt_root/backups" "$non_crypt_root/remote"
write_valid_production_env "$non_crypt_root/production.env"
non_crypt_backup="$non_crypt_root/backups/baton-20200101T000000Z-pending.sql.gz"
write_valid_backup "$non_crypt_backup"
if FAKE_RCLONE_NON_CRYPT=1 run_cycle "$non_crypt_root" >/dev/null 2>&1; then
  fail 'non-crypt remote unexpectedly succeeded'
fi
assert_file "$non_crypt_backup"
non_crypt_remote_count="$(find "$non_crypt_root/remote" -type f | wc -l | tr -d ' ')"
assert_count 0 "$non_crypt_remote_count" 'non-crypt remote files'

no_data_encryption_root="$test_root/no-data-encryption"
mkdir -p -- "$no_data_encryption_root/backups" "$no_data_encryption_root/remote"
write_valid_production_env "$no_data_encryption_root/production.env"
no_data_encryption_backup="$no_data_encryption_root/backups/baton-20200101T000000Z-pending.sql.gz"
write_valid_backup "$no_data_encryption_backup"
if FAKE_RCLONE_NO_DATA_ENCRYPTION=true run_cycle "$no_data_encryption_root" >/dev/null 2>&1; then
  fail 'crypt remote with data encryption disabled unexpectedly succeeded'
fi
no_data_encryption_remote_count="$(find "$no_data_encryption_root/remote" -type f | wc -l | tr -d ' ')"
assert_count 0 "$no_data_encryption_remote_count" 'data-encryption-disabled remote files'

generic_no_data_root="$test_root/generic-no-data-encryption"
mkdir -p -- "$generic_no_data_root/backups" "$generic_no_data_root/remote"
write_valid_production_env "$generic_no_data_root/production.env"
generic_no_data_backup="$generic_no_data_root/backups/baton-20200101T000000Z-pending.sql.gz"
write_valid_backup "$generic_no_data_backup"
if RCLONE_NO_DATA_ENCRYPTION=true run_cycle "$generic_no_data_root" >/dev/null 2>&1; then
  fail 'generic data encryption override unexpectedly succeeded'
fi
generic_no_data_remote_count="$(find "$generic_no_data_root/remote" -type f | wc -l | tr -d ' ')"
assert_count 0 "$generic_no_data_remote_count" 'generic-data-encryption-disabled remote files'

missing_sync_checksum_root="$test_root/missing-sync-checksum"
mkdir -p -- "$missing_sync_checksum_root/backups" "$missing_sync_checksum_root/remote"
write_valid_production_env "$missing_sync_checksum_root/production.env"
missing_sync_checksum_backup="$missing_sync_checksum_root/backups/baton-20200101T000000Z-pending.sql.gz"
write_valid_dump "$missing_sync_checksum_backup"
if run_cycle "$missing_sync_checksum_root" >/dev/null 2>&1; then
  fail 'backup without checksum unexpectedly synchronized'
fi
assert_no_file "$missing_sync_checksum_backup.sha256"
missing_sync_remote_count="$(find "$missing_sync_checksum_root/remote" -type f | wc -l | tr -d ' ')"
assert_count 0 "$missing_sync_remote_count" 'missing checksum remote files'

lock_root="$test_root/lock-contention"
mkdir -p -- "$lock_root/backups" "$lock_root/remote"
write_valid_production_env "$lock_root/production.env"
set +e
FAKE_FLOCK_EXIT=1 run_cycle "$lock_root" >/dev/null 2>&1
lock_status=$?
set -e
assert_count 75 "$lock_status" 'backup lock contention exit status'
lock_backup_count="$(find "$lock_root/backups" -maxdepth 1 -type f | wc -l | tr -d ' ')"
assert_count 0 "$lock_backup_count" 'backup lock contention local files'

printf 'PASS: backup cycle creates, verifies, uploads, retains, and fails closed\n'
