# DPoP-Demo — Aufbau mit Podman Compose

## Standard-Start

```bash
./gradlew build
podman-compose up --build
```

Der Gradle-Task `stagePodmanArtifacts` (`build.gradle.kts`) baut das Orchestrator-Jar, das
Frontend und das Jar der Keycloak-Erweiterung mit Gradle und npm auf dem Host. Die fertigen
Artefakte legt er unter `build/podman/orchestrator` bzw. `build/podman/keycloak` ab. Der Task hängt
am normalen Gradle-Build (`assemble` und damit auch `build`). Einzeln aufrufen muss man ihn also
nur, wenn man ausschließlich die Artefakte bereitstellen will, ohne sonst etwas zu bauen:

```bash
./gradlew stagePodmanArtifacts
```

`stagePodmanArtifacts` kopiert auch die beiden Dockerfiles nach `build/podman/*`, denn ihre
`COPY`-Pfade beziehen sich auf das jeweilige Bereitstellungsverzeichnis. `compose.yml` gibt deshalb
`build/podman/orchestrator` bzw. `build/podman/keycloak` als Build-Kontext an, nicht das
Wurzelverzeichnis des Repos. So muss Podman nur die wenigen fertigen Artefakte einlesen und nicht das
ganze Repo (`.git`, `frontend/node_modules`, Gradle-Caches, …). Fehlen die Artefakte oder sind sie
veraltet, bricht der jeweilige `COPY`-Schritt mit einem klaren „not found“-Fehler ab, statt still
ein altes Artefakt zu verwenden.

`podman-compose build` bzw. `up --build` bleibt bewusst ein eigener Schritt, den man von Hand
startet. Gradle ruft kein Podman auf, damit ein normaler Gradle-Build nicht von einer laufenden
Podman-Machine abhängt.

`podman-compose up --build` startet `keycloak` (ein echtes Keycloak, HTTPS auf Port 8543) und den
`orchestrator` im Container (Port 8080, mit eigenem Volume `orchestrator-data` für die
H2-Datenbankdatei). Beim Start wendet der Orchestrator automatisch alle Migrationen aus dem
`keycloak-migrations`-Jar an (`classpath*:keycloak-migrations/*.kc.kts`,
`KeycloakMigrationRunnerStartup`; das ersetzt den früheren separaten Realm-Import per OpenTofu).
Keycloak erreicht er intern unter `https://keycloak:8443`, nicht unter `https://localhost:8543`.

### Weitere Betriebsarten

Zwei weitere Varianten, je nachdem, ob überhaupt ein echtes Keycloak gebraucht wird:

- **Echtes Keycloak, Orchestrator lokal** (schneller beim Entwickeln als der vollständige
  Neubau der Container oben): Nur `keycloak` aus Compose starten und den Orchestrator auf dem
  Host mit dem Profil `keycloak` laufen lassen:
  ```bash
  podman-compose up keycloak
  ./gradlew bootRunKc
  ```
  `bootRunKc` ist **nicht** dasselbe wie `bootRun`: Es schaltet ausdrücklich das Profil
  `keycloak` ein (`application-keycloak.yml`). Für den Betrieb auf dem Host zeigt es auf
  `https://localhost:8543`, nicht auf das Compose-interne `https://keycloak:8443`. Ein einfaches
  `bootRun` bliebe im Standardprofil und würde das Keycloak aus Compose gar nicht ansprechen.
- **Ohne Keycloak**: nur `./gradlew bootRun` (Standardprofil). Podman und Compose sind dann nicht
  nötig. Es gibt dafür nur den App-Kanal, das Personenverzeichnis, die Nect-Simulation und die
  Admin-Seite; der Web-Kanal ist abgeschaltet.

## Basis-Images von außen einstellen

