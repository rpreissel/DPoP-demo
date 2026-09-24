# OpenShift-Prototyp

Keycloak und Orchestrator laufen als **ein Deployment mit zwei Containern**. Sie teilen sich das Netz
des Pods und sprechen sich über `localhost` an, intern per HTTP. Nach außen gehen zwei `edge`-Routes,
TLS macht der Router.

| Datei | Wofür |
|---|---|
| `dpop-demo.yaml` | Deployment, Service, Routes, PVCs. Dieselbe Datei für OpenShift und den lokalen Test |
| `imagestreams.yaml` | ImageStreams, in die `deploy.sh` pusht (nur OpenShift) |
| `deploy.sh` | Ausrollen ins aktuelle OpenShift-Projekt |
| `local.yaml`, `local-up.sh` | Lokaler Test mit Podman |

## Auf OpenShift

```bash
oc login ...
oc project <projekt>
./openshift/deploy.sh
```

Das Skript baut die Artefakte mit Gradle und die Images lokal mit Podman (immer `linux/amd64`, auch
auf einem Mac mit Apple Silicon). Es pusht sie über die Route der internen Registry in die
ImageStreams. Einen Binary-Build im Cluster gibt es nicht mehr: Dessen Upload über die API bricht am
Arbeitsplatz schon bei wenigen MB mit einem Timeout ab. Danach legt es
zuerst Service und Routes an. Die Hosts, die OpenShift den Routes gibt, trägt es in die ConfigMap
`dpop-demo-env` ein. Zum Schluss wendet es das Deployment an und wartet auf den Rollout. Am Ende
stehen die beiden URLs.

- **Admin-Console:** Benutzer `admin`. Das Passwort erzeugt das Skript beim ersten Lauf zufällig:
  `oc extract secret/dpop-demo-keycloak-admin --keys=password --to=-`
- **Erneut ausrollen:** einfach das Skript noch einmal laufen lassen, mit `SKIP_GRADLE=1`, wenn die
  Artefakte schon gebaut sind.
- **Andere Basis-Images:** `KEYCLOAK_BASE_IMAGE` und `ORCHESTRATOR_RUNTIME_BASE_IMAGE`, wie bei
  Compose. Beide Skripte lesen dafür auch `.env`, dieselbe Datei wie Compose (Vorlage
  `.env.work.example`); eine in der Shell gesetzte Variable hat Vorrang. Die Basis-Images zieht der
  lokale Build, für `registry.redhat.io` also vorher `podman login registry.redhat.io`.
- **Registry:** Das Skript nimmt den Host der Route `default-route` in `openshift-image-registry`.
  Ein Cluster-Admin muss sie einmal freischalten
  (`oc patch configs.imageregistry.operator.openshift.io/cluster --type merge -p '{"spec":{"defaultRoute":true}}'`).
  Fehlt dir das Leserecht auf diese Route, setze `REGISTRY_HOST` in der Shell oder in `.env`. Die
  Anmeldung an der Registry macht das Skript selbst mit deinem `oc`-Token.
- **Route-Hosts nicht ändern:** Sie gehören zum Realm-Aufbau. Ändern sie sich, baut die Migration das
  Realm beim nächsten Start neu auf.

Zwischen den Containern wird kein Geheimnis geteilt. Die Migration meldet sich per signierter
Assertion als `orchestrator-migration` an, Keycloak prüft sie gegen das JWKS unter
`http://localhost:8080/...`.

## Lokal mit Podman

```bash
./gradlew stagePodmanArtifacts
./openshift/local-up.sh
```

Orchestrator unter http://localhost:8090, Keycloak unter http://localhost:8091. Die Ports sind so
gewählt, dass es neben dem Compose-Setup (8080/8543) läuft. Stoppen:
`podman kube down openshift/dpop-demo.yaml` (mit `--force` auch die Volumes).

Lokal nicht prüfbar sind nur die OpenShift-eigenen Teile: Routes, der Push in die Registry und die
zufällige UID, mit der OpenShift Container startet.
