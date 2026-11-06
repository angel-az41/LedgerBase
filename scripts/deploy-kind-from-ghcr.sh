#!/usr/bin/env bash

# ============================================================
# LEDGERBASE — LOCAL KIND RELEASE DEPLOYMENT
# ============================================================
#
# PURPOSE:
#
# Deploy an explicitly selected GHCR image version into
# the existing local LedgerBase Kubernetes cluster.
#
# SAFETY:
#
# - Requires an explicit 40-character Git commit SHA.
# - Requires an explicit --deploy command to make changes.
# - Verifies the active Kubernetes context.
# - Preserves PostgreSQL, Redis, and Kafka workloads.
# - Does not delete PVCs, Secrets, or ConfigMaps.
# - Waits for each application Deployment to become ready.
#
# Images must already have been published to GHCR by
# the successful GitHub Actions workflow on main.

set -euo pipefail

# ============================================================
# DEPLOYMENT CONFIGURATION
# ============================================================

CLUSTER_NAME="ledgerbase-local"
KUBE_CONTEXT="kind-ledgerbase-local"
NAMESPACE="ledgerbase"
REGISTRY="ghcr.io/angel-az41"

SERVICES=(
  accounting-core
  reporting-service
  gateway
  frontend
)

# ============================================================
# COMMAND-LINE VALIDATION
# ============================================================

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <40-character-commit-sha> --plan|--deploy"
  exit 1
fi

COMMIT_SHA="$1"
MODE="$2"

if [[ ! "$COMMIT_SHA" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Error: provide a full, lowercase 40-character Git SHA."
  exit 1
fi

if [[ "$MODE" != "--plan" && "$MODE" != "--deploy" ]]; then
  echo "Error: mode must be --plan or --deploy."
  exit 1
fi

# ============================================================
# TOOL AND CLUSTER VERIFICATION
# ============================================================

for tool in kubectl kind docker; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Error: $tool is not installed."
    exit 1
  fi
done

CURRENT_CONTEXT="$(kubectl config current-context)"

if [[ "$CURRENT_CONTEXT" != "$KUBE_CONTEXT" ]]; then
  echo "Error: unexpected Kubernetes context: $CURRENT_CONTEXT"
  echo "Expected: $KUBE_CONTEXT"
  exit 1
fi

kubectl --context "$KUBE_CONTEXT" \
  get namespace "$NAMESPACE" >/dev/null

# ============================================================
# DETECT KIND NODE ARCHITECTURE
# ============================================================
#
# Use the target Kubernetes node architecture rather than
# assuming the developer machine's CPU architecture.

ARCH="$(kubectl --context "$KUBE_CONTEXT" get nodes \
  -o jsonpath='{.items[0].status.nodeInfo.architecture}')"

if [[ "$ARCH" != "arm64" && "$ARCH" != "amd64" ]]; then
  echo "Error: unsupported node architecture: $ARCH"
  exit 1
fi

echo "Cluster: $CLUSTER_NAME"
echo "Namespace: $NAMESPACE"
echo "Platform: linux/$ARCH"
echo "Commit: $COMMIT_SHA"

# ============================================================
# VERIFY EXISTING APPLICATION DEPLOYMENTS
# ============================================================
#
# All target Deployments must already exist.
#
# A preloaded image requires Never or IfNotPresent
# image-pull behavior.

for service in "${SERVICES[@]}"; do
  policy="$(kubectl --context "$KUBE_CONTEXT" \
    -n "$NAMESPACE" \
    get deployment "$service" \
    -o jsonpath='{.spec.template.spec.containers[0].imagePullPolicy}')"

  if [[ "$policy" != "Never" && "$policy" != "IfNotPresent" ]]; then
    echo "Error: unsupported image pull policy for $service: $policy"
    exit 1
  fi
done

# ============================================================
# PREVIEW RELEASE IMAGES
# ============================================================

echo ""
echo "Release images:"

for service in "${SERVICES[@]}"; do
  image="$REGISTRY/ledgerbase-$service:sha-$COMMIT_SHA"
  echo "  $service -> $image"
done

if [[ "$MODE" == "--plan" ]]; then
  echo ""
  echo "Plan only: no images pulled or workloads changed."
  exit 0
fi

# ============================================================
# DOWNLOAD AND LOAD ALL IMAGES
# ============================================================
#
# Load every image before changing any Deployment.
#
# This helps avoid partially deploying a release because
# a later service image could not be downloaded.

for service in "${SERVICES[@]}"; do
  image="$REGISTRY/ledgerbase-$service:sha-$COMMIT_SHA"

  echo ""
  echo "Pulling $image"

  docker pull \
    --platform "linux/$ARCH" \
    "$image"

  echo "Loading image into kind..."

  kind load docker-image \
    "$image" \
    --name "$CLUSTER_NAME"
done

# ============================================================
# UPDATE APPLICATION DEPLOYMENTS
# ============================================================
#
# Infrastructure StatefulSets are deliberately excluded.
#
# Deployments are updated one at a time and Kubernetes
# readiness is verified before proceeding.

for service in "${SERVICES[@]}"; do
  image="$REGISTRY/ledgerbase-$service:sha-$COMMIT_SHA"

  echo ""
  echo "Deploying $service..."

  kubectl --context "$KUBE_CONTEXT" \
    -n "$NAMESPACE" \
    set image \
    "deployment/$service" \
    "$service=$image"

  if ! kubectl --context "$KUBE_CONTEXT" \
    -n "$NAMESPACE" \
    rollout status \
    "deployment/$service" \
    --timeout=300s; then

    echo "Deployment failed: $service"
    echo "Inspect the Pods and consider rollout undo."
    exit 1
  fi
done

# ============================================================
# VERIFY BASIC APPLICATION CONNECTIVITY
# ============================================================

echo ""
echo "Checking LedgerBase database health..."

curl --fail --silent --show-error \
  http://localhost:3001/api/v1/health/database

echo ""
echo "Checking unauthenticated financial API protection..."

STATUS="$(curl --silent \
  --output /dev/null \
  --write-out '%{http_code}' \
  http://localhost:3001/api/v1/businesses)"

if [[ "$STATUS" != "401" ]]; then
  echo "Error: expected HTTP 401, received HTTP $STATUS"
  exit 1
fi

echo ""
echo "LedgerBase release deployment completed."
echo "Commit: $COMMIT_SHA"
