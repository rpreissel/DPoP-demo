# Drei Stufen, damit das Laufzeit-Image weder Node noch Gradle noch Quellen enthaelt.
#
# Alle drei Basis-Images sind von aussen ueberschreibbar (compose.yml build.args). Default ist
# durchgehend Red Hat (UBI, authentifizierungsfrei ueber registry.access.redhat.com) - auch
# zuhause, nicht nur am Arbeitsplatz; nur wo es kein Red-Hat-Image gibt oder es eine Subscription
# braucht (z.B. Keycloak selbst, siehe keycloak-extension/Dockerfile), bleibt es beim oeffentlichen
# Image. Am Arbeitsplatz zeigen dieselben Compose-Env-Vars stattdessen auf die dortige gespiegelte
# Registry.
ARG FRONTEND_BASE_IMAGE=registry.access.redhat.com/ubi9/nodejs-22:latest
ARG BUILD_BASE_IMAGE=registry.access.redhat.com/ubi9/openjdk-21:latest
ARG RUNTIME_BASE_IMAGE=registry.access.redhat.com/ubi9/openjdk-21-runtime:latest

# 1) Frontend. Vite schreibt nach ../src/main/resources/static (vite.config.ts),
#    also genau dorthin, wo processResources es spaeter erwartet.
FROM ${FRONTEND_BASE_IMAGE} AS frontend
# Die UBI-Images laufen per Default schon als nicht-root User (nodejs-22: UID 1001, Alpine:
# root) - fuer diese verworfene Build-Stage ist das irrelevant, aber /app muesste sonst erst
# angelegt/beschrieben werden koennen, egal welcher User das Basis-Image mitbringt.
USER root
WORKDIR /app/frontend
# Erst die Manifeste, dann der Rest: so bleibt der npm-Layer ueber Quellaenderungen hinweg
# im Cache.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# 2) Jar. npmInstall/npmBuild werden ausgelassen - das Ergebnis kommt fertig aus Stufe 1,
#    und ein zweites Mal npm im JDK-Image zu installieren waere reine Bauzeit.
# Wie die Basis-Images ueberschreibbar: am Arbeitsplatz kann services.gradle.org nicht erreichbar
# sein, die interne Registry liefert stattdessen ihre eigene Gradle-Distribution (siehe ./inittk,
# das denselben Austausch fuer Host-Builds ausserhalb von Docker macht - hier dasselbe Ziel, aber
# ueber ein Build-Arg statt eine Datei im Arbeitsbaum anzufassen).
ARG GRADLE_DISTRIBUTION_URL=""

FROM ${BUILD_BASE_IMAGE} AS build
ARG GRADLE_DISTRIBUTION_URL
USER root
WORKDIR /app
COPY gradlew ./
COPY gradle/ gradle/
RUN if [ -n "$GRADLE_DISTRIBUTION_URL" ]; then \
      escaped_url=$(printf '%s' "$GRADLE_DISTRIBUTION_URL" | sed 's/:/\\\\:/g'); \
      sed -i "s|^distributionUrl=.*|distributionUrl=${escaped_url}|" gradle/wrapper/gradle-wrapper.properties; \
    fi
COPY settings.gradle.kts build.gradle.kts ./
COPY keycloak-extension/build.gradle.kts keycloak-extension/build.gradle.kts
# keycloak-migrations ist eine echte Compile-Abhaengigkeit der Root-App (implementation(project(":keycloak-migrations"))
# in build.gradle.kts), nicht nur ein separat gebautes Artefakt wie keycloak-extension - ihr
# Quellcode muss deshalb hier mit rein, sonst schlaegt schon die Dependency-Aufloesung fehl.
COPY keycloak-migrations/build.gradle.kts keycloak-migrations/build.gradle.kts
# Dependencies vorziehen, damit der Layer nur bei Aenderung der Build-Dateien neu laeuft.
RUN ./gradlew --no-daemon dependencies --configuration runtimeClasspath > /dev/null 2>&1 || true
COPY src/ src/
COPY keycloak-extension/src keycloak-extension/src
COPY keycloak-migrations/src keycloak-migrations/src
COPY --from=frontend /app/src/main/resources/static/ src/main/resources/static/
RUN ./gradlew --no-daemon bootJar -x npmInstall -x npmBuild

# 3) Laufzeit.
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
COPY --from=build /app/build/libs/*.jar boot.jar
# kotlin-scripting-jvm-host (KeycloakMigrationRunnerStartup) bricht in der gepackten Boot-Fat-Jar
# ("java -jar boot.jar") mit mehreren "Unable to find ..."-Fehlern ab (Stdlib-Erkennung, dann
# extensions/compiler.xml) - kotlin-compiler-embeddable geht von einem klassischen, flachen
# Classpath aus einzelnen Jar-Dateien aus (java.class.path-Scan), nicht von Boots
# BOOT-INF/lib-Nested-Jars. jarmode=tools extract entpackt genau so einen flachen Classpath
# (app.jar + lib/*.jar) - Standard-Spring-Boot-Mechanismus fuer exakt diesen Interop-Fall.
RUN java -Djarmode=tools -jar boot.jar extract --destination /tmp/extracted \
 && mv /tmp/extracted/boot.jar app.jar \
 && mv /tmp/extracted/lib . \
 && rm -rf boot.jar /tmp/extracted
# KeycloakMigrationRunnerStartup liest diese Dateien zur Laufzeit (kein Build-Artefakt, kein
# Gradle-Resource-Prozessing) - relativer Pfad passt zu keycloak-migrate.migrations-dir
# (application-keycloak.yml), WORKDIR ist /app.
COPY keycloak-migrations/migrations keycloak-migrations/migrations
RUN chown -R dpop:dpop /app
USER dpop

EXPOSE 8080

# MaxRAMPercentage statt fester Heap-Groesse: die JVM liest das Container-Limit, das in
# fly.toml steht, statt dass beide Zahlen getrennt gepflegt werden muessen.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp 'app.jar:lib/*' com.example.dpop.DpopApplicationKt"]
