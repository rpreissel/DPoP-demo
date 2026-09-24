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

`stagePodmanArtifacts` kopiert dabei auch beide Dockerfiles selbst mit nach `build/podman/*` — sie
nutzen `COPY`-Pfade relativ zu ihrem jeweiligen Staging-Verzeichnis. `compose.yml` gibt deshalb
`build/podman/orchestrator` bzw. `build/podman/keycloak` — nicht das Repo-Wurzelverzeichnis — als
Build-Kontext an: Podman muss so nur noch die paar fertigen Artefakte hashen/hochladen, nicht mehr
das ganze Repo (`.git`, `frontend/node_modules`, Gradle-Caches, …) einlesen. Fehlt das Staging oder
ist es veraltet, bricht der jeweilige `COPY`-Schritt mit einem klaren "not found"-Fehler ab statt
still ein altes Artefakt zu erwischen.
`podman-compose build`/`up --build` selbst bleibt bewusst ein eigener, von Hand angestoßener
Schritt — Gradle ruft kein Podman auf, damit ein normaler Gradle-Build nicht von einer laufenden
Podman-Machine abhängt.

`podman-compose up --build` startet `keycloak` (echtes Keycloak, HTTPS auf Port 8543) und den
containerisierten `orchestrator` (Port 8080, eigenes Volume `orchestrator-data` für die
H2-Datei-DB). Der
Orchestrator wendet beim eigenen Start automatisch alle Migrationen aus dem
`keycloak-migrations`-Jar (`classpath*:keycloak-migrations/*.kc.kts`)
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
- **Ohne Keycloak**: `./gradlew bootRun` allein (Default-Profil) — kein Podman/Compose nötig,
  dafür nur App-Kanal, Personenregister und Admin; der Web-Kanal ist dann deaktiviert.

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
Projektverzeichnis liest (gitignored — für den Arbeitsplatz liegt eine Vorlage unter
[`.env.work.example`](.env.work.example) bei, einfach kopieren: `cp .env.work.example .env`).
Podman Compose liest von sich aus nur `.env`, nicht `.env.local`; eine andere Datei nur mit
`--env-file`. Die Vorlage selbst wirkt nie.

| Variable | Default | Wirkt auf |
|---|---|---|
| `KEYCLOAK_BASE_IMAGE` | `quay.io/keycloak/keycloak:26.6.4` | `keycloak-extension/Dockerfile` — Keycloak-Laufzeit-Image (öffentlich, siehe Hinweis unten) |
| `ORCHESTRATOR_RUNTIME_BASE_IMAGE` | `registry.access.redhat.com/ubi9/openjdk-21-runtime:latest` | `Dockerfile` — Laufzeit-Image |
| `KEYCLOAK_SETUP_VARIANT` | `host` (`compose.yml` setzt `compose`) | Welche Keycloak-Umgebung aufgebaut und bedient wird — siehe unten |
| `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` | `admin` / `admin` | Bootstrap-Admin von Keycloak — nur noch für die Admin-Console. Der Orchestrator braucht ihn nicht |
| `ORCHESTRATOR_CLIENT_JWKS_URL` | `http://host.containers.internal:8080/orchestrator/api/v1/kc/client-jwks/.well-known/jwks.json` | Wo Keycloak den Schlüssel des Orchestrators für den Client `orchestrator-migration` holt (siehe unten) |

### Keycloak-Umgebung: eine Variante statt einzelner Variablen

Realm-Name, Client-Ids, Redirect-URIs und die URLs beider Seiten sind **keine einzelnen
Umgebungsvariablen mehr** (Client-Secrets gibt es gar keine mehr — der Orchestrator authentisiert
sich bei Keycloak mit einer signierten Assertion, `private_key_jwt`; das gilt auch für die
Migration, die als `orchestrator-migration` im Master-Realm angemeldet ist statt als Admin mit Passwort). Sie stehen zusammen in einem benannten Satz (`KeycloakSetup`),
aus dem sich beides speist: der Realm-Aufbau durch die Migration *und* die Laufzeitwerte des
Orchestrators (Account-Sync, Peer-Auth-JWKS, OIDC-Issuer). `KEYCLOAK_SETUP_VARIANT` wählt aus:

| Variante | Wofür |
|---|---|
| `host` | Orchestrator per `./gradlew bootRun` auf dem Host, nur Keycloak im Container |
| `compose` | beide im Compose-Netz (`compose.yml` setzt sie selbst) |

Beide stehen in `application-keycloak.yml` unter `keycloak-setup.variants`, und `keycloak-setup.base`
darüber nennt jedes Feld einmal — eine weitere Umgebung ist damit ein Eintrag dort, keine
Codeänderung; jede Variante nennt nur, worin sie von der Basis abweicht. Die Werte für den Keycloak-seitigen Teil landen als
Config-Properties der `orchestrator`-Komponente im Realm (User federation in der Admin-Console),
wo die Extension sie zur Laufzeit liest.

**Achtung:** Ändert sich ein Wert, den ein bereits angewendeter Migrationsschritt verbaut hat, baut
der `MigrationRunner` das Realm neu auf — dieselbe Konsequenz wie bei einer geänderten
Migrationsdatei. Die Keycloak-User entstehen beim nächsten Sync/Login neu, die Orchestrator-DB
bleibt unberührt.

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

Beispiel `.env` für eine Firmenumgebung, die stattdessen `registry.redhat.io` mit aktiver
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
(aktuell z. B. 26.2/26.4 statt 26.6.4). Deshalb bleibt der Default beim öffentlichen
`quay.io/keycloak/keycloak` — auch zuhause.

Ohne `.env` bzw. ohne gesetzte Variablen greifen die (Red-Hat-)Defaults überall
gleich — ein einfaches `podman-compose up` funktioniert also unverändert zuhause wie in der Firma,
solange dort zusätzlich die passende `.env` liegt.
