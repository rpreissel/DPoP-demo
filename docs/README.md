# Kopplung von Orchestrator- und Keycloak-Sitzung

Diese Dokumentation beschreibt ein umsetzbares Zielmodell dafür, wie die fachliche Sitzung im
Orchestrator und die Sitzung in Keycloak zusammenspielen – für beide Kanäle:

- **App-Kanal**: Der Login beginnt fachlich im Orchestrator; erst danach entsteht der
  Keycloak-Kontext.
- **Web-Kanal**: Keycloak führt den Login. Der Orchestrator bestimmt über `KC_SELECT_METHOD`, welche
  Verfahren angeboten werden – beim ersten Login ebenso wie beim Step-up.

Nicht Teil dieser Dokumentation:

- Details der Infrastruktur (Redis, Datenbank-Cluster, Verwaltung von Geheimnissen)

---
## Schnellstart für Agents (token-sparend)

AI-Agents lesen zuerst `00-agent-quickstart.md` und öffnen danach nur die Kapitel, die sie
fachlich brauchen.

---
## Wo finde ich was

| Dokument | Inhalt | Gut für |
|---|---|---|
| [00-agent-quickstart.md](00-agent-quickstart.md) | Kompakter Projektkontext und gezielter Lesepfad für Agents | Token-sparender Einstieg |
| [01-ueberblick.md](01-ueberblick.md) | Die tragenden Konzepte in Kurzform | Einstieg, erster Überblick |
| [02-domaenenmodell.md](02-domaenenmodell.md) | Entitäten, Zustände, Aufzählungstypen, Regeln fürs Speichern, Tabellenmodell (ER) | „Wie sieht das Datenmodell aus?" |
| [03-tool-architektur.md](03-tool-architektur.md) | Tool-Katalog, Selbstbeschreibung (Descriptor), `ToolOutcome`, Modulklassen | Ein neues Verfahren anbinden |
| [04-orchestrierung.md](04-orchestrierung.md) | Wie `next` entsteht, `AuthPolicy`, MFA, Begrenzung des ACR | „Wer entscheidet was?" |
| [journeys/](journeys/) | Eine Datei je Journey (`FAST_ACCESS`, `REGISTER`, …) – der Katalog aus Kapitel 04, Abschnitt 3 | „Wie läuft genau dieser eine Ablauf?" |
| [05-api.md](05-api.md) | API-Grundsätze, App- und Keycloak-Zugang, Beispiele | Client-Entwicklung |
| [06-ablaeufe.md](06-ablaeufe.md) | `ident-fsc`, SMS/Passwort/E-Mail, Gerät, eID/KVNR und KOBIL Schritt für Schritt | Einen Ablauf implementieren |
| [07-betrieb.md](07-betrieb.md) | Fehlervertrag, Konsistenz, Aufbewahrung und Löschung | Betrieb, Datenschutz |
| [08-projektrahmen.md](08-projektrahmen.md) | Aufgabenstellung, Module, Technik, Versionen, Build | Projektkontext, Einrichtung |
| [09-dpop.md](09-dpop.md) | Schlüsselerzeugung, Prüfung der Proofs, Bindung an den Kanal | DPoP-Implementierung |
| [10-frontend.md](10-frontend.md) | Anforderungen an die Oberfläche und lokale Routing-Tabelle | Frontend-Entwicklung |
| [11-beispiel-story.md](11-beispiel-story.md) | Eine Person durchläuft Registrierung, Login, Step-up, Geräte-Verfahren, QR-Login im Browser und Löschung | Konzepte an einem konkreten Beispiel statt abstrakt |
| [12-entscheidungen.md](12-entscheidungen.md) | **Index** der Architekturentscheidungen | Review, „Warum ist das so?" |
| [adr/](adr/) | Eine Datei je Entscheidung, samt erwogener Alternative und Kosten | Genau eine Entscheidung nachlesen |
| [review-2026-09-sicherheit-und-konzept.md](review-2026-09-sicherheit-und-konzept.md) | Offenes Review: Sicherheitsbefunde und konzeptionelle Schwächen, nach Schwere sortiert | Härtung planen, Befunde abarbeiten |
| [invarianten.md](invarianten.md) | Die Regeln, auf die sich der Kern verlässt, und womit jede gesichert ist (Typ, Constraint, Test) – Lücken sichtbar | Bevor man eine Invariante anfasst oder eine neue einführt |
| [review-2026-09-bewertung-und-massnahmen.md](review-2026-09-bewertung-und-massnahmen.md) | Einschätzung des Ganzen, strukturelle Ursachen, Gegenmaßnahmen und globale Reihenfolge | Entscheiden, was zuerst passiert |
| [archiv/](archiv/) | Abgeschlossene Reviews – historisch, nicht maßgeblich | „Wie wurde das damals entschieden?" |
| [ideen/](ideen/) | Noch nicht entschiedene Überlegungen samt Herleitung | Bevor man ein größeres Redesign neu durchdenkt |
| [glossar/](glossar/) | Externes Begriffsglossar und sein Abgleich mit diesem Projekt | Prüfen, ob das Domänenmodell fremde Begriffe abbilden kann |

