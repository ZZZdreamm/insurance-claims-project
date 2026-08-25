#!/usr/bin/env bash
# Build all images, load them into a kind cluster and install the Helm chart.
#   ./deploy/kind/up.sh            # creates cluster "claims" if missing
#   kubectl port-forward svc/claim-service 8080:8080 & kubectl port-forward svc/grafana 3001:3000 &
set -euo pipefail
cd "$(dirname "$0")/../.."
CLUSTER=${CLUSTER:-claims}
kind get clusters | grep -qx "$CLUSTER" || kind create cluster --name "$CLUSTER"
for service in claim-service payout-service search-service; do
  docker build -f "$service/Dockerfile" -t "claims/$service:local" .      # multi-module: root context
  kind load docker-image "claims/$service:local" --name "$CLUSTER"
done
for service in assessment-service adjuster-console; do
  docker build -t "claims/$service:local" "./$service"
  kind load docker-image "claims/$service:local" --name "$CLUSTER"
done
helm upgrade --install claims deploy/helm/claims-platform --wait --timeout 10m
kubectl get pods
