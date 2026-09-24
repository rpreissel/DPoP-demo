#!/usr/bin/env bash
# Rollt den Prototyp in das aktuelle OpenShift-Projekt aus (`oc project` vorher waehlen).
#
# Ablauf:
#   1. Artefakte lokal bauen (Gradle) - die Images entstehen danach im Cluster.
#   2. ImageStreams/BuildConfigs anlegen, beide Images per Binary-Build bauen. Die Namespace-Quota
#      (4 GiB) reicht nicht fuer das laufende Deployment und den Keycloak-Build zugleich: ein
#      vorhandenes dpop-demo wird dafuer auf 0 skaliert und bei einem Fehler wieder hochgefahren.
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
# ueberschreibbar, in der Shell oder in .env (openshift/env.sh). Die Basis-Images zieht der Build
# im Cluster: fuer registry.redhat.io braucht er dort Zugangsdaten (meist schon im globalen
# Pull-Secret, sonst ein Pull-Secret am builder-Service-Account).
# SKIP_GRADLE=1 ueberspringt Schritt 1 (Artefakte schon gebaut).
set -euo pipefail
cd "$(dirname "$0")/.."
. openshift/env.sh

command -v oc >/dev/null || { echo "oc nicht gefunden" >&2; exit 1; }

project=$(oc project -q)
echo "==> Projekt: $project"

if [ "${SKIP_GRADLE:-}" != 1 ]; then
  echo "==> Artefakte bauen"
  ./gradlew stagePodmanArtifacts
fi

echo "==> OpenShift-Builds vorbereiten"
oc apply -f openshift/build.yaml

# Build-Args gehoeren in die BuildConfig: `oc start-build --build-arg` warnt bei Binary-Builds und
# wirkt nicht. Ohne gesetzte Variable wird buildArgs entfernt (merge-patch null), dann gilt der
# Default aus dem Dockerfile - kein zweiter Default hier, der beim naechsten Versionssprung veraltet.
set_build_arg() {
  local build_config=$1 arg=$2 value=$3 args=null
  [ -z "$value" ] || args="[{\"name\":\"$arg\",\"value\":\"$value\"}]"
  oc patch buildconfig "$build_config" --type=merge \
    -p "{\"spec\":{\"strategy\":{\"dockerStrategy\":{\"buildArgs\":$args}}}}" >/dev/null
}
set_build_arg dpop-demo-keycloak KEYCLOAK_BASE_IMAGE "${KEYCLOAK_BASE_IMAGE:-}"
set_build_arg dpop-demo-orchestrator RUNTIME_BASE_IMAGE "${ORCHESTRATOR_RUNTIME_BASE_IMAGE:-}"

for build_config in dpop-demo-keycloak dpop-demo-orchestrator; do
  while read -r build phase; do
    [ -n "$build" ] || continue
    case "$phase" in
      New)
        echo "  Veralteten wartenden Build $build abbrechen"
        oc cancel-build "$build"
        ;;
      Pending|Running)
        echo "Build $build laeuft bereits ($phase); abwarten oder abbrechen: oc cancel-build $build" >&2
        exit 1
        ;;
    esac
  done < <(oc get builds -l "openshift.io/build-config.name=$build_config" \
    -o custom-columns=NAME:.metadata.name,PHASE:.status.phase --no-headers)
done

original_replicas=
restore_deployment=0
# Vor dem Skalieren gesetzt: auch ein Timeout beim Herunterfahren stellt die Replikazahl wieder her.
on_exit() {
  status=$?
  trap - EXIT
  if [ "$status" -ne 0 ] && [ "$restore_deployment" = 1 ]; then
    echo "==> Vorherige Replikazahl $original_replicas wiederherstellen" >&2
    oc scale deployment/dpop-demo --replicas="$original_replicas" >&2 || true
  fi
  exit "$status"
}
trap on_exit EXIT

if oc get deployment dpop-demo >/dev/null 2>&1; then
  original_replicas=$(oc get deployment dpop-demo -o jsonpath='{.spec.replicas}')
  if [ "$original_replicas" -gt 0 ]; then
    restore_deployment=1
    echo "==> Deployment fuer Build-Quota auf 0 skalieren"
    oc scale deployment/dpop-demo --replicas=0
    oc rollout status deployment/dpop-demo --timeout=2m
  fi
fi

start_binary_build() {
  local name=$1
  local directory=$2

  echo "==> $name im Cluster bauen"
  oc start-build "$name" --from-dir="$directory" --follow --wait
}

start_binary_build dpop-demo-keycloak build/podman/keycloak
start_binary_build dpop-demo-orchestrator build/podman/orchestrator

echo "==> Service und Routes"
oc apply -f openshift/dpop-demo.yaml -l dpop-demo/part=routing
orchestrator_host=$(oc get route dpop-demo-orchestrator -o jsonpath='{.spec.host}')
keycloak_host=$(oc get route dpop-demo-keycloak -o jsonpath='{.spec.host}')
[ -n "$orchestrator_host" ] && [ -n "$keycloak_host" ] || {
  echo "Route-Hosts fehlen" >&2
  exit 1
}

echo "==> ConfigMap dpop-demo-env"
oc create configmap dpop-demo-env \
  --from-literal=PUBLIC_ORCHESTRATOR_URL="https://$orchestrator_host" \
  --from-literal=PUBLIC_KEYCLOAK_URL="https://$keycloak_host" \
  --dry-run=client -o yaml | oc apply -f -

if ! oc get secret dpop-demo-keycloak-admin >/dev/null 2>&1; then
  echo "==> Secret dpop-demo-keycloak-admin (neues Zufallspasswort)"
  oc create secret generic dpop-demo-keycloak-admin \
    --from-literal=username=admin \
    --from-literal=password="$(openssl rand -base64 32)"
fi

echo "==> Deployment"
oc apply -f openshift/dpop-demo.yaml
# Ein ConfigMap-Wechsel allein startet keinen Pod neu - bei unveraendertem Deployment von Hand.
oc rollout restart deployment/dpop-demo >/dev/null
oc rollout status deployment/dpop-demo --timeout=15m
restore_deployment=0

cat <<INFO

Fertig.
  Orchestrator:  https://$orchestrator_host/
  Keycloak:      https://$keycloak_host/admin/  (Benutzer admin, Passwort:
                 oc extract secret/dpop-demo-keycloak-admin --keys=password --to=-)
INFO
