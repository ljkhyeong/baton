#!/usr/bin/env bash

set -Eeuo pipefail
export LC_ALL=C

fail() {
  printf 'Isolated recovery Compose refused: %s\n' "$1" >&2
  exit 1
}

[[ $# -gt 0 ]] || fail "a Compose subcommand is required"

file_mode() {
  local target="$1"
  local mode

  if mode="$(stat -f '%Lp' "$target" 2>/dev/null)"; then
    :
  elif mode="$(stat -c '%a' "$target" 2>/dev/null)"; then
    :
  else
    fail "could not inspect file permissions: $target"
  fi
  printf '%s\n' "$mode"
}

assert_private_directory() {
  local target="$1"
  local canonical
  local mode

  [[ "$target" == /* ]] || fail "directory must be absolute: $target"
  [[ "$target" != "/" ]] || fail "filesystem root is not an isolated directory"
  [[ ! -L "$target" && -d "$target" && -O "$target" ]] \
    || fail "directory must be owned, real, and non-symbolic: $target"
  canonical="$(CDPATH= cd -- "$target" && pwd -P)"
  [[ "$canonical" == "$target" ]] || fail "directory must already be canonical: $target"
  mode="$(file_mode "$target")"
  mode=$((8#$mode))
  (( (mode & 077) == 0 )) || fail "directory must not grant group or other permissions: $target"
}

assert_run_descendant() {
  local target="$1"
  local canonical

  assert_private_directory "$target"
  canonical="$(CDPATH= cd -- "$target" && pwd -P)"
  case "$canonical" in
    "$run_root"/*) ;;
    *) fail "path escapes the isolated run directory: $target" ;;
  esac
}

real_docker="${BATON_RECOVERY_REHEARSAL_DOCKER:-}"
run_root="${BATON_RECOVERY_REHEARSAL_ROOT:-}"
run_id="${BATON_RECOVERY_REHEARSAL_RUN_ID:-}"
run_token="${BATON_RECOVERY_REHEARSAL_TOKEN:-}"
run_token_file="${BATON_RECOVERY_REHEARSAL_TOKEN_FILE:-}"
project="${BATON_RECOVERY_REHEARSAL_PROJECT:-}"
operation="${BATON_RECOVERY_REHEARSAL_OPERATION:-}"
repository_root="${BATON_RECOVERY_REHEARSAL_REPOSITORY_ROOT:-}"
expected_daemon_id="${BATON_RECOVERY_REHEARSAL_DAEMON_ID:-}"
expected_context="${BATON_RECOVERY_REHEARSAL_DOCKER_CONTEXT:-}"
expected_database="${BATON_RECOVERY_REHEARSAL_DB_NAME:-}"
env_file="${BATON_PRODUCTION_ENV_FILE:-}"
backup_dir="${BATON_BACKUP_DIR:-}"
state_dir="${BATON_BACKUP_STATE_DIR:-}"
temporary_dir="${TMPDIR:-}"

[[ "$real_docker" == /* && -x "$real_docker" ]] \
  || fail "BATON_RECOVERY_REHEARSAL_DOCKER must be an absolute executable"
assert_private_directory "$run_root"
[[ "$run_id" =~ ^[a-z0-9]{6,32}$ ]] || fail "run id has an unsafe format"
[[ "$project" == "baton-recovery-rehearsal-$run_id" ]] \
  || fail "Compose project does not match the isolated run id"
[[ "$project" != "baton-production" ]] || fail "production project is forbidden"

[[ "$run_token_file" == "$run_root/rehearsal.token" ]] \
  || fail "run token file is outside the isolated boundary"
[[ ! -L "$run_token_file" && -f "$run_token_file" && -O "$run_token_file" ]] \
  || fail "run token must be an owned regular file"
[[ "$(file_mode "$run_token_file")" == "600" ]] \
  || fail "run token must have mode 0600"
IFS= read -r stored_run_token < "$run_token_file" \
  || fail "run token could not be read"
[[ -n "$run_token" && "$run_token" == "$stored_run_token" ]] \
  || fail "run token does not match"

[[ "$repository_root" == /* && -d "$repository_root" && ! -L "$repository_root" ]] \
  || fail "repository root must be an absolute real directory"
repository_root="$(CDPATH= cd -- "$repository_root" && pwd -P)"
compose_file="$repository_root/compose.production.yml"
round_compose_file="$repository_root/compose.round.production.yml"
override_file="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/compose.recovery-rehearsal.yml"
[[ -f "$compose_file" && ! -L "$compose_file" ]] \
  || fail "production Compose file is unavailable"
[[ -f "$round_compose_file" && ! -L "$round_compose_file" ]] \
  || fail "production ROUND Compose file is unavailable"
[[ -f "$override_file" && ! -L "$override_file" ]] \
  || fail "recovery Compose override is unavailable"

[[ "$env_file" == "$run_root/rehearsal.env" ]] \
  || fail "production environment file is outside the isolated boundary"
env_file="$("$(dirname -- "${BASH_SOURCE[0]}")/validate-production-env.sh" "$env_file")" \
  || exit 1
[[ "$env_file" == "$run_root/rehearsal.env" ]] \
  || fail "validated environment file changed boundary"
assert_run_descendant "$backup_dir"
assert_run_descendant "$state_dir"
assert_run_descendant "$temporary_dir"

[[ "$expected_database" =~ ^baton_runtime_smoke_[a-z0-9]{6,32}$ ]] \
  || fail "database name is not rehearsal-specific"
[[ "$expected_database" == "${BATON_DB_NAME:-}" ]] \
  || fail "database name does not match the isolated runtime"
case "$operation" in
  backup|restore|metrics) ;;
  *) fail "operation must be backup, restore, or metrics" ;;
esac

current_daemon_id="$("$real_docker" info --format '{{.ID}}' </dev/null)"
current_context="$("$real_docker" context show </dev/null)"
[[ -n "$expected_daemon_id" && "$current_daemon_id" == "$expected_daemon_id" ]] \
  || fail "Docker daemon changed after rehearsal startup"
[[ -n "$expected_context" && "$current_context" == "$expected_context" ]] \
  || fail "Docker context changed after rehearsal startup"

production_containers="$("$real_docker" ps -aq \
  --filter label=com.docker.compose.project=baton-production </dev/null)"
production_volumes="$("$real_docker" volume ls -q \
  --filter label=com.docker.compose.project=baton-production </dev/null)"
production_networks="$("$real_docker" network ls -q \
  --filter label=com.docker.compose.project=baton-production </dev/null)"
if [[ -n "$production_containers" || -n "$production_volumes" || -n "$production_networks" ]]; then
  fail "baton-production resources exist on this Docker daemon"
fi

compose=(
  "$real_docker" compose
  --project-directory "$repository_root"
  --project-name "$project"
  --env-file "$env_file"
  --file "$compose_file"
  --file "$round_compose_file"
  --file "$override_file"
)

mysql_container_id="$("${compose[@]}" ps -q mysql </dev/null)"
[[ "$mysql_container_id" =~ ^[0-9a-f]{12,64}$ ]] \
  || fail "isolated MySQL container was not found"
[[ "$("$real_docker" inspect --format \
  '{{ index .Config.Labels "com.docker.compose.project" }}' \
  "$mysql_container_id" </dev/null)" == "$project" ]] \
  || fail "MySQL Compose project label does not match"
[[ "$("$real_docker" inspect --format \
  '{{ index .Config.Labels "com.docker.compose.service" }}' \
  "$mysql_container_id" </dev/null)" == "mysql" ]] \
  || fail "MySQL Compose service label does not match"
[[ "$("$real_docker" inspect --format \
  '{{ index .Config.Labels "com.personal.baton.recovery-rehearsal" }}' \
  "$mysql_container_id" </dev/null)" == "$run_id" ]] \
  || fail "MySQL recovery rehearsal label does not match"

expected_volume="${project}_baton_mysql_data"
mysql_volume="$("$real_docker" inspect --format \
  '{{range .Mounts}}{{if eq .Destination "/var/lib/mysql"}}{{println .Name}}{{end}}{{end}}' \
  "$mysql_container_id" </dev/null | tr -d '\r\n')"
[[ "$mysql_volume" == "$expected_volume" ]] \
  || fail "MySQL data mount is not the isolated rehearsal volume"
[[ "$("$real_docker" volume inspect --format \
  '{{ index .Labels "com.docker.compose.project" }}' \
  "$mysql_volume" </dev/null)" == "$project" ]] \
  || fail "MySQL volume Compose project label does not match"
[[ "$("$real_docker" volume inspect --format \
  '{{ index .Labels "com.personal.baton.recovery-rehearsal" }}' \
  "$mysql_volume" </dev/null)" == "$run_id" ]] \
  || fail "MySQL volume recovery rehearsal label does not match"

container_database="$("${compose[@]}" exec -T mysql \
  sh -c 'printf "%s" "$MYSQL_DATABASE"' </dev/null)"
[[ "$container_database" == "$expected_database" ]] \
  || fail "MySQL container database does not match the isolated rehearsal database"

if [[ "$operation" == "restore" ]]; then
  for service in app web; do
    service_container_id="$("${compose[@]}" ps --all -q "$service" </dev/null)"
    [[ "$service_container_id" =~ ^[0-9a-f]{12,64}$ ]] \
      || fail "$service container was not found"
    service_state="$("$real_docker" inspect --format '{{.State.Status}}' \
      "$service_container_id" </dev/null)"
    [[ "$service_state" == "exited" ]] \
      || fail "$service must remain exited during restore"
  done
fi

case "$operation:$1" in
  metrics:exec)
    [[ $# -eq 6 && "$2" == "-T" && "$3" == "app" \
      && "$4" == "sh" && "$5" == "-ec" \
      && "$6" == 'exec wget -q -O - http://127.0.0.1:8080/actuator/prometheus' ]] \
      || fail "metrics may only execute the fixed application Prometheus query"
    ;;
  backup:exec)
    [[ $# -eq 6 && "$2" == "-T" && "$3" == "mysql" \
      && "$4" == "sh" && "$5" == "-ec" && "$6" == *"exec mysqldump"* ]] \
      || fail "backup may only execute the fixed MySQL dump command"
    ;;
  restore:ps)
    [[ $# -eq 7 && "$2" == "--all" && "$3" == "-q" \
      && "$4" == "app" && "$5" == "web" \
      && "$6" == "round-web" && "$7" == "round-signaling" ]] \
      || fail "restore may only inspect app, web, and ROUND container ids"
    "${compose[@]}" ps --all -q app web
    for service in round-web round-signaling; do
      "$real_docker" ps -aq \
        --filter "label=com.docker.compose.project=$project" \
        --filter "label=com.docker.compose.service=$service"
    done
    exit 0
    ;;
  restore:exec)
    [[ $# -eq 6 && "$2" == "-T" && "$3" == "mysql" \
      && "$4" == "sh" && "$5" == "-ec" && "$6" != *"mysqldump"* ]] \
      || fail "restore may only execute MySQL restore commands"
    ;;
  *)
    fail "unsupported Compose command for $operation: $1"
    ;;
esac

exec "${compose[@]}" "$@"
