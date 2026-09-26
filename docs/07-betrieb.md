# Fehler, Konsistenz und Lebenszyklus

Dieses Kapitel beschreibt den Fehlervertrag, was transaktional zugesagt ist und wie lange welche
Daten aufbewahrt werden.

> **Einschränkung ([ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md)):**
> Produktionsreif ist der Backend-Kern. Frontends und Ausführungsumgebung (`compose.yml`,
> `openshift/`, Admin-Zugang, H2-Konsole, Keycloak-Startmodus, TLS zwischen den Containern) sind
> Vorführrahmen und werden später gehärtet. Bis dahin läuft keine Instanz mit echten Personendaten.

---

## 1) Fehlervertrag

Die üblichen Fehlerantworten (heutiger Stand und Ziel):

- `400 Bad Request`: ungültiger Inhalt oder formal ungültige Anfrage.
- `401 Unauthorized`: DPoP-Nachweis fehlt oder ist ungültig, oder dem Kanal wird nicht vertraut.
- `403 Forbidden`: Die Bindung passt nicht, oder eine Regel verbietet die Aktion.
- `404 Not Found`: Sitzung oder Vorgang unbekannt.
- `409 Conflict`: unzulässiger Zustandswechsel, nicht erlaubte Aktion, ein zweiter gleichzeitiger
  Vorgang auf derselben `ChannelSession` oder widersprüchliche Angaben zum Konto.
- `410 Gone`: Der Vorgang ist abgelaufen, bereits verbraucht oder nach zu vielen Fehlversuchen
  abgebrochen.
- `422 Unprocessable Entity`: fachlich nicht verarbeitbar, ohne dass der Nutzer etwas falsch
  eingegeben hat (z. B. unbekannte `enrollmentRef`, fehlende Einrichtung).
- `423 Locked`: Das Konto ist nach zu vielen fehlgeschlagenen Anmeldeversuchen gesperrt
  (`ACCOUNT_LOCKED`, `OrchestratorException.accountLocked()`, Abschnitt 4).
- `429 Too Many Requests`: Eine Mengenbegrenzung ist erreicht; das Konto ist dabei nicht gesperrt
  (`OrchestratorException.tooManyRequests()`, Abschnitt 4: `ChannelCreationThrottleService`,
  gezählt je `bindingKeyRef`).
- `500 Internal Server Error`: Eine interne Annahme ist verletzt (`INTERNAL_ERROR`). Die Antwort
  enthält eine feste Text-Referenz; die Einzelheiten stehen nur im Log.

**Form und Quelle.** Jede Fehlerantwort hat die Form `ErrorResponse`
(`{"error": "<CODE>", "text": {"key": …, "args": …}}`; der Text ist eine Referenz wie in
[05-api.md](05-api.md), Abschnitt „Texte“). So steht sie auch im Vertrag, als `default`-Antwort
jeder Operation. Welcher Code zu welchem Status gehört, legt das Enum `ErrorCode` fest
(`orchestrator/kernel`), und die Liste im Vertrag wird daraus erzeugt. Code und Status lassen sich
deshalb nicht mehr unabhängig voneinander wählen. Clients entscheiden anhand von `error`, nie
anhand des Textes, und müssen mit Codes rechnen, die sie noch nicht kennen.

**Welche Exception wozu führt.** Auf diese Regel verlässt sich die zentrale Fehlerbehandlung:

- `require` bzw. `IllegalArgumentException` nur, wenn eine **Eingabe des Clients** abgelehnt wird.
  Das ergibt `400`, und der Text geht unverändert hinaus.
- `check`, `checkNotNull` und `error()`, wenn eine **interne Annahme** verletzt ist. Das ergibt
  `500` mit festem Text. Früher wurde daraus pauschal `409 INVALID_STATE_TRANSITION` mit dem
  Originaltext. Interne Prüfungen sahen damit wie ein fachlicher Konflikt aus und verrieten Interna.
- Ein echter fachlicher Konflikt wird immer ausdrücklich gemeldet:
  `OrchestratorException.invalidState(...)`.

Ausdrücklich **kein** Fehler sind fehlende Pflichtfelder und Fehlversuche, nach denen noch weitere
Versuche erlaubt sind. Sie liefern `200` und ein `next` (Regel für Wiederholungen in
[Orchestrierung](04-orchestrierung.md)).

## 2) Konsistenzregeln

- Je `ChannelSession` darf höchstens eine **laufende** `AuthJourney` existieren. Eine Journey, die
  auf eine Sub-Journey wartet, ist `SUSPENDED` und zählt nicht mit
  ([Orchestrierung](04-orchestrierung.md)).
- Eine `AuthJourney` darf nur in gültige Folgezustände wechseln, sowohl im Lebenszyklus als auch im
  Zustand ihres Intents (`JourneyState`).
