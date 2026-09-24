# ADR-25: Die Keycloak-Konfiguration steht im Realm, nicht in der Container-Umgebung

**Entscheidung** (umgesetzt): Die Keycloak-Extension liest ihre Laufzeitkonfiguration aus den
Config-Properties der `orchestrator`-Komponente des Realms (`OrchestratorSettings`), nicht mehr aus
Umgebungsvariablen des Keycloak-Containers. Fehlt die Komponente oder ein Wert, scheitert der
Aufruf sofort und sichtbar — es gibt keinen Fallback.

Vorgelagert dazu beschreibt **ein** Wertobjekt die ganze Umgebung (`KeycloakSetup`, gewählt über
`keycloak-setup.variant`): aus ihm speist sich sowohl der Realm-Aufbau durch die Migration als auch
der laufende Betrieb des Orchestrators (Account-Sync, Peer-Auth-JWKS, OIDC-Prüfung).

**Warum**: Aufbau und Betrieb meinen dasselbe Realm und dieselben Clients. Solange Migration und
Laufzeit ihre Werte aus getrennten Quellen zogen — `System.getenv` im Migrationsskript, Env-Vars am
Keycloak-Container, Spring-Properties am Orchestrator —, genügte ein Tippfehler für „Migration gegen Realm A,
Sync gegen Realm B", und der Fehler zeigte sich erst als 401 tief im Betrieb. Zugleich war
die Extension-Konfiguration im laufenden Keycloak nirgends sichtbar: weder in der Admin-Console
noch im Realm-Export.

**Zwei Hälften, eine Konsequenz**: `RealmSetup` ist, was ins Realm geschrieben wird;
`KeycloakAccess` nur, wie man das fertige Realm erreicht. Ändert sich ein Wert der ersten Hälfte,
baut der `MigrationRunner` das Realm neu auf — dieselbe Folge wie bei einer geänderten
Migrationsdatei, und aus demselben Grund: Die erledigten Schritte fassen den Wert nie wieder an,
das Realm würde unbemerkt mit dem alten weiterlaufen. Das Migrationsskript bekommt deshalb
ausschließlich `RealmSetup` zu sehen — ein Schritt kann gar nicht erst einen Wert verbauen, den der
Reset nicht überwacht.

### Kein geteiltes Geheimnis mehr

Im selben Zug entfallen die Client-Secrets: `orchestrator-admin` und `orchestrator-app-token`
authentifizieren sich per **`private_key_jwt`** (RFC 7523, die in ADR-9 erwogene Härtung). Der
Orchestrator signiert jeden Token-Request mit seinem eigenen Schlüssel, Keycloak holt den
öffentlichen Teil unter `jwks.url` ab — spiegelbildlich zu der Assertion, mit der sich Keycloak
beim Orchestrator ausweist (ADR-7). Beide Richtungen tragen damit dasselbe Prinzip, und in
Konfiguration, Compose-Datei und Realm steht kein geteiltes Geheimnis mehr.

Beide Signaturschlüssel liegen jetzt **in einer Datenbank** statt im Prozessspeicher: der des
Orchestrators in `orchestrator.node_signing_key`, der der Extension als Property der
`orchestrator`-Komponente, also in Keycloaks eigener DB. Vorher entstand je Seite ein Paar pro
JVM-Lauf — das reichte nur, solange genau ein Knoten lief: Ein zweiter hätte mit einem Schlüssel
signiert, den das JWKS des ersten nie nennt.

**Kosten**, bewusst getragen:

- Ohne die `orchestrator`-Komponente läuft die Extension nicht. Das ist gewollt: Ein
  stillschweigender Standardwert wäre genau die Fehlkonfiguration, die vorher erst am seltsamen
  Verhalten eines Logins auffiel.
- Ein geänderter Wert wirft die Keycloak-Seite weg und baut sie neu. Verkraftbar, weil die
  Orchestrator-DB keine Keycloak-Ids speichert — die Nutzer entstehen über
  `orchestratorAccountId` beim nächsten Sync neu.
- Keycloak muss den Orchestrator erreichen können, um dessen JWKS zu holen — dieselbe Strecke, die
  die Extension ohnehin für jeden kc-facade-Aufruf braucht (`orchestratorBaseUrl`), also keine
  neue Abhängigkeit, aber eine zweite Stelle, an der sie sichtbar wird.
- Die privaten Schlüssel liegen im Klartext in ihrer jeweiligen Datenbank — derselbe Demo-Rahmen,
  den ADR-22 für den verwahrten PIN benennt.

**Nachtrag: auch die Migration ohne Passwort.** Übrig geblieben war ein geteiltes Geheimnis: Die
Migration meldete sich als Master-Realm-Admin mit Benutzername und Passwort an
(`KEYCLOAK_ADMIN`/`KEYCLOAK_ADMIN_PASSWORD` am Orchestrator). Jetzt legt die Extension beim Start von
Keycloak selbst einen Client `orchestrator-migration` im Master-Realm an
(`MigrationClientBootstrapFactory`, nach Keycloaks eigener Datenbank-Migration, idempotent):
`private_key_jwt` gegen das JWKS des Orchestrators — derselbe Schlüssel wie bei den Realm-Clients —
und die Master-Rolle `admin` — dieselben Rechte wie der Passwort-Admin, den er ersetzt.
`create-realm` allein trägt nicht: Keycloak prüft Admin-Rechte an den Rollen im Token, und die
Rechte auf ein neu angelegtes Realm bekäme der Anleger erst nach dem Anlegen. Der Orchestrator holt sein Token per `client_credentials` mit
Assertion (`KeycloakMigrationToken`). Der Bootstrap-Admin bleibt nur für Menschen an der
Admin-Console.

- **Die eine Ausnahme von dieser Entscheidung:** Die `jwks.url` dieses Clients steht nicht im Realm,
  sondern in der SPI-Konfiguration des Keycloak-Containers
  (`KC_SPI_ORCHESTRATOR_BOOTSTRAP__MIGRATION_CLIENT__JWKS_URL`). Beim Start gibt es noch kein Realm,
  aus dem sie kommen könnte. Die Orchestrator-Adresse steht damit an zwei Orten (dort und als
  `orchestratorBaseUrl` der Setup-Variante). Hingenommen, weil ein falscher Wert nicht spät auffällt:
  Der erste Token-Request der Migration scheitert beim Start. Fehlt die Option ganz, startet
  Keycloak nicht.
- **Nur interne Adressen:** Wer unter dieser URL antwortet, kann sich Master-Admin-Tokens
  ausstellen. Sie darf deshalb nie über eine öffentliche Route laufen.
- **Admin-Client:** `keycloak-admin-client` 26.0.12 kann sich selbst nicht per Assertion anmelden.
  Ein Request-Filter setzt deshalb bei jedem Aufruf das frische Token ein (`buildAdminClient`).

---

---
