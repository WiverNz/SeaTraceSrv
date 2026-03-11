#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="${NAMESPACE:-default}"
RELEASE="${RELEASE:-seatracesrv}"

# stern matches pods by label selector; both components share the instance label.
# Using --selector covers the main server pods, catalog-worker Job pods, and
# any CronJob-spawned pods in one invocation.
exec stern \
  --namespace "${NAMESPACE}" \
  --selector "app.kubernetes.io/instance=${RELEASE}" \
  --output multi \
  --color always \
  "$@"
