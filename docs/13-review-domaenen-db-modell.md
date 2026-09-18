# Review: Domänen- und DB-Modell (`account`, `orchestrator`)

Stand: 2026-09-16. Geprüfter Umfang: `src/main/kotlin/com/example/dpop/account/**`,
`src/main/kotlin/com/example/dpop/orchestrator/**`, `src/main/resources/db/migration/**`.

Bewertungsmaßstab war bewusst nicht „funktioniert die Demo", sondern die drei Eigenschaften, die
das Zielbild für sich beansprucht: **sicherheitskritisch**, **lange Lebensdauer**, **≥ 10 Mio.
Nutzer bei hoher Anmeldelast**. Das Modell ist konzeptionell überdurchschnittlich sauber
(Zustand statt Vererbung, Evidenz als *eine* Quelle, keine gespeicherten `next*`-Duplikate); die
Lücken liegen fast vollständig auf den Achsen **Nebenläufigkeit, Skalierung und
Lösch-/Aufbewahrungspfade**.

> **Hinweis (2026-09-17, [ADR-14](12-entscheidungen.md))**: Die hier zitierten Migrationen `V3`–`V38`
> existieren nicht mehr; sie sind in `V1__schema.sql` zusammengeführt. Die Verweise bleiben als
> Befundhistorie stehen. Mit derselben Konsolidierung sind D1 umgesetzt und B5 teilweise, außerdem
> entfallen die Projektionsspalten `account.person_id`/`email`/`email_confirmed_at` (siehe C1).

## Statuslegende

| Status | Bedeutung |
|---|---|
| ✅ behoben | Im Code/DDL umgesetzt, vollständige Testsuite grün (568 Tests) |
| 🟡 teilweise | Ein Anteil umgesetzt, ein Rest ist offen (jeweils benannt) |
| ⛔ blockiert | Analysiert, Lösung ausgearbeitet, Umsetzung durch eine Umgebungsgrenze verhindert |
| ⬜ offen | Analysiert und belegt, nicht umgesetzt |

---

## A) Sicherheitskritisch

### A1 — Throttle ist durch Parallelisierung umgehbar ✅ behoben

`AttemptCounter` zählte per Read-Modify-Write (`findByIdOrNull` → `+= 1` → `save`). `AttemptThrottle`
war zudem die einzige Multi-Writer-Entity ohne `@Version`. N gleichzeitige Requests lasen damit
alle denselben Vorher-Stand, hielten sich alle unabhängig für „im Budget" und schrieben denselben
Wert zurück: das effektive Budget war `Limit × Parallelität`, der Lockout ließ sich überspringen.
Betroffen waren alle drei Schutzräume — Login-Brute-Force (`ACCOUNT`), Ident-Rateversuche
(`PERSON`) und die Mengenbegrenzung für SMS-/E-Mail-Versand (`ACCOUNT_SEND`, `CONTACT_SEND`).

Optimistic Locking wäre hier die *falsche* Korrektur: ein unterlegener Schreiber würde nicht
gezählt — genau das Ziel des Angreifers. Das Inkrement muss in der Datenbank stattfinden, unter
der Zeilensperre, die das `UPDATE` selbst nimmt.

Umgesetzt: `AttemptThrottleRepository` bietet keinen Save-basierten Zählpfad mehr, sondern je ein
atomares `UPDATE` für Fehlversuch, Rolling Window und Reset. Die Lockout-Entscheidung wird im
selben Statement aus dem Nach-Inkrement-Wert abgeleitet.

Eine fehlende Zählerzeile wird nach dem Muster „erst `UPDATE`, nur bei 0 getroffenen Zeilen
anlegen, dann erneut `UPDATE`" behandelt (`AttemptThrottleRowInitializer`, eigene Transaktion
nach dem bereits etablierten `REQUIRES_NEW`-Muster aus `DpopReplayProtectionService`). Der
Normalfall — Zeile existiert — kostet damit genau ein Statement.

> Zwischenschritt, bewusst verworfen: Ein `INSERT … ON CONFLICT DO NOTHING` wäre kürzer gewesen,
> wird von H2 2.4 außerhalb des PostgreSQL-Kompatibilitätsmodus aber mit einem Syntaxfehler
> abgelehnt (belegt durch den Testlauf). Einen herstellerspezifischen Upsert ausgerechnet im
> Throttle-Pfad wollte ich nicht einbauen.

Verifiziert: vollständige Testsuite grün (565 Tests).

### A2 — Anchor-Konflikt wurde still verschluckt ✅ behoben

`AccountService.recordAnchor` brach bei einem fremdem Konto gehörenden Anchor mit `return` ab —
die Projektionsspalte `account.email` wurde aber trotzdem geschrieben. Ergebnis: Das Konto führte
eine E-Mail, deren Anchor auf ein **anderes** Konto zeigte; `findAccountByEmail` und
`resolveByAnchor` beantworteten dieselbe Frage ab da verschieden. Genau die Divergenz, gegen die
der Anchor eingeführt wurde. ADR-11 verlangt für Cross-Account-Konflikte eine Ablehnung nach oben,
kein stilles Überspringen.

Umgesetzt: Fremdbesitz wirft `IdentityConflictException` (bereits in `JourneyService` auf
`invalidState` abgebildet) und loggt auf WARN; Eigenbesitz bleibt idempotent. Zusätzlich wurde in
`consolidateOwnedColumn` die Reihenfolge gedreht — der Anchor wird **vor** der Projektionsspalte
geschrieben, damit ein Konflikt abbricht, bevor etwas geschrieben ist, statt nur per Rollback
rückgängig gemacht zu werden.

Verifiziert: `AccountServiceTest` prüft die Ablehnung jetzt explizit (`shouldThrow`) und belegt,
dass dabei **weder** ein zweiter Anchor **noch** die Projektionsspalte geschrieben wird.

