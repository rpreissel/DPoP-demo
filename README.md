# DPoP-Demo — Podman Compose Setup

## Standard-Start

```bash
./gradlew build
podman-compose up --build
```

`stagePodmanArtifacts` (Gradle-Task, `build.gradle.kts`) baut Orchestrator-Jar, Frontend und
Keycloak-Extension-Jar mit Gradle/npm auf dem Host und legt die fertigen Artefakte unter
`build/podman/orchestrator` bzw. `build/podman/keycloak` ab. Der Task hängt am normalen
Build-Lifecycle (`assemble`, und damit auch `build`) — ein separater Handaufruf ist also nur nötig,
wenn man ausschließlich staged, ohne sonst etwas zu bauen:

```bash
./gradlew stagePodmanArtifacts
```

Beide Dockerfiles (`Dockerfile`, `keycloak-extension/Dockerfile`) kopieren nur noch aus
`build/podman/*` — sie enthalten selbst kein Gradle, kein npm und keinen Quellcode mehr,
`.dockerignore` blendet den Rest des Repos aus dem Build-Kontext aus. Fehlt das Staging oder ist es
veraltet, bricht der jeweilige `COPY`-Schritt mit einem klaren "not found"-Fehler ab statt still
ein altes Artefakt zu erwischen. `podman-compose build`/`up --build` selbst bleibt bewusst ein
eigener, von Hand angestoßener Schritt — Gradle ruft kein Podman auf, damit ein normaler
Gradle-Build nicht von einer laufenden Podman-Machine abhängt.

`podman-compose up --build` startet `keycloak` (echtes Keycloak, HTTPS auf Port 8543) und den
containerisierten `orchestrator` (Port 8080, eigenes Volume `orchestrator-data` für die
H2-Datei-DB). Der
Orchestrator wendet beim eigenen Start automatisch alle `keycloak-migrations/migrations/*.kc.kts`
an (`KeycloakMigrationRunnerStartup`, Ersatz für den früheren separaten OpenTofu-Realm-Import) und
spricht Keycloak intern als `https://keycloak:8443` an, nicht `https://localhost:8543`.

### Alternative Betriebsarten

Zwei weitere Varianten, je nachdem, ob überhaupt ein echtes Keycloak gebraucht wird:

- **Echtes Keycloak, Orchestrator lokal** (schnellere Iterationszyklen als der volle
  Container-Rebuild oben): nur `keycloak` aus Compose starten und den Orchestrator auf dem Host
  mit dem `keycloak`-Profil laufen lassen —
  ```bash
  podman-compose up keycloak
  ./gradlew bootRunKc
  ```
  `bootRunKc` ist **nicht** dasselbe wie `bootRun` — es aktiviert explizit das `keycloak`-Profil
  (`application-keycloak.yml`, zeigt für den Host-Fall auf `https://localhost:8543`, nicht auf das
  Compose-interne `https://keycloak:8443`). Reines `bootRun` bliebe im Default-Profil und würde
  gar nicht gegen das Compose-Keycloak sprechen.
- **Kein Keycloak nötig**: `./gradlew bootRun` allein (Default-Profil, Mock-Keycloak-Frontend,
  siehe `bd DPoP-demo-f9o.9`) — dafür ist gar kein Podman/Compose erforderlich.

## Basis-Images von außen konfigurieren

**Grundsatz: Red Hat, wo es geht — auch zuhause, nicht nur am Arbeitsplatz.** Beide Dockerfiles
sind reine Laufzeit-Images (siehe oben, `./gradlew stagePodmanArtifacts` läuft vorher auf dem
Host) und defaulten auf Red-Hat-UBI-Images über `registry.access.redhat.com`
(authentifizierungsfrei, freie UBI-EULA, funktioniert ohne Red-Hat-Account). Nur wo es kein
Red-Hat-Image gibt oder es eine aktive Subscription braucht, bleibt der Default beim öffentlichen
Image — das betrifft aktuell nur das Keycloak-Laufzeit-Image selbst (`KEYCLOAK_BASE_IMAGE`, siehe
Hinweis unten).

Beide Basis-Images (in den Dockerfiles per `ARG` und in `compose.yml` per `build.args`) lassen
sich ohne Änderung an den Dockerfiles überschreiben — z. B. am Arbeitsplatz auf eine gespiegelte
interne Registry oder auf `registry.redhat.io` mit aktiver Subscription.

Steuerung über Umgebungsvariablen, die Podman Compose automatisch aus einer `.env`-Datei im
Projektverzeichnis liest (`.env`/`.env.local` sind gitignored — für den Arbeitsplatz liegt eine
Vorlage unter [`.env.work.example`](.env.work.example) bei, einfach kopieren:
`cp .env.work.example .env.local`).

| Variable | Default | Wirkt auf |
|---|---|---|
| `KEYCLOAK_BASE_IMAGE` | `quay.io/keycloak/keycloak:26.5.5` | `keycloak-extension/Dockerfile` — Keycloak-Laufzeit-Image (öffentlich, siehe Hinweis unten) |
| `ORCHESTRATOR_RUNTIME_BASE_IMAGE` | `registry.access.redhat.com/ubi9/openjdk-21-runtime:latest` | `Dockerfile` — Laufzeit-Image |

Die Laufzeit-Images legen per `USER root` (kurzzeitig) einen eigenen Nutzer an — `dpop` im
Orchestrator-Image, `keycloak` bringt sein eigenes Image schon mit — mit `groupadd`/`useradd`
(UBI, shadow-utils) statt `addgroup`/`adduser` (Alpine, BusyBox), je nachdem, welches Tool im
(überschreibbaren) Basis-Image vorhanden ist.

Für den **Host-Gradle-Lauf** (`./gradlew stagePodmanArtifacts`, außerhalb von Podman/Docker) gilt
das oben Gesagte nicht — dessen Gradle-Distribution wird weiterhin per `./inittk` umgestellt
(schreibt `gradle-wrapper.properties` im Arbeitsbaum um und markiert die Datei danach per
`git update-index --assume-unchanged`), falls `services.gradle.org` am Arbeitsplatz nicht
erreichbar ist. Container-Builds brauchen dafür kein eigenes Pendant mehr, weil Gradle/npm gar
nicht mehr innerhalb von Docker laufen.

Zuhause reicht also ein einfaches `podman-compose up --build` (nach `stagePodmanArtifacts`) ohne
jede `.env` — die Defaults ziehen bereits ausschließlich (bis auf Keycloak selbst) von
`registry.access.redhat.com`.

Beispiel `.env.local` für eine Firmenumgebung, die stattdessen `registry.redhat.io` mit aktiver
Subscription nutzt (z. B. für RHBK statt Community-Keycloak):

```
KEYCLOAK_BASE_IMAGE=registry.redhat.io/rhbk/keycloak-rhel9:26.2
ORCHESTRATOR_RUNTIME_BASE_IMAGE=registry.redhat.io/ubi9/openjdk-21-runtime:latest
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
