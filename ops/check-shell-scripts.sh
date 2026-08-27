#!/usr/bin/env bash

set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(git -C "$script_dir" rev-parse --show-toplevel)"

shell_scripts=()
while IFS= read -r -d '' shell_script; do
  shell_scripts+=("$shell_script")
done < <(
  git -C "$repository_root" ls-files -z \
    --cached \
    --others \
    --exclude-standard \
    -- \
    ':(glob)ops/**/*.sh'
)

if (( ${#shell_scripts[@]} == 0 )); then
  echo "No operational shell scripts were found in the Git index or working tree." >&2
  exit 1
fi

if ! command -v shellcheck >/dev/null 2>&1; then
  echo "shellcheck is required to validate operational shell scripts." >&2
  exit 127
fi

cd "$repository_root" || exit 1

for shell_script in "${shell_scripts[@]}"; do
  bash -n "$shell_script"
done

shellcheck -e SC1007,SC2016 -- "${shell_scripts[@]}"

printf 'Validated %d operational shell scripts from the Git index and working tree.\n' \
  "${#shell_scripts[@]}"
