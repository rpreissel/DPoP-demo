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
- `422 Unprocessable Entity`: fachlich unverarbeitbar, kein Nutzereingabefehler (z. B. unbekannte `enrollmentRef`, fehlendes Enrollment)
- `423 Locked`: Account durch zu viele fehlgeschlagene AUTH-Versuche gesperrt (`ACCOUNT_LOCKED`, `OrchestratorException.accountLocked()`, Abschnitt 4)
- `429 Too Many Requests`: Rate-Limit erreicht, kein Sperrzustand des Accounts (`OrchestratorException.tooManyRequests()`, Abschnitt 4 — `ChannelCreationThrottleService`, gekeyt auf `bindingKeyRef`)

Ausdrücklich **kein** Fehlerfall: fehlende Pflichtfelder und fehlgeschlagene Versuche mit verbleibenden Retries — sie liefern `200` plus `next` (Retry-Regel in [Orchestrierung](04-orchestrierung.md)).

## 2) Konsistenz-Regeln

- Pro `ChannelSession` darf höchstens eine **laufende** `AuthJourney` existieren. Eine Journey, die auf eine Sub-Journey wartet, ist `SUSPENDED` und zählt nicht mit ([Orchestrierung](04-orchestrierung.md)).
- `AuthJourney` darf nur auf gültige Folgezustände wechseln — sowohl im Lebenszyklus als auch im intent-eigenen `JourneyState`.
- `AuthContext` wird nur bei `SUCCEEDED` aktualisiert.
- Jede relevante Transition erzeugt einen `SessionEvent` Audit-Eintrag.
- Transaktionale Klammer: Die Verarbeitung eines `ToolOutcome.Completed` ([Orchestrierung](04-orchestrierung.md)) atomarisiert Journey-Übernahme, Account-Eintrag, Claim-Log und `AuthContext`-Nachweis. Das Methodenmodul schreibt seine Tool-/Enrollment-Daten bereits beim `PATCH` in einer eigenen Transaktion; scheitert die Journey-Übernahme, bleibt die Moduldatenzeile bestehen, wird aber nicht als Account-Credential aktiviert.
- Auch neue Accounts, Claim-Log, Identifizierungs-Log, Anker und Methodeninstanzen teilen diese Transaktion; kein vorgezogener Account-Commit mit `REQUIRES_NEW`. Bei konkurrierender Bindung rollt der Verlierer vollständig zurück und erhält `409 INVALID_STATE_TRANSITION`; kein automatischer Wiederholungsversuch. Unique-Verletzungen von `ux_anchor_value`/`ux_anchor_account_type` werden auch bei Flush/Commit gezielt übersetzt; unbekannte Integritätsfehler bleiben Serverfehler.
- Der Demo-Seed verwendet eine eigene transaktionale Klammer um Anlage und Claim-Übernahme; er ist create-only: Eine Testperson, deren PERSON_ID- oder EMAIL-Anker bereits auf ein Konto auflöst (früherer Seed-Lauf oder ein halbfertiger Registrierungs-Interessent), wird komplett übersprungen — das bestehende Konto bleibt unverändert, es wird nichts vervollständigt. Bei Neustarts entstehen so keine zusätzlichen Bootstrap-Claims.
- Nicht transaktional ist der SMS-Versand als externer Effekt: Ein Rollback macht eine bereits versendete SMS nicht rückgängig. Das ist ein Zustellthema, kein Konsistenzproblem — die zugehörige `issuedTanHash`-Zeile wurde mit zurückgerollt und läuft ins Leere.

## 3) Aufbewahrung und Löschung

Session-Daten enthalten Personenbezug (KVNR, Name, Telefonnummer) und Geheimnis-Derivate (`issuedTanHash`) und werden nach Prozessende nie wieder gelesen. Sie werden deshalb aktiv gelöscht, nicht aufbewahrt.

Richtwerte (als Default gedacht, nicht als Compliance-Vorgabe):

