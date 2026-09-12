#!/usr/bin/env bash

set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ops_dir="$(dirname -- "$script_dir")"
prometheus_image="prom/prometheus:v3.14.0-distroless"
alertmanager_image="prom/alertmanager:v0.34.0"
blackbox_image="quay.io/prometheus/blackbox-exporter:v0.28.0"

docker run --rm --network none --read-only \
  -v "$ops_dir:/ops:ro" -w /ops/integrations \
  --entrypoint /bin/promtool "$prometheus_image" check config prometheus.yml
docker run --rm --network none --read-only --tmpfs /tmp \
  -v "$ops_dir:/ops:ro" -w /ops/tests \
  --entrypoint /bin/promtool "$prometheus_image" test rules integration-alerts.test.yml https-alerts.test.yml
docker run --rm --network none --read-only \
  -v "$ops_dir:/ops:ro" -v /dev/null:/run/secrets/smtp-password:ro \
  --entrypoint /bin/amtool "$alertmanager_image" \
  check-config /ops/integrations/alertmanager.yml.example
docker run --rm --network none --read-only \
  -v "$ops_dir:/ops:ro" "$blackbox_image" \
  --config.file=/ops/integrations/blackbox.yml --config.check
