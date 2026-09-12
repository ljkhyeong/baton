#!/usr/bin/env bash
set -Eeuo pipefail
test_root="$(mktemp -d "${TMPDIR:-/tmp}/baton-image-scan.XXXXXXXX")"
trap 'rm -rf "$test_root"' EXIT
repo_root="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
mkdir -p "$test_root/ops" "$test_root/bin"
cp "$repo_root/ops/scan-images.sh" "$test_root/ops/scan-images.sh"
cat > "$test_root/bin/trivy" <<'STUB'
#!/usr/bin/env bash
if [[ "$1" == --version ]]; then printf 'test version\n'; exit 0; fi
target="${!#}"
printf '%s\n' "$target" >> "$SCAN_CALLS"
case "$target" in
  vulnerable) exit 10 ;;
  unavailable) exit 1 ;;
  *) exit 0 ;;
esac
STUB
chmod +x "$test_root/bin/trivy"
export PATH="$test_root/bin:$PATH"
export SCAN_CALLS="$test_root/calls"
check() {
  local expected="$1"
  shift
  local actual=0
  : > "$SCAN_CALLS"
  bash "$test_root/ops/scan-images.sh" "$@" > "$test_root/result" 2>&1 || actual=$?
  [[ "$actual" == "$expected" ]] || { cat "$test_root/result"; exit 1; }
  [[ "$(wc -l < "$SCAN_CALLS" | tr -d ' ')" == "$#" ]] || exit 1
}
check 0 clean
check 10 vulnerable clean
check 1 unavailable vulnerable clean
check 1 vulnerable unavailable clean
check 2
printf '이미지 검사 종료 코드와 전체 대상 순회 5건 통과\n'
