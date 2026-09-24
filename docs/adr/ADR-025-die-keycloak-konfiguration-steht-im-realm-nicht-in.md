# ADR-25: Die Keycloak-Konfiguration steht im Realm, nicht in der Container-Umgebung

**Entscheidung** (umgesetzt): Die Keycloak-Erweiterung liest ihre Einstellungen zur Laufzeit aus den
Konfigurationswerten der Komponente `orchestrator` im Realm (`OrchestratorSettings`), nicht mehr aus
Umgebungsvariablen des Keycloak-Containers. Fehlt die Komponente oder ein Wert, scheitert der Aufruf
sofort und sichtbar; einen Ersatzwert gibt es nicht.

Dahinter beschreibt **ein** Wertobjekt die ganze Umgebung (`KeycloakSetup`, ausgewählt über
`keycloak-setup.variant`). Aus ihm stammen die Werte für den Aufbau des Realms durch die Migration
ebenso wie für den laufenden Betrieb des Orchestrators (Abgleich der Konten, JWKS für die Assertion
zwischen den Servern, Prüfung der OIDC-Tokens).

**Warum**: Aufbau und Betrieb meinen dasselbe Realm und dieselben Clients. Solange Migration und
laufender Betrieb ihre Werte aus getrennten Quellen holten (`System.getenv` im Migrationsskript,
Umgebungsvariablen am Keycloak-Container, Spring-Properties am Orchestrator), genügte ein Tippfehler
für „Migration gegen Realm A, Abgleich gegen Realm B“. Der Fehler zeigte sich dann erst als 401 tief
im Betrieb. Zugleich waren die Einstellungen der Erweiterung im laufenden Keycloak nirgends zu sehen,
weder in der Admin-Console noch im Export des Realms.

**Zwei Hälften, eine Folge**: `RealmSetup` ist das, was ins Realm geschrieben wird; `KeycloakAccess`
beschreibt nur, wie man das fertige Realm erreicht. Ändert sich ein Wert der ersten Hälfte, baut der
`MigrationRunner` das Realm neu auf. Das ist dieselbe Folge wie bei einer geänderten Migrationsdatei,
und aus demselben Grund: Die schon erledigten Schritte fassen den Wert nie wieder an, und das Realm
würde unbemerkt mit dem alten Wert weiterlaufen. Das Migrationsskript sieht deshalb ausschließlich
`RealmSetup`. So kann ein Schritt gar nicht erst einen Wert verwenden, dessen Änderung den Neuaufbau
nicht auslöst.

### Kein geteiltes Geheimnis mehr

Im selben Zug entfallen die Client-Secrets: `orchestrator-admin` und `orchestrator-app-token`
authentifizieren sich per **`private_key_jwt`** (RFC 7523, die in ADR-9 erwogene Härtung). Der
Orchestrator signiert jede Anfrage nach einem Token mit seinem eigenen Schlüssel, und Keycloak holt den
öffentlichen Teil unter `jwks.url` ab. Das ist das Spiegelbild der Assertion, mit der sich Keycloak
beim Orchestrator ausweist (ADR-7). Beide Richtungen folgen damit demselben Prinzip, und in der
Konfiguration, der Compose-Datei und im Realm steht kein gemeinsames Geheimnis mehr.

Beide Signaturschlüssel liegen jetzt **in einer Datenbank** statt im Arbeitsspeicher des Prozesses:
der des Orchestrators in `orchestrator.node_signing_key`, der der Erweiterung als Wert der Komponente
`orchestrator`, also in der eigenen Datenbank von Keycloak. Vorher entstand auf jeder Seite bei jedem
Start der JVM ein neues Paar. Das reichte nur, solange genau eine Instanz lief: Eine zweite hätte mit
einem Schlüssel signiert, den das JWKS der ersten nie nennt.

**Kosten**, bewusst getragen:

- Ohne die Komponente `orchestrator` läuft die Erweiterung nicht. Das ist gewollt: Ein stillschweigend
  angenommener Standardwert wäre genau die Fehlkonfiguration, die vorher erst am seltsamen Verhalten
  einer Anmeldung auffiel.
- Ein geänderter Wert verwirft alles auf der Seite von Keycloak und baut es neu auf. Das ist
  verkraftbar, weil die Datenbank des Orchestrators keine IDs von Keycloak speichert: Die Nutzer
  entstehen über `orchestratorAccountId` beim nächsten Abgleich neu.
- Keycloak muss den Orchestrator erreichen können, um dessen JWKS zu holen. Diese Verbindung braucht
  die Erweiterung ohnehin für jeden Aufruf des Keycloak-Zugangs (`orchestratorBaseUrl`). Es ist also
  keine neue Abhängigkeit, aber eine zweite Stelle, an der sie sichtbar wird.
- Die privaten Schlüssel liegen im Klartext in ihrer jeweiligen Datenbank. Das ist derselbe
  Kompromiss für die Demo, den ADR-22 für den verwahrten PIN benennt.

**Nachtrag: auch die Migration ohne Passwort.** Ein gemeinsames Geheimnis war übrig geblieben: Die
Migration meldete sich als Master-Realm-Admin mit Benutzername und Passwort an
(`KEYCLOAK_ADMIN`/`KEYCLOAK_ADMIN_PASSWORD` am Orchestrator). Jetzt legt die Extension beim Start von
Keycloak selbst einen Client `orchestrator-migration` im Master-Realm an
(`MigrationClientBootstrapFactory`, nach der eigenen Datenbankmigration von Keycloak und beliebig oft
wiederholbar). Der Client meldet sich mit `private_key_jwt` über das JWKS des Orchestrators an, mit
demselben Schlüssel wie die Clients im Realm, und hat die Rolle `admin` im Master-Realm, also dieselben
Rechte wie der Admin mit Passwort, den er ersetzt. `create-realm` allein reicht nicht: Keycloak prüft
Admin-Rechte anhand der Rollen im Token, und Rechte auf ein neu angelegtes Realm bekäme der Client erst
nach dem Anlegen. Der Orchestrator holt sein Token über `client_credentials` mit Assertion
(`KeycloakMigrationToken`). Der beim ersten Start angelegte Admin bleibt nur für Menschen an der
Admin-Console.

- **Die eine Ausnahme von dieser Entscheidung:** Die `jwks.url` dieses Clients steht nicht im Realm,
  sondern in der SPI-Konfiguration des Keycloak-Containers
  (`KC_SPI_ORCHESTRATOR_BOOTSTRAP__MIGRATION_CLIENT__JWKS_URL`). Beim Start gibt es noch kein Realm,
  aus dem sie kommen könnte. Die Adresse des Orchestrators steht damit an zwei Stellen (dort und als
  `orchestratorBaseUrl` der Variante). Hingenommen, weil ein falscher Wert nicht erst spät auffällt:
  Schon die erste Anfrage der Migration nach einem Token scheitert beim Start. Fehlt die Option ganz,
  startet Keycloak nicht.
- **Nur interne Adressen:** Wer unter dieser URL antwortet, kann sich Tokens als Admin des
  Master-Realms ausstellen. Die URL darf deshalb nie über einen öffentlich erreichbaren Weg laufen.
- **Admin-Client:** `keycloak-admin-client` 26.0.12 kann sich selbst nicht per Assertion anmelden.
  Ein Filter für Anfragen setzt deshalb bei jedem Aufruf das aktuelle Token ein (`buildAdminClient`).

---