| Objekt | Frist läuft ab | Richtwert | Grund |
|---|---|---|---|
| `<modul>.*_tool_session` (Moduldaten) | `createdAt` | 24 h | Personenbezug und TAN-Hash. Jedes Methodenmodul hat dafür einen eigenen `*RetentionJob` (auch `auth_device`, `auth_qr`) |
| `QrLoginRequest` | `expiresAt` | 24 h | Pairing-Anfrage, nach Ablauf (5 Min.) wirkungslos; von `AuthQrRetentionJob` mit abgeräumt |
| `DpopProofReplay` | `expiresAt` | sofort (minütlich) | Replay-Schutz gilt nur im Akzeptanzfenster eines Proofs |
| `orchestrator.tool_session` | `expiresAt` | 24 h | reiner Lifecycle-Rest |
| `AuthJourney` | `consumedAt` / `expiresAt` | 7 Tage | Korrelation für Support-Rückfragen |
| `AuthContext` | Logout / Ende der `ChannelSession` | sofort | enthält Token-Referenzen |
| `ChannelSession` | `expiresAt` / `LOGGED_OUT` | 30 Tage | `JourneyLogEntry` fragt den Log über die Channel-Menge ab ([Domänenmodell](02-domaenenmodell.md) Abschnitt 5) |
| `SessionEvent` | `createdAt` | 90 Tage | eigene Audit-Frist, überlebt die Sessions bewusst |
| `JourneyLogEntry` | `createdAt` | 30 Tage | bewusst gleich `ChannelSession`, da nur über die Channel-Menge abgefragt. Debug-/Demo-Trace, NICHT der Audit-Trail — das bleibt `SessionEvent` |
| `*Enrollment` (Modul-Credentials) | — | kein Session-Cleanup | lebt bis zur Kontolöschung (erreicht über `account.auth_method`, auch deaktivierte Instanzen) |
| `account.*` (Anker, Methoden, Claim-, Identifizierungs- und Widerrufs-Log) | — | kein Session-Cleanup | gehört dem Konto, kaskadiert mit dessen Löschung. **Offen** ([12-entscheidungen.md](12-entscheidungen.md) ADR-12): Frist für das gemeinsame Löschen von Claim- und Retraktions-Zeile noch nicht entschieden |
| `DeviceAccountLink` | — | kein Session-Cleanup | Geräte-Identität (`bindingKeyRef -> accountId`), überlebt jede einzelne `ChannelSession` bewusst ([DPoP-Bindung](09-dpop.md) Abschnitt 3) |
| `AttemptThrottle` | letzter Zähler-Update | 7 Tage | weit über dem längsten Fenster/Lockout (15 Min.); ein Sweep rührt nie eine Zeile an, deren Sperre noch läuft. Zähler aller Scopes (`ACCOUNT`/`PERSON`/`BINDING_KEY`/`ACCOUNT_SEND`/`CONTACT_SEND`, Abschnitt 4) |
| `orchestrator.keycloak_keypair` | — | kein Session-Cleanup | Schlüsselpaar für den Token-Grant im `keycloak`-Profil ([05-api.md](05-api.md) Abschnitt 3); gelöscht bei `AccountDeleted`, nicht über `RetentionJob` |

Umgang mit den Referenzen:

