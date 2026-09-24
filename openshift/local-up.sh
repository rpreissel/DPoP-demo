#!/usr/bin/env bash
# Lokaler Test von openshift/dpop-demo.yaml mit Podman: dieselbe Datei wie auf OpenShift, nur die
# ConfigMap/das Secret aus local.yaml statt aus deploy.sh. Podman ignoriert Routes und
# OpenShift-Annotationen; nicht pruefbar sind hier nur diese und die zufaellige UID (SCC).
#
# Voraussetzung: `./gradlew stagePodmanArtifacts`. Veroeffentlicht 8090 (Orchestrator) und 8091
# (Keycloak), damit es neben dem Compose-Setup (8080/8543) laufen kann.
# Stoppen: podman kube down openshift/dpop-demo.yaml (Volumes bleiben, --force loescht sie).
set -euo pipefail
cd "$(dirname "$0")/.."

podman build -t localhost/dpop-demo-keycloak:latest \
  --build-arg KEYCLOAK_BASE_IMAGE="${KEYCLOAK_BASE_IMAGE:-quay.io/keycloak/keycloak:26.6.4}" \
  build/podman/keycloak
podman build -t localhost/dpop-demo-orchestrator:latest \
  --build-arg RUNTIME_BASE_IMAGE="${ORCHESTRATOR_RUNTIME_BASE_IMAGE:-registry.access.redhat.com/ubi9/openjdk-21-runtime:latest}" \
  build/podman/orchestrator

# Das "---" dazwischen trennt das letzte Dokument der einen Datei vom ersten der anderen.
{ cat openshift/local.yaml; echo '---'; cat openshift/dpop-demo.yaml; } |
  podman kube play --replace --publish 8090:8080 --publish 8091:8081 -
