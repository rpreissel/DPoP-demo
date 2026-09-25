# ADR-38: Keycloak liest die Konten, statt sie zu spiegeln

**Status**: entschieden und umgesetzt (2026-09-25). Review 2026-09, P-3; Fahrplan Phase E, Schritt 26.
Löst die Spiegelung ab, die [ADR-9](ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md)
(Public Key als Credential am Nutzer) und [ADR-34](ADR-034-personenverzeichnis-meldet-aenderungen.md)
(Änderungen per Event „bis Keycloak“) voraussetzten.

**Entscheidung**: Die Konten des Orchestrators *sind* die Nutzer von Keycloak. Die Nutzer-Federation
der Extension (`OrchestratorStorageProvider`) liest ein Konto bei Bedarf nach, ohne Import. Keycloak
hält keine Kopie von Identität, E-Mail, Namen oder Stammdaten; nur was Keycloak für sich selbst
braucht (Sitzungen, Fehlversuche, Zustimmungen), liegt in seinem föderierten Speicher.

**Warum**: Bei zehn Millionen Konten und mehr ist Spiegeln der teure Weg:

- Jede Änderung an einem Konto löste einen Admin-API-Aufruf aus, ein Voll-Abgleich einen je Konto.
- Zwei Wahrheiten mussten zusammengehalten werden: Konfliktregeln für E-Mail-Adressen (S-2), eine
  Suche per Attribut über alle Nutzer, ein zweiter Schreibweg in der Extension (`findOrCreateUser`)
  und das Hochladen des Public Keys an zwei Stellen.
- Lesen kostet nur, wenn sich jemand anmeldet: ein einzelner Zugriff über Primärschlüssel oder den
  eindeutigen E-Mail-Anker, unabhängig von der Zahl der Konten.

**Festlegungen**:

- **Feste Komponenten-Id** `orch-accounts` (`USER_STORAGE_COMPONENT_ID`, Migration V2). Keycloak bildet
  daraus die Nutzer-Id `f:orch-accounts:<accountId>` und damit das `sub` jedes Tokens. Eine bei jedem
  Realm-Aufbau neu vergebene Id würde jedes `sub`, jede Zustimmung und jede Sitzung ändern.
- **Die Nutzer-Id ist die Konto-Id, nicht der Benutzername.** Der Benutzername ist die bestätigte
  E-Mail (sonst `account-<id>`) und ändert sich mit ihr; das `sub` darf das nicht.
- **Cache höchstens 60 Sekunden** (`MAX_LIFESPAN`): Pro Nutzer höchstens ein Aufruf je Minute;
  Änderungen an Namen oder Adresse sind spätestens nach einer Minute sichtbar.
- **Schreibgeschützt:** Was das Konto besitzt, lässt sich in Keycloak nicht ändern
  (`ReadOnlyException`). Ein Schreibweg an Keycloak vorbei wäre wieder eine zweite Wahrheit.
- **Keine Liste:** Suchen finden genau einen Nutzer über exakten Benutzernamen, E-Mail oder Konto-Id.
- **Der Public Key (ADR-9) wird gelesen, nicht hochgeladen.** Der Grant holt ihn frisch beim
  Orchestrator, am Cache vorbei: Der Orchestrator erzeugt das Schlüsselpaar unmittelbar vor seinem
  ersten Grant-Aufruf.
- **Löschen ist das einzige Ereignis**, das Keycloak noch erreicht. Keycloaks eigene Daten zu einem
  gelöschten Konto räumt ein eigener Admin-Endpunkt ab (`AccountRemoval`), weil Keycloaks
  `DELETE users/{id}` den Nutzer zuerst nachschlagen würde – und ein gelöschtes Konto nicht mehr findet.
- **Ein nicht erreichbarer Orchestrator ist ein Fehler**, kein „Nutzer unbekannt“. Sonst sähe ein
  Ausfall wie ein falscher Benutzername aus.

**Erwogene Alternativen**:

- **Spiegeln beibehalten, nur härten** (eindeutiges Attribut, Konfliktregeln): verworfen. Die Kosten
  wachsen mit der Zahl der Konten, und jede Änderung am Abgleich ist ein möglicher Weg zur Übernahme
  eines fremden Kontos.
- **Federation mit Import** (Keycloak legt beim ersten Lesen eine lokale Kopie an): verworfen. Das ist
  wieder eine Kopie, nur später angelegt.
- **Kein Cache**: verworfen. Mehrere Orchestrator-Aufrufe je Anmeldung, bei Lastspitzen teuer, ohne
  Gewinn gegenüber einer Minute Frische.

**Folgen**:

- Beim Umstieg ändert sich das `sub` aller Nutzer einmal (die bisherigen Spiegel verschwinden mit der
  alten Komponente); bestehende Keycloak-Sitzungen verfallen. Bei einem Bestand von Millionen Nutzern
  ist das ein geplanter Migrationstermin.
- Der Orchestrator muss für jede Keycloak-Anmeldung erreichbar sein – das musste er auch vorher,
  denn der Anmeldeablauf fragt ihn ohnehin.
- `KeycloakAccountSyncService`, der Voll-Abgleich in der Admin-Oberfläche, das Hochladen des Public Keys
  und die S-2-Konfliktregeln für Spiegel entfallen.
