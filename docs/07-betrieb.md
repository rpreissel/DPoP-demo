# Fehler, Konsistenz und Lebenszyklus

Fehlervertrag, transaktionale Zusagen und die Frage, wie lange welche Daten aufbewahrt werden.

---

## 1) Fehlervertrag

Standardfehler (Ist und Soll):

- `400 Bad Request`: invalid payload / structurally invalid request
- `401 Unauthorized`: missing/invalid DPoP, invalid channel trust
- `403 Forbidden`: binding mismatch, policy violation
- `404 Not Found`: unknown session/process
- `409 Conflict`: invalid state transition, disallowed action, concurrent process on same channel session
- `410 Gone`: process expired/consumed/abgebrochen nach erschöpften Retries
- `422 Unprocessable Entity`: fachlich unverarbeitbarer Request, der kein Nutzereingabefehler ist (z. B. unbekannte `enrollmentRef`, fehlendes Enrollment)
- `423 Locked`: Account durch zu viele fehlgeschlagene AUTH-Versuche gesperrt (`ACCOUNT_LOCKED`, `OrchestratorException.accountLocked()`, siehe Abschnitt 4)

Ausdrücklich **kein** Fehlerfall: fehlende Pflichtfelder und fehlgeschlagene Versuche mit verbleibenden Retries. Sie liefern `200` plus `next` (Retry-Regel in [Orchestrierung](04-orchestrierung.md)).

## 2) Konsistenz-Regeln

- Pro `ChannelSession` darf höchstens eine **laufende** `AuthJourney` existieren, unabhängig vom Intent. Eine Journey, die auf eine Sub-Journey wartet, ist `SUSPENDED` und zählt deshalb nicht mit ([Orchestrierung](04-orchestrierung.md)).
- `AuthJourney` darf nur auf gültige Folgezustände wechseln — sowohl im Lebenszyklus als auch im intent-eigenen `JourneyState`.
- `AuthContext` wird nur bei `SUCCEEDED` aktualisiert.
- Jede relevante Transition erzeugt einen `SessionEvent` Audit-Eintrag.
- Transaktionale Klammer: Die Verarbeitung eines `ToolOutcome.Completed` ([Orchestrierung](04-orchestrierung.md)) läuft vollständig in einer Transaktion — Moduldaten, Enrollment-Anlage, Account-Eintrag, `AuthJourney`-Update und `AuthContext`-Nachweis committen gemeinsam oder gar nicht. Im Modulith ist das der einfache Weg, Zwischenzustände wie „Enrollment angelegt, aber nirgends verknüpft" auszuschließen.
- Nicht transaktional ist der SMS-Versand als externer Effekt: Ein Rollback macht eine bereits versendete SMS nicht rückgängig. Das ist ein Zustellthema (der Nutzer erhält im Zweifel eine TAN zu viel), kein Konsistenzproblem der Daten — die zugehörige `issuedTanHash`-Zeile wurde ja mit zurückgerollt und läuft ins Leere.

## 3) Aufbewahrung und Löschung

Session-Daten sind Arbeitsdaten mit begrenztem Zweck: Sie enthalten Personenbezug (KVNR, Name, Telefonnummer) und Geheimnis-Derivate (`issuedTanHash`), werden nach Prozessende nie wieder gelesen und wachsen linear mit der Nutzung. Sie werden deshalb aktiv gelöscht, nicht aufbewahrt.

Richtwerte (als Default gedacht, nicht als Compliance-Vorgabe):

| Objekt | Frist läuft ab | Richtwert | Grund |
|---|---|---|---|
| `*ToolData` (Moduldaten) | `createdAt` | 24 h | Personenbezug und TAN-Hash; nach Prozessende zwecklos |
| `ToolSession` | `expiresAt` | 24 h | reiner Lifecycle-Rest |
| `AuthJourney` | `consumedAt` / `expiresAt` | 7 Tage | Korrelation für Support-Rückfragen |
| `AuthContext` | Logout / Ende der `ChannelSession` | sofort | enthält Token-Referenzen |
| `ChannelSession` | `expiresAt` / `LOGGED_OUT` | 24 Stunden | bewusst kurzlebig ([Domänenmodell](02-domaenenmodell.md) Abschnitt 5) — die langlebige Geräte-Identität liegt seit `DeviceAccountLink` nicht mehr hier, ein einzelner Kanal muss nur noch eine App-Sitzung/einen Tag überdauern, nicht 30 |
| `SessionEvent` | `createdAt` | 90 Tage | eigene Audit-Frist, überlebt die Sessions bewusst |
| `AuthSmsEnrollment` | — | kein Session-Cleanup | Bestandteil des Accounts, lebt bis zur Methodenlöschung |
| `DeviceAccountLink` | — | kein Session-Cleanup | Geräte-Identität (`bindingKeyRef -> accountId`), überlebt jede einzelne `ChannelSession` bewusst (Migration `V5__add_device_account_link.sql`, [DPoP-Bindung](09-dpop.md) Abschnitt 3) |
| `LoginAttemptThrottle` | — | kein Session-Cleanup | account-gebundener Fehlversuchszähler (Abschnitt 4), überlebt jede einzelne Session; wird nur durch einen erfolgreichen Auth-Abschluss zurückgesetzt |
| `account_keycloak_keypair` | — | kein Session-Cleanup | Account-gebundenes Schlüsselpaar für den echten Token-Grant im `keycloak`-Profil ([05-api.md](05-api.md) Abschnitt 3); gelöscht direkt bei `AccountDeleted`, nicht über `RetentionJob` |

