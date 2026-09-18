# Fehler, Konsistenz und Lebenszyklus

Fehlervertrag, transaktionale Zusagen und die Frage, wie lange welche Daten aufbewahrt werden.

---

## 1) Fehlervertrag

Standardfehler (Ist und Soll):

- `400 Bad Request`: invalid payload / structurally invalid request
- `401 Unauthorized`: missing/invalid DPoP, invalid channel trust
- `403 Forbidden`: binding mismatch, policy violation
- `404 Not Found`: unknown session/process
- `409 Conflict`: invalid state transition, disallowed action, concurrent process on same channel session, conflicting account claims
- `410 Gone`: process expired/consumed/abgebrochen nach erschöpften Retries
- `422 Unprocessable Entity`: fachlich unverarbeitbarer Request, der kein Nutzereingabefehler ist (z. B. unbekannte `enrollmentRef`, fehlendes Enrollment)
- `423 Locked`: Account durch zu viele fehlgeschlagene AUTH-Versuche gesperrt (`ACCOUNT_LOCKED`, `OrchestratorException.accountLocked()`, siehe Abschnitt 4)
- `429 Too Many Requests`: Rate-Limit erreicht, kein Sperr-/Fehlerzustand des Accounts (`OrchestratorException.tooManyRequests()`, siehe Abschnitt 4 — `ChannelCreationThrottleService`, gekeyt auf `bindingKeyRef`)

Ausdrücklich **kein** Fehlerfall: fehlende Pflichtfelder und fehlgeschlagene Versuche mit verbleibenden Retries. Sie liefern `200` plus `next` (Retry-Regel in [Orchestrierung](04-orchestrierung.md)).

## 2) Konsistenz-Regeln

- Pro `ChannelSession` darf höchstens eine **laufende** `AuthJourney` existieren, unabhängig vom Intent. Eine Journey, die auf eine Sub-Journey wartet, ist `SUSPENDED` und zählt deshalb nicht mit ([Orchestrierung](04-orchestrierung.md)).
- `AuthJourney` darf nur auf gültige Folgezustände wechseln — sowohl im Lebenszyklus als auch im intent-eigenen `JourneyState`.
- `AuthContext` wird nur bei `SUCCEEDED` aktualisiert.
- Jede relevante Transition erzeugt einen `SessionEvent` Audit-Eintrag.
- Transaktionale Klammer: Die Verarbeitung eines `ToolOutcome.Completed` ([Orchestrierung](04-orchestrierung.md)) atomarisiert die Journey-Übernahme, den Account-Eintrag, den Claim-Log und den `AuthContext`-Nachweis. Das jeweilige Methodenmodul schreibt seine Tool-/Enrollment-Daten bereits beim `PATCH` in einer eigenen Transaktion; scheitert die spätere Journey-Übernahme, bleibt diese Moduldatenzeile als kurzlebige, vom Modul bereinigte Arbeitsdaten bestehen, wird aber nicht als Account-Credential aktiviert. Im Modulith verhindert die gemeinsame Übernahmetransaktion Zwischenzustände innerhalb der Orchestrierung.
- Auch neue Accounts, Claim-Log, Identifizierungs-Log, Anker und Methodeninstanzen teilen diese Transaktion; kein vorgezogener Account-Commit mit `REQUIRES_NEW`. Bei konkurrierender Bindung bleibt nur der Gewinner bestehen, der Verlierer rollt vollständig zurück und erhält `409 INVALID_STATE_TRANSITION`. Es gibt keinen automatischen Wiederholungsversuch. Unique-Verletzungen der Account-Bindungs-Constraints (`ux_anchor_value`, `ux_anchor_account_type`) werden auch bei Flush/Commit gezielt übersetzt; unbekannte Integritätsfehler bleiben Serverfehler. After-Commit-Synchronisierung läuft nur nach erfolgreichem Commit.
- Der reine Demo-Seed verwendet eine eigene transaktionale Klammer um seine Anlage und Claim-Übernahme. Er löst bestehende Accounts über PersonId-Anker auf und erzeugt bei Neustarts keine zusätzlichen Bootstrap-Claims; er benötigt keine produktive Wiederholungslogik.
- Nicht transaktional ist der SMS-Versand als externer Effekt: Ein Rollback macht eine bereits versendete SMS nicht rückgängig. Das ist ein Zustellthema (der Nutzer erhält im Zweifel eine TAN zu viel), kein Konsistenzproblem der Daten — die zugehörige `issuedTanHash`-Zeile wurde ja mit zurückgerollt und läuft ins Leere.

