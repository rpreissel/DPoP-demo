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
- `429 Too Many Requests`: Rate-Limit erreicht, kein Sperrzustand des Accounts (`OrchestratorException.tooManyRequests()`, Abschnitt 4 — `ChannelCreationThrottleService`, gezählt je `bindingKeyRef`)
- `500 Internal Server Error`: eine interne Annahme ist verletzt (`INTERNAL_ERROR`). Die Antwort trägt einen festen Text, die Details stehen nur im Log.

**Form und Quelle.** Jede Fehlerantwort hat die Form `ErrorResponse` (`{"error": "<CODE>", "message": "..."}`) und steht so im Vertrag, als `default`-Antwort an jeder Operation. Welcher Code welchen Status hat, legt das Enum `ErrorCode` fest (`orchestrator/kernel`); die Liste im Vertrag wird daraus erzeugt. Ein Code und sein Status lassen sich deshalb nicht mehr getrennt wählen. Clients verzweigen auf `error`, nie auf den Text, und müssen mit einem Code rechnen, den sie noch nicht kennen.

**Welche Exception wozu führt.** Die Regel, auf die sich der Handler verlässt:

- `require` / `IllegalArgumentException` nur für eine abgelehnte **Eingabe des Clients** → `400`, der Text geht so hinaus.
- `check` / `checkNotNull` / `error()` für eine verletzte **interne Annahme** → `500` mit festem Text. Früher wurde daraus pauschal `409 INVALID_STATE_TRANSITION` mit dem Rohtext; damit sahen interne Prüfungen wie ein fachlicher Konflikt aus und gaben Interna preis.
- Ein echter fachlicher Konflikt ist immer ausdrücklich: `OrchestratorException.invalidState(...)`.

Ausdrücklich **kein** Fehlerfall: fehlende Pflichtfelder und fehlgeschlagene Versuche mit verbleibenden Retries — sie liefern `200` plus `next` (Retry-Regel in [Orchestrierung](04-orchestrierung.md)).

## 2) Konsistenz-Regeln

- Pro `ChannelSession` darf höchstens eine **laufende** `AuthJourney` existieren. Eine Journey, die auf eine Sub-Journey wartet, ist `SUSPENDED` und zählt nicht mit ([Orchestrierung](04-orchestrierung.md)).
- `AuthJourney` darf nur auf gültige Folgezustände wechseln — sowohl im Lebenszyklus als auch im intent-eigenen `JourneyState`.
- `AuthContext` wird nur bei `SUCCEEDED` aktualisiert.
- Jede relevante Transition erzeugt einen `SessionEvent` Audit-Eintrag.
- Transaktionale Klammer: Die Verarbeitung eines `ToolOutcome.Completed` ([Orchestrierung](04-orchestrierung.md)) führt Journey-Übernahme, Account-Eintrag, Claim-Log und `AuthContext`-Nachweis in einer einzigen Transaktion zusammen — entweder alles oder nichts. Das Methodenmodul schreibt seine Tool-/Enrollment-Daten bereits beim `PATCH` in einer eigenen Transaktion; scheitert die Journey-Übernahme, bleibt die Moduldatenzeile bestehen, wird aber nicht als Account-Credential aktiviert.
- Auch neue Accounts, Claim-Log, Identifizierungs-Log, Anker und Methodeninstanzen teilen diese Transaktion; kein vorgezogener Account-Commit mit `REQUIRES_NEW`. Bei konkurrierender Bindung rollt der Verlierer vollständig zurück und erhält `409 INVALID_STATE_TRANSITION`; kein automatischer Wiederholungsversuch. Unique-Verletzungen von `ux_anchor_value`/`ux_anchor_account_type` werden auch bei Flush/Commit gezielt übersetzt; unbekannte Integritätsfehler bleiben Serverfehler.
- Der Demo-Seed verwendet eine eigene transaktionale Klammer um Anlage und Claim-Übernahme; er ist create-only: Eine Testperson, deren PERSON_ID- oder EMAIL-Anker bereits auf ein Konto auflöst (früherer Seed-Lauf oder ein halbfertiger Registrierungs-Interessent), wird komplett übersprungen — das bestehende Konto bleibt unverändert, es wird nichts vervollständigt. Bei Neustarts entstehen so keine zusätzlichen Bootstrap-Claims.
- Nicht transaktional ist der SMS-Versand als externer Effekt: Ein Rollback macht eine bereits versendete SMS nicht rückgängig. Das ist ein Zustellthema, kein Konsistenzproblem — die zugehörige `issuedTanHash`-Zeile wurde mit zurückgerollt und läuft ins Leere.

