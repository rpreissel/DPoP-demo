# Review: Domänen- und DB-Modell (`account`, `orchestrator`)

Stand: 2026-09-16. Geprüfter Umfang: `src/main/kotlin/com/example/dpop/account/**`,
`src/main/kotlin/com/example/dpop/orchestrator/**`, `src/main/resources/db/migration/**`.

Bewertungsmaßstab war bewusst nicht „funktioniert die Demo", sondern die drei Eigenschaften, die
das Zielbild für sich beansprucht: **sicherheitskritisch**, **lange Lebensdauer**, **≥ 10 Mio.
Nutzer bei hoher Anmeldelast**. Das Modell ist konzeptionell überdurchschnittlich sauber
(Zustand statt Vererbung, Evidenz als *eine* Quelle, keine gespeicherten `next*`-Duplikate); die
Lücken liegen fast vollständig auf den Achsen **Nebenläufigkeit, Skalierung und
Lösch-/Aufbewahrungspfade**.

## Statuslegende

| Status | Bedeutung |
|---|---|
| ✅ behoben | Im Code/DDL umgesetzt, vollständige Testsuite grün (565 Tests) |
| 🟡 teilweise | Ein Anteil umgesetzt, ein Rest ist offen (jeweils benannt) |
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
`findAccountByEmail`/`existsByEmail`/`resolveAccountByEmail` laufen über den normalisierten Anchor;
`AccountRepository.findByEmail`/`existsByEmail` wurden entfernt, damit kein zweiter, schwächerer
Lesepfad zurückkehren kann. `account.email` bleibt rohe Projektionsspalte.

Umgesetzt (DDL): `V33__email_anchor_is_sole_uniqueness.sql` zieht fehlende Anchors kollisionsfrei
nach (first writer wins, wie der Live-Schreibpfad) und tauscht den UNIQUE-Index auf der Rohspalte
gegen einen einfachen Index. Die drei Annahmen aus dem ursprünglichen SQL-Entwurf sind am
tatsächlichen Schema verifiziert: `V31` enthielt noch keinen Backfill (Schritt 1 ist kein No-Op),
die Spalte heißt `email_confirmed_at` (`V6` Zeile 6), der Index heißt `idx_account_email` (`V6`
Zeile 9). `./gradlew :test` bleibt grün (565 Tests), `ddl-auto: validate` läuft weiterhin durch.

### A4 — Anchor-Reihenfolge hängt an einer nicht garantierten Eigenschaft ⬜ offen