## 3) Aufbewahrung und Löschung

Session-Daten sind Arbeitsdaten mit begrenztem Zweck: Sie enthalten Personenbezug (KVNR, Name, Telefonnummer) und Geheimnis-Derivate (`issuedTanHash`), werden nach Prozessende nie wieder gelesen und wachsen linear mit der Nutzung. Sie werden deshalb aktiv gelöscht, nicht aufbewahrt.

Richtwerte (als Default gedacht, nicht als Compliance-Vorgabe):

| Objekt | Frist läuft ab | Richtwert | Grund |
|---|---|---|---|
| `<modul>.*_tool_session` (Moduldaten) | `createdAt` | 24 h | Personenbezug und TAN-Hash; nach Prozessende zwecklos. Jedes Methodenmodul hat dafür einen eigenen `*RetentionJob` (auch `auth_device`, `auth_qr`) |
| `QrLoginRequest` | `expiresAt` | 24 h | Pairing-Anfrage, nach Ablauf (5 Min.) wirkungslos; von `AuthQrRetentionJob` mit abgeräumt |
| `DpopProofReplay` | `expiresAt` | sofort (minütlich) | Replay-Schutz gilt nur im Akzeptanzfenster eines Proofs |
| `orchestrator.tool_session` | `expiresAt` | 24 h | reiner Lifecycle-Rest |
| `AuthJourney` | `consumedAt` / `expiresAt` | 7 Tage | Korrelation für Support-Rückfragen |
| `AuthContext` | Logout / Ende der `ChannelSession` | sofort | enthält Token-Referenzen |
| `ChannelSession` | `expiresAt` / `LOGGED_OUT` | 30 Tage | die langlebige Geräte-Identität liegt seit `DeviceAccountLink` nicht mehr hier, aber `JourneyLogEntry` fragt den Log über die Channel-Menge ab ([Domänenmodell](02-domaenenmodell.md) Abschnitt 5) |
| `SessionEvent` | `createdAt` | 90 Tage | eigene Audit-Frist, überlebt die Sessions bewusst |
| `JourneyLogEntry` | `createdAt` | 30 Tage | bewusst gleich `ChannelSession`: wird nur über die Channel-Menge abgefragt, länger zu leben bringt nichts. Debug-/Demo-Trace, NICHT der Audit-Trail — das bleibt `SessionEvent` |
| `*Enrollment` (Modul-Credentials) | — | kein Session-Cleanup | Bestandteil des Accounts, lebt bis zur Kontolöschung (erreicht über `account.auth_method`, auch deaktivierte Instanzen) |
| `account.*` (Anker, Methoden, Claim-, Identifizierungs- und Widerrufs-Log) | — | kein Session-Cleanup | gehört dem Konto, kaskadiert mit dessen Löschung. **Offen** ([12-entscheidungen.md](12-entscheidungen.md) ADR-12): ein widerrufener Wert bleibt bis zur Kontolöschung stehen — eine Frist, nach der Claim- und Retraktions-Zeile gemeinsam gelöscht werden, ist noch nicht entschieden |
| `DeviceAccountLink` | — | kein Session-Cleanup | Geräte-Identität (`bindingKeyRef -> accountId`), überlebt jede einzelne `ChannelSession` bewusst ([DPoP-Bindung](09-dpop.md) Abschnitt 3) |
| `AttemptThrottle` | letzter Zähler-Update | 7 Tage | zwei Größenordnungen über dem längsten Fenster/Lockout (15 Min.); ein Sweep rührt nie eine Zeile an, deren Sperre noch läuft. Fehlversuchs-/Versand-Zähler aller Scopes (`ACCOUNT`/`PERSON`/`BINDING_KEY`/`ACCOUNT_SEND`/`CONTACT_SEND`, Abschnitt 4); die beiden Fehlversuchs-Scopes werden nur durch einen erfolgreichen Auth-/Ident-Abschluss zurückgesetzt, die drei Fenster-Scopes laufen einfach ab |
| `orchestrator.keycloak_keypair` | — | kein Session-Cleanup | Account-gebundenes Schlüsselpaar für den echten Token-Grant im `keycloak`-Profil ([05-api.md](05-api.md) Abschnitt 3); gelöscht direkt bei `AccountDeleted`, nicht über `RetentionJob` |