## 3) Aufbewahrung und Löschung

Session-Daten enthalten Personenbezug (KVNR, Name, Telefonnummer) und Geheimnis-Derivate (`issuedTanHash`) und werden nach Prozessende nie wieder gelesen. Sie werden deshalb aktiv gelöscht, nicht aufbewahrt.

Richtwerte (als Default gedacht, nicht als Compliance-Vorgabe):

| Objekt | Frist läuft ab | Richtwert | Grund |
|---|---|---|---|
| `<modul>.*_tool_session` (Moduldaten) | `createdAt` | 24 h (`tool-session.retention`) | Personenbezug und TAN-Hash. Jedes Methodenmodul löscht seine eigenen Tabellen selbst (`*RetentionJob` implementiert `ToolSessionSweeper`); Frist und Intervall stehen dagegen nur einmal, in `tool_api/ToolSessionRetention.kt`. Bei `auth_kobil.enroll_tool_session` ist die Frist besonders wichtig: dort liegen während einer laufenden Einrichtung KOBIL-PIN und Unlock-Secret im Klartext ([ADR-22](adr/ADR-022-der-verwahrte-pin-liegt-im-klartext-demo-rahmen.md)) |
| `kobil_mock.*` (Fremdsystem) | — | **kein** Cleanup durch uns | `kobil_mock` simuliert KOBIL und untersteht nicht unserer Aufbewahrung. Dass es überhaupt persistiert, ist Voraussetzung und nicht Bequemlichkeit: ohne das würde nach jedem Neustart jede `auth_kobil.enrollment`-Zeile auf einen Nutzer zeigen, den es beim Anbieter nicht mehr gibt |
| `QrLoginRequest` | `expiresAt` | 24 h | Pairing-Anfrage, nach Ablauf (5 Min.) wirkungslos; von `AuthQrRetentionJob` mit abgeräumt |
| `DpopProofReplay` | `expiresAt` | sofort (minütlich) | Replay-Schutz gilt nur im Akzeptanzfenster eines Proofs |
| `orchestrator.tool_session` | `expiresAt` | 24 h | reiner Lifecycle-Rest |
| `AuthJourney` | `consumedAt` / `expiresAt` | 7 Tage | Korrelation für Support-Rückfragen |
| `AuthContext` | Logout / Ende der `ChannelSession` | sofort | enthält Token-Referenzen |
| `ChannelSession` | `expiresAt` / `LOGGED_OUT` | 30 Tage | `JourneyLogEntry` fragt den Log über die Channel-Menge ab ([Domänenmodell](02-domaenenmodell.md) Abschnitt 5) |
| `SessionEvent` | `createdAt` | 90 Tage | eigene Audit-Frist, überlebt die Sessions bewusst |
| `JourneyLogEntry` | `createdAt` | 30 Tage | bewusst gleich `ChannelSession`, da nur über die Channel-Menge abgefragt. Debug-/Demo-Trace, NICHT der Audit-Trail — das bleibt `SessionEvent` |
| `*Enrollment` (Modul-Credentials) | — | kein Session-Cleanup | lebt bis zur Kontolöschung (erreicht über `account.auth_method`, auch deaktivierte Instanzen) |
| `account.*` (Anker, Methoden, Claim-, Identifizierungs- und Widerrufs-Log) | — | kein Session-Cleanup | gehört dem Konto, kaskadiert mit dessen Löschung. **Offen** ([12-entscheidungen.md](12-entscheidungen.md) ADR-12): Frist für das gemeinsame Löschen von Claim- und Widerrufszeile noch nicht entschieden |
| `DeviceAccountLink` | — | kein Session-Cleanup | Geräte-Identität (`bindingKeyRef -> accountId`), überlebt jede einzelne `ChannelSession` bewusst ([DPoP-Bindung](09-dpop.md) Abschnitt 3) |
| `AttemptThrottle` | letzter Zähler-Update | 7 Tage | weit über dem längsten Fenster/Lockout (15 Min.); ein Aufräumlauf rührt nie eine Zeile an, deren Sperre noch läuft. Zähler aller Scopes (`ACCOUNT`/`PERSON`/`BINDING_KEY`/`ACCOUNT_SEND`/`CONTACT_SEND`, Abschnitt 4) |
| `orchestrator.keycloak_keypair` | — | kein Session-Cleanup | Schlüsselpaar für den Token-Grant im `keycloak`-Profil ([05-api.md](05-api.md) Abschnitt 3); gelöscht bei `AccountDeleted`, nicht über `RetentionJob` |

