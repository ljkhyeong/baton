#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

write_checksum=false
require_checksum=false
checksum_name=""
backup_path=""

usage() {
  printf 'Usage: %s [--write-checksum] [--require-checksum] [--checksum-name NAME] /path/to/backup.sql.gz\n' "$0" >&2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --write-checksum)
      write_checksum=true
      ;;
    --require-checksum)
      require_checksum=true
      ;;
    --checksum-name)
      if [[ $# -lt 2 || -n "$checksum_name" ]]; then
        usage
        exit 1
      fi
      checksum_name="$2"
      shift
      ;;
    --*)
      usage
      exit 1
      ;;
    *)
      if [[ -n "$backup_path" ]]; then
        usage
        exit 1
      fi
      backup_path="$1"
      ;;
  esac
  shift
done

if [[ -z "$backup_path" ]]; then
  usage
  exit 1
fi

if [[ -n "$checksum_name" ]]; then
  if [[ "$write_checksum" != true || "$checksum_name" == */* || "$checksum_name" == "." || "$checksum_name" == ".." ]]; then
    usage
    exit 1
  fi
fi

if [[ ! -f "$backup_path" || ! -r "$backup_path" || ! -s "$backup_path" ]]; then
  printf 'Backup is not a readable non-empty file: %s\n' "$backup_path" >&2
  exit 1
fi

if ! gzip -cd -- "$backup_path" | awk '
  index($0, "CREATE TABLE `flyway_schema_history`") { flyway = 1 }
  index($0, "CREATE TABLE `teams`") { teams = 1 }
  index($0, "CREATE TABLE `seasons`") { seasons = 1 }
  END { exit !(flyway && teams && seasons) }
'; then
  printf 'Backup is missing one or more BATON schema markers: %s\n' "$backup_path" >&2
  exit 1
fi

sha256_file() {
  local target="$1"
  local output
  if command -v sha256sum >/dev/null 2>&1; then
    output="$(sha256sum -- "$target")"
  elif command -v shasum >/dev/null 2>&1; then
    output="$(shasum -a 256 -- "$target")"
  else
    printf 'sha256sum or shasum is required.\n' >&2
    return 1
  fi
  printf '%s\n' "${output%% *}"
}

checksum_path="$backup_path.sha256"
checksum_temp=""
cleanup() {
  if [[ -n "$checksum_temp" ]]; then
    rm -f -- "$checksum_temp"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [[ "$write_checksum" == true && ! -e "$checksum_path" ]]; then
  backup_dir="$(dirname -- "$backup_path")"
  backup_name="${checksum_name:-$(basename -- "$backup_path")}"
  checksum_temp="$(mktemp "$backup_dir/.baton-checksum.XXXXXX")"
  printf '%s  %s\n' "$(sha256_file "$backup_path")" "$backup_name" > "$checksum_temp"
  mv -- "$checksum_temp" "$checksum_path"
  checksum_temp=""
fi

if [[ -e "$checksum_path" ]]; then
  if [[ ! -f "$checksum_path" || ! -r "$checksum_path" ]]; then
    printf 'Backup checksum is not a readable file: %s\n' "$checksum_path" >&2
    exit 1
  fi

  checksum_line=""
  exec 8<"$checksum_path"
  IFS= read -r checksum_line <&8 || true
  if IFS= read -r _ <&8; then
    printf 'Backup checksum must contain exactly one line: %s\n' "$checksum_path" >&2
    exec 8<&-
    exit 1
  fi
  exec 8<&-

  if [[ ! "$checksum_line" =~ ^([0-9a-f]{64})[[:space:]][[:space:]]([^/]+)$ ]]; then
    printf 'Backup checksum has an invalid format: %s\n' "$checksum_path" >&2
    exit 1
  fi

  expected_hash="${BASH_REMATCH[1]}"
  expected_name="${BASH_REMATCH[2]}"
  actual_name="${checksum_name:-$(basename -- "$backup_path")}"
  if [[ "$expected_name" != "$actual_name" ]]; then
    printf 'Backup checksum names %s instead of %s.\n' "$expected_name" "$actual_name" >&2
    exit 1
  fi

  actual_hash="$(sha256_file "$backup_path")"
  if [[ "$actual_hash" != "$expected_hash" ]]; then
    printf 'Backup checksum does not match: %s\n' "$backup_path" >&2
    exit 1
  fi
elif [[ "$require_checksum" == true ]]; then
  printf 'Backup checksum is required: %s\n' "$checksum_path" >&2
  exit 1
fi

trap - EXIT INT TERM
printf 'Backup verified: %s\n' "$backup_path"
