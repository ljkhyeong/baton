#!/usr/bin/env bash

set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ops_dir="$(dirname -- "$script_dir")"
prometheus_image="prom/prometheus:v3.14.0-distroless"
alertmanager_image="prom/alertmanager:v0.34.0"
blackbox_image="quay.io/prometheus/blackbox-exporter:v0.28.0"

docker run --rm --network none --read-only \
  -v "$ops_dir:/ops:ro" -w /ops/integrations \
  --entrypoint /bin/promtool "$prometheus_image" check config prometheus.yml prometheus-host.yml.example
bash "$script_dir/integration-metrics-scrape-test.sh" "$prometheus_image"
bash "$script_dir/integration-metrics-scrape-test.sh" "$prometheus_image" host
docker run --rm --network none --read-only --tmpfs /tmp \
  -v "$ops_dir:/ops:ro" -w /ops/tests \
  --entrypoint /bin/promtool "$prometheus_image" test rules integration-alerts.test.yml https-alerts.test.yml host-alerts.test.yml
docker run --rm --network none --read-only \
  -v "$ops_dir:/ops:ro" -v /dev/null:/run/secrets/smtp-password:ro \
  --entrypoint /bin/amtool "$alertmanager_image" \
  check-config /ops/integrations/alertmanager.yml.example
docker run --rm --network none --read-only \
  -v "$ops_dir:/ops:ro" -v /dev/null:/run/secrets/discord-webhook-url:ro \
  --entrypoint /bin/amtool "$alertmanager_image" \
  check-config /ops/integrations/alertmanager-discord.yml.example
docker run --rm --network none --read-only \
  -v "$ops_dir:/ops:ro" -v /dev/null:/run/secrets/smtp-password:ro \
  -v /dev/null:/run/secrets/monitoring-heartbeat-url:ro \
  --entrypoint /bin/amtool "$alertmanager_image" \
  check-config /ops/integrations/alertmanager-heartbeat.yml.example

for target in 'alertmanager.yml.example operations-email monitoring-heartbeat-disabled' \
  'alertmanager-discord.yml.example operations-discord monitoring-heartbeat-disabled' \
  'alertmanager-heartbeat.yml.example operations-email monitoring-heartbeat'; do
  read -r config_file operations_receiver heartbeat_receiver <<< "$target"
  for alert_name in BatonMonitoringWatchdog BatonMetricsUnavailable BatonEmailDeliveryRejected BatonTlsCertificateExpiring BatonHostDiskSpaceLow; do
    receiver="$operations_receiver"
    if [[ "$alert_name" == BatonMonitoringWatchdog ]]; then receiver="$heartbeat_receiver"; fi
    docker run --rm --network none --read-only \
      -v "$ops_dir:/ops:ro" -v /dev/null:/run/secrets/smtp-password:ro \
      -v /dev/null:/run/secrets/discord-webhook-url:ro \
      -v /dev/null:/run/secrets/monitoring-heartbeat-url:ro \
      --entrypoint /bin/amtool "$alertmanager_image" config routes test \
      --config.file="/ops/integrations/$config_file" --verify.receivers="$receiver" \
      "alertname=$alert_name" service=baton
  done
done
docker run --rm --network none --read-only \
  -v "$ops_dir:/ops:ro" "$blackbox_image" \
  --config.file=/ops/integrations/blackbox.yml --config.check
