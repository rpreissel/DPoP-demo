# DPoP-Demo — Podman Compose Setup

## Standard-Start

```bash
podman-compose up --build
```

Startet `keycloak` (echtes Keycloak, HTTPS auf Port 8543) und den containerisierten
`orchestrator` (Port 8080, eigenes Volume `orchestrator-data` für die H2-Datei-DB). Der
Orchestrator wendet beim eigenen Start automatisch alle `keycloak-migrations/migrations/*.kc.kts`
an (`KeycloakMigrationRunnerStartup`, Ersatz für den früheren separaten OpenTofu-Realm-Import) und
spricht Keycloak intern als `https://keycloak:8443` an, nicht `https://localhost:8543`.

Alternativ weiterhin lokal per `./gradlew bootRun` betreibbar (z. B. für schnellere
Iterationszyklen) — dann nur `keycloak` aus Compose starten und den Orchestrator-Service ignorieren
(`podman-compose up keycloak`); `application-keycloak.yml` zeigt in dem Fall auf
`https://localhost:8543`.

## Basis-Images von außen konfigurieren

**Grundsatz: Red Hat, wo es geht — auch zuhause, nicht nur am Arbeitsplatz.** Alle Basis-Images
defaulten auf Red-Hat-UBI-Images über `registry.access.redhat.com` (authentifizierungsfrei, freie
UBI-EULA, funktioniert ohne Red-Hat-Account). Nur wo es kein Red-Hat-Image gibt oder es eine
aktive Subscription braucht, bleibt der Default beim öffentlichen Image (Docker Hub / Quay /
GitHub Releases) — das betrifft aktuell nur das Keycloak-Laufzeit-Image selbst
(`KEYCLOAK_BASE_IMAGE`, siehe Hinweis unten) und die Gradle-Distribution.

Alle Basis-Images (in den Dockerfiles per `ARG` und in `compose.yml` per `image:`/`build.args`)
lassen sich ohne Änderung an den Dockerfiles überschreiben — z. B. am Arbeitsplatz auf eine
gespiegelte interne Registry oder auf `registry.redhat.io` mit aktiver Subscription.

Steuerung über Umgebungsvariablen, die Podman Compose automatisch aus einer `.env`-Datei im
Projektverzeichnis liest (`.env`/`.env.local` sind gitignored — für den Arbeitsplatz liegt eine
Vorlage unter [`.env.work.example`](.env.work.example) bei, einfach kopieren:
`cp .env.work.example .env.local`).

| Variable | Default | Wirkt auf |
|---|---|---|
| `KEYCLOAK_BUILDER_BASE_IMAGE` | `registry.access.redhat.com/ubi9/openjdk-21:latest` | `keycloak-extension/Dockerfile` — Builder-Stage (Gradle-Shadow-Jar) |
| `KEYCLOAK_BASE_IMAGE` | `quay.io/keycloak/keycloak:26.5.5` | `keycloak-extension/Dockerfile` — Keycloak-Laufzeit-Image (öffentlich, siehe Hinweis unten) |
| `ORCHESTRATOR_FRONTEND_BASE_IMAGE` | `registry.access.redhat.com/ubi9/nodejs-22:latest` | `Dockerfile` — Frontend-Build-Stage (Vite) |
| `ORCHESTRATOR_BUILD_BASE_IMAGE` | `registry.access.redhat.com/ubi9/openjdk-21:latest` | `Dockerfile` — Gradle-Build-Stage |
| `ORCHESTRATOR_RUNTIME_BASE_IMAGE` | `registry.access.redhat.com/ubi9/openjdk-21-runtime:latest` | `Dockerfile` — Laufzeit-Image |
| `GRADLE_DISTRIBUTION_URL` | leer (nutzt die in `gradle/wrapper/gradle-wrapper.properties` eingecheckte, öffentliche URL) | Gradle-Build-Stage in `Dockerfile` und `keycloak-extension/Dockerfile` — überschreibt `distributionUrl`, falls `services.gradle.org` in der Umgebung nicht erreichbar ist |

Die UBI-Build-Stages (`FRONTEND_BASE_IMAGE`, `BUILD_BASE_IMAGE`, `KEYCLOAK_BUILDER_BASE_IMAGE`)
laufen per `USER root`, weil ihre Red-Hat-Basis-Images (anders als die bisherigen Alpine-Images)
schon einen eigenen, nicht-root Default-User mitbringen (z. B. UID 185 bei `openjdk-21`) — für
verworfene Build-Stages irrelevant, aber ohne `USER root` fehlten die Rechte für `npm ci`/
`gradlew`. Die Laufzeit-Stage (`RUNTIME_BASE_IMAGE`) legt weiterhin einen eigenen `dpop`-User an,
mit `groupadd`/`useradd` (UBI, shadow-utils) statt `addgroup`/`adduser` (Alpine, BusyBox) — welches
Tool vorhanden ist, wird zur Build-Zeit erkannt, nicht angenommen.

