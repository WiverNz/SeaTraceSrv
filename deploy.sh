#!/usr/bin/env bash
set -euo pipefail

APP="seatracesrv"
WORKER="catalog-worker"
NAMESPACE="default"
RELEASE="seatracesrv"
REGISTRY="localhost:32000"
CHART="$(dirname "$0")/helm/seatracesrv"

TAG="${1:-$(git rev-parse --short HEAD 2>/dev/null || date +%Y%m%d-%H%M%S)}"
IMAGE="${REGISTRY}/${APP}:${TAG}"
WORKER_IMAGE="${REGISTRY}/${WORKER}:${TAG}"

# ── 1. Build ──────────────────────────────────────────────────────────────────
echo "==> Building image: ${IMAGE}"
docker build -t "${IMAGE}" .

echo "==> Building catalog-worker image: ${WORKER_IMAGE}"
docker build -f "workers/${WORKER}/Dockerfile" -t "${WORKER_IMAGE}" .

# ── 2. Push ───────────────────────────────────────────────────────────────────
echo "==> Pushing images to local registry"
docker push "${IMAGE}"
docker push "${WORKER_IMAGE}"

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
  --set "catalogWorker.image.repository=${REGISTRY}/${WORKER}" \
  --set "catalogWorker.image.tag=${TAG}" \
  --set "catalogWorker.image.pullPolicy=IfNotPresent" \
  --atomic \
  --timeout 120s

# ── 4. Done ───────────────────────────────────────────────────────────────────
echo "==> Done"
echo "Deployed image:        ${IMAGE}"
echo "Catalog-worker image:  ${WORKER_IMAGE}"
echo ""
echo "Useful commands:"
echo "  microk8s kubectl -n ${NAMESPACE} get pods -l app.kubernetes.io/name=${APP}"
echo "  microk8s kubectl -n ${NAMESPACE} logs -l app.kubernetes.io/name=${APP} -f"
echo "  microk8s kubectl -n ${NAMESPACE} port-forward svc/${APP} 8080:8080 --address 0.0.0.0"
echo ""
echo "  # Enable catalog-worker CronJob (disabled by default):"
echo "  microk8s helm3 upgrade ${RELEASE} ${CHART} --reuse-values --set catalogWorker.enabled=true"
echo "  # Trigger a manual run immediately:"
echo "  microk8s kubectl -n ${NAMESPACE} create job --from=cronjob/${RELEASE}-catalog-worker catalog-worker-manual-\$(date +%s)"