**Grundsatz: Red Hat, wo es geht – auch zu Hause, nicht nur am Arbeitsplatz.** Beide Dockerfiles
beschreiben reine Laufzeit-Images (siehe oben: `./gradlew stagePodmanArtifacts` läuft vorher auf
dem Host). Standardmäßig nutzen sie Red-Hat-UBI-Images von `registry.access.redhat.com`: ohne
Anmeldung, unter der freien UBI-Lizenz und ohne Red-Hat-Konto. Nur wo es kein Red-Hat-Image gibt
oder eines nur mit aktivem Abonnement, bleibt es beim öffentlichen Image. Das betrifft derzeit nur
das Laufzeit-Image von Keycloak selbst (`KEYCLOAK_BASE_IMAGE`, siehe Hinweis unten).

Beide Basis-Images (in den Dockerfiles per `ARG`, in `compose.yml` per `build.args`) lassen sich
überschreiben, ohne die Dockerfiles zu ändern – z. B. am Arbeitsplatz auf eine gespiegelte interne
Registry oder auf `registry.redhat.io` mit aktivem Abonnement.

Eingestellt wird das über Umgebungsvariablen, die Podman Compose automatisch aus einer Datei
`.env` im Projektverzeichnis liest. Diese Datei steht nicht im Repo; für den Arbeitsplatz liegt
eine Vorlage bei: [`.env.work.example`](.env.work.example), einfach kopieren mit
`cp .env.work.example .env`. Podman Compose liest von sich aus nur `.env`, nicht `.env.local`;
eine andere Datei nur mit `--env-file`. Die Vorlage selbst wirkt nie.

| Variable | Standardwert | Wirkt auf |
|---|---|---|
| `KEYCLOAK_BASE_IMAGE` | `quay.io/keycloak/keycloak:26.6.4` | `keycloak-extension/Dockerfile` – Laufzeit-Image von Keycloak (öffentlich, siehe Hinweis unten) |
| `ORCHESTRATOR_RUNTIME_BASE_IMAGE` | `registry.access.redhat.com/ubi9/openjdk-21-runtime:latest` | `Dockerfile` – Laufzeit-Image |
| `KEYCLOAK_SETUP_VARIANT` | `host` (`compose.yml` setzt `compose`) | Welche Keycloak-Umgebung aufgebaut und angesprochen wird – siehe unten |
| `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` | `admin` / `admin` | Der erste Admin von Keycloak – nur noch für die Admin-Konsole. Der Orchestrator braucht ihn nicht |
| `ORCHESTRATOR_CLIENT_JWKS_URL` | `http://host.containers.internal:8080/orchestrator/api/v1/kc/client-jwks/.well-known/jwks.json` | Wo Keycloak den Schlüssel des Orchestrators für den Client `orchestrator-migration` abholt (siehe unten) |

### Keycloak-Umgebung: eine Variante statt einzelner Variablen

Realm-Name, Client-Ids, Redirect-URIs und die URLs beider Seiten sind **keine einzelnen
Umgebungsvariablen mehr**. Client-Secrets gibt es gar nicht mehr: Der Orchestrator weist sich bei
Keycloak mit einer signierten Assertion aus (`private_key_jwt`). Das gilt auch für die Migration;
sie meldet sich als `orchestrator-migration` im Master-Realm an statt als Admin mit Passwort. Alle
diese Werte stehen gemeinsam in einem benannten Satz (`KeycloakSetup`). Aus ihm speisen sich beide
Seiten: der Aufbau des Realms durch die Migration *und* die Laufzeitwerte des Orchestrators
(Abgleich der Konten, JWKS für die Peer-Auth, OIDC-Aussteller). `KEYCLOAK_SETUP_VARIANT` wählt die
Variante aus:

| Variante | Wofür |
|---|---|
| `host` | Orchestrator per `./gradlew bootRunKc` auf dem Host, nur Keycloak im Container |
| `compose` | beide im Compose-Netz (`compose.yml` setzt die Variante selbst) |
| `openshift` | Prototyp `openshift/dpop-demo.yaml`: ein Pod, beide Container teilen sich das Netz |