- **Besitzkette** (`ChannelSession` -> `AuthJourney` -> `orchestrator.tool_session` -> die Modulhälfte `<modul>.*_tool_session`): wird von innen nach außen abgeräumt. Weil die Fristen von innen nach außen wachsen, ergibt sich diese Reihenfolge automatisch.
- **Moduldaten** räumt jedes Modul eigenständig nach Alter (`createdAt`) auf, ohne Signal vom Orchestrator. Das ist robuster als ein Löschbefehl (ein verpasstes Signal hinterließe dauerhafte Waisen).
- **Audit ist entkoppelt**: `SessionEvent` hält `channelSessionId`/`processSessionId` als historische Werte, nicht als Fremdschlüssel — das Audit muss die Sessions überleben und speichert nur `payloadHash` statt Nutzdaten. Ins Leere zeigende IDs sind erwartet, kein Defekt.
- **Account-Objekte sind für den Session-Cleanup tabu**: die Modul-Credentials (`*_enrollment`), `account.auth_method`, `account.identification` und `DeviceAccountLink` gehören dem Account bzw. dem Gerät, nicht der Session. `account.identification` überlebt damit bewusst auch die Audit-Frist der `SessionEvent`s.
- **Kontolöschung räumt zusätzlich zwei Session-Tabellen für die gelöschte `accountId` auf**, obwohl beide keinen Fremdschlüssel auf `account` tragen: `orchestrator.journey_log` (über **zwei** Schlüssel — Konto **und** dessen Channel-Sessions, da Einträge vor der Kontobindung `account_id = NULL` tragen) und `orchestrator.attempt_throttle` (nur die Scopes `ACCOUNT`/`ACCOUNT_SEND`; `BINDING_KEY`/`CONTACT_SEND` wären sonst über eine Neuregistrierung zurücksetzbar). `AccountDeletionService.deleteAccount` erledigt das explizit, unabhängig von den Fristen oben.
- **`KEYCLOAK`-Kanäle: Aufräumen fragt bei Keycloak nach, statt blind auf Zeit zu vertrauen.** Logout gehört im Web-Kanal vollständig Keycloak ([05-api.md](05-api.md) Abschnitt 3). `RetentionJob` prüft deshalb für abgelaufene `KEYCLOAK`-Kanäle per Keycloak-Admin-API, ob die Session noch lebt (`ChannelSession.durableKcSessionId`), und räumt bei bestätigt beendeter Session sofort auf. Eine nicht bestätigbare Antwort (kein Client im aktiven Profil, Admin-API nicht erreichbar) fällt auf die normale zeitbasierte Frist zurück.

## 4) Kontosperre, Rate-Limits und Versand-Drosselung (Brute-Force-/Bombing-Schutz)

Gemeinsame Basis: `AttemptThrottle` (Entität, PK `(scope, subject)`) + `AttemptCounter`
(`src/main/kotlin/com/example/dpop/orchestrator/session/`). `scope` (`ThrottleScope`) trennt
mehrere Zählräume, die sich denselben Mechanismus, aber nie denselben Schlüsselraum teilen — je ein
benannter `@Service` mit eigenen Limits:

| Service | Scope | Zählt | Antwort bei Überschreitung |
|---|---|---|---|
| `LoginThrottleService` | `ACCOUNT` | Fehlgeschlagene AUTH-Versuche gegen ein Konto | `423 Locked` (`ACCOUNT_LOCKED`) bei IDENTIFIED_AUTH; bei LOOKUP_AUTH in die gewöhnliche "E-Mail/Code ungültig"-Antwort gefaltet (sonst Enumeration-Oracle) |
| `IdentThrottleService` | `PERSON` | Fehlgeschlagene IDENT-Versuche gegen eine Person (`ident-fsc` rät ein Geheimnis; ein Treffer übernimmt das Konto). Greift nur, wo der Versuch überhaupt eine Person benennt — `ident-eid` bezeugt seit ADR-18 nur die Karte und löst niemanden auf, seine PIN-Versuche begrenzt das Retry-Budget der Tool-Session | immer in die gewöhnliche Fehlerantwort gefaltet, nie eigener Fehler |
| `ChannelCreationThrottleService` | `BINDING_KEY` | Kanaleröffnungen pro DPoP-Binding-Key (rollierendes Fenster, jeder Versuch zählt) | `429 Too Many Requests` |
| `SendThrottleService` | `ACCOUNT_SEND` / `CONTACT_SEND` | TAN-/Code-**Versendungen**, unabhängig von richtig/falsch (rollierendes Fenster, 3/10 Min) | `ACCOUNT_SEND` (LOOKUP_AUTH) in die gewöhnliche Fehlerantwort gefaltet; `CONTACT_SEND` (Self-Service-ENROLL, Subject SHA-256-gehasht statt Klartext) darf offen als eigener Fehler zurückkommen |

