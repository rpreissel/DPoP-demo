# DPoP-Demo — Podman Compose Setup

## Standard-Start

```bash
podman-compose up --build
```

Startet nur `keycloak` (echtes Keycloak, HTTPS auf Port 8543) und `keycloak-config`
(OpenTofu-Realm-Import). Der Orchestrator läuft **nicht** mit — wie bisher lokal:

```bash
./gradlew bootRun --args='--spring.profiles.active=keycloak'
```

## Orchestrator containerisiert mitlaufen lassen (optional)

Der Orchestrator-Service ist hinter einem Compose-Profil versteckt und startet nur, wenn man
ihn explizit anfordert:

```bash
podman-compose --profile orchestrator up --build
```

Damit laufen `keycloak`, `keycloak-config` und zusätzlich `orchestrator` (Port 8080, eigenes
Volume `orchestrator-data` für die H2-Datei-DB, spricht Keycloak intern als `https://keycloak:8443`
an statt `https://localhost:8543`). Der Service wartet auf den abgeschlossenen Realm-Import
(`keycloak-config`), bevor er startet.

Ohne `--profile orchestrator` bleibt alles wie im Standard-Start — die lokale `gradlew
bootRun`-Instanz ist weiterhin der vorgesehene Weg, den Orchestrator zu betreiben; der
Compose-Service ist nur ein Ersatz dafür, falls das gesamte Setup containerisiert (z. B. auf
einer anderen Maschine) laufen soll.

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
| `OPENTOFU_BASE_IMAGE` | `registry.access.redhat.com/ubi9/ubi-minimal:latest` | `infra/tofu/Dockerfile` — Basis für das selbstgebaute OpenTofu-Image |
| `OPENTOFU_VERSION` | `1.8.8` | `infra/tofu/Dockerfile` — welches OpenTofu-Release installiert wird |
| `OPENTOFU_DOWNLOAD_BASE_URL` | `https://github.com/opentofu/opentofu/releases/download` | `infra/tofu/Dockerfile` — woher das Release-ZIP + `SHA256SUMS` geladen werden (öffentlich, kein Red-Hat-Äquivalent) |
| `GRADLE_DISTRIBUTION_URL` | leer (nutzt die in `gradle/wrapper/gradle-wrapper.properties` eingecheckte, öffentliche URL) | Gradle-Build-Stage in `Dockerfile` und `keycloak-extension/Dockerfile` — überschreibt `distributionUrl`, falls `services.gradle.org` in der Umgebung nicht erreichbar ist |

Die UBI-Build-Stages (`FRONTEND_BASE_IMAGE`, `BUILD_BASE_IMAGE`, `KEYCLOAK_BUILDER_BASE_IMAGE`)
laufen per `USER root`, weil ihre Red-Hat-Basis-Images (anders als die bisherigen Alpine-Images)
schon einen eigenen, nicht-root Default-User mitbringen (z. B. UID 185 bei `openjdk-21`) — für
verworfene Build-Stages irrelevant, aber ohne `USER root` fehlten die Rechte für `npm ci`/
`gradlew`. Die Laufzeit-Stage (`RUNTIME_BASE_IMAGE`) legt weiterhin einen eigenen `dpop`-User an,
mit `groupadd`/`useradd` (UBI, shadow-utils) statt `addgroup`/`adduser` (Alpine, BusyBox) — welches
Tool vorhanden ist, wird zur Build-Zeit erkannt, nicht angenommen.

### OpenTofu: selbstgebaut statt gezogen

Es gibt kein offizielles Red-Hat-Image für OpenTofu — weder `registry.redhat.io` noch das
authentifizierungsfreie `registry.access.redhat.com` führen eins. `keycloak-config` baut das
Image deshalb selbst über `infra/tofu/Dockerfile`: eine `ubi9/ubi-minimal`-Basis (bewusst schon
im Default Red Hat, nicht Alpine/Docker Hub — Alpine wäre am Arbeitsplatz ohnehin nicht
erreichbar), auf der das OpenTofu-Standalone-Binary von den GitHub-Releases geladen, per
`SHA256SUMS` verifiziert und nach `/usr/local/bin/tofu` installiert wird.

Alle drei Stellhebel sind einzeln überschreibbar — Basis-Image, Version, Download-Quelle —, falls
`github.com` am Arbeitsplatz nicht erreichbar ist und stattdessen ein internes Mirror-Verzeichnis
mit derselben Struktur (`.../v<version>/tofu_<version>_linux_<arch>.zip` +
`tofu_<version>_SHA256SUMS`) bereitsteht.

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
OPENTOFU_BASE_IMAGE=registry.redhat.io/ubi9/ubi-minimal:latest
OPENTOFU_DOWNLOAD_BASE_URL=https://nxrm.dst.tk-inline.net/repository/opentofu-mirror/releases/download
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