Umgang mit den Referenzen:

- **Besitzkette** (`ChannelSession` -> `AuthJourney` -> `orchestrator.tool_session` -> die Modulhälfte `<modul>.*_tool_session`): wird von innen nach außen abgeräumt. Weil die Fristen von innen nach außen wachsen, ergibt sich diese Reihenfolge automatisch — die Lifecycle-Zeile verschwindet nie vor den Moduldaten, die an ihrer `tool_session_id` hängen.
- **Moduldaten** räumt jedes Modul eigenständig nach Alter (`createdAt`) auf, ohne Signal vom Orchestrator. Das ist robuster als ein Löschbefehl (ein verpasstes Signal hinterließe dauerhafte Waisen) und bleibt gültig, falls ein Modul später ein eigener Service mit eigener Datenbank wird.
- **Audit ist entkoppelt**: `SessionEvent` hält `channelSessionId`/`processSessionId` als historische Werte, nicht als Fremdschlüssel. Das ist Absicht — das Audit muss die Sessions überleben, und der Eintrag speichert ohnehin nur `payloadHash` statt Nutzdaten. Ins Leere zeigende IDs sind hier erwartet, kein Defekt.
- **Account-Objekte sind für den Session-Cleanup tabu**: die Modul-Credentials (`*_enrollment`), `account.auth_method`, `account.identification` und `DeviceAccountLink` gehören dem Account bzw. dem Gerät, nicht der Session. Ein Cleanup-Job, der sie mitnimmt, würde dem Nutzer seinen zweiten Faktor entfernen, den Nachweis vernichten, wie seine Identität festgestellt wurde, oder die Geräte-Wiedererkennung kappen. `account.identification` überlebt damit bewusst auch die Audit-Frist der `SessionEvent`s.
- **Kontolöschung räumt zusätzlich zwei Session-Tabellen für die gelöschte `accountId` auf**, obwohl beide keinen Fremdschlüssel auf `account` tragen: `orchestrator.journey_log` (über **zwei** Schlüssel — Konto **und** dessen Channel-Sessions, da Einträge vor der Kontobindung `account_id = NULL` tragen) und `orchestrator.attempt_throttle` (nur die kontobezogenen Scopes `ACCOUNT`/`ACCOUNT_SEND` — `BINDING_KEY`/`CONTACT_SEND` blieben sonst ein Weg, fremde Throttle-Budgets über eine Neuregistrierung zurückzusetzen). `AccountDeletionService.deleteAccount` erledigt das explizit, unabhängig von den Fristen oben.
- **`KEYCLOAK`-Kanäle: Aufräumen fragt bei Keycloak nach, statt blind auf Zeit zu vertrauen.** Logout gehört im Web-Kanal vollständig Keycloak ([05-api.md](05-api.md) Abschnitt 3) - der Orchestrator erfährt nie aktiv davon. `RetentionJob` prüft deshalb für bereits abgelaufene `KEYCLOAK`-Kanäle zusätzlich per Keycloak-Admin-API, ob die zugehörige Session noch lebt (`ChannelSession.durableKcSessionId`), und räumt bei bestätigt beendeter Session sofort auf statt erst nach der vollen Retention-Frist. Eine nicht bestätigbare Antwort (kein Client im aktiven Profil, Admin-API nicht erreichbar) führt nie zu einem verfrühten Löschen - sie fällt zurück auf die normale zeitbasierte Frist.

