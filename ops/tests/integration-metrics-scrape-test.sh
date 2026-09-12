#!/usr/bin/env bash
set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ops_dir="$(dirname -- "$script_dir")"
prometheus_image="${1:?Prometheus 검사 이미지가 필요합니다.}"
caddy_image="$(sed -n 's/^ARG CADDY_IMAGE=//p' "$ops_dir/../frontend/Dockerfile")"
case "${2:-app}" in
  app)
    config_file="$ops_dir/integrations/prometheus.yml"
    job=baton
    expressions=(
      'count(baton_integration_delivery_actionable_failed_items{job="baton",integration="email"} == 0)'
      'count(baton_email_delivery_receipts{job="baton",event="hard_bounce"} == 1)'
      'count(http_server_requests_seconds_count{job="baton",uri="/api/v1/seasons/{seasonId}",status="200"} == 7)'
      'count(http_server_requests_seconds_count{job="baton",uri="/api/v1/seasons/{seasonId}",status="500"} == 3)'
      'count(absent(http_server_requests_seconds_count{job="baton",uri!~"/api/v1(/.*)?"}))'
      'count(absent(http_server_requests_seconds_sum{job="baton"}))'
      'count(absent(jvm_memory_used_bytes{job="baton"}))'
    )
    ;;
  host)
    config_file="$ops_dir/integrations/prometheus-host.yml.example"
    job=baton-host
    expressions=(
      'count(node_filesystem_avail_bytes{job="baton-host",mountpoint="/srv"} == 9)'
      'count(node_filesystem_size_bytes{job="baton-host",mountpoint="/srv"} == 100)'
      'count(node_filesystem_device_error{job="baton-host",mountpoint="/srv"} == 0)'
      'count(node_scrape_collector_success{job="baton-host",collector="filesystem"} == 1)'
      'count(absent(node_filesystem_avail_bytes{job="baton-host",fstype="tmpfs"}))'
      'count(absent(node_cpu_seconds_total{job="baton-host"}))'
    )
    ;;
  *) printf '알 수 없는 수집 검사 대상입니다.\n' >&2; exit 2 ;;
esac
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
http_server_requests_seconds_count{uri="/actuator/health",status="200"} 100
http_server_requests_seconds_count{uri="UNKNOWN",status="500"} 9
http_server_requests_seconds_count{uri="/api/v10/example",status="500"} 8
http_server_requests_seconds_count{uri="/api/v1/seasons/{seasonId}",status="200"} 7
http_server_requests_seconds_count{uri="/api/v1/seasons/{seasonId}",status="500"} 3
http_server_requests_seconds_sum{uri="/api/v1/seasons/{seasonId}",status="200"} 2
jvm_memory_used_bytes{area="heap"} 100
METRICS
cat > "$test_root/metrics" <<'METRICS'
node_filesystem_avail_bytes{device="/dev/test",fstype="ext4",mountpoint="/srv"} 9
node_filesystem_size_bytes{device="/dev/test",fstype="ext4",mountpoint="/srv"} 100
node_filesystem_device_error{device="/dev/test",fstype="ext4",mountpoint="/srv"} 0
node_scrape_collector_success{collector="filesystem"} 1
node_filesystem_avail_bytes{device="tmpfs",fstype="tmpfs",mountpoint="/run"} 1
node_cpu_seconds_total{cpu="0",mode="idle"} 100
METRICS
cat > "$test_root/Caddyfile" <<'CADDY'
{
  admin off
  auto_https off
}
:8080, :9100 {
  root * /fixture
  header Content-Type "text/plain; version=0.0.4; charset=utf-8"
  file_server
}
CADDY
# 운영 수집 경로·필터를 유지하고 테스트 주소와 대기 시간만 바꾼다.
sed -e 's/30s/1s/g' -e 's/scrape_timeout: 10s/scrape_timeout: 1s/' \
  -e 's/scrape_interval: 1m/scrape_interval: 1s/' \
  -e 's/node-exporter:9100/127.0.0.1:9100/' \
  "$config_file" > "$test_root/prometheus.yml"

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
  if [[ "$(query "count(up{job=\"$job\"} == 1)" 2>/dev/null || true)" == '{} => 1 @'* ]]; then
    break
  fi
  sleep 1
done
for expression in "count(up{job=\"$job\"} == 1)" "${expressions[@]}"; do
  result="$(query "$expression")"
  if [[ "$result" != '{} => 1 @'* ]]; then
    printf '지표 수집 검증 실패: %s\n%s\n' "$expression" "$result" >&2
    exit 1
  fi
done
printf '%s의 필수 지표 수집과 불필요한 지표 제외 확인\n' "$job"