### Lesepfade je Zielgruppe

Die Nummerierung 01–12 schreibt keine Leserichtung vor; sie folgt nur der Reihenfolge, in der die
Kapitel aufeinander aufbauen. Je nach Rolle braucht man selten alle:

- **Architekt, Stakeholder, Reviewer** – das große Bild und die Kompromisse, keine
  Implementierungsdetails: [01](01-ueberblick.md) -> [12](12-entscheidungen.md) ->
  [08](08-projektrahmen.md) -> [02](02-domaenenmodell.md) Abschnitt 1 (nur das Klassendiagramm) ->
  [04](04-orchestrierung.md) (Zustandsdiagramme und Regeln, ohne Code lesbar). Überspringen:
  06, 07, 09 und 10 – das sind Anleitungen zur Implementierung, keine Entscheidungen.
- **Backend-Entwickler** – Konzepte *und* Implementierungsmuster, also fast die ganze Liste:
  [01](01-ueberblick.md) -> [02](02-domaenenmodell.md) -> [03](03-tool-architektur.md) ->
  [04](04-orchestrierung.md) -> [06](06-ablaeufe.md) -> [09](09-dpop.md) ->
  [07](07-betrieb.md) -> [08](08-projektrahmen.md); [12](12-entscheidungen.md) bei Bedarf, um
  nachzulesen, warum etwas entschieden wurde. Überspringen: 10 (reines Frontend-Kapitel). Einen
  schnellen, bildhaften Einstieg bietet vorab der Abschnitt „Einstieg: Zusammenspiel an einem
  Schritt" in [03-tool-architektur.md](03-tool-architektur.md).
