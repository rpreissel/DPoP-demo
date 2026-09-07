# Beide Basis-Images sind von aussen ueberschreibbar (compose.yml build.args). Die Builder-Stage
# ist wie ueberall Red Hat per Default (UBI, authentifizierungsfrei). Keycloak selbst bleibt beim
# oeffentlichen Community-Image: das Red-Hat-Pendant (registry.redhat.io/rhbk/keycloak-rhel9)
# braucht eine aktive Subscription zum Pull (anonym 403) und zaehlt Versionen unabhaengig vom
# Community-Keycloak - kein freier Ersatz, den man default setzen koennte. Am Arbeitsplatz zeigen
# dieselben Compose-Env-Vars stattdessen auf die dortige gespiegelte Registry bzw. das
# RHBK-Image.
ARG BUILDER_BASE_IMAGE=registry.access.redhat.com/ubi9/openjdk-21:latest
ARG KEYCLOAK_BASE_IMAGE=quay.io/keycloak/keycloak:26.5.5
# Wie bei den Basis-Images: am Arbeitsplatz kann services.gradle.org unerreichbar sein, die
# interne Registry liefert stattdessen ihre eigene Gradle-Distribution (siehe ./inittk, das
# denselben Austausch fuer Host-Builds ausserhalb von Docker macht).
ARG GRADLE_DISTRIBUTION_URL=""

FROM ${BUILDER_BASE_IMAGE} AS builder
ARG GRADLE_DISTRIBUTION_URL
# ubi9/openjdk-21 laeuft per Default schon als nicht-root User (UID 185) - fuer diese verworfene
# Build-Stage irrelevant, aber /workspace muesste sonst erst angelegt/beschrieben werden koennen.
USER root

WORKDIR /workspace
COPY gradlew ./
COPY gradle/ gradle/
RUN if [ -n "$GRADLE_DISTRIBUTION_URL" ]; then \
      escaped_url=$(printf '%s' "$GRADLE_DISTRIBUTION_URL" | sed 's/:/\\\\:/g'); \
      sed -i "s|^distributionUrl=.*|distributionUrl=${escaped_url}|" gradle/wrapper/gradle-wrapper.properties; \
    fi
COPY settings.gradle.kts build.gradle.kts ./
COPY keycloak-extension/build.gradle.kts keycloak-extension/build.gradle.kts
COPY keycloak-extension/src keycloak-extension/src
RUN ./gradlew --no-daemon :keycloak-extension:shadowJar

FROM ${KEYCLOAK_BASE_IMAGE}

COPY --from=builder /workspace/keycloak-extension/build/libs/dpop-demo-keycloak-extension-0.1.0.jar /opt/keycloak/providers/dpop-demo-keycloak-extension.jar
COPY keycloak-extension/src/main/resources/theme/orchestrator /opt/keycloak/themes/orchestrator

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