- Warum zusätzlich zu `ToolSession.retryCount` nötig (Retry-Regel in
  [Orchestrierung](04-orchestrierung.md) Abschnitt 1): `retryCount` zählt nur innerhalb *eines*
  Tool-Anlaufs; per erneutem `POST .../tools/{toolId}` (bzw. bei LOOKUP-Tools erneutem `PATCH` auf
  derselben `toolSessionId`) startet ein Client jederzeit einen neuen. `ACCOUNT`/`PERSON` schließen
  das für falsche Rateversuche; `ACCOUNT_SEND`/`CONTACT_SEND` schließen zusätzlich das reine
  *Neu-Versenden*, das nie ein falscher Rateversuch ist und deshalb `recordFailure` nie
  auslöst — ohne sie wäre `auth-sms-lookup`/`auth-email-lookup`/`enroll-sms`/`enroll-email` ein
  freies SMS-/Mail-Bombing.
- ENROLL-Fehlschläge selbst bleiben außerhalb von `LoginThrottleService`/
  `IdentThrottleService` (`ToolControllerSupport.chargeThrottles`, `ToolCategory.ENROLL -> Unit`):
  beim Einrichten wird kein bestehendes Credential erraten. Der Versand *während* eines
  ENROLL-Vorgangs wird separat über `CONTACT_SEND` begrenzt (Zeile oben).
- Schwellwerte Fehlversuche: `MAX_FAILURES = 5`, `LOCKOUT_DURATION = 15 Minuten`. Schwellwerte
  Kanaleröffnung: `20`/`5 Minuten`. Schwellwerte Versand: `3`/`10 Minuten`.
- Jeder Zähler erhöht sich über ein einziges atomares `UPDATE` unter der Zeilensperre, die das
  Statement selbst nimmt — nie per Lesen-dann-Schreiben, sonst wäre das effektive Budget
  `Limit × Parallelität` und der Lockout überspringbar. Eine fehlende Zählerzeile
  wird nach dem Muster „erst `UPDATE`, nur bei 0 getroffenen Zeilen anlegen, dann erneut `UPDATE`"
  behandelt (`AttemptThrottleRowInitializer`, eigene Transaktion).
- Ein erfolgreicher AUTH-/IDENT-Abschluss setzt den jeweiligen Zähler zurück (`recordSuccess`).
  Versand- und Kanal-Throttles kennen keinen Reset — sie sind reine rollierende Fenster.
- Aufbewahrung: siehe Tabelle in Abschnitt 3.

## 5) QR-Login (`auth_qr`): Pairing-Code-Sicherheit

`confirm-qr-login`s `input`-Schritt nimmt einen vom Nutzer eingegebenen oder per Deep-Link
vorbefüllten `pairingCode` entgegen ([Orchestrierung](04-orchestrierung.md) `CONFIRM_PEER_LOGIN`):

- **QR-Jacking-Schutz**: `verificationCode` (dreistellig) wird auf beiden Bildschirmen gezeigt und
  nur per Auge verglichen, nie übertragen oder eingegeben — entwertet den Angriff, bei dem ein
  Angreifer den eigenen QR-Code vom Opfer bestätigen lässt. Schützt **nicht** gegen einen
  Angreifer, der in Echtzeit beide Seiten kontrolliert (Live-Relay/MITM).
- **Atomarer Zustandsübergang**: `accept`/`reject` schreiben bedingt (`WHERE status = 'PENDING'`,
  `QrLoginRequestRepository.resolveIfPending`) — `0` betroffene Zeilen heißt „bereits entschieden
  oder abgelaufen", nie zwei Accounts gleichzeitig als `resolvingAccountId`.