- **App-Frontend-Entwickler** – vor allem die APIs, kaum Details des Domänenmodells:
  [01](01-ueberblick.md) -> [05](05-api.md) -> [10](10-frontend.md). Bei Bedarf ergänzend die
  [Begriffe](README.md#begriffe) zu den Sitzungsebenen, die Kapitel 05 voraussetzt. Überspringen:
  02, 03, 04, 06, 07 und 09 – das sind Konzepte im Backend, die die API bereits verbirgt. Einen
  schnellen, bildhaften Einstieg bietet vorab der Abschnitt „Einstieg: Wie `next` die App steuert"
  in [10-frontend.md](10-frontend.md).

---

## Begriffe

Drei Sitzungsebenen, von lang- zu kurzlebig:

- **ChannelSession**: der Kontext eines Kanals (App oder Web) auf dem Server. Sie überdauert
  einzelne Verfahren, ist aber bewusst kurzlebig (ADR-3); welches Gerät zu welchem Konto gehört,
  steht dauerhaft in `DeviceAccountLink`. Den fachlichen Zustand eines laufenden Verfahrens
  speichert sie nie.
- **AuthIntent**: das Ziel des Nutzers *samt* der Strategie, die ihn dorthin führt. Zum Einstieg:
  `FAST_ACCESS`, `REGISTER`, `LOOKUP_LOGIN`, `KC_SELECT_METHOD`, `CONFIRM_PEER_LOGIN`; innerhalb
  eines bestehenden Kanals: `STEP_UP`, `MANAGE_AUTH_METHODS`, `DELETE_ACCOUNT`, `LOGOUT`,
  `RE_IDENTIFY`.
- **AuthJourney**: ein laufender Durchlauf zu einem Intent; er umfasst ein oder mehrere Tools.
- **JourneyState**: wo die Journey gerade steht, samt der Angaben dazu (was angeboten wurde, was
  abgelehnt ist, welches Tool läuft). Jeder Intent hat seine eigene, abgeschlossene Menge von
  Zuständen.
- **ToolSession**: ein einzelner Durchlauf eines Tools innerhalb einer Journey (z. B. die
  TAN-Eingabe bei `auth-sms`). Sie hält nur Daten zum Lebenszyklus; die Fachdaten liegen im Modul.
- **AuthEvidence**: die Nachweise einer Sitzung auf dem Server; aus ihnen folgen `acr` und `amr`.
- **AuthContext**: die Tokens des App-Kanals, gekoppelt an die `AuthEvidence` dieser Sitzung.
- **binding_key_ref**: der aus dem DPoP-Schlüssel abgeleitete Wert, über den ein Gerät gebunden
  wird.
- **toolId**: der technische Name eines konkreten Verfahrensschritts zum Identifizieren,
  Einrichten oder Anmelden (z. B. `enroll-sms`). In der API ersetzt er die frühere Aufteilung in
  Art und Methode.
- **toolSessionId**: die UUID einer konkreten, gestarteten Tool-Instanz (nicht zu verwechseln
  mit `toolId`). Sie entsteht beim Anlegen über `POST .../channels/{channelSessionId}/tools/{toolId}`
  und bezeichnet danach die Ressource unter `/tools/{toolSessionId}/{toolId}`, die per `PATCH`
  und `GET` angesprochen wird.

### Deutsche Begriffe und ihre Namen im Code

Die Doku ist deutsch, der Code englisch. Wo die Namen auseinandergehen:

| In der Doku | Im Code / in der Datenbank |
|---|---|
| Angabe, bestätigte Angabe | `AccountClaim`, `account.claim` |
| bestätigen (ein Attribut) | `attest`, `ToolOutcome.Completed.Attested`, Rolle `ATTESTATION` |
| Widerruf, ein Attribut zurücknehmen | `AccountRetraction`, `account.retraction`, `AccountService.retractAttribute` |
| Anker | `AccountAnchor`, `account.anchor` |
| Obergrenze eines Niveaus | `maxAcr`, `enrolledUnderAcr` |
| Mindestniveau, um einen Anker zu schreiben | `AnchorRule.acrFloor` |

---
## Bezug zum bestehenden Code

Diese Dokumentation beschreibt das **Zielbild**. Backend und Frontend wurden vollständig darauf
umgebaut, einschließlich der Keycloak-Anbindung
([12-entscheidungen.md](12-entscheidungen.md) ADR-7/ADR-8/ADR-9). Bekannte Betriebsrisiken stehen
in [07-betrieb.md](07-betrieb.md).

---

## Umsetzungsstand

1. **Domänenmodell** ✅: `ChannelSession`, `AuthJourney` (mit einem `JourneyState` je Intent),
   `AuthEvidence`, `AuthContext`, `SessionEvent`, `ToolSession`, `DeviceAccountLink`.
2. **Tool-Architektur** ✅: `ToolDescriptor` und `ToolOutcome` (Modul `tool_spi`); die Handler
   liegen innerhalb ihrer Module. Jedes Tool hat einen eigenen Controller – `ident-fsc`,
   `ident-eid`, `ident-nect`, `ident-kvnr`, `confirm-email`,
   `enroll-sms`/`auth-sms`/`auth-sms-lookup`, `enroll-password`/`auth-password`/`auth-password-lookup`,
   `enroll-email`/`auth-email`/`auth-email-lookup`, `enroll-device`/`auth-device`,
   `enroll-kobil`/`auth-kobil`, `enroll-qr`/`auth-qr`/`auth-qr-lookup`, `confirm-qr-login`
   ([03-tool-architektur.md](03-tool-architektur.md) Abschnitt 1).
3. **App-Zugang (API)** ✅: `/orchestrator/api/v1/app/...`, einschließlich Abbruch der Journey
   (`DELETE /channels/{channelSessionId}/journey`) sowie Zurück und Verfahrenswechsel
   (`DELETE /tools/{toolSessionId}/{toolId}`).
4. **Keycloak-Zugang** ✅: `/orchestrator/api/v1/kc/...` (`KcChannelController`), einschließlich
   Step-up und der Anbindung an Keycloaks eigene Credentials von Server zu Server. Der Abgleich der
   Konten (`KeycloakSyncController`) ist ein Betriebsendpunkt unter
   `/orchestrator/admin/keycloak/sync` ([05-api.md](05-api.md) Abschnitt 3,
   [12-entscheidungen.md](12-entscheidungen.md) ADR-7/ADR-8/ADR-9). Ob Keycloak die Anmeldeseiten mit
   FreeMarker oder mit Keycloakify zeigt, schaltet der Betriebsendpunkt
   `/orchestrator/admin/login-theme` zur Laufzeit um
   ([ideen/keycloakify-statt-freemarker.md](ideen/keycloakify-statt-freemarker.md)).
5. **`AuthPolicy`** ✅: zentrale Prüfung anhand von `currentAcr` und `currentAmr`, samt der
   Schleife über mehrere Faktoren. Welche `amr`-Kombination welchen `acr`-Wert ergibt, ist bewusst
   nur vorläufig festgelegt; eine fachlich oder regulatorisch verbindliche Festlegung gehört nicht
   zu diesem Umbau.

---

## Hinweis zu künftigen Änderungen

Wünschst du später Änderungen an dieser Dokumentation, weise ich dich darauf hin, wenn eine neue
Anforderung oder Formulierung früheren Aussagen widersprechen könnte. Ich zeige dir die
betroffene Stelle und den Widerspruch und frage dich, wie damit umgegangen werden soll.