## 4) Kontosperre, Rate-Limits und Versand-Drosselung (Brute-Force-/Bombing-Schutz)

Gemeinsame Basis: `AttemptThrottle` (Entität, PK `(scope, subject)`) + `AttemptCounter`
(`src/main/kotlin/com/example/dpop/orchestrator/session/`). `scope` (`ThrottleScope`) trennt
mehrere Zählräume, die sich denselben Mechanismus teilen, aber nie denselben Schlüsselraum — darauf
aufbauend je ein benannter `@Service` mit eigenem Vokabular und eigenen Limits:

| Service | Scope | Zählt | Antwort bei Überschreitung |
|---|---|---|---|
| `LoginThrottleService` | `ACCOUNT` | Fehlgeschlagene AUTH-Versuche gegen ein Konto | `423 Locked` (`ACCOUNT_LOCKED`) bei IDENTIFIED_AUTH; bei LOOKUP_AUTH in die gewöhnliche "E-Mail/Code ungültig"-Antwort gefaltet (kein eigener Fehler — sonst Enumeration-Oracle) |
| `IdentThrottleService` | `PERSON` | Fehlgeschlagene IDENT-Versuche gegen eine Person (`ident-fsc`/`ident-eid` raten je ein Geheimnis; ein Treffer übernimmt das Konto) | immer in die gewöhnliche Fehlerantwort gefaltet, nie eigener Fehler |
| `ChannelCreationThrottleService` | `BINDING_KEY` | Kanaleröffnungen pro DPoP-Binding-Key (rollierendes Fenster, jeder Versuch zählt) | `429 Too Many Requests` |
| `SendThrottleService` | `ACCOUNT_SEND` / `CONTACT_SEND` | TAN-/Code-**Versendungen**, unabhängig von richtig/falsch (rollierendes Fenster, 3/10 Min) | `ACCOUNT_SEND` (LOOKUP_AUTH, Ziel bereits als Konto aufgelöst) in die gewöhnliche Fehlerantwort gefaltet; `CONTACT_SEND` (Self-Service-ENROLL, Ziel = vom Anrufer selbst gewählte Adresse, Subject SHA-256-gehasht statt Klartext) darf offen als eigener Fehler zurückkommen |

- Warum zusätzlich zu `ToolSession.retryCount` nötig (Abschnitt 3, Retry-Regel in
  [Orchestrierung](04-orchestrierung.md) Abschnitt 1): `retryCount` liegt auf der `ToolSession` und
  zählt deshalb nur innerhalb *eines* Tool-Anlaufs. Ein Client kann per erneutem
  `POST .../tools/{toolId}` (bzw. bei LOOKUP-Tools: per erneutem `PATCH` mit derselben Adresse auf
  derselben `toolSessionId`) jederzeit einen neuen Anlauf starten. `ACCOUNT`/`PERSON` schließen das
  für falsche Rateversuche; `ACCOUNT_SEND`/`CONTACT_SEND` schließen zusätzlich das reine
  *Neu-Versenden* selbst — ohne das wäre `auth-sms-lookup`/`auth-email-lookup`/`enroll-sms`/
  `enroll-email` ein freies SMS-/Mail-Bombing gegen jede bekannte Kontaktadresse, denn ein
  wiederholtes Absenden derselben Adresse ist nie selbst ein falscher Rateversuch und löst deshalb
  `recordFailure` nie aus.
- ENROLL-Fehlschläge selbst bleiben weiterhin außerhalb von `LoginThrottleService`/
  `IdentThrottleService` (`ToolControllerSupport.chargeThrottles`, `ToolCategory.ENROLL -> Unit`):
  beim Einrichten wird kein bestehendes Credential erraten, das Konto wählt sein eigenes Geheimnis
  selbst. Der Versand *während* eines ENROLL-Vorgangs ist trotzdem ein Ziel und wird separat über
  `CONTACT_SEND` begrenzt (Zeile oben).