- `AuthContext` wird nur aktualisiert, wenn eine Journey erfolgreich abgeschlossen ist.
- Jede Identifizierung, jeder Widerruf und jede eingerichtete oder deaktivierte Methode erzeugt einen Eintrag im Änderungsprotokoll des Kontos (`account.change_log`, [ADR-39](adr/ADR-039-was-eine-kontoloeschung-ueberlebt.md)); jeder Übergang einer Journey einen Eintrag im Journey-Trace.
- **Eine Transaktion für alles:** Verarbeitet der Orchestrator ein `ToolOutcome.Completed`
  ([Orchestrierung](04-orchestrierung.md)), speichert er in einer einzigen Transaktion den neuen
  Journey-Zustand, den Konto-Eintrag, das Claim-Log und den Nachweis der Sitzung (`AuthEvidence`).
  Entweder gelingt alles oder nichts. Das Methodenmodul speichert seine Tool- und
  Einrichtungsdaten schon beim `PATCH` in einer eigenen Transaktion. Scheitert danach der Schritt
  in der Journey, bleibt die Zeile des Moduls zwar stehen, wird aber nicht als Credential des
  Kontos aktiviert.
- Auch neue Konten, Claim-Log, Identifizierungs-Log, Anker und Methodeninstanzen liegen in dieser
  Transaktion. Ein Konto wird nicht vorab in einer eigenen Transaktion (`REQUIRES_NEW`)
  festgeschrieben. Binden zwei Vorgänge gleichzeitig, wird der unterlegene vollständig
  zurückgerollt und erhält `409 INVALID_STATE_TRANSITION`; einen automatischen neuen Versuch gibt
  es nicht. Verstöße gegen die Eindeutigkeit von `ux_anchor_value` und `ux_anchor_account_type`
  werden auch dann gezielt übersetzt, wenn sie erst beim Schreiben oder Festschreiben auffallen.
  Unbekannte Integritätsfehler bleiben Serverfehler.
- Der Demo-Seed (nur im `keycloak`-Profil) legt Konto und Claims in einer eigenen Transaktion an. Er
  legt nur an und ändert nie etwas. Löst der PERSON_ID- oder EMAIL-Anker einer Testperson bereits
  ein Konto auf (aus einem früheren Seed-Lauf oder als halb fertig registrierter Interessent), wird
  die Testperson ganz übersprungen. Das bestehende Konto bleibt unverändert und wird nicht ergänzt.
  So entstehen bei Neustarts keine zusätzlichen Claims aus dem Seed.
- Nicht transaktional ist der SMS-Versand, denn er wirkt nach außen: Ein Zurückrollen macht eine
  bereits verschickte SMS nicht rückgängig. Das betrifft nur die Zustellung, nicht die Konsistenz.
  Die zugehörige Zeile mit `issuedTanHash` wurde mit zurückgerollt, und der Code in der SMS passt
  zu nichts mehr.

## 3) Aufbewahrung und Löschung

Die Daten einer Sitzung enthalten Personenbezug (KVNR, Name, Telefonnummer) und Werte, die aus
Geheimnissen abgeleitet sind (`issuedTanHash`). Nach dem Ende des Vorgangs werden sie nie wieder
gelesen. Deshalb werden sie aktiv gelöscht und nicht aufbewahrt.

Richtwerte (als Voreinstellung gedacht, nicht als Vorgabe für Compliance):

- **`<modul>.*_tool_session` (Moduldaten)**
  - *Frist beginnt mit:* `createdAt`
  - *Richtwert:* 24 h (`tool-session.retention`)
  - *Grund:* Personenbezug und TAN-Hash. Jedes Methodenmodul löscht seine eigenen Tabellen selbst (`*RetentionJob` implementiert `ToolSessionSweeper`); Frist und Intervall stehen dagegen nur einmal, in `tool_api/ToolSessionRetention.kt`. Bei `auth_kobil.enroll_tool_session` ist die Frist besonders wichtig: Dort liegen während einer laufenden Einrichtung KOBIL-PIN und Entsperrgeheimnis im Klartext ([ADR-22](adr/ADR-022-der-verwahrte-pin-liegt-im-klartext-demo-rahmen.md))
- **`kobil_mock.*` (Fremdsystem)**
  - *Frist beginnt mit:* —
  - *Richtwert:* **kein** Aufräumen durch uns
  - *Grund:* `kobil_mock` simuliert KOBIL und unterliegt nicht unseren Aufbewahrungsregeln. Dass es seine Daten überhaupt speichert, ist nötig und keine Bequemlichkeit: Sonst würde nach jedem Neustart jede `auth_kobil.enrollment`-Zeile auf einen Nutzer zeigen, den es beim Anbieter nicht mehr gibt
- **`nect_mock.*`, `ext_personenverzeichnis.*` (Fremdsysteme)**
  - *Frist beginnt mit:* —
  - *Richtwert:* **kein** Aufräumen durch uns
  - *Grund:* simulierte Fremdsysteme wie `kobil_mock`. Auch die Briefe des Personenverzeichnisses mit den Freischaltcodes im Klartext bleiben dort, wie Papier beim Empfänger
- **`QrLoginRequest`**
  - *Frist beginnt mit:* `expiresAt`
  - *Richtwert:* 24 h
  - *Grund:* Kopplungsanfrage, nach Ablauf (5 Min.) wirkungslos; `AuthQrRetentionJob` räumt sie mit auf
- **`DpopProofReplay`**
  - *Frist beginnt mit:* `expiresAt`
  - *Richtwert:* sofort (minütlich)
  - *Grund:* Der Schutz vor wiederholten Proofs gilt nur in dem Zeitfenster, in dem ein Proof angenommen wird
