#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

if (( $# == 0 )); then
  printf 'Usage: %s IMAGE [IMAGE ...]\n이미 빌드하거나 내려받은 로컬 Docker 이미지를 검사합니다.\n' "$0" >&2
  exit 2
fi
command -v trivy >/dev/null 2>&1 || {
  printf 'Trivy가 필요합니다. docs/runbooks/free-integrations.md의 이미지 검사 절을 참고하세요.\n' >&2
  exit 2
}

repo_root="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$repo_root/output/security"
report_dir="$(mktemp -d "$repo_root/output/security/images.XXXXXXXX")"
trivy --version > "$report_dir/trivy-version.txt"
result=0
index=0
for target in "$@"; do
  index=$((index + 1))
  printf '%s\t%s\n' "$index.json" "$target" >> "$report_dir/images.tsv"
  status=0
  trivy image --image-src docker --scanners vuln --severity HIGH,CRITICAL \
    --disable-telemetry --cache-dir "$repo_root/output/security/.cache" \
    --ignore-unfixed=false --timeout 10m --exit-code 10 \
    --format json --output "$report_dir/$index.json" -- "$target" || status=$?
  if (( status != 0 && status != 10 )); then
    result=1
  elif (( status == 10 && result == 0 )); then
    result=10
  fi
done
printf '검사 보고서: %s\n종료 코드: %s (0: 대상 취약점 없음, 10: HIGH·CRITICAL 발견, 1: 검사 실패)\n' \
  "$report_dir" "$result"
exit "$result"
