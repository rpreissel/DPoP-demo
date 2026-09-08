# Reines Laufzeit-Image - der Build selbst passiert vorher auf dem Host per
# `./gradlew stagePodmanArtifacts` (baut Jar + Frontend, entpackt den Boot-Jar in einen flachen
# Classpath unter build/podman/orchestrator). Dieses Dockerfile kopiert nur noch das fertige
# Ergebnis; .dockerignore blendet den Rest des Repos (Quellen, node_modules, Gradle-Caches) aus
# dem Build-Kontext aus, ein COPY schlaegt also klar fehl, falls stagePodmanArtifacts vergessen
# wurde, statt heimlich veraltete Artefakte zu erwischen.
#
# Basis-Image ist von aussen ueberschreibbar (compose.yml build.args). Default ist Red Hat (UBI,
# authentifizierungsfrei ueber registry.access.redhat.com) - auch zuhause, nicht nur am
# Arbeitsplatz.
ARG RUNTIME_BASE_IMAGE=registry.access.redhat.com/ubi9/openjdk-21-runtime:latest

FROM ${RUNTIME_BASE_IMAGE} AS runtime
# UBI-Laufzeit-Images (Default) setzen bereits einen eigenen, nicht-root Default-User (z.B. UID
# 185 bei openjdk-21-runtime) - fuer unseren eigenen dpop-User muss die Stage trotzdem erst als
# root laufen, sonst fehlen die Rechte fuer useradd/chown selbst. Auf Alpine (root per Default)
# ist das ein No-Op.
USER root
WORKDIR /app

# Das Volume wird unter /data gemountet (fly.toml). Es gehoert dem Anwendungsnutzer, weil
# die H2-Datei zur Laufzeit angelegt und geschrieben wird - als root zu laufen, nur damit
# ein Verzeichnis beschreibbar ist, waere der falsche Tausch.
#
# addgroup/adduser (Busybox, Alpine) und groupadd/useradd (shadow-utils, UBI/RHEL) sind beide
# noetig, nicht austauschbar - welches Tool da ist, haengt vom ueberschreibbaren
# RUNTIME_BASE_IMAGE ab, nicht von einem festen Default.
RUN if command -v addgroup >/dev/null 2>&1; then \
      addgroup -S dpop && adduser -S dpop -G dpop; \
    else \
      groupadd -r dpop && useradd -r -g dpop dpop; \
    fi \
 && mkdir -p /data && chown dpop:dpop /data

# app.jar, lib/*.jar (flacher Classpath, siehe stagePodmanArtifacts in build.gradle.kts) und
# keycloak-migrations/migrations (von KeycloakMigrationRunnerStartup zur Laufzeit gelesen, kein
# Gradle-Resource-Prozessing) kommen alle schon fertig aus dem Staging-Verzeichnis.
COPY build/podman/orchestrator/ ./
RUN chown -R dpop:dpop /app
USER dpop

EXPOSE 8080

# MaxRAMPercentage statt fester Heap-Groesse: die JVM liest das Container-Limit, das in
# fly.toml steht, statt dass beide Zahlen getrennt gepflegt werden muessen.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp 'app.jar:lib/*' com.example.dpop.DpopApplicationKt"]