- **`orchestrator.tool_session`**
  - *Frist beginnt mit:* `expiresAt`
  - *Richtwert:* 24 h
  - *Grund:* nur noch Angaben zum Lebenszyklus
- **`AuthJourney`**
  - *Frist beginnt mit:* `consumedAt` / `expiresAt`
  - *Richtwert:* 7 Tage
  - *Grund:* Zuordnung bei Rückfragen an den Support
- **`AuthContext`**
  - *Frist beginnt mit:* Abmeldung / Ende der `ChannelSession`
  - *Richtwert:* sofort
  - *Grund:* enthält Verweise auf Tokens
- **`ChannelSession`**
  - *Frist beginnt mit:* `expiresAt` / `LOGGED_OUT`
  - *Richtwert:* 30 Tage
  - *Grund:* `JourneyTraceEntry` fragt das Log über die Menge der Kanäle ab ([Domänenmodell](02-domaenenmodell.md) Abschnitt 5)
- **`JourneyTraceEntry`**
  - *Frist beginnt mit:* `createdAt`
  - *Richtwert:* 14 Tage
  - *Grund:* Ablaufprotokoll für Fehlersuche, Support und Demo, NICHT das Änderungsprotokoll. Mit Abstand die volumenstärkste Tabelle (eine Zeile je Schritt); 14 Tage decken Support-Fälle ab, länger ist über den Zweck nicht zu begründen
- **`account.change_log`**
  - *Frist beginnt mit:* Löschung des Kontos (`ACCOUNT_DELETED`)
  - *Richtwert:* 10 Jahre (`account.change-log.retention-years`, von der Datenschutzbeauftragten zu bestätigen)
  - *Grund:* Nachweis, dass und wie ein Konto identifiziert wurde und welche Methoden es hatte – ohne Werte, ohne Fremdschlüssel, überlebt die Löschung bewusst ([ADR-39](adr/ADR-039-was-eine-kontoloeschung-ueberlebt.md)); `ChangeLogRetention` räumt ab
  - *Suche:* über Name, Vorname und Geburtsdatum (`ChangeLogSearch`, Suchschlüssel als HMAC). Das Geheimnis `CHANGE_LOG_LOOKUP_SECRET` ist außerhalb des Demomodus Pflicht und muss so lange aufbewahrt werden wie das Protokoll; ein neues Geheimnis macht alle älteren Einträge unauffindbar
- **`account.sign_in_log`**
  - *Frist beginnt mit:* dem Ereignis
  - *Richtwert:* 6 Monate (`account.sign-in-log.retention-months`)
  - *Grund:* wer sich wann womit angemeldet hat, Fehlversuche, Sperren und Logouts – für die Aufklärung einer Kontoübernahme; Verhaltensdaten, deshalb kurz und mit dem Konto gelöscht (ADR-39, Nachtrag); `SignInLogRetention` räumt ab
- **`*Enrollment` (Credentials der Module)**
  - *Frist beginnt mit:* —
  - *Richtwert:* kein Aufräumen mit der Sitzung
  - *Grund:* lebt bis zur Löschung des Kontos (erreichbar über `account.auth_method`, auch deaktivierte Instanzen)
- **`account.*` (Anker, Methoden, Claim-, Identifizierungs- und Widerrufs-Log)**
  - *Frist beginnt mit:* —
  - *Richtwert:* kein Aufräumen mit der Sitzung
  - *Grund:* gehört dem Konto und wird mit ihm gelöscht – mit allen Werten. Was die Löschung überlebt, ist nur `account.change_log` (ADR-39)
- **`DeviceAccountLink`**
  - *Frist beginnt mit:* —
  - *Richtwert:* kein Aufräumen mit der Sitzung
  - *Grund:* Identität des Geräts (`bindingKeyRef -> accountId`), überlebt bewusst jede einzelne `ChannelSession` ([DPoP-Bindung](09-dpop.md) Abschnitt 3)
- **`AttemptThrottle`**
  - *Frist beginnt mit:* letzte Änderung des Zählers
  - *Richtwert:* 7 Tage
  - *Grund:* weit länger als das längste Zählfenster und die längste Sperre (15 Min.); ein Aufräumlauf löscht nie eine Zeile, deren Sperre noch läuft. Gilt für die Zähler aller Bereiche (`ACCOUNT`/`PERSON`/`BINDING_KEY`/`ACCOUNT_SEND`/`CONTACT_SEND`, Abschnitt 4)
- **`orchestrator.keycloak_keypair`**
  - *Frist beginnt mit:* —
  - *Richtwert:* kein Aufräumen mit der Sitzung
  - *Grund:* Schlüsselpaar für den Token-Grant im `keycloak`-Profil ([05-api.md](05-api.md) Abschnitt 2); wird bei `AccountDeleted` gelöscht, nicht über `RetentionJob`

Wie mit den Verweisen zwischen den Tabellen umgegangen wird:

- **Besitzkette** (`ChannelSession` → `AuthJourney` → `orchestrator.tool_session` → der Teil im
  Modul, `<modul>.*_tool_session`): Sie wird von innen nach außen aufgeräumt. Weil die Fristen von
  innen nach außen länger werden, ergibt sich diese Reihenfolge von selbst.