Alle drei stehen in `application-keycloak.yml` unter `keycloak-setup.variants`; `keycloak-setup.base`
darüber nennt jedes Feld einmal. Eine weitere Umgebung ist damit ein Eintrag dort, keine
Codeänderung, und jede Variante nennt nur, worin sie von der Basis abweicht. Die Werte für die
Keycloak-Seite landen als Konfiguration der Komponente `orchestrator` im Realm (in der
Admin-Konsole unter „User federation“); dort liest die Erweiterung sie zur Laufzeit.

**Achtung:** Ändert sich ein Wert, den ein bereits angewendeter Migrationsschritt verwendet hat,
baut der `MigrationRunner` das Realm neu auf – genau wie bei einer geänderten Migrationsdatei. Die
Nutzer in Keycloak entstehen beim nächsten Abgleich bzw. Login neu; die Datenbank des Orchestrators
bleibt unberührt.

Die Laufzeit-Images legen kurzzeitig als `USER root` einen eigenen Nutzer an: `dpop` im
Orchestrator-Image; das Keycloak-Image bringt seinen Nutzer `keycloak` schon mit. Dafür nutzen sie
`groupadd`/`useradd` (UBI, shadow-utils) oder `addgroup`/`adduser` (Alpine, BusyBox), je nachdem,
welches Werkzeug im (überschreibbaren) Basis-Image vorhanden ist.

Für den **Gradle-Lauf auf dem Host** (`./gradlew stagePodmanArtifacts`, außerhalb von Podman oder
Docker) gilt das oben Gesagte nicht. Ist `services.gradle.org` am Arbeitsplatz nicht erreichbar,
wird dessen Gradle-Distribution weiterhin per `./inittk` umgestellt: Das Skript schreibt
`gradle-wrapper.properties` im Arbeitsverzeichnis um und markiert die Datei danach per
`git update-index --assume-unchanged`. Für die Container-Builds braucht es dafür nichts Eigenes
mehr, weil Gradle und npm gar nicht mehr in Docker laufen.

Zu Hause reicht also ein einfaches `podman-compose up --build` (nach `stagePodmanArtifacts`) ganz
ohne `.env`. Die Standardwerte beziehen dann alles außer Keycloak selbst von
`registry.access.redhat.com`.

Beispiel einer `.env` für eine Firmenumgebung, die stattdessen `registry.redhat.io` mit aktivem
Abonnement nutzt (z. B. für RHBK statt des Community-Keycloak):

```
KEYCLOAK_BASE_IMAGE=registry.redhat.io/rhbk/keycloak-rhel9:26.2
ORCHESTRATOR_RUNTIME_BASE_IMAGE=registry.redhat.io/ubi9/openjdk-21-runtime:latest
```

Für `registry.redhat.io` braucht es vorher ein `podman login registry.redhat.io` mit gültigen
Red-Hat-Zugangsdaten. Anders als bei `registry.access.redhat.com` (dem Standard) lassen sich die
Images dort nicht anonym herunterladen.

Hinweis zu `KEYCLOAK_BASE_IMAGE`: `rhbk/keycloak-rhel9` („Red Hat build of Keycloak“) ist kein
freies UBI-Image; zum Herunterladen braucht es ein aktives Red-Hat-Abonnement, anonym scheitert
es mit 403. Außerdem zählt es seine Versionen unabhängig vom Community-Keycloak (derzeit etwa
26.2/26.4 statt 26.6.4). Deshalb bleibt der Standardwert beim öffentlichen
`quay.io/keycloak/keycloak` – auch zu Hause.

Ohne `.env` bzw. ohne gesetzte Variablen gelten überall dieselben (Red-Hat-)Standardwerte. Ein
einfaches `podman-compose up` funktioniert also zu Hause wie in der Firma unverändert, solange
dort zusätzlich die passende `.env` liegt.
