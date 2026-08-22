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

**Empfohlene Lesereihenfolge:** 01 -> 02 -> 03 -> 04. Wer nur einen Client baut, kommt mit 01 und 05 aus.

---

## Begriffe

Drei Session-Ebenen mit fallender Lebensdauer:

- **ChannelSession**: langlebiger serverseitiger Kanal-Kontext (App/Web), nie direkt fachlicher Challenge-State.
- **ProcessSession**: kurzlebiger fachlicher Verfahrens-Kontext (Registration, Login, Step-up); läuft über ein oder mehrere Tools.
- **ToolSession**: ein einzelner Tool-Durchlauf innerhalb eines Verfahrens (z. B. die TAN-Eingabe bei `auth-sms`); trägt nur Lifecycle-Metadaten, die Fachdaten liegen im Modul.
- **AuthContext**: serverseitig gespeicherter IAM-Kontext inkl. Keycloak-Token-Referenz und `acr`/`amr`.
- **binding_key_ref**: Binding-Referenz aus DPoP-Keymaterial für App-Bindung.
- **toolId**: flacher technischer Bezeichner einer konkreten Ident-/Enroll-/Auth-Methode (z. B. `enroll-sms`); ersetzt in der API die getrennte Kind-/Methode-Aufteilung.
- **toolSessionId**: UUID einer konkreten, aktivierten Tool-Instanz (nicht zu verwechseln mit `toolId`); wird beim Anlegen über `tool-activate` erzeugt und referenziert danach die PATCH/GET-Ressource unter `/tools/{toolSessionId}/{toolId}`.

---
## Bezug zum bestehenden Code

- Die `binding_session`-Tabelle wurde vollständig entfernt (Flyway V16); fachlicher Flow-Kontext wird nun über `ProcessSession` abgebildet.
- Das alte Public-API (`/orchestrator/sessions`) wurde entfernt; das aktuelle API liegt unter `/orchestrator/api/v1/app/...`.
- `ChannelSession` ist langlebig und DPoP-gebunden über `binding_key_ref`.
- `AuthContext` ist bereit für Keycloak-Integration (Struktur vorhanden, Keycloak-Anbindung noch nicht implementiert).

---

## Umsetzungsstatus

1. **Schritt 1** ✅: Entitäten `ChannelSession`, `AuthContext`, `SessionEvent` hinzugefügt.
2. **Schritt 2** ✅: Bestehende Flow-Session in konkrete Prozessklassen aufgeteilt (`RegistrationProcessSession`, `LoginProcessSession`, `StepUpProcessSession`).
3. **Schritt 3** ✅: App-API-Fassade (`/orchestrator/api/v1/app/...`) aufgebaut; alte Ist-Stand-API entfernt.
4. **Schritt 4** 🔲: Keycloak-Fassade (`/orchestrator/api/v1/kc/...`) mit Step-up-Start/Confirm (noch nicht implementiert).
5. **Schritt 5** 🔲: `AuthPolicy` implementieren (siehe [04-orchestrierung.md](04-orchestrierung.md)) — zentrales Gating anhand `currentAcr`/`currentAmr` inklusive Mehr-Faktor-Schleife. Die konkrete Abbildung von `amr`-Kombinationen auf `acr`-Werte ist fachlich/regulatorisch festzulegen und bewusst noch offen.

---

## Hinweis zu zukünftigen Änderungen

Bei zukünftigen Änderungswünschen an dieser Dokumentation weise ich dich aktiv darauf hin, wenn neue Anforderungen oder Formulierungen mit bisher getroffenen Aussagen in Konflikt stehen könnten. Ich stelle die betroffene Stelle und den Widerspruch dar und überlasse dir die Entscheidung, wie damit umgegangen werden soll, indem ich dich interaktiv frage.