- **Daten der Module:** Welche Zeilen gelöscht werden, entscheidet jedes Modul selbst. Nur das Modul
  weiß, welche seiner Tabellen zur Sitzung gehören und welche (`*_enrollment`) zum Konto. Wann
  gelöscht wird, steht dagegen an einer einzigen Stelle (`tool-session.retention`), und ein
  gemeinsamer Zeitplaner (`ToolSessionRetentionDriver`) stößt es an.

  Früher hatte jedes Modul einen eigenen `@Scheduled`-Job mit einer eigenen Konstante
  `private val RETENTION = 24h`, also zehn Kopien derselben Frist. Fehlte einem Modul der Job, fiel
  das niemandem auf, weil nichts fehlschlug; es fehlte einfach eine Datei. So blieben die Zeilen in
  `id_kvnr.ident_tool_session` dauerhaft stehen. Heute prüft `ToolSessionCoverageTest` gegen das
  tatsächliche Schema, dass ein Aufräumlauf jede `*_tool_session`-Tabelle leert, auch eine später
  hinzukommende.

  Scheitert der Aufräumlauf eines Moduls, laufen die übrigen trotzdem. Sonst würden Daten länger
  aufbewahrt als erlaubt, nur weil an anderer Stelle ein Fehler auftrat.
- **Das Änderungsprotokoll hängt an keiner anderen Tabelle:** `account.change_log` speichert die `accountId` als
  historischen Wert, nicht als Fremdschlüssel – das Protokoll muss die Löschung des Kontos
  überleben. Eine Id, die auf kein Konto mehr zeigt, ist deshalb erwartet und kein Fehler.
- **Bei KOBIL betrifft das Widerrufen eines Verfahrens auch den Anbieter.** `KobilEnrollmentCleanup`
  löscht nicht nur unsere Zeile, sondern entfernt auch den Nutzer beim Anbieter. Sonst bliebe dort
  ein gebundenes Gerät stehen, von dem bei uns niemand mehr weiß. Dieser zweite Aufruf wirkt nach
  außen und liegt deshalb außerhalb der Transaktion, genau wie der simulierte SMS-Versand. Unsere
  Zeile verschwindet in jedem Fall.
- **Objekte des Kontos sind beim Aufräumen der Sitzungen tabu:** Die Credentials der Module
  (`*_enrollment`), `account.auth_method`, `account.change_log` (IDENTIFIED) und `DeviceAccountLink` gehören
  dem Konto bzw. dem Gerät, nicht der Sitzung.
- **Wird ein Konto gelöscht, räumt das zusätzlich zwei Sitzungstabellen für diese `accountId` auf**,
  obwohl keine von beiden einen Fremdschlüssel auf `account` hat:
  - `orchestrator.journey_trace`, und zwar über **zwei** Schlüssel: das Konto **und** seine
    `ChannelSession`s, weil Einträge aus der Zeit, bevor die Sitzung einem Konto zugeordnet war,
    `account_id = NULL` haben;
  - `orchestrator.attempt_throttle`, aber nur die Bereiche `ACCOUNT` und `ACCOUNT_SEND`. Würden auch
    `BINDING_KEY` und `CONTACT_SEND` gelöscht, ließen sich diese Zähler durch eine neue
    Registrierung zurücksetzen.

  `AccountDeletionService.deleteAccount` erledigt das ausdrücklich und unabhängig von den Fristen
  oben.
- **Bei `KEYCLOAK`-Kanälen fragt das Aufräumen bei Keycloak nach, statt sich nur auf die Zeit zu
  verlassen.** Die Abmeldung im Web-Kanal gehört ganz Keycloak ([05-api.md](05-api.md)
  Abschnitt 3). `RetentionJob` prüft deshalb für abgelaufene `KEYCLOAK`-Kanäle über die Admin-API
  von Keycloak, ob die Sitzung dort noch besteht (`ChannelSession.durableKcSessionId`). Ist sie
  nachweislich beendet, wird sofort aufgeräumt. Lässt sich das nicht klären (kein Client im aktiven
  Profil, Admin-API nicht erreichbar), gilt die normale zeitbasierte Frist.

  Diese Abfrage läuft vor der Löschtransaktion und außerhalb von ihr. `RetentionJob` ist nicht
  transaktional und fragt nur ab; gelöscht wird in `SessionRetentionSweeper`. Sonst würde ein Lauf
  über Hunderte Kandidaten Zeilensperren so lange halten, wie Keycloak zum Antworten braucht.
  `OrchestratorArchitectureTest` prüft diese Regel inzwischen für den ganzen Orchestrator.

## 3a) Keycloak liest die Konten – keine Spiegelung

Keycloak hält keine Kopie der Konten. Seine Nutzer-Federation (`OrchestratorStorageProvider`, ohne
Import) liest ein Konto bei Bedarf beim Orchestrator nach (`KcAccountLookupController`): nach
Konto-Id, exakter E-Mail-Adresse oder Benutzername, jeweils ein einzelner Zugriff über Primärschlüssel
oder den eindeutigen E-Mail-Anker. Eine Liste aller Konten gibt es nicht; die Suche der Admin-Konsole
findet nur exakte Treffer ([ADR-38](adr/ADR-038-keycloak-liest-konten.md), Review 2026-09 P-3).

