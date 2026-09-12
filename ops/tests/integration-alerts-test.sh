#!/usr/bin/env bash

set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ops_dir="$(dirname -- "$script_dir")"
prometheus_image="prom/prometheus:v3.14.0-distroless"
alertmanager_image="prom/alertmanager:v0.34.0"

docker run --rm --network none --read-only \
  -v "$ops_dir:/ops:ro" -w /ops/integrations \
  --entrypoint /bin/promtool "$prometheus_image" check config prometheus.yml
docker run --rm --network none --read-only --tmpfs /tmp \
  -v "$ops_dir:/ops:ro" -w /ops/tests \
  --entrypoint /bin/promtool "$prometheus_image" test rules integration-alerts.test.yml
docker run --rm --network none --read-only \
  -v "$ops_dir:/ops:ro" -v /dev/null:/run/secrets/smtp-password:ro \
  --entrypoint /bin/amtool "$alertmanager_image" \
  check-config /ops/integrations/alertmanager.yml.example
