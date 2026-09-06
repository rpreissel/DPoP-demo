FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /workspace
COPY gradlew ./
COPY gradle/ gradle/
COPY settings.gradle.kts build.gradle.kts ./
COPY keycloak-extension/build.gradle.kts keycloak-extension/build.gradle.kts
COPY keycloak-extension/src keycloak-extension/src
RUN ./gradlew --no-daemon :keycloak-extension:shadowJar

FROM quay.io/keycloak/keycloak:26.5.5

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