### Gradle-Distribution (`./inittk` vs. `GRADLE_DISTRIBUTION_URL`)

`./inittk` schreibt `gradle-wrapper.properties` direkt im Arbeitsbaum um (und markiert die Datei
danach per `git update-index --assume-unchanged`) — das ist für **Host-Builds** gedacht, also
`./gradlew` direkt auf der Maschine bzw. in der IDE, außerhalb von Podman/Docker.

Für **Container-Builds** (`podman-compose build` für `keycloak` und `orchestrator`) genügt das
nicht, weil Docker den Build-Kontext beim `COPY gradle/ gradle/`-Schritt eigenständig einliest —
dort greift stattdessen `GRADLE_DISTRIBUTION_URL` als Build-Arg: ist die Variable gesetzt, ersetzt
ein `sed`-Schritt in der Build-Stage `distributionUrl` in der kopierten Datei, bevor `./gradlew`
zum ersten Mal läuft. Ist sie leer (Default), bleibt die eingecheckte URL unverändert.

Am Arbeitsplatz braucht man also **beides** in der `.env.local`:

```
GRADLE_DISTRIBUTION_URL=https://nxrm.dst.tk-inline.net/repository/tk-gradle-distributions/de/tk/build/tkeasy/tkeasy-gradle-distribution/9.4.1/tkeasy-gradle-distribution-9.4.1-tk1.zip
```

— und weiterhin `./inittk` einmalig für den lokalen `./gradlew`-Aufruf außerhalb von Compose.

Zuhause reicht also ein einfaches `podman-compose up --build` ohne jede `.env` — die Defaults
ziehen bereits ausschließlich (bis auf Keycloak selbst und die Gradle-Distribution) von
`registry.access.redhat.com`.

Beispiel `.env.local` für eine Firmenumgebung, die stattdessen `registry.redhat.io` mit aktiver
Subscription nutzt (z. B. für RHBK statt Community-Keycloak) und interne Mirrors für die beiden
Stellen ohne Red-Hat-Äquivalent:

```
KEYCLOAK_BASE_IMAGE=registry.redhat.io/rhbk/keycloak-rhel9:26.2
KEYCLOAK_BUILDER_BASE_IMAGE=registry.redhat.io/ubi9/openjdk-21:latest
ORCHESTRATOR_FRONTEND_BASE_IMAGE=registry.redhat.io/ubi9/nodejs-22:latest
ORCHESTRATOR_BUILD_BASE_IMAGE=registry.redhat.io/ubi9/openjdk-21:latest
ORCHESTRATOR_RUNTIME_BASE_IMAGE=registry.redhat.io/ubi9/openjdk-21-runtime:latest
GRADLE_DISTRIBUTION_URL=https://nxrm.dst.tk-inline.net/repository/tk-gradle-distributions/de/tk/build/tkeasy/tkeasy-gradle-distribution/9.4.1/tkeasy-gradle-distribution-9.4.1-tk1.zip
```

`registry.redhat.io` braucht dafür einen `podman login registry.redhat.io` mit gültigen
Red-Hat-Zugangsdaten — anders als `registry.access.redhat.com` (der Default) ist das kein
anonymer Pull.

Hinweis zu `KEYCLOAK_BASE_IMAGE`: `rhbk/keycloak-rhel9` ("Red Hat build of Keycloak") ist kein
freies UBI-Image, sondern erfordert eine aktive Red-Hat-Subscription zum Pull — anonymer Zugriff
schlägt mit 403 fehl. Die Versionszählung läuft außerdem unabhängig vom Community-Keycloak
(aktuell z. B. 26.2/26.4 statt 26.5.5). Deshalb bleibt der Default beim öffentlichen
`quay.io/keycloak/keycloak` — auch zuhause.

Ohne `.env`/`.env.local` bzw. ohne gesetzte Variablen greifen die (Red-Hat-)Defaults überall
gleich — ein einfaches `podman-compose up` funktioniert also unverändert zuhause wie in der Firma,
solange dort zusätzlich die passende `.env.local` liegt.