Umgang mit den Referenzen:

- **Besitzkette** (`ChannelSession` -> `AuthJourney` -> `orchestrator.tool_session` -> die Modulhälfte `<modul>.*_tool_session`): wird von innen nach außen abgeräumt. Weil die Fristen von innen nach außen wachsen, ergibt sich diese Reihenfolge automatisch.
- **Moduldaten**: Welche Zeilen gelöscht werden, entscheidet jedes Modul selbst. Nur das Modul weiß, welche seiner Tabellen zur Session gehören und welche (`*_enrollment`) zum Konto. Wann gelöscht wird, steht an einer Stelle (`tool-session.retention`) und wird von einem gemeinsamen Scheduler (`ToolSessionRetentionDriver`) ausgelöst.

  Vorher hatte jedes Modul einen eigenen `@Scheduled`-Job mit einer eigenen Konstante `private val RETENTION = 24h`: zehn Kopien derselben Frist. Ein Modul ohne Job fiel dabei niemandem auf, weil nichts fehlschlug — es fehlte einfach eine Datei. So blieben die Zeilen in `id_kvnr.ident_tool_session` dauerhaft stehen. `ToolSessionCoverageTest` prüft jetzt gegen das tatsächliche Schema, dass jede `*_tool_session`-Tabelle von einem Sweeper geleert wird, auch eine später hinzukommende.

  Schlägt der Sweep eines Moduls fehl, laufen die übrigen trotzdem. Sonst würden Daten ihre Frist überleben, weil an anderer Stelle ein Fehler auftrat.
