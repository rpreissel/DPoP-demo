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

Ausdrücklich **kein** Fehlerfall: fehlende Pflichtfelder und fehlgeschlagene Versuche mit verbleibenden Retries. Sie liefern `200` plus `next` (Retry-Regel in [Orchestrierung](04-orchestrierung.md)).

## 2) Konsistenz-Regeln

- Pro `ChannelSession` darf höchstens ein aktiver `ProcessSession` existieren, unabhängig vom `purpose`.
- `ProcessSession` darf nur auf gültige Folgezustände wechseln.
- `AuthContext` wird nur bei `SUCCEEDED` aktualisiert.
- Jede relevante Transition erzeugt einen `SessionEvent` Audit-Eintrag.
- Transaktionale Klammer: Die Verarbeitung eines `ToolOutcome.Completed` ([Orchestrierung](04-orchestrierung.md)) läuft vollständig in einer Transaktion — Moduldaten, Enrollment-Anlage, Account-Eintrag, `ProcessSession`-Update und `AuthContext`-Nachweis committen gemeinsam oder gar nicht. Im Modulith ist das der einfache Weg, Zwischenzustände wie „Enrollment angelegt, aber nirgends verknüpft" auszuschließen.
- Nicht transaktional ist der SMS-Versand als externer Effekt: Ein Rollback macht eine bereits versendete SMS nicht rückgängig. Das ist ein Zustellthema (der Nutzer erhält im Zweifel eine TAN zu viel), kein Konsistenzproblem der Daten — die zugehörige `issuedTanHash`-Zeile wurde ja mit zurückgerollt und läuft ins Leere.

## 3) Aufbewahrung und Löschung

Session-Daten sind Arbeitsdaten mit begrenztem Zweck: Sie enthalten Personenbezug (KVNR, Name, Telefonnummer) und Geheimnis-Derivate (`issuedTanHash`), werden nach Prozessende nie wieder gelesen und wachsen linear mit der Nutzung. Sie werden deshalb aktiv gelöscht, nicht aufbewahrt.

Richtwerte (als Default gedacht, nicht als Compliance-Vorgabe):

| Objekt | Frist läuft ab | Richtwert | Grund |
|---|---|---|---|
| `*ToolData` (Moduldaten) | `createdAt` | 24 h | Personenbezug und TAN-Hash; nach Prozessende zwecklos |
| `ToolSession` | `expiresAt` | 24 h | reiner Lifecycle-Rest |
| `ProcessSession` | `consumedAt` / `expiresAt` | 7 Tage | Korrelation für Support-Rückfragen |
| `AuthContext` | Logout / Ende der `ChannelSession` | sofort | enthält Token-Referenzen |
| `ChannelSession` | `expiresAt` / `LOGGED_OUT` | 30 Tage | DPoP-Bindung, danach wertlos |
| `SessionEvent` | `createdAt` | 90 Tage | eigene Audit-Frist, überlebt die Sessions bewusst |
| `AuthSmsEnrollment` | — | kein Session-Cleanup | Bestandteil des Accounts, lebt bis zur Methodenlöschung |

Umgang mit den Referenzen:

- **Besitzkette** (`ChannelSession` -> `ProcessSession` -> `ToolSession` -> `*ToolData`): wird von innen nach außen abgeräumt. Weil die Fristen von innen nach außen wachsen, ergibt sich diese Reihenfolge automatisch — ein `ToolSession` verschwindet nie vor seinen Moduldaten.
- **Moduldaten** räumt jedes Modul eigenständig nach Alter (`createdAt`) auf, ohne Signal vom Orchestrator. Das ist robuster als ein Löschbefehl (ein verpasstes Signal hinterließe dauerhafte Waisen) und bleibt gültig, falls ein Modul später ein eigener Service mit eigener Datenbank wird.
- **Audit ist entkoppelt**: `SessionEvent` hält `channelSessionId`/`processSessionId` als historische Werte, nicht als Fremdschlüssel. Das ist Absicht — das Audit muss die Sessions überleben, und der Eintrag speichert ohnehin nur `payloadHash` statt Nutzdaten. Ins Leere zeigende IDs sind hier erwartet, kein Defekt.
- **Account-Objekte sind für den Session-Cleanup tabu**: `AuthSmsEnrollment`, `account.authenticationMethods` und `account.identifications` gehören dem Account, nicht der Session. Ein Cleanup-Job, der sie mitnimmt, würde dem Nutzer seinen zweiten Faktor entfernen bzw. den Nachweis vernichten, wie seine Identität festgestellt wurde. `account.identifications` überlebt damit bewusst auch die Audit-Frist der `SessionEvent`s.