### A3 — Zwei widersprüchliche Eindeutigkeiten für dieselbe E-Mail ✅ behoben

`V6` legte einen UNIQUE-Index auf die **rohe** Spalte `account.email`, `V31` einen auf den
**normalisierten** `account_anchor(anchor_type, anchor_value)`. `A@x.de` und `a@x.de` passierten
damit den Spaltenindex, kollidierten aber am Anchor — und diese Kollision wurde von A2 verschluckt.
Zusätzlich war der Lesepfad `findByEmail` case-sensitiv: Wer sich mit `Max@x.de` anmeldete, fand
das Konto nicht, das `max@x.de` bestätigt hatte.

Umgesetzt (Code): `account_anchor` ist die alleinige Auflösungs- und Eindeutigkeitsautorität.
`findAccountByEmail`/`resolveAccountByEmail` sind Extensions über dem normalisierten Anchor;
der ungenutzte `existsByEmail`-Sonderweg entfällt. Reine ID-Lookups laden kein Account-Profil;
`AccountRepository.findByEmail`/`existsByEmail` wurden entfernt, damit kein zweiter, schwächerer
Lesepfad zurückkehren kann. `account.email` bleibt rohe Projektionsspalte.

Umgesetzt (DDL): `V33__email_anchor_is_sole_uniqueness.sql` zieht fehlende Anchors kollisionsfrei
nach (first writer wins, wie der Live-Schreibpfad) und tauscht den UNIQUE-Index auf der Rohspalte
gegen einen einfachen Index. Die drei Annahmen aus dem ursprünglichen SQL-Entwurf sind am
tatsächlichen Schema verifiziert: `V31` enthielt noch keinen Backfill (Schritt 1 ist kein No-Op),
die Spalte heißt `email_confirmed_at` (`V6` Zeile 6), der Index heißt `idx_account_email` (`V6`
Zeile 9). `./gradlew :test` bleibt grün (565 Tests), `ddl-auto: validate` läuft weiterhin durch.

### A4 — Anchor-Reihenfolge hängt an einer nicht garantierten Eigenschaft ✅ behoben

