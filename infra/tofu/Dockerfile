# Es gibt kein offizielles Red-Hat-Image fuer OpenTofu (weder registry.redhat.io noch
# registry.access.redhat.com fuehren eins) - deshalb selbst gebaut, auf einer UBI-Basis statt
# ghcr.io/opentofu/opentofu, damit die gesamte Compose-Umgebung ausschliesslich Red-Hat-Images
# zieht. Default-Basis ist bewusst schon Red Hat, nicht Alpine/Docker Hub: der Grund fuer dieses
# Dockerfile ist ja gerade, dass am Arbeitsplatz nur Red-Hat-Registries erreichbar sind - ein
# generischer Default waere dort sofort wieder nutzlos.
#
# Bewusst das volle ubi9-Image, nicht ubi9-minimal: es bringt curl/tar/gzip/bash/sha256sum schon
# mit, ubi9-minimal keins davon ausser curl/bash/sha256sum. Zusammen mit der .tar.gz-Variante der
# OpenTofu-Releases (statt .zip) und curl statt wget in run-keycloak-config.sh (siehe dort) ist so
# ueberhaupt kein `microdnf install` mehr noetig - der einzige Download ist OpenTofu selbst.
ARG OPENTOFU_BASE_IMAGE=registry.access.redhat.com/ubi9/ubi:latest
FROM ${OPENTOFU_BASE_IMAGE}

ARG OPENTOFU_VERSION=1.8.8
# Ueberschreibbar auf eine interne Spiegelung, falls github.com am Arbeitsplatz nicht erreichbar
# ist (gleiches Muster wie GRADLE_DISTRIBUTION_URL) - erwartet dieselbe Verzeichnisstruktur wie
# die offiziellen Releases (".../v<version>/tofu_<version>_linux_<arch>.tar.gz" + "..._SHA256SUMS").
ARG OPENTOFU_DOWNLOAD_BASE_URL=https://github.com/opentofu/opentofu/releases/download
# Von Podman/BuildKit automatisch gesetzt (amd64/arm64) - Default nur fuer manuelle
# `podman build`-Aufrufe ohne Buildx-Kontext.
ARG TARGETARCH=amd64

RUN set -eux; \
    base_url="${OPENTOFU_DOWNLOAD_BASE_URL}/v${OPENTOFU_VERSION}"; \
    archive_name="tofu_${OPENTOFU_VERSION}_linux_${TARGETARCH}.tar.gz"; \
    curl -fsSL "${base_url}/${archive_name}" -o "/tmp/${archive_name}"; \
    curl -fsSL "${base_url}/tofu_${OPENTOFU_VERSION}_SHA256SUMS" -o /tmp/tofu.sha256sums; \
    ( cd /tmp && grep " ${archive_name}\$" tofu.sha256sums | sha256sum -c - ); \
    tar -xzf "/tmp/${archive_name}" -C /usr/local/bin tofu; \
    chmod +x /usr/local/bin/tofu; \
    rm -f "/tmp/${archive_name}" /tmp/tofu.sha256sums
