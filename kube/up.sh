#!/usr/bin/env bash
# PROTOTYP: startet kube/dpop-demo.yaml per `podman kube play` - baut nichts. Die Images muessen
# vorher mit kube/build.sh entstanden sein (imagePullPolicy: Never in der YAML).
# --replace ersetzt laufende Pods; die Volumes bleiben erhalten.
set -euo pipefail
cd "$(dirname "$0")/.."

podman kube play --replace kube/dpop-demo.yaml