- Schwellwerte Fehlversuche: `MAX_FAILURES = 5`, `LOCKOUT_DURATION = 15 Minuten`. Schwellwerte
  Kanaleröffnung: `20`/`5 Minuten`. Schwellwerte Versand: `3`/`10 Minuten`.
- Jeder Zähler erhöht sich über ein einziges atomares `UPDATE` unter der Zeilensperre, die das
  Statement selbst nimmt — nie per Lesen-dann-Schreiben. Read-Modify-Write ließe N gleichzeitige
  Requests denselben Vorher-Stand lesen und denselben Wert zurückschreiben; das effektive Budget
  wäre `Limit × Parallelität` und der Lockout überspringbar gewesen. Eine fehlende Zählerzeile
  wird nach dem Muster „erst `UPDATE`, nur bei 0 getroffenen Zeilen anlegen, dann erneut `UPDATE`"
  behandelt (`AttemptThrottleRowInitializer`, eigene Transaktion), der Normalfall kostet also
  genau ein Statement.
- Ein erfolgreicher AUTH-/IDENT-Abschluss setzt den jeweiligen Zähler zurück (`recordSuccess`),
  auch wenn zuvor kein Fehlversuch vorlag (dann ein No-op). Versand- und Kanal-Throttles kennen
  keinen Reset — sie sind reine rollierende Fenster.
- Aufbewahrung: siehe Tabelle in Abschnitt 3 (7 Tage nach letztem Update, nie während einer laufenden Sperre).

## 5) QR-Login (`auth_qr`): Pairing-Code-Sicherheit

`confirm-qr-login`s `input`-Schritt nimmt einen vom Nutzer eingegebenen oder per Deep-Link
vorbefüllten `pairingCode` entgegen ([Orchestrierung](04-orchestrierung.md) `CONFIRM_PEER_LOGIN`).
Drei Maßnahmen sind umgesetzt:

- **QR-Jacking-Schutz**: `verificationCode` (dreistellig) wird auf beiden Bildschirmen (Web-Seite
  und App) gezeigt und nur per Auge verglichen, nie übertragen oder eingegeben — entwertet den
  bekannten Angriff, bei dem ein Angreifer den eigenen QR-Code vom Opfer bestätigen lässt, solange
  Opfer und Angreifer nicht gleichzeitig denselben Bildschirminhalt sehen. Schützt **nicht** gegen
  einen Angreifer, der in Echtzeit beide Seiten kontrolliert (Live-Relay/MITM) — dieselbe Grenze
  wie bei jedem Cross-Device-Abgleich dieser Art (z. B. FIDO/Passkey-QR-Flows).
- **Atomarer Zustandsübergang**: `accept`/`reject` schreiben bedingt (`WHERE status = 'PENDING'`,
  `QrLoginRequestRepository.resolveIfPending`) statt Lesen-dann-Schreiben — `0` betroffene Zeilen
  heißt „bereits entschieden oder abgelaufen", nie ein zweiter Schreibversuch oder zwei Accounts
  gleichzeitig als `resolvingAccountId`.
- **`pairingCode`-Entropie**: 8 Zeichen aus einem verwechslungsarmen Alphabet (Crockford-Base32-
  artig, ohne `I`/`L`/`O`/`U`), ~40 Bit — bewusst niedriger als ein reiner API-Token, weil der Code
  ein Mensch fehlerfrei abschreiben können muss; manuelle Eingabe ist hier kein Fallback, sondern
  ein gleichwertiger Weg neben QR/Deep-Link.

**Noch offener Punkt**, analog zu Abschnitt 4: Weil manuelle Eingabe ein regulärer Weg ist, bräuchte
der `input`-Schritt einen eigenen, IP-/anonymen Zähler auf fehlgeschlagene `pairingCode`-Lookups —
`AttemptThrottle` (Abschnitt 4) greift hier nicht, da an dieser Stelle noch kein Account
bekannt ist, an den sich ein Zähler hängen ließe. Bei 8 Zeichen aus einem 32er-Alphabet ist das
kein optionales Add-on, sondern Voraussetzung dafür, dass manuelle Eingabe unbegrenzt sicher
angeboten werden darf — aktuell **nicht implementiert**.

