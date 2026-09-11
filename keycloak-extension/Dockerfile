# Reines Laufzeit-Image - der Build passiert vorher auf dem Host per
# `./gradlew stagePodmanArtifacts` (baut :keycloak-extension:shadowJar und kopiert Jar, Theme,
# Healthcheck und dieses Dockerfile selbst nach build/podman/keycloak, siehe stageKeycloakArtifact
# in build.gradle.kts). Dieses Dockerfile kopiert nur noch das fertige Ergebnis, mit Pfaden
# relativ zu build/podman/keycloak - genau das ist auch der Build-Kontext, den compose.yml fuer
# den keycloak-Service angibt. So muss Podman nicht mehr das ganze Repo (inkl. .git,
# node_modules, Gradle-Caches) durchlaufen und hochladen, sondern nur noch das kleine
# Staging-Verzeichnis.
#
# Keycloak selbst bleibt beim oeffentlichen Community-Image: das Red-Hat-Pendant
# (registry.redhat.io/rhbk/keycloak-rhel9) braucht eine aktive Subscription zum Pull (anonym 403)
# und zaehlt Versionen unabhaengig vom Community-Keycloak - kein freier Ersatz, den man default
# setzen koennte. Am Arbeitsplatz zeigt dieselbe Compose-Env-Var stattdessen auf die dortige
# gespiegelte Registry bzw. das RHBK-Image.
ARG KEYCLOAK_BASE_IMAGE=quay.io/keycloak/keycloak:26.5.5

FROM ${KEYCLOAK_BASE_IMAGE}

COPY dpop-demo-keycloak-extension.jar /opt/keycloak/providers/dpop-demo-keycloak-extension.jar
COPY theme/orchestrator /opt/keycloak/themes/orchestrator
# Healthcheck-Programm (siehe compose.yml) - Single-File-Source-Launch (JEP 330, `java
# JwksHealthCheck.java`), kein separates javac noetig.
COPY healthcheck/JwksHealthCheck.java /opt/keycloak/healthcheck/JwksHealthCheck.java

# start-dev does NOT auto-generate an HTTPS certificate on this version - a self-signed one is
# needed for local dev (docs/12-entscheidungen.md ADR-7: only the client certificate is out of
# scope, server-side TLS is still the baseline). Cookie-based SSO reuse (auth-cookie, the step-up
# path's precondition) needs a secure context or the browser drops the cookie on cross-origin
# requests. Deliberately NOT generated here at image-build time (that produced a fresh keypair on
# every rebuild - Chrome then treats localhost:8543 as a NEW host and re-prompts/hard-blocks the
# cert warning every time): compose.yml's command now generates it once into the persistent
# keycloak-data volume on first container start and reuses it after, so the cert survives image
# rebuilds and only ever changes if that volume is removed.

USER keycloak
RUN /opt/keycloak/bin/kc.sh build
