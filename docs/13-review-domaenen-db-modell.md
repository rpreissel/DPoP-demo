# Review: Domänen- und DB-Modell (`account`, `orchestrator`)

Stand: 2026-09-16. Geprüfter Umfang: `src/main/kotlin/com/example/dpop/account/**`,
`src/main/kotlin/com/example/dpop/orchestrator/**`, `src/main/resources/db/migration/**`.

Bewertungsmaßstab waren nicht „funktioniert die Demo", sondern die drei Eigenschaften, die das
Zielbild für sich beansprucht: **sicherheitskritisch**, **lange Lebensdauer**, **≥ 10 Mio. Nutzer
bei hoher Anmeldelast**. Das Modell ist konzeptionell sauber (Zustand statt Vererbung, Evidenz als
*eine* Quelle, keine gespeicherten `next*`-Duplikate); die Lücken lagen fast vollständig auf den
Achsen **Nebenläufigkeit, Skalierung und Lösch-/Aufbewahrungspfade**.

**Gesamtstatus**: inhaltlich abgeschlossen. Alle Befunde sind umgesetzt, widerlegt oder mit
expliziter Begründung zurückgestellt; keiner ist übersehen. Testsuite zuletzt 569 Tests grün.
Die Nachträge zu A3 und C1 (Bindungsstärke als eigene Achse, Wegfall von `AnchorType`) sind in
[ideen/account-attribute-und-trust-vereinheitlichen.md](ideen/account-attribute-und-trust-vereinheitlichen.md)
ausgeführt.

> **Hinweis (2026-09-17, [ADR-14](12-entscheidungen.md))**: Die hier zitierten Migrationen `V3`–`V38`
> existieren nicht mehr; sie sind in `V1__schema.sql` zusammengeführt. Die Verweise bleiben als
> Befundhistorie stehen. Mit derselben Konsolidierung sind D1 umgesetzt und B5 teilweise, außerdem
> entfallen die Projektionsspalten `account.person_id`/`email`/`email_confirmed_at` (siehe C1).

## Befunde

