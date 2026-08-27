#!/usr/bin/env bash

production_lifecycle_lock_error() {
  printf 'Production lifecycle lock failed: %s\n' "$1" >&2
  return 1
}

production_lifecycle_lock_mode() {
  local target="$1"
  local mode

  if mode="$(stat -f '%Lp' "$target" 2>/dev/null)"; then
    :
  elif mode="$(stat -c '%a' "$target" 2>/dev/null)"; then
    :
  else
    return 1
  fi
  printf '%s' "$mode"
}

production_lifecycle_lock_canonical_file() {
  local target="$1"
  local directory

  directory="$(CDPATH= cd -- "$(dirname -- "$target")" && pwd -P)" || return 1
  printf '%s/%s' "$directory" "$(basename -- "$target")"
}

production_lifecycle_lock_descriptor_file() {
  local descriptor_path=""

  if [[ -e "/proc/$$/fd/8" ]]; then
    descriptor_path="$(readlink "/proc/$$/fd/8")" || return 1
  elif command -v lsof >/dev/null 2>&1; then
    descriptor_path="$(
      lsof -a -p "$$" -d 8 -Fn 2>/dev/null | sed -n 's/^n//p'
    )" || return 1
  else
    return 1
  fi
  [[ -n "$descriptor_path" && "$descriptor_path" != *$'\n'* ]] || return 1
  production_lifecycle_lock_canonical_file "$descriptor_path"
}

acquire_production_lifecycle_lock() {
  local descriptor_file
  local directory
  local directory_mode
  local file_mode
  local inherited_fd="${BATON_PRODUCTION_LIFECYCLE_LOCK_FD:-}"
  local lock_file=/srv/baton/state/production-lifecycle.lock

  if ! command -v flock >/dev/null 2>&1; then
    production_lifecycle_lock_error "flock is required"
    return 1
  fi
  directory="$(dirname -- "$lock_file")"
  if [[ -L "$directory" || ! -d "$directory" || ! -O "$directory" ]]; then
    production_lifecycle_lock_error \
      "dedicated lock directory must be real and owned by the service user: $directory"
    return 1
  fi
  directory="$(CDPATH= cd -- "$directory" && pwd -P)" || {
    production_lifecycle_lock_error "dedicated lock directory could not be resolved"
    return 1
  }
  if [[ "$lock_file" != "$directory/$(basename -- "$lock_file")" \
    || -L "$lock_file" || ! -f "$lock_file" || ! -O "$lock_file" ]]; then
    production_lifecycle_lock_error \
      "dedicated lock file must be real and owned by the service user: $lock_file"
    return 1
  fi
  directory_mode="$(production_lifecycle_lock_mode "$directory")" || {
    production_lifecycle_lock_error "dedicated lock directory mode could not be inspected"
    return 1
  }
  file_mode="$(production_lifecycle_lock_mode "$lock_file")" || {
    production_lifecycle_lock_error "dedicated lock file mode could not be inspected"
    return 1
  }
  if [[ ! "$directory_mode" =~ ^[0-7]{3,4}$ \
    || ! "$file_mode" =~ ^[0-7]{3,4}$ \
    || $((8#$directory_mode & 077)) -ne 0 \
    || $((8#$file_mode & 077)) -ne 0 ]]; then
    production_lifecycle_lock_error \
      "dedicated lock directory and file must not grant group or other permissions"
    return 1
  fi

  if [[ -n "$inherited_fd" ]]; then
    if [[ "$inherited_fd" != "8" || ! -e /dev/fd/8 ]]; then
      production_lifecycle_lock_error "inherited lock file descriptor is invalid"
      return 1
    fi
    descriptor_file="$(production_lifecycle_lock_descriptor_file)" || {
      production_lifecycle_lock_error "inherited lock file descriptor could not be resolved"
      return 1
    }
    if [[ "$descriptor_file" != "$lock_file" ]]; then
      production_lifecycle_lock_error "inherited lock file descriptor is invalid"
      return 1
    fi
    if ! flock -n 8; then
      printf 'Production lifecycle is already locked by another operation.\n' >&2
      return 75
    fi
    return 0
  fi

  umask 077
  if ! exec 8<>"$lock_file"; then
    production_lifecycle_lock_error "dedicated lock file could not be opened: $lock_file"
    return 1
  fi
  descriptor_file="$(production_lifecycle_lock_descriptor_file)" || {
    production_lifecycle_lock_error "opened lock descriptor could not be resolved"
    return 1
  }
  if [[ "$descriptor_file" != "$lock_file" ]]; then
    production_lifecycle_lock_error "opened lock descriptor changed identity"
    return 1
  fi
  if ! flock -n 8; then
    printf 'Production lifecycle is already locked by another operation.\n' >&2
    return 75
  fi
  export BATON_PRODUCTION_LIFECYCLE_LOCK_FD=8
}
