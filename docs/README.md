# Orchestrator-Keycloak Session-Kopplung

Diese Dokumentation beschreibt ein umsetzbares Zielmodell für die Kopplung von fachlicher Orchestrator-Session und Keycloak-IAM-Session für:

- **App-Kanal**: Login startet fachlich im Orchestrator, danach wird Keycloak-Auth-Kontext erzeugt.
- **Web-Kanal**: Keycloak-Session existiert bereits, Orchestrator steuert nur Verfahren (z. B. Step-up).

Nicht Teil dieser Dokumentation:

- konkrete Keycloak-SPI-Implementierung
- Infrastruktur-Details (Redis/DB-Cluster/Secrets-Management)

---
## Schnellstart für Agents (token-sparend)

Für AI-Agents zuerst `00-agent-quickstart.md` lesen und danach nur die fachlich benötigten Kapitel öffnen.

---
## Wo finde ich was

| Dokument | Inhalt | Gut für |
|---|---|---|
| [00-agent-quickstart.md](00-agent-quickstart.md) | Kompakter Projektkontext und zielgerichteter Lesepfad für Agents | Token-sparender Einstieg |
| [01-ueberblick.md](01-ueberblick.md) | Die tragenden Konzepte in Kurzform | Einstieg, erster Überblick |
| [02-domaenenmodell.md](02-domaenenmodell.md) | Entitäten, Zustände, Enumerationen, Persistenz-Regeln, Tabellenmodell (ER) | „Wie sieht das Datenmodell aus?" |
| [03-tool-architektur.md](03-tool-architektur.md) | Tool-Katalog, Descriptor, `ToolOutcome`, Modulklassen | Ein neues Verfahren anbinden |
| [04-orchestrierung.md](04-orchestrierung.md) | `next`-Ermittlung, `AuthPolicy`, MFA, ACR-Deckelung | „Wer entscheidet was?" |
| [05-api.md](05-api.md) | API-Grundsätze, App- und Keycloak-Fassade, Beispiele | Client-Entwicklung |
| [06-ablaeufe.md](06-ablaeufe.md) | `ident-fsc`, `auth-sms`, `enroll-sms` Schritt für Schritt | Implementierung eines Flows |
| [07-betrieb.md](07-betrieb.md) | Fehlervertrag, Konsistenz, Aufbewahrung und Löschung | Betrieb, Datenschutz |
| [08-projektrahmen.md](08-projektrahmen.md) | Aufgabenstellung, Module, Tech-Stack, Versionen, Build | Projektkontext, Einrichtung |
| [09-dpop.md](09-dpop.md) | Schlüsselerzeugung, Proof-Validierung, Kanalbindung | DPoP-Implementierung |
| [10-frontend.md](10-frontend.md) | UI-Anforderungen und lokale Routing-Tabelle | Frontend-Entwicklung |
| [11-beispiel-story.md](11-beispiel-story.md) | Eine Person durchläuft Registrierung, Login, Step-up, Löschung | Konzepte an einem konkreten Beispiel statt abstrakt |
| [12-entscheidungen.md](12-entscheidungen.md) | Architekturentscheidungen samt erwogener Alternative und Preis | Review, „Warum ist das so?" |
| [ideen/](ideen/) | Noch nicht entschiedene Gedankenspuren, mit Herleitung | Bevor man ein größeres Redesign neu durchdenkt |

### Lesepfade je Zielgruppe

Die Nummerierung 01-12 ist keine verbindliche Leserichtung — sie folgt nur der Reihenfolge, in
der die Kapitel aufeinander aufbauen. Je nach Rolle braucht man selten alle:

- **Architekt/Stakeholder/Reviewer** — großes Bild, Kompromisse, kein Implementierungsdetail:
  [01](01-ueberblick.md) -> [12](12-entscheidungen.md) -> [08](08-projektrahmen.md) ->
  [02](02-domaenenmodell.md) Abschnitt 1 (nur das Klassendiagramm) -> [04](04-orchestrierung.md)
  (Zustandsdiagramme und Policy-Regeln, ohne Code lesbar). Überspringen: 06, 07, 09, 10 — das sind
  Implementierungsanleitungen, keine Entscheidungen.
- **Backend-Entwickler** — Konzepte *und* Implementierungsmuster, praktisch die volle Liste:
  [01](01-ueberblick.md) -> [02](02-domaenenmodell.md) -> [03](03-tool-architektur.md) ->
  [04](04-orchestrierung.md) -> [06](06-ablaeufe.md) -> [09](09-dpop.md) ->
  [07](07-betrieb.md) -> [08](08-projektrahmen.md), [12](12-entscheidungen.md) bei Bedarf für das
  Warum einer bestehenden Entscheidung. Überspringen: 10 (reines Frontend-Kapitel). Für einen
  schnellen visuellen Einstieg vorab: der Abschnitt "Einstieg: Zusammenspiel an einem Schritt" in
  [03-tool-architektur.md](03-tool-architektur.md).