- **Das Audit hängt an nichts**: `SessionEvent` hält `channelSessionId`/`processSessionId` als historische Werte, nicht als Fremdschlüssel — das Audit muss die Sessions überleben und speichert nur `payloadHash` statt Nutzdaten. IDs, die ins Leere zeigen, sind erwartet und kein Defekt.
- **Eine Methode zu widerrufen hat bei KOBIL eine Außenhälfte.** `KobilEnrollmentCleanup` löscht nicht nur unsere Zeile, sondern räumt auch den Nutzer beim Anbieter ab — sonst bliebe dort ein gebundenes Gerät stehen, von dem hier niemand mehr etwas weiß. Dieser zweite Aufruf wirkt nach außen und liegt außerhalb der Transaktion — wie der gemockte SMS-Versand: Unsere Zeile verschwindet in jedem Fall.
- **Account-Objekte sind für den Session-Cleanup tabu**: die Modul-Credentials (`*_enrollment`), `account.auth_method`, `account.identification` und `DeviceAccountLink` gehören dem Account bzw. dem Gerät, nicht der Session. `account.identification` überlebt damit bewusst auch die Audit-Frist der `SessionEvent`s.
- **Kontolöschung räumt zusätzlich zwei Session-Tabellen für die gelöschte `accountId` auf**, obwohl beide keinen Fremdschlüssel auf `account` tragen: `orchestrator.journey_log` (über **zwei** Schlüssel — Konto **und** dessen Channel-Sessions, da Einträge vor der Kontobindung `account_id = NULL` tragen) und `orchestrator.attempt_throttle` (nur die Scopes `ACCOUNT`/`ACCOUNT_SEND`; `BINDING_KEY`/`CONTACT_SEND` ließen sich sonst durch eine Neuregistrierung zurücksetzen). `AccountDeletionService.deleteAccount` erledigt das explizit, unabhängig von den Fristen oben.
- **`KEYCLOAK`-Kanäle: Aufräumen fragt bei Keycloak nach, statt blind auf Zeit zu vertrauen.** Logout gehört im Web-Kanal vollständig Keycloak ([05-api.md](05-api.md) Abschnitt 3). `RetentionJob` prüft deshalb für abgelaufene `KEYCLOAK`-Kanäle per Keycloak-Admin-API, ob die Session noch lebt (`ChannelSession.durableKcSessionId`), und räumt bei bestätigt beendeter Session sofort auf. Eine nicht bestätigbare Antwort (kein Client im aktiven Profil, Admin-API nicht erreichbar) fällt auf die normale zeitbasierte Frist zurück.

  Diese Abfrage läuft vor und außerhalb der Löschtransaktion. `RetentionJob` ist nicht transaktional und fragt nur ab; gelöscht wird in `SessionRetentionSweeper`. Andernfalls würde ein Lauf über hunderte Kandidaten Zeilensperren so lange halten, wie Keycloak zum Antworten braucht. `OrchestratorArchitectureTest` prüft diese Regel jetzt für den ganzen Orchestrator.

## 3a) Keycloak-Spiegelung: offene Zustellungen stehen in einer Tabelle

Neben Konto-Änderungen löst auch eine Änderung im Personenverzeichnis die Spiegelung aus: `PersonChanged`
→ Konto (`applyDirectoryChange`) → `AccountChanged(changed)` → Keycloak, beide Schritte als Einträge dieser
Tabelle (ADR-34). Berührt die Änderung nichts Gespiegeltes, entfällt der Keycloak-Aufruf.

Jedes Konto wird als Keycloak-Nutzer gespiegelt (`KeycloakAccountSyncListener`, nur im
`keycloak`-Profil). Die Spiegelung läuft nach dem Commit und ist absichtlich best-effort: Ein
fehlgeschlagener Keycloak-Aufruf darf eine bereits abgeschlossene Kontoänderung nicht nachträglich
scheitern lassen.

Früher blieb bei einem Fehler aber gar nichts zurück. Als Wiederholung gab es nur „irgendwann
ändert sich das Konto nochmal" — für ein Konto, das sich nie wieder ändert, also keine. Das Konto
existierte, der Keycloak-Nutzer fehlte, eine Anmeldung war unmöglich, und nirgends stand, dass das
so ist.

Dafür gibt es jetzt die **Event Publication Registry** von Spring Modulith
([ADR-29](adr/ADR-029-event-publication-registry-statt-eigener-outbox.md)):

- Der Listener ist ein `@ApplicationModuleListener`. Bevor die Transaktion der Kontoänderung
  committet, schreibt Modulith eine Zeile nach `orchestrator.event_publication`.
- Die Zeile wird erst geschlossen, wenn die Methode ohne Fehler zurückkehrt. Deshalb fängt der
  Listener Fehler **nicht** mehr ab: Die Exception ist das Signal „nicht erledigt".
- Zugestellt wird auf einem eigenen Thread (`keycloakSyncExecutor`), also ein Sync nach dem
  anderen, über alle Konten hinweg. Die Registry garantiert nur die Zustellung, nicht die
  Reihenfolge: mit Springs gemeinsamem Async-Pool liefen die Syncs mehrerer Konten parallel, und
  Keycloak lehnte parallele Anlagen auf einem frischen Realm teils ab.
