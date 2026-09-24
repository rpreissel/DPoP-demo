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

Zwischen Orchestrator und Keycloak gibt es kein gemeinsames Geheimnis, weder in der Konfiguration
noch in der Compose-Datei noch im Realm. Jede Richtung weist sich mit einer Signatur aus:

- **Orchestrator → Keycloak (Clients im Realm).** `orchestrator-admin` und `orchestrator-app-token`
  authentifizieren sich per **`private_key_jwt`** (RFC 7523, die in ADR-9 erwogene Härtung). Der
  Orchestrator signiert jede Anfrage nach einem Token mit seinem eigenen Schlüssel, und Keycloak holt
  den öffentlichen Teil unter `jwks.url` ab.
- **Orchestrator → Keycloak (Migration).** Auch die Migration meldet sich ohne Passwort an. Die
  Erweiterung legt beim Start von Keycloak selbst einen Client `orchestrator-migration` im Master-Realm
  an (`MigrationClientBootstrapFactory`, nach der eigenen Datenbankmigration von Keycloak, beliebig oft
  wiederholbar). Er meldet sich mit `private_key_jwt` über dasselbe JWKS des Orchestrators an und hat
  die Rolle `admin` im Master-Realm. `create-realm` allein reicht nicht, weil Keycloak Admin-Rechte an
  den Rollen im Token prüft und Rechte auf ein neues Realm erst nach dessen Anlegen vergäbe. Der
  Orchestrator holt sein Token über `client_credentials` mit Assertion (`KeycloakMigrationToken`). Der
  beim ersten Start angelegte Admin mit Passwort bleibt nur für Menschen an der Admin-Console.
- **Keycloak → Orchestrator.** Die Erweiterung weist sich mit einer signierten Assertion aus (ADR-7).
  Das ist das Spiegelbild der ersten Richtung, nach demselben Prinzip.

Beide Signaturschlüssel liegen **in einer Datenbank** statt im Arbeitsspeicher des Prozesses: der des
Orchestrators in `orchestrator.node_signing_key`, der der Erweiterung als Wert der Komponente
`orchestrator`, also in der Datenbank von Keycloak. Vorher entstand auf jeder Seite bei jedem Start
der JVM ein neues Paar. Das reichte nur, solange genau eine Instanz lief: Eine zweite hätte mit einem
Schlüssel signiert, den das JWKS der ersten nie nennt. Dass die Schlüssel dort im Klartext liegen,
ist der Demo-Kompromiss aus [ADR-22](ADR-022-der-verwahrte-pin-liegt-im-klartext-demo-rahmen.md).

**Die eine Ausnahme vom Realm als Quelle:** Die `jwks.url` des Migrations-Clients steht nicht im
Realm, sondern in der SPI-Konfiguration des Keycloak-Containers
(`KC_SPI_ORCHESTRATOR_BOOTSTRAP__MIGRATION_CLIENT__JWKS_URL`). Beim Start gibt es noch kein Realm, aus
dem sie kommen könnte. Die Adresse des Orchestrators steht damit an zwei Stellen (dort und als
`orchestratorBaseUrl` der Variante). Hingenommen, weil ein falscher Wert sofort auffällt: Schon die
erste Anfrage der Migration nach einem Token scheitert beim Start, und fehlt die Option ganz, startet
Keycloak nicht.

**Kosten**, bewusst getragen:

- Ohne die Komponente `orchestrator` läuft die Erweiterung nicht. Das ist gewollt: Ein stillschweigend
  angenommener Standardwert wäre genau die Fehlkonfiguration, die vorher erst am seltsamen Verhalten
  einer Anmeldung auffiel.
- Ein geänderter Wert verwirft alles auf der Seite von Keycloak und baut es neu auf. Das ist
  verkraftbar, weil die Datenbank des Orchestrators keine IDs von Keycloak speichert: Die Nutzer
  entstehen über `orchestratorAccountId` beim nächsten Abgleich neu.
- Keycloak muss den Orchestrator erreichen können, um dessen JWKS zu holen. Diese Verbindung braucht
  die Erweiterung ohnehin für jeden Aufruf (`orchestratorBaseUrl`); neu ist nur, dass sie an einer
  zweiten Stelle sichtbar wird.
- **Nur interne Adressen:** Wer unter der `jwks.url` des Migrations-Clients antwortet, kann sich Tokens
  als Admin des Master-Realms ausstellen. Die URL darf deshalb nie über einen öffentlich erreichbaren
  Weg laufen.
- `keycloak-admin-client` 26.0.12 kann sich selbst nicht per Assertion anmelden. Ein Filter setzt
  deshalb bei jedem Aufruf das aktuelle Token ein (`buildAdminClient`).

## Geschichte

Zuerst entfielen nur die Client-Secrets im Realm; die Migration meldete sich noch als Master-Realm-Admin
mit Benutzername und Passwort an (`KEYCLOAK_ADMIN`/`KEYCLOAK_ADMIN_PASSWORD` am Orchestrator). Der
Client `orchestrator-migration` ersetzte später auch dieses letzte gemeinsame Geheimnis.