`IdentityMatchingService.resolveByAnchor` iteriert `Set<Claim>` und verlässt sich laut Kommentar
darauf, dass `toSet()` die Attestierungsreihenfolge erhält („der stärkste zuerst attestierte Anchor
wird zuerst konsultiert"). `Set` gibt keine Reihenfolge zu; übergibt ein Aufrufer ein `HashSet`,
verschwindet diese Sicherheitsaussage lautlos und der schwächere Anchor kann gewinnen.

Empfehlung: explizites Ranking über `AnchorClass` statt Iterationsreihenfolge — dann ist die
Stärkeordnung eine Eigenschaft des Codes, nicht des Aufrufers.

### A5 — Löschpfad unvollständig, aber nur bei `journey_log`/`attempt_throttle` (DSGVO) ⬜ offen

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
`DeviceAccountLink`, Channels, `AuthContext`, `AuthEvidence` — **nicht** aber `journey_log` und
`attempt_throttle`. Beide enthalten identitätsnahe Daten (`journey_log.detail`-JSON,
`attempt_throttle.subject` bei `CONTACT_SEND` rohe Telefonnummern/E-Mails, siehe A7) und keine
Beziehung zu `account`, die eine Cascade tragen könnte — das ist eher B3/eine explizite
Aufräum-Query im Löschpfad als ein fehlender Fremdschlüssel.

Empfehlung: `AccountDeletionService` um `journey_log`/`attempt_throttle`-Aufräumung für die
gelöschte `accountId` ergänzen, unabhängig von der Aufbewahrungsfrist aus B3.

### A6 — Interne Konto-IDs verlassen das System ⬜ offen

`Resolution.Ambiguous(candidates)` transportiert eine Liste interner, fortlaufender `accountId`s
nach außen. `channelSessionId` ist ausdrücklich opaque gehalten — `accountId`/`personId` sind es
nicht. Empfehlung: nach außen nur die Anzahl, Kandidaten intern (oder opaque Referenz).

### A7 — Rohe Kontaktdaten als Primärschlüsselbestandteil ⬜ offen

`ThrottleScope.CONTACT_SEND` speichert Telefonnummer bzw. E-Mail im Klartext in
`attempt_throttle.subject` — unbegrenzt lange, ohne Aufbewahrungsgrenze (siehe auch A5/B3).
Empfehlung: gepfefferter Hash, analog zur bereits vorhandenen `dpop.secrets.otp-pepper`-Begründung
in `application.yml`.

---

## B) Skalierung (10 Mio. Nutzer, hohe Anmeldelast)

### B1 — Full-Table-Scan im Identifikationspfad ⬜ offen

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

### B2 — `allAccountIds()` lädt alle Konten in den Heap ⬜ offen

`AccountService.allAccountIds()` ruft `accountRepository.findAll()` und mappt danach auf die ID —
lädt also 10 Mio. `Account`-Entities inklusive beider JSON-Collections. Empfehlung: projizierende,
paginierte Query (`select a.id from Account a`, Stream/Slice).

### B3 — `journey_log` hat keinerlei Aufbewahrungsgrenze ⬜ offen

`RetentionJob` deckt `ToolSession`, `AuthJourney`, `ChannelSession`, `AuthContext`, `AuthEvidence`
und `SessionEvent` ab — `journey_log` kommt darin **nicht vor**. Die Tabelle bekommt pro
Journey-Schritt eine Zeile samt `detail`-JSON und ist laut eigener Doku ein Debug-/Demo-Trace. Bei
„vielen Anmeldungen" ist sie mit Abstand die größte Tabelle des Systems und enthält zugleich
identitätsnahe Daten (siehe A5). Gleiches gilt für `attempt_throttle`.

### B4 — `RetentionJob` ohne Batching ⬜ offen

`findByExpiresAtBefore` lädt alle fälligen Zeilen als vollständige Entities in **einer**
Transaktion; anschließend werden `IN`-Listen unbekannter Größe gebaut
(`deleteByJourneyIdIn`, `deleteAllByIdInBatch`). Das skaliert nicht über einen Ausfalltag hinweg
und läuft zusätzlich in Parameter-Obergrenzen. Empfehlung: Paging mit fester Batchgröße und
Schleife bis leer.

### B5 — `dpop_proof_replay` als Durchsatzdeckel ⬜ offen

Die Mechanik ist richtig gedacht (PK-Insert *ist* die Prüfung, kein Read-then-Write, überlebt
Neustart und gilt über Replicas). Der Preis: ein INSERT pro authentifiziertem Request auf eine
global heiße Tabelle mit `VARCHAR(255)`-Primärschlüssel. Empfehlung für den Produktivpfad:
Hash-PK (`BINARY(32)`/`UUID`) plus Zeitpartitionierung, alternativ ein persistenter KV-Store.
`jti` ist in beiden Validatoren Pflicht — der naheliegende Verdacht „Proof ohne `jti` sperrt das
Gerät über den Schlüssel `thumbprint:null` dauerhaft aus" wurde geprüft und trifft **nicht** zu.

### B6 — `availableClientTools` ist `FetchType.EAGER` ⬜ offen

`ChannelSession.availableClientTools` ist eine `@ElementCollection(fetch = EAGER)` und erzwingt
damit auf dem heißesten Pfad des Systems einen zusätzlichen Join/Query — für einen Wert, der laut
eigener Dokumentation über die gesamte Kanal-Lebenszeit **konstant** ist. Empfehlung: JSON-Spalte
direkt auf `channel_session`.

---

## C) Vereinheitlichung

### C1 — E-Mail existiert vierfach ✅ behoben

`account.email` + `account.emailConfirmedAt` + `account_anchor(EMAIL)` + `account_attribute(EMAIL)`.
Sauberes Zielbild: `account_attribute` = Provenienz (append-only), `account_anchor` =
Auflösung **und** Eindeutigkeit, Spalte = reine Projektion ohne eigene Unique-Zusage.
Mit A3 ist das Zielbild jetzt vollständig erreicht: Lesepfad und Eindeutigkeitsautorität laufen
über den Anchor, die Spalte trägt nur noch die (unbenutzte) Rohwert-Historie. Die vierfache
Existenz der E-Mail als solche bleibt bestehen — das ist die bewusste Drei-Schichten-Trennung
(Provenienz/Auflösung/Projektion), keine offene Inkonsistenz mehr.

### C2 — Typisierung zwischen den Modulen inkonsistent ⬜ offen

`AccountAttribute.attributeType`/`trustAnchor` und `AccountAnchor.anchorType` sind `String`,
obwohl `AttributeType`, `AnchorType` und `AnchorClass` als Typen existieren — während das
`orchestrator`-Modul durchgängig `@Enumerated(EnumType.STRING)` verwendet. Über eine Laufzeit von
10+ Jahren wird aus einer Umbenennung so stille Datenkorruption statt eines Compilerfehlers.

### C3 — Spaltenname trägt die falsche Bedeutung ⬜ offen

`ChannelSession.channelAnchor` liegt auf der Spalte `kc_session_id`, während das *tatsächliche*
Keycloak-Session-Feld `durableKcSessionId` auf `kc_durable_session_id` liegt. Das ist exakt die
Verwechslung, vor der der Doc-Kommentar über 20 Zeilen warnt — in SQL, Betrieb und Forensik ist
diese Warnung aber nicht sichtbar. Empfehlung: Spalte auf `channel_anchor` umbenennen.

### C4 — `findOrCreateAccount` verliert das Rennen mit einem 500er ⬜ offen

Der Kommentar verweist korrekt auf `ux_account_person_id` (V32) als DB-seitigen Rennabschluss —
die Verletzung wird aber nicht gefangen. Der unterlegene von zwei gleichzeitigen Step-up-Kanälen
bekommt also einen Serverfehler statt des existierenden Kontos. Empfehlung: Constraint-Verletzung
fangen und einmalig neu lesen.

---

## D) Struktur / unnötige Komplexität

### D1 — `authenticationMethods` als JSON-Liste auf einer versionierten Zeile ⬜ offen

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

### D2 — Kein Konto-Lebenszyklus, kein Merge-Pfad ⬜ offen

`Account` kennt keinen Status (gesperrt, deaktiviert, verstorben) und kein `merged_into`.
Gleichzeitig ist „mehrdeutig / Merge-Konflikt" ein explizit modellierter Fall, der laut ADR-11
heute nur nach oben abgelehnt wird. Über die angestrebte Lebensdauer entsteht Merge-Bedarf
zwangsläufig — ohne `merged_into` gibt es später keinen verlustfreien Weg dorthin, weil die
Historie an der gelöschten ID hängt.

### D3 — Tote Felder und ein widersprüchliches Lebensdauer-Versprechen ⬜ offen

`AuthContext.keycloakSessionId`/`keycloakSubject` sind laut eigenem Kommentar dauerhaft ungenutzt
(„moot anyway"). `ChannelSession` ist dokumentiert „bewusst kurzlebig", wird aber 30 Tage
aufbewahrt. Beides sind Einladungen zur späteren Fehlinterpretation.

---

## Empfohlene Reihenfolge

1. **A1, A2, A3** — aktive Sicherheitslücken. *(vollständig erledigt, Code + DDL.)*
2. **B1, B3** — DoS-Fläche und unbegrenztes Datenwachstum.
3. **A5** — verbleibender Löschpfad-Rest (`journey_log`/`attempt_throttle`); die
   Cascade-Prüfung in `V30`/`V31` ist erledigt und ergab: kein Handlungsbedarf dort.
4. **C2, D1** — Strukturbereinigung, bevor weitere Verfahren auf das Modell aufsetzen. *(C1 ist
   mit A3 erledigt.)*

A4, A6, A7, B2, B4–B6, C3, C4, D2, D3 sind einzeln klein und können jederzeit eingeschoben werden.

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

Migration `V33__email_anchor_is_sole_uniqueness.sql` ist angelegt und verifiziert (565 Tests
grün, `ddl-auto: validate` erfolgreich).
