# Drei Stufen, damit das Laufzeit-Image weder Node noch Gradle noch Quellen enthaelt.

# 1) Frontend. Vite schreibt nach ../src/main/resources/static (vite.config.ts),
#    also genau dorthin, wo processResources es spaeter erwartet.
FROM node:22-alpine AS frontend
WORKDIR /app/frontend
# Erst die Manifeste, dann der Rest: so bleibt der npm-Layer ueber Quellaenderungen hinweg
# im Cache.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# 2) Jar. npmInstall/npmBuild werden ausgelassen - das Ergebnis kommt fertig aus Stufe 1,
#    und ein zweites Mal npm im JDK-Image zu installieren waere reine Bauzeit.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradlew ./
COPY gradle/ gradle/
COPY settings.gradle.kts build.gradle.kts ./
# Dependencies vorziehen, damit der Layer nur bei Aenderung der Build-Dateien neu laeuft.
RUN ./gradlew --no-daemon dependencies --configuration runtimeClasspath > /dev/null 2>&1 || true
COPY src/ src/
COPY --from=frontend /app/src/main/resources/static/ src/main/resources/static/
RUN ./gradlew --no-daemon bootJar -x npmInstall -x npmBuild

# 3) Laufzeit.
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Das Volume wird unter /data gemountet (fly.toml). Es gehoert dem Anwendungsnutzer, weil
# die H2-Datei zur Laufzeit angelegt und geschrieben wird - als root zu laufen, nur damit
# ein Verzeichnis beschreibbar ist, waere der falsche Tausch.
RUN addgroup -S dpop && adduser -S dpop -G dpop && mkdir -p /data && chown dpop:dpop /data
COPY --from=build --chown=dpop:dpop /app/build/libs/*.jar app.jar
USER dpop

EXPOSE 8080

# MaxRAMPercentage statt fester Heap-Groesse: die JVM liest das Container-Limit, das in
# fly.toml steht, statt dass beide Zahlen getrennt gepflegt werden muessen.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
