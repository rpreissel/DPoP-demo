#!/usr/bin/env bash
# PROTOTYP: baut beide Images fuer kube/dpop-demo.yaml. Starten danach mit kube/up.sh.
# Das Bauen liegt hier statt in der YAML, weil kube play keine build.args kennt - die
# Basis-Images bleiben so wie bei compose.yml per Env-Var ueberschreibbar.
# Voraussetzung wie bei Compose: `./gradlew stagePodmanArtifacts` ist gelaufen.
set -euo pipefail
cd "$(dirname "$0")/.."

podman build -t localhost/dpop-demo-kube-keycloak:latest \
  --build-arg KEYCLOAK_BASE_IMAGE="${KEYCLOAK_BASE_IMAGE:-quay.io/keycloak/keycloak:26.6.4}" \
  build/podman/keycloak
podman build -t localhost/dpop-demo-kube-orchestrator:latest \
  --build-arg RUNTIME_BASE_IMAGE="${ORCHESTRATOR_RUNTIME_BASE_IMAGE:-registry.access.redhat.com/ubi9/openjdk-21-runtime:latest}" \
  build/podman/orchestrator
