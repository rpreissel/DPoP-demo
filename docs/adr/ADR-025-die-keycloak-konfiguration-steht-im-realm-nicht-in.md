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

---

---