`IdentityMatchingService.resolveByAnchor` iterierte `Set<Claim>` und verließ sich laut Kommentar
darauf, dass `toSet()` die Attestierungsreihenfolge erhält („der stärkste zuerst attestierte Anchor
wird zuerst konsultiert"). `Set` gibt keine Reihenfolge zu; übergibt ein Aufrufer ein `HashSet`,
verschwindet diese Sicherheitsaussage lautlos und der schwächere Anchor kann gewinnen.

**Umsetzung:** `resolveByAnchor` iterierte zunächst `claims.sortedByDescending { anchorClassOf(it.trustAnchor).rank }`
— die Stärkeordnung war damit eine Eigenschaft des Codes, nicht mehr der `Set`-Implementierung
des Aufrufers. **Nachtrag** ([ideen/account-attribute-und-trust-vereinheitlichen.md](ideen/account-attribute-und-trust-vereinheitlichen.md)):
das sortierte fachlich nach dem falschen Rang — Claim-Quelle/Vertrauensstufe statt Bindungsstärke
des Attributtyps, zwei verschiedene Achsen (dort explizit als eigener Befund benannt: „bindingStrength
NICHT aus TrustLevel.rank berechnen"). Seither `claims.sortedByDescending { it.attributeType.anchorBindingStrength ?: 0 }`
(`tool_api/AttributeRules.kt`) — `PERSON_ID` rangiert damit unabhängig davon, welches Tool die
Claims geliefert hat, immer über `EMAIL`/`KVNR`.

### A5 — Löschpfad unvollständig, aber nur bei `orchestrator_journey_log`/`orchestrator_attempt_throttle` (DSGVO) ✅ behoben

**Korrektur gegenüber der ursprünglichen Fassung:** Die vermutete funktionale Lücke trifft **nicht**
zu. `V30__add_account_attribute.sql` und `V31__add_account_anchor.sql` sind beide lesbar und
setzen `account_id BIGINT NOT NULL REFERENCES account(id) ON DELETE CASCADE` (`V30` Zeile 15,
`V31` Zeile 13) — beide Migrationen dokumentieren das sogar explizit im Kopfkommentar als
gewollte Eigenschaft. `AccountService.deleteAccount` führt mit `accountRepository.deleteById(...)`
ein echtes SQL-`DELETE FROM account` aus, das diese DB-seitige Cascade auslöst. Damit räumt sich
`account_attribute` **und** `account_anchor` beim Löschen einer Account-Zeile selbst mit auf: kein
verwaister EMAIL-Anchor, `resolveByAnchor` findet nach der Löschung nichts mehr, die Adresse ist
sofort wieder für eine Neuregistrierung frei.

Was bleibt, ist die DSGVO-Hälfte: `AccountService.deleteAccount` löscht nur die `account`-Zeile
(mit den beiden Cascades im Schlepptau); `AccountDeletionService` räumt zusätzlich Credentials,
`DeviceAccountLink`, Channels, `AuthContext`, `AuthEvidence` — **nicht** aber `orchestrator_journey_log` und
`orchestrator_attempt_throttle`. Beide enthalten identitätsnahe Daten (`orchestrator_journey_log.detail`-JSON,
`orchestrator_attempt_throttle.subject` bei `CONTACT_SEND` ungepfefferte Hashes von Kontaktadressen, siehe A7) und keine
Beziehung zu `account`, die eine Cascade tragen könnte — das ist eher B3/eine explizite
Aufräum-Query im Löschpfad als ein fehlender Fremdschlüssel.

Empfehlung: `AccountDeletionService` um `orchestrator_journey_log`/`orchestrator_attempt_throttle`-Aufräumung für die
gelöschte `accountId` ergänzen, unabhängig von der Aufbewahrungsfrist aus B3.

**Umsetzung:** `AccountDeletionService.deleteAccount` räumt beides jetzt explizit ab.

- `orchestrator_journey_log` wird über **zwei** Schlüssel gelöscht — `account_id` **und** die Channel-Sessions
  des Kontos. Einträge, die geschrieben wurden, bevor der Channel ein Konto aufgelöst hatte,
  tragen `account_id = NULL` und hätten eine rein kontobezogene Löschung überlebt; es ist derselbe
  Grund, aus dem `getLogForAccount` in der Leserichtung über die Channel-Menge geht.
- `orchestrator_attempt_throttle` wird **nur** in den kontobezogenen Scopes (`ACCOUNT`, `ACCOUNT_SEND`)
  gelöscht. `PERSON` gehört zum externen Register, `BINDING_KEY` und `CONTACT_SEND` sind bewusst
  nicht kontobezogen — `CONTACT_SEND` speichert ohnehin nur einen Hash (siehe A7, dessen Annahme
  roher Kontaktdaten damit hinfällig ist). Diese Scopes mitzulöschen würde die Kontolöschung in
  einen Weg verwandeln, fremde Throttle-Budgets zurückzusetzen.

Zwei Fallstricke, die dabei zutage traten und im Code dokumentiert sind:

1. Die Löschung muss **eine einzige** Anweisung sein. Jede Bulk-Mutation auf `orchestrator_journey_log` löst
   vorher einen Auto-Flush aus; bei zwei Anweisungen schrieb der zweite Flush einen Eintrag fort,
   den die erste bereits gelöscht hatte → `Unexpected row count (expected 1 but was 0)`, nach
   außen ein falscher `409 CONCURRENT_MODIFICATION` auf genau der Anfrage, die die Löschung
   angestoßen hat.
2. `flushAutomatically` **und** `clearAutomatically` gehören hier zusammen: Der Flush schreibt
   alle offenen Änderungen (vor allem die Channel-Logouts) vor der Löschung weg, damit das Clear
   sie nicht verwirft; das Clear löst die gelöschten Einträge aus dem Persistence-Context. Ohne
   das bleiben sie verwaltet, und da `detail` eine veränderliche JSON-Map ist, die Hibernate als
   dirty erneut prüft, flusht die nächste Abfrage derselben Anfrage ein UPDATE gegen nicht mehr
   existierende Zeilen — derselbe falsche 409, nur später.

Nebenbefund daraus: Ein `409 CONCURRENT_MODIFICATION` war betrieblich vollständig unsichtbar. Der
`OrchestratorExceptionHandler` protokolliert jetzt Entity und Id des Konflikts (WARN) — echte und
selbstverschuldete Kollisionen sind von außen nicht unterscheidbar, nur die betroffene Entity
trennt sie.

### A6 — Interne Konto-IDs verlassen das System ✅ behoben

`Resolution.Ambiguous(candidates)` transportierte eine Liste interner, fortlaufender `accountId`s
nach außen. `channelSessionId` ist ausdrücklich opaque gehalten — `accountId`/`personId` sind es
nicht.

**Umsetzung:** `Resolution.Ambiguous` trägt jetzt `candidateCount: Int` statt `candidates: List<Long>`.
Der einzige Aufrufer (`JourneyService`) nutzte ohnehin nur `candidates.size` für die
Fehlermeldung; kein Downstream-Code brauchte je die IDs selbst.

### A7 — Kontaktadressen als Primärschlüsselbestandteil, ungepfeffert gehasht ✅ behoben

**Korrektur gegenüber der ursprünglichen Fassung:** Die Behauptung „im Klartext" trifft **nicht**
zu. `SendThrottleService.isThrottledForContact` legt nicht die Adresse, sondern ihren
SHA-256-Hash als `orchestrator_attempt_throttle.subject` ab (`SendThrottleService.hash`, mit eigener Begründung
im Code). Der `CONTACT_SEND`-Scope enthält damit keine im Klartext lesbaren Kontaktdaten.

Was blieb: Der Hash war **ungepfeffert**. Telefonnummern und E-Mail-Adressen haben zu wenig
Entropie, um das allein zu tragen — der Suchraum deutscher Mobilnummern ist vollständig
durchrechenbar, gängige Adressen stehen in Wörterbüchern. Aus einem Datenbankabzug ließe sich
also weiterhin bestimmen, ob eine konkrete Adresse das System benutzt hat.

**Umsetzung:** `SendThrottleService.hash` ist jetzt HMAC-SHA256 unter demselben
`dpop.secrets.otp-pepper` wie `TanGenerator`/`EmailCodeGenerator` — eigener `@Value`-Lookup pro
Modul (Modulgrenzen bleiben entkoppelt), aber dieselbe Konfiguration und Begründung. Blank
bedeutet wie dort einen frischen Zufalls-Pepper pro Boot; das 10-Minuten-Fenster, das dieser
Pepper schützt, macht einen Neustart-bedingten Reset praktisch irrelevant (gleiches
Kosten-Nutzen-Verhältnis wie bei den Fünf-Minuten-OTPs).

Die zweite Hälfte des ursprünglichen Befunds — „unbegrenzt lange, ohne Aufbewahrungsgrenze" — ist
mit B3 erledigt: `orchestrator_attempt_throttle` wird nach 7 Tagen ausgekehrt.

---

## B) Skalierung (10 Mio. Nutzer, hohe Anmeldelast)

### B1 — Full-Table-Scan im Identifikationspfad ✅ behoben

`AccountAttributeRepository.findAccountIdsByTypeAndNormalizedValue` vergleicht
`lower(trim(a.value))` — nicht sargable. Auf `account_attribute` existiert ausschließlich
`idx_account_attribute_account_id`; für `(attribute_type, value)` gibt es **keinen** Index, und
selbst mit einem wäre das funktionsumhüllte Prädikat nicht nutzbar.

`IdentityMatchingService.resolveByAttributeCombination` setzt drei solcher Abfragen hintereinander
und schneidet die Ergebnismengen **in der Anwendung**. Die Tabelle ist append-only und wird nie
gekürzt. Bei 10 Mio. Konten ist das ein trivial auslösbarer DoS aus einem Pfad, der vor jeder
Authentisierung erreichbar ist.

Empfehlung: `normalized_value`-Spalte beim Insert schreiben, Index `(attribute_type,
normalized_value)`, Schnittmenge in **einer** SQL-Abfrage, zusätzlich eine harte
Kandidaten-Obergrenze.

**Warum zuvor blockiert:** Der Fix braucht zwingend DDL (`normalized_value` plus Index), und
`ddl-auto: validate` lässt keine Entity-Spalte ohne passende Migration zu — eine halb
angewandte Code-Hälfte würde die gesamte Testsuite rot färben. In der Umgebung, in der dieser
Schritt zuerst bearbeitet wurde, verweigerte die Content-Exclusion-Policy jeden Schreibzugriff auf
`src/main/resources/db/migration/`, deshalb wurde er dort nur vollständig vorbereitet.

**Umsetzung (diese Umgebung, Migrationsordner schreibbar):** Migration `V34` wie unten angewendet
(Typ `VARCHAR(255)` gegen `V30__add_account_attribute.sql` verifiziert — passt). `AccountAttribute`
trägt jetzt `normalizedValue`, gefüllt über `@PrePersist`/`@PreUpdate` plus eine
`companion object`-Funktion `normalize()`, die sowohl der Hook als auch
`IdentityMatchingService` beim Aufbau der Suchparameter aufrufen — die Regel existiert damit
syntaktisch an einer Stelle. `AccountAttributeRepository.findAccountIdsByTypeAndNormalizedValue`
(dreimal aufgerufen) ist ersetzt durch `findAccountIdsMatchingAllThree` — eine Abfrage, `group by
account_id`, `having count(distinct attribute_type) = 3`, `order by account_id`, beantwortet allein
aus `idx_account_attribute_type_normalized`. `IdentityMatchingService.resolveByAttributeCombination`
holt eine Seite der Größe `CANDIDATE_LIMIT + 1` (50 + 1); wird die Obergrenze überschritten, ist das
Ergebnis `Resolution.Ambiguous` (auf die ersten 50 gekappt) — nie ein Treffer. Test ergänzt
(„attribute matching past the candidate ceiling"), 569 Tests grün.

Angewendete Migration `V34__account_attribute_normalized_value.sql`:

```sql
ALTER TABLE account_attribute ADD COLUMN normalized_value VARCHAR(255);

UPDATE account_attribute
SET normalized_value = lower(trim(attribute_value))
WHERE attribute_value IS NOT NULL;

-- Führendes attribute_type hält den Index für die typgebundenen Gleichheits-Lookups selektiv;
-- account_id ist mit aufgenommen, damit die Kandidatenabfrage allein aus dem Index beantwortet
-- werden kann.
CREATE INDEX idx_account_attribute_type_normalized
    ON account_attribute (attribute_type, normalized_value, account_id);
```

Codeseitig gehören dazu:

1. `AccountAttribute`: Spalte `normalized_value` (nullable wie `attribute_value`), gefüllt über
   einen `@PrePersist`/`@PreUpdate`-Hook, damit die Normalisierungsregel genau **einmal**
   existiert und nicht zwischen Schreib- und Lesepfad auseinanderlaufen kann.
2. `AccountAttributeRepository`: die drei Einzelabfragen durch **eine** ersetzen —
   `where (attribute_type, normalized_value)` dreifach ver-`or`-t, `group by account_id`,
   `having count(distinct attribute_type) = 3`, `order by account_id`, plus `Pageable` als harte
   Obergrenze (Vorschlag: 50 + 1 Zeile, um „mehr als die Obergrenze" von „genau die Obergrenze"
   unterscheiden zu können).
3. `IdentityMatchingService.resolveByAttributeCombination`: In-Memory-`intersect` entfällt. Wird
   die Obergrenze überschritten, ist das Ergebnis `Resolution.Ambiguous` — nie ein Treffer, denn
   die Regel „lieber gar nicht als falsch zusammenführen" darf eine Obergrenze nicht aufweichen.

### B2 — `allAccountIds()` lädt alle Konten in den Heap 🟡 teilweise

`AccountService.allAccountIds()` rief `accountRepository.findAll()` und mappte danach auf die ID —
lud also 10 Mio. `Account`-Entities inklusive beider JSON-Collections.

**Umsetzung:** `AccountRepository.findAllIds()` (projizierende Query `select a.id from Account a`)
ersetzt `findAll().mapNotNull { it.id }` — die JSON-Collections werden nicht mehr mitgeladen.
**Nicht umgesetzt:** echte Pagination/Streaming der ID-Liste selbst. Der einzige Aufrufer
(`KeycloakAccountSyncService.syncAll`) lädt ohnehin pro ID das volle `AccountProfile` in derselben
Schleife — eine paginierte ID-Query allein würde den Speicherdruck nicht senken, ohne dass auch
diese Schleife selbst batchweise arbeitet. Das wäre ein Umbau des vollständigen
Reconciliation-Ablaufs, kein kleiner Einzelbefund mehr.

### B3 — `orchestrator_journey_log` hat keinerlei Aufbewahrungsgrenze ✅ behoben

`RetentionJob` deckt `ToolSession`, `AuthJourney`, `ChannelSession`, `AuthContext`, `AuthEvidence`
und `SessionEvent` ab — `orchestrator_journey_log` kommt darin **nicht vor**. Die Tabelle bekommt pro
Journey-Schritt eine Zeile samt `detail`-JSON und ist laut eigener Doku ein Debug-/Demo-Trace. Bei
„vielen Anmeldungen" ist sie mit Abstand die größte Tabelle des Systems und enthält zugleich
identitätsnahe Daten (siehe A5). Gleiches gilt für `orchestrator_attempt_throttle`.

**Umsetzung:** `RetentionJob` kehrt beide Tabellen jetzt rein altersbasiert aus — beide hängen an
keinem Fremdschlüssel, der sie mit aufräumen könnte, und sind durch nichts anderes begrenzt.

- `JOURNEY_LOG_RETENTION = 30 Tage`, bewusst gleich `CHANNEL_SESSION_RETENTION`: Der Log wird über
  die Channel-Sessions abgefragt (`JourneyLogService.getLogForAccount` löst erst die Channel-Menge
  auf), länger zu leben als sie bringt also nichts. Er ist der Debug-Trace, **nicht** der
  Audit-Trail — das bleibt `SessionEvent` mit seinen 90 Tagen.
- `ATTEMPT_THROTTLE_RETENTION = 7 Tage`, zwei Größenordnungen über dem längsten Fenster bzw.
  Lockout irgendeines Throttle-Dienstes (15 Minuten). Zusätzlich rührt
  `AttemptThrottleRepository.deleteStaleCounters` keine Zeile an, deren `locked_until` noch läuft:
  Ein Sweep darf einem Angreifer niemals seine Sperre abräumen.

Beides sind Bulk-Statements statt abgeleiteter `deleteBy…`-Methoden — die abgeleitete Form würde
die größte Tabelle des Systems zeilenweise in den Persistence-Context laden, nur um sie zu löschen.

### B4 — `RetentionJob` ohne Batching 🟡 teilweise

`findByExpiresAtBefore` lud alle fälligen Zeilen als vollständige Entities in **einer**
Transaktion; anschließend wurden `IN`-Listen unbekannter Größe gebaut
(`deleteByJourneyIdIn`, `deleteAllByIdInBatch`). Das skaliert nicht über einen Ausfalltag hinweg
und läuft zusätzlich in Parameter-Obergrenzen.

**Umsetzung:** `deleteExpiredJourneys`/`deleteExpiredChannels` paginieren jetzt mit fester
Batchgröße (`RETENTION_BATCH_SIZE = 500`) über `AuthJourneyRepository.findIdsForRetention`
bzw. `ChannelSessionRepository.findByExpiresAtBefore` (beide um `Pageable` erweitert) — Schleife
bis leer, jede Runde löscht ihre Zeilen vollständig, bevor erneut Seite 0 abgefragt wird (korrekt
unabhängig von Sortierung, weil bereits gelöschte Zeilen nicht wieder erscheinen können).

**Nicht umgesetzt:** `confirmedDeadKcChannels` (der KEYCLOAK-Frühräum-Pfad) bleibt unbatched — dort
wird die geladene Menge erst per Admin-API-Aufruf gefiltert, sodass nicht jede geladene Zeile in
derselben Runde gelöscht wird; das "Seite 0 nach Löschen erneut abfragen"-Muster wäre dort falsch
(Zeilen, die die Liveness-Prüfung nicht bestehen, blieben stehen und würden bei der nächsten
Abfrage erneut zurückgegeben — kein Fortschritt, potenzielle Endlosschleife). Ein korrekter Fix
bräuchte Keyset-Pagination (Sortierung nach `channel_session_id`, Fortschritt über die letzte
gesehene ID statt Offset/Requery) — das ist ein eigener, sorgfältiger Schritt, kein Teil dieser
kleinen Korrektur. Diese Menge ist zudem klein (nur `KEYCLOAK`-Kanäle, nur profilgebunden aktiv),
also nicht die vom Befund gemeinte Hauptskalierungssorge.
Ebenfalls unverändert: die gesamte `cleanup()`-Transaktion bleibt eine einzige `@Transactional`
über alle Batches hinweg — echtes "übersteht einen Ausfalltag" bräuchte zusätzlich unabhängige
Transaktionen pro Batch (eigenes Bean wegen Self-Invocation, analog `AttemptThrottleRowInitializer`),
was über eine kleine Korrektur hinausgeht.

### B5 — `orchestrator_dpop_proof_replay` als Durchsatzdeckel 🟡 teilweise

**Umsetzung (ADR-14):** Der Primärschlüssel ist `proof_hash VARCHAR(64)` = SHA-256(`thumbprint:jti`),
berechnet in `DpopReplayProtectionService`. **Offen:** Zeitpartitionierung bzw. KV-Store (unten).

**Warum nicht in diesem Durchgang:** Die Empfehlung selbst ist explizit als Produktionsentscheidung
formuliert ("Empfehlung für den Produktivpfad") — Wahl zwischen Hash-PK mit Zeitpartitionierung
oder einem separaten persistenten KV-Store ist eine Infrastrukturentscheidung, keine lokale
Codeänderung, die sich in dieser H2-Demo-Umgebung sinnvoll klein umsetzen ließe. Bleibt offen für
den produktiven Zielstack.

Die Mechanik ist richtig gedacht (PK-Insert *ist* die Prüfung, kein Read-then-Write, überlebt
Neustart und gilt über Replicas). Der Preis: ein INSERT pro authentifiziertem Request auf eine
global heiße Tabelle mit `VARCHAR(255)`-Primärschlüssel. Empfehlung für den Produktivpfad:
Hash-PK (`BINARY(32)`/`UUID`) plus Zeitpartitionierung, alternativ ein persistenter KV-Store.
`jti` ist in beiden Validatoren Pflicht — der naheliegende Verdacht „Proof ohne `jti` sperrt das
Gerät über den Schlüssel `thumbprint:null` dauerhaft aus" wurde geprüft und trifft **nicht** zu.

### B6 — `availableClientTools` ist `FetchType.EAGER` ✅ behoben

`ChannelSession.availableClientTools` war eine `@ElementCollection(fetch = EAGER)` und erzwang
damit auf dem heißesten Pfad des Systems einen zusätzlichen Join/Query — für einen Wert, der laut
eigener Dokumentation über die gesamte Kanal-Lebenszeit **konstant** ist.

**Umsetzung:** Migration `V35` fügt `orchestrator_channel_session.available_tools` als `JSON`-Spalte hinzu
(gleiches Muster wie `account.identifications`/`authentication_methods`, `V1__schema.sql`),
migriert die Bestandsdaten aus `channel_session_available_tools` per `LISTAGG` und löscht die
alte Tabelle. Entity nutzt jetzt `@JdbcTypeCode(SqlTypes.JSON)` statt
`@ElementCollection`/`@CollectionTable`.

---

## C) Vereinheitlichung

### C1 — E-Mail existiert vierfach ✅ behoben

`account.email` + `account.emailConfirmedAt` + `account_anchor(EMAIL)` + `account_attribute(EMAIL)`.
Sauberes Zielbild: `account_attribute` = Provenienz (append-only), `account_anchor` =
Auflösung **und** Eindeutigkeit, Spalte = reine Projektion ohne eigene Unique-Zusage.
Mit A3 ist das Zielbild jetzt vollständig erreicht: Lesepfad und Eindeutigkeitsautorität laufen
über den Anchor, die Spalte trägt nur noch die (unbenutzte) Rohwert-Historie.

**Nachtrag (ADR-14):** Die Projektionsspalten `account.email`/`email_confirmed_at` (und ebenso
`account.person_id`) sind entfallen. Es bleiben zwei Schichten: `account_attribute` (Provenienz,
Rohwert) und `account_anchor` (aktueller normalisierter Wert, Auflösung, Eindeutigkeit).
`AccountProfile.email`/`emailConfirmedAt`/`personId` werden aus dem Anker gelesen.

### C2 — Typisierung zwischen den Modulen inkonsistent 🟡 teilweise

`AccountAttribute.attributeType`/`trustAnchor` und `AccountAnchor.anchorType` waren `String`,
obwohl `AttributeType`, `AnchorType` und `AnchorClass` als Typen existieren — während das
`orchestrator`-Modul durchgängig `@Enumerated(EnumType.STRING)` verwendet. Über eine Laufzeit von
10+ Jahren wird aus einer Umbenennung so stille Datenkorruption statt eines Compilerfehlers.

**Umsetzung:** `attributeType` (→ `AttributeType`) und ursprünglich `AccountAnchor.anchorType`
(→ ein damals eigenes `AnchorType`) waren typisiert, über eigene `AttributeConverter`
(`AttributeTypeConverter`, `AnchorTypeConverter`), nicht `@Enumerated(STRING)` — beide Spalten
enthalten bereits seit Jahren Wire-Names in Kleinschreibung (`person_id`, `email`),
`@Enumerated(EnumType.STRING)` würde stattdessen den Enum-Konstantennamen (`PERSON_ID`)
schreiben/erwarten und stumm gegen jede Bestandszeile ins Leere laufen. Die Konverter rundeten
über `wireName` und eine `fromWireName`-Rücklaufrichtung, die bei einem unbekannten Wert hart
wirft (`error(...)`) statt still `null` zu liefern.

**Nachtrag** ([ideen/account-attribute-und-trust-vereinheitlichen.md](ideen/account-attribute-und-trust-vereinheitlichen.md), Paket 2): `AnchorType`
entfiel als eigene, zur `AttributeType`-Taxonomie doppelte Hierarchie - `account_anchor.anchor_type`
nutzt seither denselben `AttributeTypeConverter` wie `account_attribute.attribute_type` (identisches
Wire-Format), `AnchorTypeConverter` ist entfallen. Die Anker-Regeln (welcher Typ überhaupt Anker
ist, Normalisierung, Ersetzbarkeit) leben seither gebündelt in `tool_api/AttributeRules.kt` statt
in einer eigenen Sealed-Hierarchie.

**Weiterhin nicht umgesetzt: die Quelle bleibt `String`** (seit ADR-14 `AccountAttribute.claimSource`,
Spalte `claim_source`). `TrustAnchor` (seit Paket 1 in
`ClaimSource` umbenannt) ist eine Kotlin `@JvmInline value class` — beim Testen mit echtem
`AttributeConverter<TrustAnchor, String>` warf Hibernate zur Laufzeit `JpaSystemException: Error
attempting to apply AttributeConverter: class java.lang.String cannot be cast to class
TrustAnchor` bei jedem Schreibzugriff (verifiziert: `AttributeType`, ein echtes Enum, und das
damalige `AnchorType`, ein sealed interface aus Objects, haben dieses Problem NICHT — nur der
Value-Class-Fall). Eine nullable Value-Class-Property wird auf JVM-Ebene zwar geboxt, aber
Hibernates Property-Access/Enhancement-Pfad reicht dem Konverter dafür eine rohe `String`-Instanz
statt der geboxten `ClaimSource` durch. Das ist eine bekannte Inkompatibilität zwischen Kotlin
Value Classes und Hibernates `AttributeConverter`-Mechanismus, keine lokal behebbare
Fehlkonfiguration - unverändert seit diesem Befund, auch nach der Umbenennung in
`ClaimSource`/`AttributeRules.kt`; `AccountService.recordClaims` entpackt weiterhin manuell
(`claim.source.value`).

### C3 — Spaltenname trägt die falsche Bedeutung ✅ behoben

`ChannelSession.channelAnchor` lag auf der Spalte `kc_session_id`, während das *tatsächliche*
Keycloak-Session-Feld `durableKcSessionId` auf `kc_durable_session_id` liegt. Das ist exakt die
Verwechslung, vor der der Doc-Kommentar über 20 Zeilen warnt — in SQL, Betrieb und Forensik war
diese Warnung aber nicht sichtbar.

**Umsetzung:** Migration `V36` benennt Spalte und Index um (`kc_session_id` →
`channel_anchor`, `idx_channel_session_kc_session_id` → `idx_channel_session_channel_anchor`);
`@Column(name = ...)` auf der Entity folgt.

### C4 — `findOrCreateAccount` verliert das Rennen mit einem 500er ✅ behoben

Der Kommentar verwies korrekt auf `ux_account_person_id` (V32) als DB-seitigen Rennabschluss —
die Verletzung wurde aber nicht gefangen. Der unterlegene von zwei gleichzeitigen Step-up-Kanälen
bekam also einen Serverfehler statt des existierenden Kontos.

**Aktuelle Umsetzung:** Die zwischenzeitliche `REQUIRES_NEW`-Erzeugung ist entfernt.
Account-Anlage und Claim-/Anker-Übernahme laufen gemeinsam in der Journey-Transaktion.
Ein Bindungsrennen wird durch die DB-Eindeutigkeit entschieden: Der Verlierer rollt
einschließlich Account-Anlage zurück und erhält gezielt HTTP 409, keinen automatischen Retry
und keinen unvollständigen Account. Die Zuordnung umfasst nur bekannte Bindungs-Unique-Constraints,
auch bei Flush/Commit; andere Integritätsfehler bleiben sichtbar.

---

## D) Struktur / unnötige Komplexität

### D1 — `authenticationMethods` als JSON-Liste auf einer versionierten Zeile ✅ behoben

**Umsetzung (ADR-14):** `account_auth_method` (eine Zeile je Methodeninstanz, `id UUID`,
`enrollment_type`/`enrollment_id` als Spalten, `deactivated_at` mit CHECK-Constraint gegen `active`,
Indizes `(account_id, method)` und `(enrollment_type, enrollment_id)`). Die
Nebenläufigkeitssemantik ist bewusst erhalten: Jede Änderung lädt die Kontozeile mit
`OPTIMISTIC_FORCE_INCREMENT`, zwei konkurrierende Methodenänderungen desselben Kontos enden also
weiterhin in genau einem `409`; Lesen schreibt dagegen nie mehr, und der Dirty-Checking-Kommentar
samt `data class`-Zwang ist gegenstandslos. `identifications` ist analog zu `account_identification`
geworden (append-only, keine Versionserhöhung). Der ursprüngliche Befund folgt unverändert.

Der teuerste Entwurfsentscheid im Modell. Drei Konsequenzen:

1. **Contention**: Jede Enrollment- oder Deaktivierungsoperation serialisiert über `@Version` auf
   *eine* Zeile pro Konto.
2. **Nicht abfragbar**: Es gibt keine Möglichkeit, „alle Konten mit Methode X" zu finden — bei 10
   Jahren Laufzeit ist ein Revocation-/Kryptowechsel-Sweep aber keine Option, sondern eine
   Gewissheit.
3. **Fragilität**: Der 15-Zeilen-Kommentar zum Hibernate-Dirty-Checking auf `AuthenticationMethod`
   ist selbst das Symptom — die Korrektheit hängt daran, dass niemand die `data class` in eine
   `class` ändert oder ein Feld aus dem Primärkonstruktor herausbewegt.

Eine eigene Tabelle `account_auth_method(id, account_id, method, active, enrolled_under_acr, …)`
beseitigt Kommentar, Contention und Abfragelücke in einem Zug. Für `identifications` gilt dasselbe
abgeschwächt (weniger Schreiblast).

**Warum nicht in diesem Durchgang:** Anders als B1 (eine Migration, drei lokale Code-Schritte in
zwei Dateien) ist das hier eine echte Architekturmigration: `authenticationMethods`/
`AuthenticationMethod` werden produktivseitig in 16 Dateien über mindestens sechs Module hinweg
gelesen oder geschrieben (`account`, `auth_email`, `auth_password`, `auth_qr`, `demo_seed`,
mehrere `orchestrator`-Unterpakete), plus mindestens sechs Testdateien. Der Umbau ändert zudem
echtes Laufzeitverhalten, nicht nur die Speicherform: die heutige `@Version`-Serialisierung auf
*einer* Konto-Zeile (Punkt 1 oben, „Contention") ist genau die Nebenläufigkeitssemantik, die eine
eigene Tabelle absichtlich auflöst — jeder Aufrufer, der sich (auch nur implizit) auf „ein
Methoden-Update sperrt das ganze Konto" verlässt, braucht eine neue Prüfung. Das ist die vom
Review selbst als teuerste Entwurfsentscheidung markierte Änderung — sie verdient einen eigenen,
sorgfältig geplanten Durchgang mit Migrationsstrategie für Bestandsdaten vorab, keinen
Einzelbefund nebenbei. Gleiche Kategorie wie B5/D2.

### D2 — Kein Konto-Lebenszyklus, kein Merge-Pfad ⬜ offen (bewusst zurückgestellt)

**Warum nicht in diesem Durchgang:** Anders als B1/C3/C4 gibt es hier keine mechanische, lokal
abgeschlossene Änderung — ein Status-Enum plus `merged_into` zu ergänzen berührt jeden Lesepfad,
der bisher voraussetzt, dass eine `account`-Zeile immer „die eine gültige" ist (Login,
Step-up-Auflösung, `IdentityMatchingService`, Admin-Sync), plus die Frage, was beim Merge mit den
Anchor-/Attribute-/Auth-Method-Zeilen zweier Konten passiert. Das ist die vom Review selbst als
teuerste Entwurfsentscheidung markierte Änderung (siehe D1) und verdient einen eigenen,
sorgfältig geplanten Durchgang mit Entwurfsentscheidung vorab, keinen Einzelbefund nebenbei.

`Account` kennt keinen Status (gesperrt, deaktiviert, verstorben) und kein `merged_into`.
Gleichzeitig ist „mehrdeutig / Merge-Konflikt" ein explizit modellierter Fall, der laut ADR-11
heute nur nach oben abgelehnt wird. Über die angestrebte Lebensdauer entsteht Merge-Bedarf
zwangsläufig — ohne `merged_into` gibt es später keinen verlustfreien Weg dorthin, weil die
Historie an der gelöschten ID hängt.

### D3 — Tote Felder und ein widersprüchliches Lebensdauer-Versprechen ✅ behoben (mit Korrektur)

**Korrektur gegenüber der ursprünglichen Fassung:** Nur `keycloakSubject` war wirklich tot.
`keycloakSessionId` ist unter dem `keycloak`-Profil aktiv geschrieben (`KcTokenProvider.tokenFor`)
und gelesen (`JourneyService`, `Transition.Logout`, um genau die eine zugehörige Keycloak-Session
zu beenden) — der alte Klassenkommentar („moot anyway, since the KEYCLOAK channel never creates
an AuthContext") verwechselte zwei verschiedene Kanäle: den KEYCLOAK-Kanal selbst (der wirklich
nie einen `AuthContext` anlegt) mit dem APP-Kanal, dessen `AuthContext.keycloakSessionId` unter
dem `keycloak`-Profil sehr wohl gebraucht wird.

**Umsetzung:**
- `keycloakSubject` (Feld + Spalte `keycloak_subject`) entfernt — nirgends gelesen oder
  geschrieben (Migration `V37`).
- Klassenkommentar auf `AuthContext` korrigiert: beschreibt jetzt akkurat, dass
  `keycloakSessionId` unter `keycloak` tragend ist, statt es pauschal für „moot" zu erklären.
- Das zweite Teilproblem („`ChannelSession` bewusst kurzlebig, aber 30 Tage aufbewahrt") war eine
  veraltete `docs/07-betrieb.md`-Zeile, kein Code-Bug: Der 30-Tage-Wert in `RetentionJob` ist
  bewusst (siehe B3 — der Journey-Log wird über die Channel-Menge abgefragt, `JOURNEY_LOG_RETENTION`
  ist deshalb absichtlich gleich groß). Die Tabelle in `docs/07-betrieb.md` war zudem an einer
  zweiten Stelle bereits stale (`AttemptThrottle` als „kein Session-Cleanup" — von B3 überholt).
  Beide Zeilen korrigiert, `JourneyLogEntry`-Zeile ergänzt; Laufzeitverhalten unverändert.

---

## Empfohlene Reihenfolge

1. **A1, A2, A3** — aktive Sicherheitslücken. *(vollständig erledigt, Code + DDL.)*
2. **B3, A5** — unbegrenztes Datenwachstum und der Löschpfad-Rest. *(erledigt; die
   Cascade-Prüfung in `V30`/`V31` ergab dort keinen Handlungsbedarf.)*
3. **B1** — die verbliebene DoS-Fläche. *(erledigt; Migration `V34` angewendet, ein statt drei
   Abfragen, harte Kandidaten-Obergrenze.)*
4. **D1** — mit ADR-14 umgesetzt (Schema-Konsolidierung), B5 dabei teilweise. *(C1 ist mit A3 erledigt; C2 zu
   zwei Dritteln erledigt, siehe dort.)*

Erledigt (dritter/vierter/fünfter Durchgang): A4, A6, A7-Rest, B2 (teilweise), B4 (teilweise), B6,
C2 (teilweise), C3, C4, D3; mit ADR-14 zusätzlich D1 und B5 (teilweise). Bewusst zurückgestellt
(Produktions-/Architekturentscheidung, jeweils mit Begründung im Dokument): B5-Rest, D2. Damit ist das Review
inhaltlich abgeschlossen — jeder verbleibende offene Punkt trägt eine explizite Zurückstellungs-
begründung, keiner ist übersehen.

---

## Hinweis zur Prüftiefe

Die ursprüngliche Fassung dieses Dokuments entstand unter einer Content-Exclusion-Policy, die
mehrere Migrationsdateien (u. a. `V1__schema.sql`, `V30__add_account_attribute.sql`,
`V31__add_account_anchor.sql`) sowie Log-Ausgaben in `build/` gesperrt hatte; Aussagen zu Indizes
stützten sich auf Treffer einer Mustersuche, Aussagen zu Fremdschlüsseln/Cascades waren gar nicht
verifizierbar. In dieser Umgebung sind alle Migrationsdateien lesbar. Nachverifiziert:

- `V6__add_auth_email.sql`: Spaltenname `email_confirmed_at`, Indexname `idx_account_email` —
  beide Annahmen aus dem V33-Entwurf bestätigt.
- `V30__add_account_attribute.sql`, `V31__add_account_anchor.sql`: beide setzen
  `ON DELETE CASCADE` auf `account_id`. Die A5-Vermutung eines fehlenden Cascades war **falsch**
  und wurde korrigiert (siehe A5 oben).

Migration `V33__email_anchor_is_sole_uniqueness.sql` ist angelegt und verifiziert (`ddl-auto:
validate` erfolgreich).

Für B3 und A5 galt die Policy-Einschränkung erneut, aber folgenlos: Beide sind reine
Code-Änderungen ohne DDL. B1 ist die Ausnahme — der Fix ist ohne Migration nicht teilbar, deshalb
steht er dort vollständig vorbereitet statt halb angewandt. Ebenfalls in dieser Runde
richtiggestellt: A7 (Hash statt Klartext, siehe dort).

Stand der Testsuite nach B3/A5: **568 Tests grün** (565 vorher, plus drei Regressionstests — der
Journey-Log-Sweep in `RetentionJobTest`, die zweischlüsselige Löschung und der Nachweis, dass die
nicht kontobezogenen Throttle-Scopes unangetastet bleiben, beide in
`AccountDeletionServiceTest`).