Umgang mit den Referenzen:

- **Besitzkette** (`ChannelSession` -> `AuthJourney` -> `ToolSession` -> `*ToolData`): wird von innen nach außen abgeräumt. Weil die Fristen von innen nach außen wachsen, ergibt sich diese Reihenfolge automatisch — ein `ToolSession` verschwindet nie vor seinen Moduldaten.
- **Moduldaten** räumt jedes Modul eigenständig nach Alter (`createdAt`) auf, ohne Signal vom Orchestrator. Das ist robuster als ein Löschbefehl (ein verpasstes Signal hinterließe dauerhafte Waisen) und bleibt gültig, falls ein Modul später ein eigener Service mit eigener Datenbank wird.
- **Audit ist entkoppelt**: `SessionEvent` hält `channelSessionId`/`processSessionId` als historische Werte, nicht als Fremdschlüssel. Das ist Absicht — das Audit muss die Sessions überleben, und der Eintrag speichert ohnehin nur `payloadHash` statt Nutzdaten. Ins Leere zeigende IDs sind hier erwartet, kein Defekt.
- **Account-Objekte sind für den Session-Cleanup tabu**: `AuthSmsEnrollment`, `account.authenticationMethods`, `account.identifications`, `DeviceAccountLink` und `LoginAttemptThrottle` gehören dem Account bzw. dem Gerät, nicht der Session. Ein Cleanup-Job, der sie mitnimmt, würde dem Nutzer seinen zweiten Faktor entfernen, den Nachweis vernichten, wie seine Identität festgestellt wurde, die Geräte-Wiedererkennung kappen oder den Brute-Force-Schutz aushebeln. `account.identifications` überlebt damit bewusst auch die Audit-Frist der `SessionEvent`s.
- **`KEYCLOAK`-Kanäle: Aufräumen fragt bei Keycloak nach, statt blind auf Zeit zu vertrauen.** Logout gehört im Web-Kanal vollständig Keycloak ([05-api.md](05-api.md) Abschnitt 3) - der Orchestrator erfährt nie aktiv davon. `RetentionJob` prüft deshalb für bereits abgelaufene `KEYCLOAK`-Kanäle zusätzlich per Keycloak-Admin-API, ob die zugehörige Session noch lebt (`ChannelSession.durableKcSessionId`), und räumt bei bestätigt beendeter Session sofort auf statt erst nach der vollen Retention-Frist. Eine nicht bestätigbare Antwort (kein Client im aktiven Profil, Admin-API nicht erreichbar) führt nie zu einem verfrühten Löschen - sie fällt zurück auf die normale zeitbasierte Frist.

## 4) Kontosperre bei wiederholten Fehlversuchen (Brute-Force-Schutz)

`LoginAttemptThrottle` (Entität) + `LoginThrottleService` (`src/main/kotlin/com/example/dpop/orchestrator/session/`) sperren einen Account **account-bezogen**, nicht sitzungsbezogen — bewusst unabhängig vom bereits bestehenden `ToolSession.retryCount` (Abschnitt 3, Retry-Regel in [Orchestrierung](04-orchestrierung.md) Abschnitt 1).

- Warum zusätzlich zu `retryCount` nötig: `retryCount` liegt auf der `ToolSession` und zählt deshalb nur innerhalb *eines* Tool-Anlaufs. Ein Client kann per erneutem `POST .../tools/{toolId}` jederzeit einen neuen Anlauf starten und damit einen frischen Zähler bei `0` — das allein schließt keinen Angriff aus, der beliebig viele Anläufe gegen denselben Account startet, insbesondere sobald ein geräteunabhängiger, lookup-basierter Login existiert (noch offener Punkt).
- Geprüft nur für Kategorie `AUTH` (`ToolControllerSupport.beginActivation`/`applyOutcome`): IDENT-/ENROLL-Fehlschläge sind kein Brute-Force-Ziel im selben Sinn, da dort kein Credential erraten, sondern eine Identität festgestellt oder ein neues Mittel eingerichtet wird.
- Schwellwerte: `MAX_FAILURES = 5`, `LOCKOUT_DURATION = 15 Minuten`.
- Gesperrter Account: `423 Locked` (`OrchestratorException.accountLocked()`, Fehlercode `ACCOUNT_LOCKED`, Abschnitt 1).
- Ein erfolgreicher AUTH-Abschluss setzt den Zähler zurück (`recordSuccess`), auch wenn zuvor kein Fehlversuch vorlag (dann ein No-op).
- Migration: `V8__add_login_attempt_throttle.sql`. Aufbewahrung: siehe Tabelle in Abschnitt 3 — kein Session-Cleanup, der Zähler ist Bestandteil des Accounts.
