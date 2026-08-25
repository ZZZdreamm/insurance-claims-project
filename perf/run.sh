#!/usr/bin/env bash
# Load test the running Compose stack with k6 in Docker (no local install).
# The per-client rate limit (30/min) would dominate a load test, so restart claim-service with a high
# limit for the run, then put it back:
#   RATE_LIMIT_SUBMIT_PER_MINUTE=1000000 docker compose --profile core up -d claim-service
#   ./perf/run.sh            # VUS=20 DURATION=30s by default
#   docker compose --profile core up -d claim-service
set -euo pipefail
cd "$(dirname "$0")/.."
docker run --rm --network host -v "$PWD/perf:/perf:ro" -e BASE_URL="${BASE_URL:-http://localhost:8080}" \
  -e VUS="${VUS:-20}" -e DURATION="${DURATION:-30s}" grafana/k6:0.56.0 run /perf/k6-submit.js
