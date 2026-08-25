# Orchestrator-Keycloak Session-Kopplung

Diese Dokumentation beschreibt ein umsetzbares Zielmodell für die Kopplung von fachlicher Orchestrator-Session und Keycloak-IAM-Session für:

- **App-Kanal**: Login startet fachlich im Orchestrator, danach wird Keycloak-Auth-Kontext erzeugt.
- **Web-Kanal**: Keycloak-Session existiert bereits, Orchestrator steuert nur Verfahren (z. B. Step-up).

Nicht Teil dieser Dokumentation:

- konkrete Keycloak-SPI-Implementierung
- Infrastruktur-Details (Redis/DB-Cluster/Secrets-Management)

---
## Wo finde ich was

| Dokument | Inhalt | Gut für |
|---|---|---|
| [01-ueberblick.md](01-ueberblick.md) | Die tragenden Konzepte in Kurzform | Einstieg, erster Überblick |
| [02-domaenenmodell.md](02-domaenenmodell.md) | Entitäten, Zustände, Enumerationen, Persistenz-Regeln | „Wie sieht das Datenmodell aus?" |
| [03-tool-architektur.md](03-tool-architektur.md) | Tool-Katalog, Descriptor, `ToolOutcome`, Modulklassen | Ein neues Verfahren anbinden |
| [04-orchestrierung.md](04-orchestrierung.md) | `next`-Ermittlung, `AuthPolicy`, MFA, ACR-Deckelung | „Wer entscheidet was?" |
| [05-api.md](05-api.md) | API-Grundsätze, App- und Keycloak-Fassade, Beispiele | Client-Entwicklung |
| [06-ablaeufe.md](06-ablaeufe.md) | `ident-fsc`, `auth-sms`, `enroll-sms` Schritt für Schritt | Implementierung eines Flows |
| [07-betrieb.md](07-betrieb.md) | Fehlervertrag, Konsistenz, Aufbewahrung und Löschung | Betrieb, Datenschutz |
| [08-projektrahmen.md](08-projektrahmen.md) | Aufgabenstellung, Module, Tech-Stack, Versionen, Build | Projektkontext, Einrichtung |
| [09-dpop.md](09-dpop.md) | Schlüsselerzeugung, Proof-Validierung, Kanalbindung | DPoP-Implementierung |
| [10-frontend.md](10-frontend.md) | UI-Anforderungen und lokale Routing-Tabelle | Frontend-Entwicklung |
| [11-umsetzungsplan.md](11-umsetzungsplan.md) | Phasenplan Backend/Frontend, Entscheidung zum Altcode | Umsetzung starten |

**Empfohlene Lesereihenfolge:** 01 -> 02 -> 03 -> 04 für das Fachkonzept; 08 für den
Projektkontext. Wer nur einen Client baut, kommt mit 01, 05 und 10 aus.

---

## Begriffe

Drei Session-Ebenen mit fallender Lebensdauer:

- **ChannelSession**: langlebiger serverseitiger Kanal-Kontext (App/Web), nie direkt fachlicher Challenge-State.
- **ProcessSession**: kurzlebiger fachlicher Verfahrens-Kontext (Registration, Login, Step-up); läuft über ein oder mehrere Tools.
- **ToolSession**: ein einzelner Tool-Durchlauf innerhalb eines Verfahrens (z. B. die TAN-Eingabe bei `auth-sms`); trägt nur Lifecycle-Metadaten, die Fachdaten liegen im Modul.
- **AuthContext**: serverseitig gespeicherter IAM-Kontext inkl. Keycloak-Token-Referenz und `acr`/`amr`.
- **binding_key_ref**: Binding-Referenz aus DPoP-Keymaterial für App-Bindung.
- **toolId**: flacher technischer Bezeichner einer konkreten Ident-/Enroll-/Auth-Methode (z. B. `enroll-sms`); ersetzt in der API die getrennte Kind-/Methode-Aufteilung.
- **toolSessionId**: UUID einer konkreten, aktivierten Tool-Instanz (nicht zu verwechseln mit `toolId`); wird beim Anlegen über `POST .../channels/{channelSessionId}/tools/{toolId}` erzeugt und referenziert danach die PATCH/GET-Ressource unter `/tools/{toolSessionId}/{toolId}`.

---
## Bezug zum bestehenden Code

Diese Dokumentation beschreibt das **Zielbild**; Backend und Frontend wurden gemäß
[11-umsetzungsplan.md](11-umsetzungsplan.md) vollständig darauf umgebaut (Details und
nachträgliche Korrekturen dort, Abschnitt 5). Die einzige bewusste Lücke ist die
Keycloak-Anbindung — sie ist explizit außerhalb dieses Umbaus.

---

## Umsetzungsstatus

1. **Domänenmodell** ✅: `ChannelSession`, `ProcessSession` (+Subklassen), `AuthContext`, `SessionEvent`, `ToolSession`.
2. **Tool-Architektur** ✅: `ToolDescriptor`/`ToolOutcome`/`ToolHandler` (Modul `tool_spi`), je ein Controller pro Tool (`ident-fsc`, `enroll-sms`, `auth-sms`, `enroll-password`, `auth-password`, `enroll-email`, `auth-email`).
3. **App-API-Fassade** ✅: `/orchestrator/api/v1/app/...` inkl. Cancel (`POST .../cancel`) und Back/Switch (`DELETE /tools/{toolSessionId}/{toolId}`).
4. **Keycloak-Fassade** 🔲: `/orchestrator/api/v1/kc/...` mit Step-up-Start/Confirm — bewusst nicht umgesetzt.
5. **`AuthPolicy`** ✅: zentrales Gating anhand `currentAcr`/`currentAmr` inklusive Mehr-Faktor-Schleife. Die konkrete Abbildung von `amr`-Kombinationen auf `acr`-Werte bleibt eine bewusst vorläufige Platzhalter-Implementierung — fachlich/regulatorisch verbindlich festzulegen ist das nicht Teil dieses Umbaus (siehe [11-umsetzungsplan.md](11-umsetzungsplan.md) Abschnitt 4).

---

## Hinweis zu zukünftigen Änderungen

Bei zukünftigen Änderungswünschen an dieser Dokumentation weise ich dich aktiv darauf hin, wenn neue Anforderungen oder Formulierungen mit bisher getroffenen Aussagen in Konflikt stehen könnten. Ich stelle die betroffene Stelle und den Widerspruch dar und überlasse dir die Entscheidung, wie damit umgegangen werden soll, indem ich dich interaktiv frage.