- Offene Zeilen werden nach fünf Minuten erneut zugestellt
  (`spring.modulith.events.staleness.*`) und beim Neustart ebenfalls
  (`republish-outstanding-events-on-restart`).
- Die Zeile enthält Status, Zahl der Zustellversuche und den Zeitpunkt der letzten Wiederholung.

Was noch offen ist, lässt sich damit abfragen:

```sql
SELECT event_type, listener_id, publication_date, completion_attempts, status
FROM orchestrator.event_publication
WHERE completion_date IS NULL
ORDER BY publication_date;
```

Zwei Dinge, die man wissen muss:

- `spring.modulith.events.jdbc.schema: orchestrator` ist zwingend. Ohne diese Einstellung sucht die
  Registry die Tabelle im Standardschema, findet sie nicht und schreibt nichts — ohne
  Fehlermeldung. `EventPublicationRegistryTest` prüft deshalb, dass ein fehlschlagender Listener
  tatsächlich eine offene Zeile hinterlässt.
- Die Tabelle legt Flyway an (`orchestrator/V15__event_publication.sql`), nicht Modulith. Die Datei ist
  unverändert aus dem `spring-modulith-events-jdbc`-Jar übernommen und muss beim Anheben der
  Modulith-Version damit verglichen werden.

`KeycloakSessionLogoutListener` benutzt die Registry bewusst nicht. Eine nicht beendete
Keycloak-Session läuft von selbst nach wenigen Minuten ab; ein fehlender Keycloak-Nutzer bleibt.
Nur der zweite Fall braucht eine Wiederholung.

## 3b) Das System läuft als eine Instanz

Diese Annahme galt schon vorher, stand aber nirgends. Sie steckte in drei unabhängigen Stellen:

- elf `@Scheduled`-Jobs ohne Sperre oder Leader-Election. Bei mehreren Instanzen liefe jeder Lauf
  mehrfach parallel.
- `dpop.secrets.otp-pepper` ist standardmäßig leer, das Pepper wird also bei jedem Start neu
  gewürfelt. Zwei Instanzen könnten die SMS- und E-Mail-Codes der jeweils anderen nicht prüfen.
- Die `@Volatile`-Caches im `KeycloakAdminClient` gelten nur im eigenen Prozess.
- `KeycloakAccountSyncListener` arbeitet alle Syncs nacheinander auf einem prozessinternen Thread ab (`keycloakSyncExecutor`). Zwei Instanzen würden denselben Keycloak-User und dasselbe Keypair parallel anlegen.

Beim Lesen des Codes wäre das nicht aufgefallen, sondern erst beim zweiten Pod — als sporadisch
fehlschlagende TAN-Prüfung. Deshalb steht es jetzt in der Konfiguration:

```yaml
deployment:
  instances: single   # oder: multiple
```

`multiple` schaltet nichts frei. Es ist eine Aussage über die Umgebung, und
`DeploymentTopologyCheck` prüft beim Start, ob der Code das trägt. Wenn nicht, bricht der Start mit
einer Liste dessen ab, was fehlt. Sobald die Voraussetzungen da sind — eine gemeinsame Sperre für
die Jobs, ein gesetztes Pepper —, ist diese Prüfung die Stelle, an der man sie lockert.

## 4) Kontosperre, Rate-Limits und Versand-Drosselung (Brute-Force-/Bombing-Schutz)

Gemeinsame Basis: `AttemptThrottle` (Entität, PK `(scope, subject)`) + `AttemptCounter`
(`src/main/kotlin/com/example/dpop/orchestrator/session/`). `scope` (`ThrottleScope`) trennt
mehrere Zählräume, die sich denselben Mechanismus, aber nie denselben Schlüsselraum teilen — je ein
benannter `@Service` mit eigenen Limits:

