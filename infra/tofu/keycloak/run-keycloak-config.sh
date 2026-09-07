#!/usr/bin/env bash
set -eu

STATE_DIR="${TOFU_STATE_DIR:-/state}"
STATE_PATH="${STATE_DIR}/keycloak.tfstate"

mkdir -p "${STATE_DIR}"

# Keycloak 26's health endpoints live on the management interface (port 9000 by default), not the
# app port itself - same swap auth-sandbox-2's own run script makes. HTTP is disabled entirely
# (compose.yml), so KEYCLOAK_URL is already https://keycloak:8443 - -k (insecure) is fine here,
# this is a one-shot in-cluster readiness poll, not a browser trusting the cert. curl statt wget,
# weil das schon Teil des OpenTofu-Images ist (infra/tofu/Dockerfile) - kein Extra-Paket dafuer.
MANAGEMENT_URL="${KEYCLOAK_URL/:8443/:9000}"

until curl -sfk -o /dev/null "${MANAGEMENT_URL}/health/ready"; do
  echo "waiting for keycloak..."
  sleep 3
done

attempt=1

apply_config() {
  tofu init -input=false && tofu apply \
    -input=false \
    -auto-approve \
    -state="${STATE_PATH}" \
    -var="keycloak_url=${KEYCLOAK_URL}" \
    -var="keycloak_admin=${KEYCLOAK_ADMIN}" \
    -var="keycloak_admin_password=${KEYCLOAK_ADMIN_PASSWORD}" \
    -var="realm_name=${KEYCLOAK_REALM}" \
    -var="browser_client_id=${KEYCLOAK_BROWSER_CLIENT_ID:-dpop-demo-web}" \
    -var="browser_client_secret=${KEYCLOAK_BROWSER_CLIENT_SECRET:-change-me-browser}" \
    -var="orchestrator_admin_client_secret=${ORCHESTRATOR_ADMIN_CLIENT_SECRET:-change-me-orchestrator-admin}"
}

while true; do
  if apply_config; then
    echo "keycloak config applied successfully"
    exit 0
  fi

  echo "keycloak config apply failed on attempt ${attempt}; retrying in 5 seconds"
  attempt=$((attempt + 1))
  sleep 5
done