- **Was Keycloak zeigt:** Benutzername (die bestätigte E-Mail, sonst `account-<id>`), E-Mail, Vor- und
  Nachname und die Attribute hinter den Token-Claims (`personId`, `kvnr`, `versnr`, `birthDate`,
  `streetAddress`, `postalCode`, `locality`; im Token `birth_date`, `street_address`, `postal_code`, `locality`). Für ein Konto mit Person gelten nur die Werte des Personenverzeichnisses,
  für einen Interessenten der stärkste bestätigte Wert aus dem Konto; ein Konto ohne beides zeigt
  Platzhalternamen. Alles davon ist in Keycloak schreibgeschützt.
- **Frische:** Keycloak cacht einen föderierten Nutzer höchstens 60 Sekunden (Migration V2). Eine
  geänderte Adresse oder ein geänderter Name ist spätestens dann sichtbar.
- **Nutzer-Id und `sub`:** `f:orch-accounts:<accountId>`. Die Komponenten-Id ist fest
  (`USER_STORAGE_COMPONENT_ID`); eine neu gewürfelte Id würde jedes `sub` ändern.
- **Was Keycloak selbst hält:** Sitzungen, Fehlversuche (Brute-Force-Schutz), Zustimmungen und
  sonstige föderierte Daten eines Nutzers.
- **Konto gelöscht:** `KeycloakAccountSyncListener` räumt genau diese Keycloak-eigenen Daten ab
  (`DELETE /admin/realms/{realm}/orchestrator-accounts/{accountId}`, `AccountRemoval`). Das ist das
  einzige Ereignis, das Keycloak noch erreicht; eine Änderung am Konto braucht keinen Aufruf mehr.

Das Abräumen nach einer Löschung läuft über die **Event Publication Registry** von Spring Modulith
([ADR-29](adr/ADR-029-event-publication-registry-statt-eigener-outbox.md)), damit ein fehlgeschlagener
Aufruf nicht spurlos verloren geht:

- Der Listener ist ein `@ApplicationModuleListener`. Bevor die Transaktion der Löschung
  festgeschrieben wird, schreibt Modulith eine Zeile nach `orchestrator.event_publication`.
- Die Zeile wird erst abgeschlossen, wenn die Methode ohne Fehler zurückkehrt. Deshalb fängt der
  Listener Fehler **nicht** mehr ab: Die Exception ist das Signal „nicht erledigt“.
- Zugestellt wird auf einem eigenen Thread (`keycloakSyncExecutor`), eine Löschung nach der anderen.
- Offene Zeilen werden nach fünf Minuten erneut zugestellt (`spring.modulith.events.staleness.*`),
  ebenso beim Neustart (`republish-outstanding-events-on-restart`).
- Die Zeile enthält den Status, die Zahl der Zustellversuche und den Zeitpunkt der letzten
  Wiederholung.

Was noch offen ist, lässt sich damit abfragen:

```sql
SELECT event_type, listener_id, publication_date, completion_attempts, status
FROM orchestrator.event_publication
WHERE completion_date IS NULL
ORDER BY publication_date;
```

Zwei Dinge sollte man wissen:

- `spring.modulith.events.jdbc.schema: orchestrator` ist zwingend. Ohne diese Einstellung sucht die
  Registry die Tabelle im Standardschema, findet sie nicht und schreibt nichts, ohne jede
  Fehlermeldung. `EventPublicationRegistryTest` prüft deshalb, dass ein fehlschlagender Listener
  wirklich eine offene Zeile hinterlässt.
- Die Tabelle legt Flyway an (`orchestrator/V15__event_publication.sql`), nicht Modulith. Die Datei
  ist unverändert aus dem Jar `spring-modulith-events-jdbc` übernommen. Beim Wechsel auf eine neue
  Modulith-Version muss man sie damit vergleichen.

`KeycloakSessionLogoutListener` nutzt die Registry bewusst nicht. Eine nicht beendete Sitzung in
Keycloak läuft nach wenigen Minuten von selbst ab; die Sitzungen eines gelöschten Kontos dagegen
nicht. Nur der zweite Fall braucht eine Wiederholung.

## 3b) Das System läuft als eine einzige Instanz

Diese Annahme galt schon vorher, stand aber nirgends. Sie steckte an vier voneinander unabhängigen
Stellen:

- Drei `@Scheduled`-Jobs (Aufräumen der Tool-Sessions, `RetentionJob`, Aufräumen des Schutzes vor
  wiederholten DPoP-Proofs) laufen ohne Sperre und ohne Wahl einer führenden Instanz. Bei mehreren
  Instanzen liefe jeder Lauf mehrfach parallel.
- `dpop.secrets.otp-pepper` ist standardmäßig leer; der Pepper wird also bei jedem Start neu
  gewürfelt. Zwei Instanzen könnten die SMS- und E-Mail-Codes der jeweils anderen nicht prüfen, und
  jede Instanz hätte eigene `CONTACT_SEND`-Zähler.
