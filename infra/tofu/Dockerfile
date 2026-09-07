# Es gibt kein offizielles Red-Hat-Image fuer OpenTofu (weder registry.redhat.io noch
# registry.access.redhat.com fuehren eins) - deshalb selbst gebaut, auf einer UBI-Basis statt
# ghcr.io/opentofu/opentofu, damit die gesamte Compose-Umgebung ausschliesslich Red-Hat-Images
# zieht. Default-Basis ist bewusst schon Red Hat, nicht Alpine/Docker Hub: der Grund fuer dieses
# Dockerfile ist ja gerade, dass am Arbeitsplatz nur Red-Hat-Registries erreichbar sind - ein
# generischer Default waere dort sofort wieder nutzlos.
ARG OPENTOFU_BASE_IMAGE=registry.access.redhat.com/ubi9/ubi-minimal:latest
FROM ${OPENTOFU_BASE_IMAGE}

ARG OPENTOFU_VERSION=1.8.8
# Ueberschreibbar auf eine interne Spiegelung, falls github.com am Arbeitsplatz nicht erreichbar
# ist (gleiches Muster wie GRADLE_DISTRIBUTION_URL) - erwartet dieselbe Verzeichnisstruktur wie
# die offiziellen Releases (".../v<version>/tofu_<version>_linux_<arch>.zip" + "..._SHA256SUMS").
ARG OPENTOFU_DOWNLOAD_BASE_URL=https://github.com/opentofu/opentofu/releases/download
# Von Podman/BuildKit automatisch gesetzt (amd64/arm64) - Default nur fuer manuelle
# `podman build`-Aufrufe ohne Buildx-Kontext.
ARG TARGETARCH=amd64

# bash, curl und sha256sum sind bereits Teil von ubi-minimal (run-keycloak-config.sh braucht
# bash als Shebang/fuer ${var/pattern/repl}). unzip fehlt (fuer das Release-Archiv), wget fehlt
# (run-keycloak-config.sh nutzt es selbst zum Warten auf Keycloaks Health-Endpoint) - beide nach.
RUN microdnf install -y wget unzip \
 && microdnf clean all

RUN set -eux; \
    base_url="${OPENTOFU_DOWNLOAD_BASE_URL}/v${OPENTOFU_VERSION}"; \
    zip_name="tofu_${OPENTOFU_VERSION}_linux_${TARGETARCH}.zip"; \
    curl -fsSL "${base_url}/${zip_name}" -o "/tmp/${zip_name}"; \
    curl -fsSL "${base_url}/tofu_${OPENTOFU_VERSION}_SHA256SUMS" -o /tmp/tofu.sha256sums; \
    ( cd /tmp && grep " ${zip_name}\$" tofu.sha256sums | sha256sum -c - ); \
    unzip -o "/tmp/${zip_name}" tofu -d /usr/local/bin; \
    chmod +x /usr/local/bin/tofu; \
    rm -f "/tmp/${zip_name}" /tmp/tofu.sha256sums
