#!/usr/bin/env bash
# Rollt den Prototyp in das aktuelle OpenShift-Projekt aus (`oc project` vorher waehlen).
#
# Ablauf:
#   1. Artefakte lokal bauen (Gradle).
#   2. Images lokal mit Podman bauen (linux/amd64) und ueber die Route der internen Registry in die
#      ImageStreams pushen. Kein Binary-Build im Cluster: dessen Upload ueber die API scheitert am
#      Arbeitsplatz schon bei wenigen MB an einem Timeout, der Registry-Push geht ueber den Router.
#   3. Service und Routes zuerst anlegen: OpenShift vergibt die Route-Hosts, und genau diese sind
#      die oeffentlichen Adressen fuer Keycloak (KC_HOSTNAME) und den Orchestrator.
#   4. ConfigMap dpop-demo-env mit diesen Hosts, Secret fuer den Keycloak-Admin (einmalig, mit
#      zufaelligem Passwort - kein admin/admin auf einem Cluster).
#   5. Den Rest anwenden und auf den Rollout warten.
#
# Idempotent: ein zweiter Lauf baut neu und rollt aus. Die Route-Hosts bleiben dabei gleich - das
# ist wichtig, denn sie gehoeren zum Realm-Aufbau; aendern sie sich, baut die Migration das Realm neu.
#
# Basis-Images wie bei Compose per KEYCLOAK_BASE_IMAGE / ORCHESTRATOR_RUNTIME_BASE_IMAGE
# ueberschreibbar, in der Shell oder in .env (openshift/env.sh). Die Basis-Images zieht der lokale
# Build: fuer registry.redhat.io vorher `podman login registry.redhat.io`.
#
# Registry: REGISTRY_HOST, sonst der Host der Route default-route in openshift-image-registry
# (muss ein Cluster-Admin einmal freischalten; ohne Leserecht dort REGISTRY_HOST setzen).
# SKIP_GRADLE=1 ueberspringt Schritt 1 (Artefakte schon gebaut).
set -euo pipefail
cd "$(dirname "$0")/.."
. openshift/env.sh

command -v oc >/dev/null || { echo "oc nicht gefunden" >&2; exit 1; }
command -v podman >/dev/null || { echo "podman nicht gefunden" >&2; exit 1; }
project=$(oc project -q)
echo "==> Projekt: $project"

registry=${REGISTRY_HOST:-$(oc get route default-route -n openshift-image-registry \
  -o jsonpath='{.spec.host}' 2>/dev/null || true)}
[ -n "$registry" ] || {
  echo "Registry-Host unbekannt: REGISTRY_HOST setzen oder die Route default-route freischalten lassen" >&2
  exit 1
}

if [ "${SKIP_GRADLE:-}" != 1 ]; then
  echo "==> Artefakte bauen"
  ./gradlew stagePodmanArtifacts
fi

echo "==> Images bauen und nach $registry pushen"
# Vor dem Push: lookupPolicy local, damit der Kurzname in dpop-demo.yaml auf den ImageStream aufloest.
oc apply -f openshift/imagestreams.yaml
oc whoami -t | podman login --username "$(oc whoami)" --password-stdin "$registry"

# linux/amd64 fest: auf einem Mac mit Apple Silicon baut Podman sonst arm64, und das startet im Cluster nicht.
podman build --platform linux/amd64 -t "$registry/$project/dpop-demo-keycloak:latest" \
  ${KEYCLOAK_BASE_IMAGE:+--build-arg=KEYCLOAK_BASE_IMAGE=$KEYCLOAK_BASE_IMAGE} build/podman/keycloak
podman push "$registry/$project/dpop-demo-keycloak:latest"
podman build --platform linux/amd64 -t "$registry/$project/dpop-demo-orchestrator:latest" \
  ${ORCHESTRATOR_RUNTIME_BASE_IMAGE:+--build-arg=RUNTIME_BASE_IMAGE=$ORCHESTRATOR_RUNTIME_BASE_IMAGE} build/podman/orchestrator
podman push "$registry/$project/dpop-demo-orchestrator:latest"

echo "==> Service und Routes"
oc apply -f openshift/dpop-demo.yaml -l dpop-demo/part=routing
orchestrator_host=$(oc get route dpop-demo-orchestrator -o jsonpath='{.spec.host}')
keycloak_host=$(oc get route dpop-demo-keycloak -o jsonpath='{.spec.host}')
[ -n "$orchestrator_host" ] && [ -n "$keycloak_host" ] || { echo "Route-Hosts fehlen" >&2; exit 1; }

echo "==> ConfigMap dpop-demo-env"
oc create configmap dpop-demo-env \
  --from-literal=PUBLIC_ORCHESTRATOR_URL="https://$orchestrator_host" \
  --from-literal=PUBLIC_KEYCLOAK_URL="https://$keycloak_host" \
  --dry-run=client -o yaml | oc apply -f -

if ! oc get secret dpop-demo-keycloak-admin >/dev/null 2>&1; then
  echo "==> Secret dpop-demo-keycloak-admin (neues Zufallspasswort)"
  oc create secret generic dpop-demo-keycloak-admin \
    --from-literal=username=admin \
    --from-literal=password="$(openssl rand -base64 24)"
fi

echo "==> Deployment"
oc apply -f openshift/dpop-demo.yaml
# Ein ConfigMap-Wechsel allein startet keinen Pod neu - bei unveraendertem Deployment von Hand.
oc rollout restart deployment/dpop-demo >/dev/null
oc rollout status deployment/dpop-demo --timeout=15m

cat <<INFO

Fertig.
  Orchestrator:  https://$orchestrator_host/
  Keycloak:      https://$keycloak_host/admin/  (Benutzer admin, Passwort:
                 oc extract secret/dpop-demo-keycloak-admin --keys=password --to=-)
INFO
