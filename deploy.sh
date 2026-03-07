#!/usr/bin/env bash
set -euo pipefail

APP="seatracesrv"
NAMESPACE="default"
RELEASE="seatracesrv"
REGISTRY="localhost:32000"
CHART="$(dirname "$0")/helm/seatracesrv"

TAG="${1:-$(git rev-parse --short HEAD 2>/dev/null || date +%Y%m%d-%H%M%S)}"
IMAGE="${REGISTRY}/${APP}:${TAG}"

# ── 1. Build ──────────────────────────────────────────────────────────────────
echo "==> Building image: ${IMAGE}"
docker build -t "${IMAGE}" .

# ── 2. Push ───────────────────────────────────────────────────────────────────
echo "==> Pushing image to local registry"
docker push "${IMAGE}"

# ── 3. Helm deploy ────────────────────────────────────────────────────────────
echo "==> Deploying with Helm (release: ${RELEASE}, namespace: ${NAMESPACE})"

# Resolve API key: prefer env var, else read from .env file next to this script
if [[ -z "${AISSTREAM_API_KEY:-}" ]]; then
  ENV_FILE="$(dirname "$0")/.env"
  if [[ -f "${ENV_FILE}" ]]; then
    AISSTREAM_API_KEY="$(grep -E '^AISSTREAM_API_KEY=' "${ENV_FILE}" | cut -d= -f2-)"
  fi
fi

if [[ -z "${AISSTREAM_API_KEY:-}" ]]; then
  echo "ERROR: AISSTREAM_API_KEY is not set. Export it or add it to .env" >&2
  exit 1
fi

microk8s helm3 upgrade --install "${RELEASE}" "${CHART}" \
  --namespace "${NAMESPACE}" \
  --create-namespace \
  --set "image.repository=${REGISTRY}/${APP}" \
  --set "image.tag=${TAG}" \
  --set "image.pullPolicy=IfNotPresent" \
  --set "secret.aisStreamApiKey=${AISSTREAM_API_KEY}" \
  --wait \
  --timeout 120s

# ── 4. Done ───────────────────────────────────────────────────────────────────
echo "==> Done"
echo "Deployed image: ${IMAGE}"
echo ""
echo "Useful commands:"
echo "  microk8s kubectl -n ${NAMESPACE} get pods -l app.kubernetes.io/name=${APP}"
echo "  microk8s kubectl -n ${NAMESPACE} logs -l app.kubernetes.io/name=${APP} -f"
echo "  microk8s kubectl -n ${NAMESPACE} port-forward svc/${APP} 8080:8080 --address 0.0.0.0"