- Die `@Volatile`-Zwischenspeicher im `KeycloakAdminClient` gelten nur im eigenen Prozess.
- `KeycloakAccountSyncListener` arbeitet alle Abgleiche nacheinander auf einem Thread des eigenen
  Prozesses ab (`keycloakSyncExecutor`). Zwei Instanzen würden denselben Keycloak-Nutzer und
  dasselbe Schlüsselpaar gleichzeitig anlegen.

Beim Lesen des Codes wäre das nicht aufgefallen, sondern erst mit der zweiten Instanz, als
gelegentlich fehlschlagende TAN-Prüfung. Deshalb steht es jetzt in der Konfiguration:

```yaml
deployment:
  instances: single   # oder: multiple
```

`multiple` schaltet nichts frei. Der Wert beschreibt die Umgebung, und `DeploymentTopologyCheck`
prüft beim Start, ob der Code dafür geeignet ist. Wenn nicht, bricht der Start mit einer Liste
dessen ab, was fehlt. Sobald die Voraussetzungen erfüllt sind (eine gemeinsame Sperre für die Jobs,
ein fest gesetzter Pepper), ist diese Prüfung die Stelle, an der man sie lockert.

## 3c) Außerhalb des Demomodus: was gesetzt sein muss

`demo.mode=false` heißt: Hier dürfen echte Personendaten liegen ([ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md)).
`ProductionModeCheck` bricht dann den Start ab, solange eine Demo-Voreinstellung übrig ist, und nennt
alle auf einmal:

- `demo.disclosure=false` – keine Demo-Werte (Klartext-TANs, Codes) in Antworten.
- `demo.admin.password` gesetzt, nicht `admin`, und als Hash (`{bcrypt}…`, `{argon2}…`), nicht im Klartext.
- `spring.h2.console.enabled=false`.
- `dpop.secrets.otp-pepper` und `account.change-log.lookup-secret` mit mindestens 32 Zeichen.
- Keycloak über https mit geprüftem Zertifikat (kein `trustSelfSignedCertificate`).

Außerdem gibt es außerhalb des Demomodus die Demo-Oberflächen nicht (`@DemoSurface`: Mocks der
Fremdsysteme, Kontenverwaltung mit Demo-Reset), keinen Flyway-Reset und keine Demo-Personen.
Admin-Anmeldungen sind gedrosselt: fünf falsche Passwörter für einen Benutzernamen sperren ihn für
15 Minuten (429), auch für das richtige Passwort.

## 4) Kontosperre, Mengenbegrenzung und Versanddrosselung (Schutz vor Ausprobieren und Massenversand)

Gemeinsame Grundlage sind `AttemptThrottle` (Entität, Primärschlüssel `(scope, subject)`) und
`AttemptCounter` (`src/main/kotlin/com/example/dpop/orchestrator/session/`). `scope`
(`ThrottleScope`) trennt mehrere Zählbereiche. Sie teilen sich denselben Mechanismus, nie aber
dieselben Schlüssel. Jeder Bereich hat einen eigenen `@Service` mit eigenen Grenzen:

- **`LoginThrottleService`**
  - *Bereich:* `ACCOUNT`
  - *Zählt:* Fehlgeschlagene Anmeldeversuche an einem Konto
  - *Antwort, wenn die Grenze überschritten ist:* `423 Locked` (`ACCOUNT_LOCKED`) bei IDENTIFIED_AUTH. Bei LOOKUP_AUTH steckt die Sperre in der gewöhnlichen Antwort „E-Mail oder Code ungültig“; sonst ließe sich daraus ablesen, ob ein Konto existiert
- **`IdentThrottleService`**
  - *Bereich:* `PERSON`
  - *Zählt:* Fehlgeschlagene Identifizierungsversuche für eine Person (`ident-fsc` rät ein Geheimnis, und ein Treffer übernimmt das Konto). Zählt nur, wo der Versuch überhaupt eine Person benennt: `ident-eid` bestätigt seit ADR-18 nur die Karte und findet niemanden; seine PIN-Versuche begrenzt die erlaubte Zahl von Versuchen der Tool-Session
  - *Antwort, wenn die Grenze überschritten ist:* immer in der gewöhnlichen Fehlerantwort, nie als eigener Fehler
- **`ChannelCreationThrottleService`**
  - *Bereich:* `BINDING_KEY`
  - *Zählt:* Eröffnete Kanäle je DPoP-Schlüssel (gleitendes Zeitfenster, jeder Versuch zählt)
  - *Antwort, wenn die Grenze überschritten ist:* `429 Too Many Requests`
- **`SendThrottleService`**
  - *Bereich:* `ACCOUNT_SEND` / `CONTACT_SEND`
  - *Zählt:* **Versendete** TANs und Codes, egal ob sie später richtig eingegeben werden (gleitendes Zeitfenster, 3 in 10 Min.)
  - *Antwort, wenn die Grenze überschritten ist:* `ACCOUNT_SEND` (LOOKUP_AUTH) steckt in der gewöhnlichen Fehlerantwort. `CONTACT_SEND` (Einrichten durch den Nutzer selbst; der Schlüssel ist ein HMAC-SHA256 mit dem OTP-Pepper statt der Adresse im Klartext) darf offen als eigener Fehler zurückkommen