`QrLoginRequest.expiresAt` (5 Minuten, `QR_LOGIN_TTL`) orientiert sich an bestehenden TAN-Timeouts
(`enroll-sms`/`auth-sms`), ist aber nicht weiter validiert; abgelaufene Zeilen sind über `expiresAt`
beim Lesen unwirksam und werden von `AuthQrRetentionJob` abgeräumt (Abschnitt 3).

## 6) Datenbankschema: Konventionen

Das Schema steht vollständig in `src/main/resources/db/migration/V1__schema.sql`; die Regeln
stehen einmal in dessen Kopf und gelten für jede Tabelle ([12-entscheidungen.md](12-entscheidungen.md) ADR-14/ADR-16).
Die tragenden Tabellen und ihre Beziehungen als Diagramm stehen in
[02-domaenenmodell.md](02-domaenenmodell.md) Abschnitt 7. Die Regeln im Einzelnen:

- **Besitz ist strukturell**: Ein Datenbankschema je Modul, und jede Tabelle liegt im Schema
  ihres Moduls (`account.anchor`, `auth_sms.enrollment`). „Wem gehört diese Tabelle?" beantwortet
  damit ihr Ort, nicht ihre Schreibweise — eine Tabelle kann nicht versehentlich am falschen Platz
  entstehen. Tabellennamen bleiben kurz, weil das Schema den Modulnamen schon trägt.
- **Fremdschlüssel** nur innerhalb eines Schemas; modulübergreifende Bezüge (z. B. `account_id` in
  Orchestrator-Tabellen) sind indizierte Spalten und werden über die Modul-APIs aufgeräumt, nie per
  schemaübergreifender Kaskade.
- **Namen**: Langlebige Credentials heißen `<modul>.enrollment`, und dieser qualifizierte Name ist
  `EnrollmentRef.type`. Die Arbeitsdaten eines Tool-Durchlaufs heißen
  `<modul>.<tool-rolle>_tool_session` — ihr Schlüssel *ist* die `tool_session_id`, die Zeile ist
  also die Modulhälfte der `orchestrator.tool_session`, keine vierte Session-Ebene und keine Zeile
  je Versuch. PK-Spalte immer `id`, Referenzen `<tabelle>_id`. Indizes und Constraints sind
  schema-eigene Objekte und tragen deshalb ebenfalls kein Modulpräfix (`ux_anchor_value`).
- **Typen**: Zeitpunkte `TIMESTAMP WITH TIME ZONE`, Enum-Werte `VARCHAR(32)`, ACR-Werte
  `VARCHAR(16)`, Tool-IDs/Methoden/Attributtypen/Quellen `VARCHAR(50)`, Hashes `VARCHAR(64)`.
- **Anker**: Jeder Schreibvorgang auf `account.anchor` ist nach `AttributeType.anchorAcrFloor`
  bepreist (Erstbinden und Ersetzen getrennt) und die Zeile merkt sich mit `established_loa` das
  tatsächlich bewiesene, nach ADR-5 gedeckelte Niveau
  ([Domänenmodell](02-domaenenmodell.md) Abschnitt 6).
- **Konto**: `account.account` ist Sperrwurzel; aktueller Zustand in eigenen Zeilen, Historie append-only
  ([Domänenmodell](02-domaenenmodell.md) Abschnitt 6).
- **Retention**: Jede Aufräumabfrage ist ein Bulk-Statement und hat einen Index auf ihrer
  Stichtagsspalte.
- **Migrationen**: `V1`/`V2` sind eine Neubaseline ohne Produktivdaten (lokale H2-Dateien setzt
  `FlywayResetConfig` bei Checksummen-Abweichung zurück). Ab dem ersten produktiven Einsatz sind
  Migrationen ausschließlich additiv; eine Tabelle mit ≥ 10 Mio. Zeilen wird dabei nie in einem
  einzelnen Statement umgeschrieben, sondern in idempotenten Chunks nachgezogen.