- **`pairingCode`-Entropie**: 8 Zeichen aus einem verwechslungsarmen Alphabet (Crockford-Base32-
  artig, ohne `I`/`L`/`O`/`U`), ~40 Bit — bewusst niedriger als ein reiner API-Token, weil ein
  Mensch den Code fehlerfrei abschreiben können muss.

**Noch offener Punkt**: Weil manuelle Eingabe ein regulärer Weg ist, bräuchte der `input`-Schritt
einen eigenen, IP-/anonymen Zähler auf fehlgeschlagene `pairingCode`-Lookups — `AttemptThrottle`
(Abschnitt 4) greift hier nicht, da noch kein Account bekannt ist. Aktuell **nicht implementiert**.

`QrLoginRequest.expiresAt` (5 Minuten, `QR_LOGIN_TTL`) orientiert sich an bestehenden TAN-Timeouts
(`enroll-sms`/`auth-sms`); abgelaufene Zeilen sind beim Lesen unwirksam und werden von
`AuthQrRetentionJob` abgeräumt (Abschnitt 3).

## 6) Datenbankschema: Konventionen

Das Schema steht vollständig in `src/main/resources/db/migration/V1__schema.sql`; die Regeln
stehen in dessen Kopf und gelten für jede Tabelle ([12-entscheidungen.md](12-entscheidungen.md) ADR-14/ADR-16).
Diagramm der tragenden Tabellen: [02-domaenenmodell.md](02-domaenenmodell.md) Abschnitt 7.

- **Besitz ist strukturell**: Ein Datenbankschema je Modul, und jede Tabelle liegt im Schema
  ihres Moduls (`account.anchor`, `auth_sms.enrollment`). Tabellennamen bleiben kurz, weil das
  Schema den Modulnamen schon trägt.
- **Fremdschlüssel** nur innerhalb eines Schemas; modulübergreifende Bezüge (z. B. `account_id` in
  Orchestrator-Tabellen) sind indizierte Spalten und werden über die Modul-APIs aufgeräumt.
- **Namen**: Langlebige Credentials heißen `<modul>.enrollment`, und dieser qualifizierte Name ist
  `EnrollmentRef.type`. Die Arbeitsdaten eines Tool-Durchlaufs heißen
  `<modul>.<tool-rolle>_tool_session` — ihr Schlüssel *ist* die `tool_session_id`, die Zeile ist
  also die Modulhälfte der `orchestrator.tool_session`. PK-Spalte immer `id`, Referenzen
  `<tabelle>_id`. Indizes und Constraints tragen kein Modulpräfix (`ux_anchor_value`).
- **Typen**: Zeitpunkte `TIMESTAMP WITH TIME ZONE`, Enum-Werte `VARCHAR(32)`, ACR-Werte
  `VARCHAR(16)`, Tool-IDs/Methoden/Attributtypen/Quellen `VARCHAR(50)`, Hashes `VARCHAR(64)`.
- **Anker**: Jeder Schreibvorgang auf `account.anchor` ist nach `AttributeType.anchorAcrFloor`
  bepreist (Erstbinden und Ersetzen getrennt); `established_loa` hält das tatsächlich bewiesene,
  nach ADR-5 gedeckelte Niveau ([Domänenmodell](02-domaenenmodell.md) Abschnitt 6).
- **Konto**: `account.account` ist Sperrwurzel; aktueller Zustand in eigenen Zeilen, Historie append-only
  ([Domänenmodell](02-domaenenmodell.md) Abschnitt 6).
- **Retention**: Jede Aufräumabfrage ist ein Bulk-Statement und hat einen Index auf ihrer
  Stichtagsspalte.
- **Migrationen**: `V1`/`V2` sind eine Neubaseline ohne Produktivdaten (lokale H2-Dateien setzt
  `FlywayResetConfig` bei Checksummen-Abweichung zurück). Ab dem ersten produktiven Einsatz sind
  Migrationen ausschließlich additiv; Tabellen mit ≥ 10 Mio. Zeilen werden in idempotenten Chunks
  nachgezogen.
