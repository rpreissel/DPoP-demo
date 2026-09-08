# Reines Laufzeit-Image - der Build passiert vorher auf dem Host per
# `./gradlew stagePodmanArtifacts` (baut :keycloak-extension:shadowJar und kopiert Jar + Theme
# nach build/podman/keycloak). Dieses Dockerfile kopiert nur noch das fertige Ergebnis;
# .dockerignore blendet den Rest des Repos aus dem Build-Kontext aus.
#
# Keycloak selbst bleibt beim oeffentlichen Community-Image: das Red-Hat-Pendant
# (registry.redhat.io/rhbk/keycloak-rhel9) braucht eine aktive Subscription zum Pull (anonym 403)
# und zaehlt Versionen unabhaengig vom Community-Keycloak - kein freier Ersatz, den man default
# setzen koennte. Am Arbeitsplatz zeigt dieselbe Compose-Env-Var stattdessen auf die dortige
# gespiegelte Registry bzw. das RHBK-Image.
ARG KEYCLOAK_BASE_IMAGE=quay.io/keycloak/keycloak:26.5.5

FROM ${KEYCLOAK_BASE_IMAGE}

COPY build/podman/keycloak/dpop-demo-keycloak-extension.jar /opt/keycloak/providers/dpop-demo-keycloak-extension.jar
COPY build/podman/keycloak/theme/orchestrator /opt/keycloak/themes/orchestrator
# Healthcheck-Programm (siehe compose.yml) - Single-File-Source-Launch (JEP 330, `java
# JwksHealthCheck.java`), kein separates javac noetig.
COPY keycloak-extension/healthcheck/JwksHealthCheck.java /opt/keycloak/healthcheck/JwksHealthCheck.java

# start-dev does NOT auto-generate an HTTPS certificate on this version - a self-signed one, baked
# in at build time, is enough for local dev (docs/ideen/web-keycloak-kanal.md #1: only the client
# certificate is out of scope, server-side TLS is still the baseline). Cookie-based SSO reuse
# (auth-cookie, the step-up path's precondition) needs a secure context or the browser drops the
# cookie on cross-origin requests.
RUN keytool -genkeypair -alias server -keyalg RSA -keysize 2048 -validity 3650 \
      -keystore /opt/keycloak/conf/server.keystore.p12 -storetype PKCS12 -storepass password \
      -dname "CN=localhost" -ext "SAN=dns:localhost,ip:127.0.0.1"

USER keycloak
RUN /opt/keycloak/bin/kc.sh build
