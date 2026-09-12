#!/usr/bin/env bash
set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ops_dir="$(dirname -- "$script_dir")"
prometheus_image="${1:?Prometheus 검사 이미지가 필요합니다.}"
caddy_image="$(sed -n 's/^ARG CADDY_IMAGE=//p' "$ops_dir/../frontend/Dockerfile")"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/baton-metrics-scrape.XXXXXXXX")"
metrics_container="$(basename "$test_root")-source"
prometheus_container="$(basename "$test_root")-prometheus"

cleanup() {
  local result=$?
  if (( result != 0 )); then
    docker logs "$metrics_container" 2>&1 || true
    docker logs "$prometheus_container" 2>&1 || true
  fi
  docker rm -f "$prometheus_container" "$metrics_container" >/dev/null 2>&1 || true
  rm -rf "$test_root"
}
trap cleanup EXIT

mkdir -p "$test_root/actuator"
cat > "$test_root/actuator/prometheus" <<'METRICS'
baton_integration_delivery_actionable_failed_items{integration="email"} 0
baton_email_delivery_receipts{event="hard_bounce"} 1
http_server_requests_seconds_count{uri="/private/example"} 7
METRICS
cat > "$test_root/Caddyfile" <<'CADDY'
{
  admin off
  auto_https off
}
:8080 {
  root * /fixture
  header Content-Type "text/plain; version=0.0.4; charset=utf-8"
  file_server
}
CADDY
# 운영 수집 경로·필터를 그대로 사용하고 테스트 대기 시간만 줄인다.
sed -e 's/30s/1s/g' -e 's/scrape_timeout: 10s/scrape_timeout: 1s/' \
  "$ops_dir/integrations/prometheus.yml" > "$test_root/prometheus.yml"

docker run --detach --name "$metrics_container" --network none --read-only \
  --tmpfs /config --tmpfs /data \
  -v "$test_root:/fixture:ro" "$caddy_image" \
  caddy run --config /fixture/Caddyfile >/dev/null
docker run --detach --name "$prometheus_container" --read-only \
  --network "container:$metrics_container" --tmpfs /tmp \
  -v "$ops_dir/integrations:/etc/prometheus:ro" \
  -v "$test_root/prometheus.yml:/etc/prometheus/prometheus.yml:ro" \
  "$prometheus_image" --config.file=/etc/prometheus/prometheus.yml \
  --storage.tsdb.path=/tmp/prometheus --web.listen-address=127.0.0.1:9090 >/dev/null

query() {
  docker exec "$prometheus_container" /bin/promtool query instant \
    http://127.0.0.1:9090 "$1"
}
for ((attempt = 0; attempt < 30; attempt++)); do
  if [[ "$(query 'count(up{job="baton"} == 1)' 2>/dev/null || true)" == '{} => 1 @'* ]]; then
    break
  fi
  sleep 1
done
for expression in \
  'count(up{job="baton"} == 1)' \
  'count(baton_integration_delivery_actionable_failed_items{job="baton",integration="email"} == 0)' \
  'count(baton_email_delivery_receipts{job="baton",event="hard_bounce"} == 1)' \
  'count(absent(http_server_requests_seconds_count{job="baton"}))'; do
  result="$(query "$expression")"
  if [[ "$result" != '{} => 1 @'* ]]; then
    printf '지표 수집 검증 실패: %s\n%s\n' "$expression" "$result" >&2
    exit 1
  fi
done
printf '연동·메일 결과 지표 수집과 요청 지표 제외 확인\n'