| Service | Scope | Zählt | Antwort bei Überschreitung |
|---|---|---|---|
| `LoginThrottleService` | `ACCOUNT` | Fehlgeschlagene AUTH-Versuche gegen ein Konto | `423 Locked` (`ACCOUNT_LOCKED`) bei IDENTIFIED_AUTH; bei LOOKUP_AUTH in die gewöhnliche "E-Mail/Code ungültig"-Antwort eingebettet (sonst ließe sich daraus ablesen, ob ein Konto existiert) |
| `IdentThrottleService` | `PERSON` | Fehlgeschlagene IDENT-Versuche gegen eine Person (`ident-fsc` rät ein Geheimnis; ein Treffer übernimmt das Konto). Greift nur, wo der Versuch überhaupt eine Person benennt — `ident-eid` bestätigt seit ADR-18 nur die Karte und löst niemanden auf, seine PIN-Versuche begrenzt das Retry-Budget der Tool-Session | immer in die gewöhnliche Fehlerantwort gefaltet, nie eigener Fehler |
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
  Statement selbst nimmt — nie per Lesen-dann-Schreiben, sonst wäre das tatsächliche Budget
  `Limit × Parallelität`, und die Sperre ließe sich überspringen. Eine fehlende Zählerzeile
  wird nach dem Muster „erst `UPDATE`, nur bei 0 getroffenen Zeilen anlegen, dann erneut `UPDATE`"
  behandelt (`AttemptThrottleRowInitializer`, eigene Transaktion).
- Ein erfolgreicher AUTH-/IDENT-Abschluss setzt den jeweiligen Zähler zurück (`recordSuccess`).
  Versand- und Kanal-Throttles kennen keinen Reset — sie sind reine rollierende Fenster.
- Aufbewahrung: siehe Tabelle in Abschnitt 3.

## 5) QR-Login (`auth_qr`): Pairing-Code-Sicherheit

`confirm-qr-login`s `input`-Schritt nimmt einen vom Nutzer eingegebenen oder per Deep-Link
vorbefüllten `pairingCode` entgegen ([Orchestrierung](04-orchestrierung.md) `CONFIRM_PEER_LOGIN`):

- **QR-Jacking-Schutz**: `verificationCode` (dreistellig) wird auf beiden Bildschirmen gezeigt und
  nur per Auge verglichen, nie übertragen oder eingegeben — das nimmt dem Angriff die Wirkung, bei dem ein
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

Das Schema steht in `src/main/resources/db/migration/<modul>/`, ein Ordner je Modul; die Regeln
stehen in `db/migration/KONVENTIONEN.md` und gelten für jede Tabelle ([12-entscheidungen.md](12-entscheidungen.md) ADR-14/ADR-16/ADR-30).
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
- **Anker**: Jeder Schreibvorgang auf `account.anchor` verlangt ein Mindestniveau nach
  `AnchorRule.acrFloor` (Erstbinden und Ersetzen getrennt); `established_acr` hält das
  tatsächlich bewiesene, nach ADR-5 begrenzte Niveau ([Domänenmodell](02-domaenenmodell.md) Abschnitt 6).
- **Konto**: Über `account.account` werden Änderungen gesperrt; der aktuelle Zustand steht in
  eigenen Zeilen, die Historie wird nur angefügt
  ([Domänenmodell](02-domaenenmodell.md) Abschnitt 6).
- **Aufbewahrung**: Jede Aufräumabfrage läuft als ein einzelnes Statement über viele Zeilen und hat
  einen Index auf ihrer Stichtagsspalte.
- **Migrationen**: eine Datei je Modul unter `db/migration/<modul>/`
  ([ADR-30](adr/ADR-030-eine-migration-je-modul.md)), der Bestand ist eine Neubaseline ohne
  Produktivdaten. Passt eine lokale H2-Datei nicht mehr zu den Migrationen, löscht
  `orchestrator.schema.FlywayResetConfig` sie beim Start und baut sie neu auf — ein `rm -rf data/`
  von Hand ist nicht nötig. Das greift ausschließlich bei H2; bei jeder anderen Datenbank bricht
  Flyway ab, wie es soll. Ab dem ersten produktiven Einsatz sind Migrationen ausschließlich additiv;
  Tabellen mit ≥ 10 Mio. Zeilen werden in wiederholbaren Portionen nachgezogen.