| ID | Befund | Status | Umsetzung |
|---|---|---|---|
| A1 | `AttemptCounter` zählte per Read-Modify-Write ohne `@Version`, das effektive Throttle-Budget war dadurch `Limit × Parallelität`. | behoben | `AttemptThrottleRepository` zählt nur noch über atomare `UPDATE`s (Fehlversuch, Rolling Window, Reset), Lockout-Entscheidung aus dem Nach-Inkrement-Wert; fehlende Zeile über `AttemptThrottleRowInitializer` (`REQUIRES_NEW`). Optimistic Locking wäre hier falsch — der unterlegene Schreiber würde nicht gezählt. |
| A2 | `AccountService.recordAnchor` brach bei fremdem Anchor still ab, schrieb die Projektionsspalte `account.email` aber trotzdem. | behoben | Fremdbesitz wirft `IdentityConflictException` (WARN), Eigenbesitz bleibt idempotent; in `consolidateOwnedColumn` wird der Anchor **vor** der Projektionsspalte geschrieben. |
| A3 | Zwei widersprüchliche Eindeutigkeiten für dieselbe E-Mail (UNIQUE auf roher `account.email` vs. normalisiertem `account.anchor`), Lesepfad `findByEmail` case-sensitiv. | behoben | `account.anchor` ist alleinige Auflösungs- und Eindeutigkeitsautorität; `AccountRepository.findByEmail`/`existsByEmail` entfernt. Migration `V33__email_anchor_is_sole_uniqueness.sql` zieht Anchors nach (first writer wins) und ersetzt den UNIQUE-Index auf der Rohspalte durch einen einfachen. |
| A4 | `IdentityMatchingService.resolveByAnchor` verließ sich auf die nicht garantierte Iterationsreihenfolge eines `Set`. | behoben | Explizite Sortierung `claims.sortedByDescending { it.attributeType.anchorBindingStrength ?: 0 }` (`tool_api/AttributeRules.kt`) — zuvor kurzzeitig nach `anchorClassOf(...).rank`, was fachlich die falsche Achse war. |
| A5 | Löschpfad unvollständig: `orchestrator.journey_log` und `orchestrator.attempt_throttle` blieben beim Kontolöschen stehen (DSGVO). | behoben, Teilannahme widerlegt | Die vermutete fehlende Cascade auf `account.attribute`/`account.anchor` trifft **nicht** zu (`ON DELETE CASCADE` in `V30`/`V31`). `AccountDeletionService.deleteAccount` räumt `journey_log` über `account_id` **und** die Channel-Sessions in **einer** Anweisung ab (`flushAutomatically` + `clearAutomatically`), `attempt_throttle` nur in den kontobezogenen Scopes (`ACCOUNT`, `ACCOUNT_SEND`). `OrchestratorExceptionHandler` loggt `409 CONCURRENT_MODIFICATION` jetzt mit Entity und Id. |
| A6 | `Resolution.Ambiguous` transportierte interne, fortlaufende `accountId`s nach außen. | behoben | `Resolution.Ambiguous` trägt `candidateCount: Int` statt `candidates: List<Long>`. |
| A7 | Kontaktadressen als Primärschlüsselbestandteil im `CONTACT_SEND`-Scope, ungepfeffert gehasht. | behoben, Teilannahme widerlegt | „Im Klartext" traf **nicht** zu (SHA-256 in `SendThrottleService.hash`). Der Hash ist jetzt HMAC-SHA256 unter `dpop.secrets.otp-pepper`; die Aufbewahrungshälfte ist mit B3 erledigt. |
| B1 | Full-Table-Scan im Identifikationspfad: drei nicht sargable `lower(trim(...))`-Abfragen, Schnittmenge in der Anwendung, ohne Obergrenze — trivialer DoS vor jeder Authentisierung. | behoben | Migration `V34__account_attribute_normalized_value.sql` (Spalte `normalized_value`, Index `idx_account_attribute_type_normalized`); `AccountAttribute` füllt sie über `@PrePersist`/`@PreUpdate` plus `normalize()`. Eine Abfrage `findAccountIdsMatchingAllThree` ersetzt die drei; `CANDIDATE_LIMIT` 50 + 1, Überschreitung ergibt `Resolution.Ambiguous`, nie einen Treffer. |
| B2 | `AccountService.allAccountIds()` lud über `findAll()` alle Konten samt JSON-Collections in den Heap. | teilweise behoben | `AccountRepository.findAllIds()` (projizierende Query). Offen bleibt echte Pagination/Streaming — der einzige Aufrufer `KeycloakAccountSyncService.syncAll` lädt pro ID ohnehin das volle `AccountProfile`; das wäre ein Umbau des Reconciliation-Ablaufs. |
| B3 | `orchestrator.journey_log` (und `attempt_throttle`) ohne jede Aufbewahrungsgrenze, zugleich identitätsnah. | behoben | `RetentionJob` kehrt beide rein altersbasiert aus: `JOURNEY_LOG_RETENTION` 30 Tage (bewusst gleich `CHANNEL_SESSION_RETENTION`; Audit-Trail bleibt `SessionEvent` mit 90 Tagen), `ATTEMPT_THROTTLE_RETENTION` 7 Tage, wobei `deleteStaleCounters` laufende `locked_until`-Zeilen nicht anrührt. Bulk-Statements statt abgeleiteter `deleteBy…`-Methoden. |
| B4 | `RetentionJob` lud alle fälligen Zeilen als Entities in einer Transaktion und baute `IN`-Listen unbekannter Größe. | teilweise behoben | `deleteExpiredJourneys`/`deleteExpiredChannels` paginieren mit `RETENTION_BATCH_SIZE = 500`. Offen: `confirmedDeadKcChannels` (bräuchte Keyset-Pagination, da gefilterte Zeilen stehen bleiben) und unabhängige Transaktionen je Batch. |
| B5 | `orchestrator.dpop_proof_replay`: ein INSERT pro authentifiziertem Request auf eine global heiße Tabelle mit `VARCHAR(255)`-Primärschlüssel. | teilweise behoben, Rest zurückgestellt | Primärschlüssel ist seit ADR-14 `proof_hash VARCHAR(64)` = SHA-256(`thumbprint:jti`) aus `DpopReplayProtectionService`. Offen: Zeitpartitionierung bzw. separater KV-Store (siehe unten). Der Verdacht „Proof ohne `jti` sperrt das Gerät dauerhaft aus" ist geprüft und trifft **nicht** zu — `jti` ist in beiden Validatoren Pflicht. |
| B6 | `ChannelSession.availableClientTools` war `@ElementCollection(fetch = EAGER)` auf dem heißesten Pfad, obwohl über die Kanal-Lebenszeit konstant. | behoben | Migration `V35` fügt `orchestrator.channel_session.available_tools` als `JSON`-Spalte hinzu und löscht die alte Collection-Tabelle; Entity nutzt `@JdbcTypeCode(SqlTypes.JSON)`. |
| C1 | E-Mail existierte vierfach (`account.email`, `emailConfirmedAt`, `account.anchor(EMAIL)`, `account.attribute(EMAIL)`). | behoben | Mit A3 laufen Lesepfad und Eindeutigkeit über den Anchor. Seit ADR-14 sind die Projektionsspalten `email`/`email_confirmed_at`/`person_id` entfallen; es bleiben `account.attribute` (Provenienz, Rohwert) und `account.anchor` (normalisierter Wert, Auflösung, Eindeutigkeit), `AccountProfile` liest aus dem Anker. |
| C2 | Typisierung zwischen den Modulen inkonsistent: `attributeType`/`trustAnchor`/`anchorType` als `String`, während `orchestrator` durchgängig `@Enumerated(EnumType.STRING)` nutzt. | teilweise behoben | `attributeType` über `AttributeTypeConverter` typisiert (`wireName`-Rundlauf, `fromWireName` wirft hart) — `@Enumerated(STRING)` wäre unbrauchbar, da die Spalten seit Jahren Kleinschreibung (`person_id`, `email`) tragen. `AnchorType`/`AnchorTypeConverter` sind entfallen, `account.anchor` nutzt denselben Konverter; Anker-Regeln liegen in `tool_api/AttributeRules.kt`. **Nicht umgesetzt:** die Quelle bleibt `String` (`AccountAttribute.claimSource`, Spalte `claim_source`) — `ClaimSource` ist eine `@JvmInline value class`, und Hibernate reicht dem `AttributeConverter` beim Schreiben eine rohe `String`-Instanz durch (`JpaSystemException`, verifiziert). Siehe [ADR-13](12-entscheidungen.md). |
| C3 | `ChannelSession.channelAnchor` lag auf der Spalte `kc_session_id`, verwechselbar mit `durableKcSessionId`. | behoben | Migration `V36` benennt Spalte und Index um (`channel_anchor`, `idx_channel_session_channel_anchor`), `@Column(name = ...)` folgt. |
| C4 | `findOrCreateAccount` fing die `ux_account_person_id`-Verletzung nicht ab; der Verlierer zweier gleichzeitiger Step-up-Kanäle bekam einen 500er. | behoben | Account-Anlage und Claim-/Anker-Übernahme laufen gemeinsam in der Journey-Transaktion (die zwischenzeitliche `REQUIRES_NEW`-Erzeugung ist entfernt). Der Verlierer rollt vollständig zurück und erhält `409`; nur bekannte Bindungs-Unique-Constraints werden zugeordnet, andere Integritätsfehler bleiben sichtbar. |
| D1 | `authenticationMethods` als JSON-Liste auf einer versionierten Zeile: Contention über `@Version`, „alle Konten mit Methode X" nicht abfragbar, Korrektheit hing am Dirty-Checking-Kommentar. | behoben | Mit ADR-14: `account.auth_method` (eine Zeile je Methodeninstanz, `id UUID`, `enrollment_type`/`enrollment_id`, `deactivated_at` mit CHECK, Indizes `(account_id, method)` und `(enrollment_type, enrollment_id)`). Die Nebenläufigkeitssemantik bleibt erhalten (`OPTIMISTIC_FORCE_INCREMENT` auf der Kontozeile), Lesen schreibt nie mehr. `identifications` ist analog `account.identification` geworden (append-only). |
| D2 | `Account` kennt keinen Status (gesperrt, deaktiviert, verstorben) und kein `merged_into`. | zurückgestellt | Siehe unten. |
| D3 | Tote Felder auf `AuthContext` und ein widersprüchliches Lebensdauer-Versprechen für `ChannelSession`. | behoben, Teilannahme widerlegt | Nur `keycloakSubject` war tot (entfernt, Migration `V37`); `keycloakSessionId` ist unter dem `keycloak`-Profil aktiv (`KcTokenProvider.tokenFor`, `Transition.Logout`), der Klassenkommentar verwechselte APP- und KEYCLOAK-Kanal und ist korrigiert. Das Lebensdauer-Versprechen war eine veraltete Zeile in [07-betrieb.md](07-betrieb.md), kein Code-Bug — korrigiert, Laufzeitverhalten unverändert. |

