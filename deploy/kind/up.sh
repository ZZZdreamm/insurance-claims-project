#!/usr/bin/env bash
# Build all images, load them into a kind cluster and install the Helm chart.
#   ./deploy/kind/up.sh            # creates cluster "claims" if missing
#   kubectl port-forward svc/claim-service 8080:8080 & kubectl port-forward svc/grafana 3001:3000 &
set -euo pipefail
cd "$(dirname "$0")/../.."
CLUSTER=${CLUSTER:-claims}
kind get clusters | grep -qx "$CLUSTER" || kind create cluster --name "$CLUSTER"
for svc in claim-service payout-service search-service assessment-service adjuster-console; do
  docker build -t "claims/$svc:local" "./$svc"
  kind load docker-image "claims/$svc:local" --name "$CLUSTER"
done
helm upgrade --install claims deploy/helm/claims-platform --wait --timeout 10m
kubectl get pods