- **Warum das zusätzlich zu `ToolSession.retryCount` nötig ist** (Regel für Wiederholungen in
  [Orchestrierung](04-orchestrierung.md) Abschnitt 1): `retryCount` zählt nur innerhalb *eines*
  Tool-Starts. Mit einem neuen `POST .../tools/{toolId}` (bei den Tools, die über die E-Mail-Adresse
  arbeiten, mit einem neuen `PATCH` auf derselben `toolSessionId`) kann ein Client jederzeit neu
  beginnen. `ACCOUNT` und `PERSON` begrenzen deshalb falsche Rateversuche. `ACCOUNT_SEND` und
  `CONTACT_SEND` begrenzen zusätzlich das bloße *erneute Versenden*. Das ist nie ein falscher
  Rateversuch und löst deshalb nie `recordFailure` aus. Ohne diese Zähler könnte man über
  `auth-sms-lookup`, `auth-email-lookup`, `enroll-sms` und `confirm-email` beliebig viele SMS und
  E-Mails an fremde Empfänger auslösen.
- Fehlschläge beim Einrichten zählen weder bei `LoginThrottleService` noch bei
  `IdentThrottleService` (`ToolControllerSupport.chargeThrottles`, `ToolCategory.ENROLL -> Unit`):
  Beim Einrichten wird kein vorhandenes Credential erraten. Den Versand *während* des Einrichtens
  begrenzt `CONTACT_SEND` (Tabelle oben).
- Grenzwerte: bei Fehlversuchen `MAX_FAILURES = 5` und `LOCKOUT_DURATION = 15 Minuten`; beim Eröffnen
  von Kanälen 20 in 5 Minuten; beim Versand 3 in 10 Minuten.
- Jeder Zähler wird mit einer einzigen atomaren `UPDATE`-Anweisung erhöht, unter der Zeilensperre,
  die diese Anweisung selbst setzt. Er wird nie erst gelesen und dann geschrieben: Sonst wäre das
  tatsächliche Budget „Grenze × Zahl gleichzeitiger Anfragen“, und die Sperre ließe sich umgehen.
  Fehlt die Zeile für einen Zähler, gilt: erst `UPDATE`; wurde keine Zeile getroffen, die Zeile
  anlegen und das `UPDATE` wiederholen (`AttemptThrottleRowInitializer`, in einer eigenen
  Transaktion).
- Eine erfolgreiche Anmeldung oder Identifizierung setzt den jeweiligen Zähler zurück
  (`recordSuccess`). Die Zähler für Versand und Kanaleröffnung werden nie zurückgesetzt; sie sind
  reine gleitende Zeitfenster.
- Aufbewahrung: siehe die Tabelle in Abschnitt 3.

## 5) QR-Login (`auth_qr`): Sicherheit des Pairing-Codes

Der Schritt `input` von `confirm-qr-login` nimmt einen `pairingCode` entgegen, den der Nutzer
eingibt oder den ein Deep-Link vorausfüllt ([`CONFIRM_PEER_LOGIN`](journeys/confirm-peer-login.md)):

- **Schutz davor, einen fremden QR-Code zu bestätigen – Code in Gegenrichtung:** Die Freigabe in
  der App meldet den Browser noch nicht an. Sie erzeugt einen sechsstelligen **Bestätigungscode**,
  den nur die App anzeigt und den der Nutzer in den wartenden Browser tippt; erst dann ist der
  Browser angemeldet (`QrLoginBrowserSide`). Ein Angreifer, der dem Opfer seinen eigenen
  Pairing-Code schickt (per Link oder als QR-Bild), bekommt damit nichts: Das Opfer müsste den Code
  in den Browser des Angreifers tippen oder ihn ausdrücklich weitergeben; die App warnt davor.
  *Früher* zeigten beide Seiten einen dreistelligen Vergleichscode, den man nur mit dem Auge
  verglich. Den konnte der Angreifer einfach mit in seine Nachricht schreiben (Review 2026-09, M-2).
  **Nicht** geschützt ist gegen ein Opfer, das den Bestätigungscode auf Nachfrage selbst herausgibt.
- **Bestätigungscode:** gespeichert nur als Hash, im Klartext genau einmal an die App ausgeliefert.
  Nach der Freigabe hat der Browser zwei Minuten Zeit; nach drei falschen Codes ist die Anfrage
  verbrannt (`EXPIRED`, `countWrongConfirmation`). Das Versuchsbudget der Journey (3) greift
  zusätzlich.
- **Unteilbare Zustandswechsel:** Freigabe, Ablehnung und Abschluss schreiben nur unter einer
  Bedingung (`approveIfPending`/`denyIfPending`: `status = 'PENDING'` und nicht abgelaufen;
  `completeIfConfirmed`: `status = 'APPROVED'`, richtiger Hash, nicht abgelaufen). Wird keine Zeile
  getroffen, war die Anfrage bereits entschieden, abgelaufen oder der Code falsch. So können nie
  zwei Konten gleichzeitig als `resolvingAccountId` eingetragen werden, und ein Code meldet nie zwei
  Browser an.