## Bewusst zurückgestellt

Beide Punkte sind Architektur-/Infrastrukturentscheidungen, kein lokal abschließbarer Fix; sie
verdienen einen eigenen Durchgang mit vorab getroffener Entwurfsentscheidung. Siehe auch
[ADR-14](12-entscheidungen.md) und den Abschnitt „Erkannte, bewusst zurückgestellte Verbesserungen"
in [12-entscheidungen.md](12-entscheidungen.md).

**B5-Rest — Skalierung von `orchestrator.dpop_proof_replay`** (siehe [09-dpop.md](09-dpop.md)
Abschnitt 2): Die Mechanik ist richtig gedacht (der PK-Insert *ist* die Prüfung, kein
Read-then-Write, überlebt Neustart, gilt über Replicas). Die verbleibende Empfehlung — Hash-PK mit
Zeitpartitionierung oder ein separater persistenter KV-Store — ist ausdrücklich für den
Produktivpfad formuliert und lässt sich in dieser H2-Demo-Umgebung nicht sinnvoll klein umsetzen.

**D2 — Konto-Lebenszyklus und Merge-Pfad**: Ein Status-Enum plus `merged_into` berührt jeden
Lesepfad, der voraussetzt, dass eine `account`-Zeile immer „die eine gültige" ist (Login,
Step-up-Auflösung, `IdentityMatchingService`, Admin-Sync), plus die Frage, was beim Merge mit den
Anchor-/Attribute-/Auth-Method-Zeilen zweier Konten passiert. ADR-11 weist einen
`person_id`-Konflikt bewusst ab, statt zu mergen; über die angestrebte Lebensdauer entsteht
Merge-Bedarf aber zwangsläufig, und ohne `merged_into` gibt es dann keinen verlustfreien Weg
dorthin, weil die Historie an der gelöschten ID hängt.

## Hinweis zur Prüftiefe

Die ursprüngliche Fassung entstand unter einer Content-Exclusion-Policy, die mehrere
Migrationsdateien (u. a. `V1__schema.sql`, `V30__add_account_attribute.sql`,
`V31__add_account_anchor.sql`) gesperrt hatte; Aussagen zu Indizes stützten sich auf Mustersuchen,
Aussagen zu Fremdschlüsseln/Cascades waren nicht verifizierbar. Inzwischen sind alle
Migrationsdateien gelesen und die Annahmen zu `V6` (`email_confirmed_at`, `idx_account_email`) und
zu den Cascades in `V30`/`V31` nachverifiziert — Letzteres hat die ursprüngliche A5-Vermutung
widerlegt. B1 war die einzige Ausnahme, die ohne schreibbaren Migrationsordner nicht teilbar war.