- **App-Frontend-Entwickler** — hauptsächlich die APIs, kein Domänenmodell-Detail:
  [01](01-ueberblick.md) -> [05](05-api.md) -> [10](10-frontend.md). Bei Bedarf ergänzend
  [README.md](README.md#begriffe) für die Session-Begriffe, die 05 voraussetzt. Überspringen: 02,
  03, 04, 06, 07, 09 — das sind Backend-interne Konzepte, die die API bereits gekapselt hat. Für
  einen schnellen visuellen Einstieg vorab: der Abschnitt "Einstieg: Wie `next` die App steuert"
  in [10-frontend.md](10-frontend.md).

---

## Begriffe

Drei Session-Ebenen mit fallender Lebensdauer:

- **ChannelSession**: langlebiger serverseitiger Kanal-Kontext (App/Web), nie direkt fachlicher Challenge-State.
- **AuthIntent**: Ziel des Nutzers *samt* Strategie, nach der er dorthin geführt wird. Einstieg: `FAST_ACCESS`, `REGISTER`, `LOOKUP_LOGIN`, `KC_SELECT_METHOD`, `CONFIRM_PEER_LOGIN`; innerhalb eines bestehenden Kanals: `STEP_UP`, `MANAGE_AUTH_METHODS`, `DELETE_ACCOUNT`, `LOGOUT`, `RE_IDENTIFY`.
- **AuthJourney**: ein laufender Durchlauf eines Intents; läuft über ein oder mehrere Tools.
- **JourneyState**: die Position auf diesem Weg samt ihrer Attribute (was angeboten wurde, was abgelehnt ist, welches Tool läuft); je Intent eine eigene versiegelte Zustandsmenge.
- **ToolSession**: ein einzelner Tool-Durchlauf innerhalb einer Journey (z. B. die TAN-Eingabe bei `auth-sms`); trägt nur Lifecycle-Metadaten, die Fachdaten liegen im Modul.
- **AuthContext**: serverseitig gespeicherter IAM-Kontext inkl. Keycloak-Token-Referenz und `acr`/`amr`.
- **binding_key_ref**: Binding-Referenz aus DPoP-Keymaterial für App-Bindung.
- **toolId**: flacher technischer Bezeichner einer konkreten Ident-/Enroll-/Auth-Methode (z. B. `enroll-sms`); ersetzt in der API die getrennte Kind-/Methode-Aufteilung.
- **toolSessionId**: UUID einer konkreten, aktivierten Tool-Instanz (nicht zu verwechseln mit `toolId`); wird beim Anlegen über `POST .../channels/{channelSessionId}/tools/{toolId}` erzeugt und referenziert danach die PATCH/GET-Ressource unter `/tools/{toolSessionId}/{toolId}`.

---
## Bezug zum bestehenden Code

Diese Dokumentation beschreibt das **Zielbild**; Backend und Frontend wurden vollständig darauf
umgebaut, einschließlich der Keycloak-Anbindung
([12-entscheidungen.md](12-entscheidungen.md) ADR-7/ADR-8/ADR-9). Bekannte Betriebsrisiken stehen
in [07-betrieb.md](07-betrieb.md).

---

## Umsetzungsstatus

1. **Domänenmodell** ✅: `ChannelSession`, `AuthJourney` (+`JourneyState` je Intent), `AuthContext`, `SessionEvent`, `ToolSession`, `DeviceAccountLink`.
2. **Tool-Architektur** ✅: `ToolDescriptor`/`ToolOutcome`/`ToolHandler` (Modul `tool_spi`), je ein Controller pro Tool — `ident-fsc`, `ident-eid`, `ident-kvnr`, `enroll-sms`/`auth-sms`/`auth-sms-lookup`, `enroll-password`/`auth-password`/`auth-password-lookup`, `enroll-email`/`auth-email`/`auth-email-lookup`, `enroll-device`/`auth-device`, `enroll-qr`/`auth-qr`/`auth-qr-lookup`, `confirm-qr-login` ([03-tool-architektur.md](03-tool-architektur.md) Abschnitt 1).
3. **App-API-Fassade** ✅: `/orchestrator/api/v1/app/...` inkl. Journey-Abbruch (`DELETE /channels/{channelSessionId}/journey`) und Back/Switch (`DELETE /tools/{toolSessionId}/{toolId}`).
4. **Keycloak-Fassade** ✅: `/orchestrator/api/v1/kc/...` (`KcChannelController`/`KcMeController`/`KeycloakSyncController`), inkl. Step-up und Server-zu-Server-Anbindung an Keycloaks native Credentials ([05-api.md](05-api.md) Abschnitt 3, [12-entscheidungen.md](12-entscheidungen.md) ADR-7/ADR-8/ADR-9).
5. **`AuthPolicy`** ✅: zentrales Gating anhand `currentAcr`/`currentAmr` inklusive Mehr-Faktor-Schleife. Die konkrete Abbildung von `amr`-Kombinationen auf `acr`-Werte bleibt eine bewusst vorläufige Platzhalter-Implementierung — fachlich/regulatorisch verbindlich festzulegen ist das nicht Teil dieses Umbaus.

---

## Hinweis zu zukünftigen Änderungen

Bei zukünftigen Änderungswünschen an dieser Dokumentation weise ich dich aktiv darauf hin, wenn neue Anforderungen oder Formulierungen mit bisher getroffenen Aussagen in Konflikt stehen könnten. Ich stelle die betroffene Stelle und den Widerspruch dar und überlasse dir die Entscheidung, wie damit umgegangen werden soll, indem ich dich interaktiv frage.