- **Zufallsgehalt des `pairingCode`:** 8 Zeichen aus einem Alphabet mit wenig Verwechslungsgefahr
  (ähnlich Crockford-Base32, ohne `I`, `L`, `O` und `U`), etwa 40 Bit. Das ist bewusst weniger als
  bei einem reinen API-Token, weil ein Mensch den Code fehlerfrei abschreiben können muss.

**Noch offen:** Weil die Eingabe von Hand ein regulärer Weg ist, bräuchte der Schritt `input` einen
eigenen Zähler für fehlgeschlagene Suchen nach einem `pairingCode`, etwa je IP-Adresse oder ohne
Bezug auf ein Konto. `AttemptThrottle` (Abschnitt 4) hilft hier nicht, weil noch kein Konto bekannt
ist. Das ist derzeit **nicht umgesetzt**.

`QrLoginRequest.expiresAt` (5 Minuten, `QR_LOGIN_TTL`) orientiert sich an den Laufzeiten der
bestehenden TANs (`enroll-sms`/`auth-sms`). Abgelaufene Zeilen sind beim Lesen wirkungslos, und
`AuthQrRetentionJob` räumt sie auf (Abschnitt 3).

## 6) Datenbankschema: Konventionen

Das Schema liegt in `src/main/resources/db/migration/<modul>/`, ein Ordner je Modul. Die Regeln
stehen in `db/migration/KONVENTIONEN.md` und gelten für jede Tabelle
([12-entscheidungen.md](12-entscheidungen.md) ADR-14/ADR-16). Ein Diagramm der wichtigsten
Tabellen zeigt [02-domaenenmodell.md](02-domaenenmodell.md) Abschnitt 7.

- **Besitz ist im Aufbau verankert:** Jedes Modul hat ein eigenes Datenbankschema, und jede Tabelle
  liegt im Schema ihres Moduls (`account.anchor`, `auth_sms.enrollment`). Die Tabellennamen bleiben
  kurz, weil das Schema den Modulnamen schon enthält.
- **Fremdschlüssel** gibt es nur innerhalb eines Schemas. Bezüge über Modulgrenzen hinweg (z. B.
  `account_id` in Tabellen des Orchestrators) sind Spalten mit Index und werden über die
  Schnittstellen der Module aufgeräumt.
- **Namen:** Dauerhafte Credentials heißen `<modul>.enrollment`, und dieser vollständige Name ist
  `EnrollmentRef.type`. Die Arbeitsdaten eines Tool-Durchlaufs heißen
  `<modul>.<tool-rolle>_tool_session`. Ihr Schlüssel *ist* die `tool_session_id`; die Zeile ist
  also der Teil von `orchestrator.tool_session`, der im Modul liegt. Die Primärschlüsselspalte heißt
  immer `id`, Verweise heißen `<tabelle>_id`. Indizes und Constraints tragen kein Modulpräfix
  (`ux_anchor_value`).
- **Typen:** Zeitpunkte `TIMESTAMP WITH TIME ZONE`, Enum-Werte `VARCHAR(32)`, ACR-Werte
  `VARCHAR(16)`, Tool-IDs, Methoden, Attributtypen und Quellen `VARCHAR(50)`, Hashes `VARCHAR(64)`.
- **Anker:** Jeder Schreibvorgang auf `account.anchor` verlangt ein Mindestniveau nach
  `AnchorRule.acrFloor` (für das erste Binden und das Ersetzen getrennt). `established_acr` hält das
  tatsächlich nachgewiesene, nach ADR-5 begrenzte Niveau fest ([Domänenmodell](02-domaenenmodell.md)
  Abschnitt 6).
- **Konto:** Änderungen werden über `account.account` gesperrt. Der aktuelle Zustand steht in
  eigenen Zeilen; die Historie wird nur ergänzt, nie geändert ([Domänenmodell](02-domaenenmodell.md)
  Abschnitt 6).
- **Aufbewahrung:** Jede Aufräumabfrage ist eine einzige SQL-Anweisung über viele Zeilen und hat
  einen Index auf ihrer Stichtagsspalte.
- **Migrationen:** grundsätzlich eine Datei je Modul unter `db/migration/<modul>/`; `orchestrator`
  hat zusätzlich `V14__node_signing_key.sql` und `V15__event_publication.sql`
  ([ADR-16](adr/ADR-016-ein-datenbankschema-je-modul-statt-namenspraefix.md)). Der heutige Stand ist eine neue Ausgangsbasis
  ohne Produktivdaten. **Nur im Demomodus** gilt: Passt eine lokale H2-Datei nicht mehr zu den
  Migrationen, löscht `orchestrator.schema.FlywayResetConfig` sie beim Start und baut sie neu auf;
  `rm -rf data/` von Hand ist nicht nötig. Außerhalb des Demomodus gibt es die Klasse gar nicht,
  und Flyway bricht den Start ab, wie es soll: Die H2-Datei ist dann die Betriebsdatenbank, und ein
  Migrationsfehler darf nie Konten und Änderungsprotokoll löschen. Die Demo-Personen
  (`demo_seed`) werden außerhalb des Demomodus nicht migriert. Ab dem ersten produktiven Einsatz sind Migrationen nur noch additiv, und Tabellen
  mit 10 Millionen Zeilen oder mehr werden in wiederholbaren Portionen umgestellt.
