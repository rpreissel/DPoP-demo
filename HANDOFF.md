# Handoff: Fortsetzung Domänen-/DB-Modell-Review (`account`, `orchestrator`)

Dieses Dokument richtet sich an den nächsten Agenten, der diese Sicherheitsarbeit fortsetzt.
Erledigt und committet sind inzwischen **A1–A3** (erster Durchgang), **B3** und der
**A5-Rest** (zweiter Durchgang), **B1** (dritter Durchgang) sowie **A4, A6, A7-Rest, B2 (teilweise),
B4 (teilweise), B6, C3, C4, D3** (vierter Durchgang — alle kleinen Einzelbefunde abgearbeitet).
Als Nächstes dran sind **C2, D1** — die einzigen noch offenen Punkte im gesamten Review, neben den
bewusst zurückgestellten **B5, D2** (siehe unten).

- Vollständige Befundliste mit aktuellem Status: [`docs/13-review-domaenen-db-modell.md`](docs/13-review-domaenen-db-modell.md)
- Projektkontext: [`docs/00-agent-quickstart.md`](docs/00-agent-quickstart.md)
- Kanonische Arbeitsanweisung: `AGENTS.md` (konservatives Git-Profil — nicht pushen ohne
  ausdrückliche Anweisung)

## 1. Was bereits erledigt ist

19 Befunde aus dem Domänen-/DB-Modell-Review von `account`/`orchestrator` unter den Annahmen
„sicherheitskritisch, lange Lebensdauer, ≥ 10 Mio. Nutzer, hohe Anmeldelast". Vollständig behoben:

- **A1** — Throttle-Zähler liefen per Read-Modify-Write und waren durch Parallelisierung
  umgehbar. Jetzt atomare `UPDATE`-Statements unter Zeilensperre.
- **A2** — Anchor-Konflikt mit fremdem Konto wurde still verschluckt, Projektionsspalte trotzdem
  geschrieben. Jetzt `IdentityConflictException`, Anchor vor Projektionsspalte geschrieben.
- **A3** — Zwei widersprüchliche Eindeutigkeiten für dieselbe E-Mail (roher UNIQUE-Index vs.
  normalisierter Anchor). Lesepfad läuft jetzt ausschließlich über den Anchor; Migration `V33`
  zieht fehlende Anchors kollisionsfrei nach und ersetzt den UNIQUE-Index auf der Rohspalte.
- **C1** — folgt automatisch aus A3 (E-Mail-Vierfachexistenz ist jetzt sauber geschichtet:
  Provenienz/Auflösung/Projektion statt Widerspruch).
- **A5** — die vermutete Lücke „fehlendes `ON DELETE CASCADE`" wurde geprüft und **widerlegt**
  (`V30`/`V31` setzen beide bereits `ON DELETE CASCADE`). Der DSGVO-Rest ist jetzt umgesetzt:
  `AccountDeletionService` löscht `journey_log` über **zwei** Schlüssel (Konto **und** dessen
  Channel-Sessions — Einträge vor der Kontobindung tragen `account_id = NULL`) sowie
  `attempt_throttle` in den kontobezogenen Scopes. Die übrigen Scopes bleiben absichtlich stehen,
  sonst wäre Kontolöschung ein Weg, fremde Throttle-Budgets zurückzusetzen.
- **B3** — `RetentionJob` kehrt jetzt auch `journey_log` (30 Tage) und `attempt_throttle`
  (7 Tage, aktive Sperren ausgenommen) aus. Beide hängen an keinem Fremdschlüssel, der sie
  mitnehmen könnte.
- **A7 richtiggestellt** — der Befund behauptete rohe Kontaktdaten in `attempt_throttle.subject`;
  tatsächlich steht dort ein SHA-256-Hash. Offen bleibt nur der fehlende Pepper.
- **B1** — `AccountAttributeRepository.findAccountIdsByTypeAndNormalizedValue` verglich
  `lower(trim(value))` ohne nutzbaren Index; `IdentityMatchingService` setzte drei solche
  Abfragen ab und schnitt in der Anwendung — trivial auslösbarer DoS im Pfad vor jeder
  Authentisierung. Migration `V34__account_attribute_normalized_value.sql` (Spalte
  `normalized_value`, Index `(attribute_type, normalized_value, account_id)`) angewendet, Typ
  `VARCHAR(255)` gegen `V30` verifiziert. `AccountAttribute` füllt `normalized_value` über einen
  `@PrePersist`/`@PreUpdate`-Hook, der dieselbe `companion object`-Funktion `normalize()` nutzt
  wie `IdentityMatchingService` beim Aufbau der Suchparameter — die Regel existiert damit an
  genau einer Stelle. Repository hat jetzt eine Abfrage (`findAccountIdsMatchingAllThree`,
  `group by account_id having count(distinct attribute_type) = 3`) statt drei; In-Memory-
  `intersect` ist raus. `IdentityMatchingService` holt `CANDIDATE_LIMIT + 1` (50 + 1) Zeilen;
  wird die Obergrenze überschritten, ist das Ergebnis `Resolution.Ambiguous` (auf 50 gekappt) —
  nie ein Treffer. Neuer Test für den Obergrenze-Fall.
- **A4** — `resolveByAnchor` verließ sich auf `Set<Claim>`-Iterationsreihenfolge statt auf ein
  explizites Ranking. Iteriert jetzt `sortedByDescending { anchorClassOf(it.trustAnchor).rank }`.
- **A6** — `Resolution.Ambiguous` transportierte interne `accountId`s nach außen, obwohl kein
  Aufrufer je mehr als die Anzahl brauchte. Trägt jetzt nur `candidateCount: Int`.
- **A7-Rest** — `SendThrottleService` hashte `CONTACT_SEND`-Kontaktadressen ungepeffert. Nutzt
  jetzt HMAC-SHA256 unter demselben `dpop.secrets.otp-pepper` wie `TanGenerator`/`EmailCodeGenerator`.
- **B2 (teilweise)** — `AccountRepository.findAllIds()` (projizierende Query) ersetzt
  `findAll().mapNotNull { it.id }`. Echte Pagination bleibt offen (siehe Dokument: der einzige
  Aufrufer lädt ohnehin pro ID das volle Profil in derselben Schleife).
- **B4 (teilweise)** — `RetentionJob` paginiert jetzt mit fester Batchgröße (500) über
  `deleteExpiredJourneys`/`deleteExpiredChannels`. Der KEYCLOAK-Frühräum-Pfad
  (`confirmedDeadKcChannels`) bleibt bewusst unbatched (Teilfilterung nach dem Laden macht das
  einfache Requery-Muster dort unsicher — bräuchte Keyset-Pagination).
- **B6** — `ChannelSession.availableClientTools` war `@ElementCollection(fetch = EAGER)`.
  Migration `V35` macht daraus eine `JSON`-Spalte direkt auf `channel_session`.
- **C3** — Spalte `kc_session_id` (trug `channelAnchor`, nicht die Keycloak-Session) auf
  `channel_anchor` umbenannt (Migration `V36`, inkl. Index).
- **C4** — `findOrCreateAccount` fing die `ux_account_person_id`-Kollision (V32) nicht ab, der
  Verlierer eines Rennens bekam einen 500er. Neue Bean `AccountRaceSafeCreator.createIfAbsent`
  (`REQUIRES_NEW`, eigenes Bean wegen Self-Invocation, analog `AttemptThrottleRowInitializer`).
- **D3** — `AuthContext.keycloakSubject` war wirklich tot (entfernt, Migration `V37`);
  `keycloakSessionId` dagegen ist unter `keycloak` aktiv genutzt — Klassenkommentar korrigiert.
  Der vermeintliche Aufbewahrungswiderspruch war eine veraltete `docs/07-betrieb.md`-Zeile
  (Laufzeitverhalten unverändert), dort korrigiert.

Verifiziert: `./gradlew :test` → **BUILD SUCCESSFUL, 570 Tests, 0 Fehler**. `ddl-auto: validate`
läuft durch.

## 2. Was noch offen ist

1. **C2, D1 — als Nächstes dran, letzte offene Punkte im Review.** Strukturbereinigung
   (String- statt Enum-Typisierung zwischen den Modulen; `authenticationMethods` als JSON-Liste
   auf einer versionierten Zeile — teuerste Entwurfsentscheidung im Modell, siehe D1 im Dokument
   für die drei Konsequenzen).
2. **B5, D2 — bewusst zurückgestellt, keine Einzelbefunde mehr.** B5 (`dpop_proof_replay`
   Hash-PK/Partitionierung oder KV-Store) ist explizit eine Produktionsinfrastruktur-Entscheidung.
   D2 (Konto-Lebenszyklus/Merge-Pfad) berührt jeden Lesepfad, der eine `account`-Zeile als „die
   eine gültige" voraussetzt — verdient einen eigenen, geplanten Durchgang, siehe Begründung im
   Dokument.

## 3. Arbeitsweise

- Doku (`docs/`) beschreibt das Zielbild; bei Abweichung hat sie Vorrang vor dem Code — aber
  prüfen, ob die Doku nicht selbst stale ist (siehe D3-Fallstrick unten), bevor man Laufzeit-
  verhalten danach ändert.
- Nach jeder Änderung `./gradlew :test` — muss grün bleiben (aktuell 570 Tests).
- `bd` (beads) für Aufgaben-Tracking; `bd prime` für Workflow-Details. **Achtung:** In der
  Umgebung des zweiten Durchgangs war `bd` wegen eines Schema-Skews (DB auf v65, Binary kennt
  v53) nicht benutzbar; das Tracking lief deshalb über das Review-Dokument. Vor der Nutzung
  prüfen und ggf. den Recovery-Guide aus der `bd`-Fehlermeldung fahren.
- Konservatives Git-Profil: committen ja, **pushen nur auf ausdrückliche Anweisung**.

## 4. Fallstricke, die Zeit gekostet haben

Alle drei sind im Code an der jeweiligen Stelle dokumentiert — hier nur als Vorwarnung:

- **Bulk-Statements auf `journey_log` müssen eine einzige Anweisung sein.** Jede Bulk-Mutation
  löst vorher einen Auto-Flush aus; bei zwei Anweisungen schreibt der zweite Flush einen Eintrag
  fort, den die erste schon gelöscht hat → `Unexpected row count (expected 1 but was 0)`, nach
  außen ein falscher `409 CONCURRENT_MODIFICATION` auf genau der Anfrage, die löschen wollte.
- **`flushAutomatically` und `clearAutomatically` gehören dort zusammen.** Ohne Clear bleiben die
  gelöschten Einträge verwaltet, und da `detail` eine veränderliche JSON-Map ist, flusht die
  nächste Abfrage ein UPDATE gegen nicht mehr existierende Zeilen. Ohne den Flush davor verwirft
  das Clear die noch offenen Channel-Logouts. Umgekehrt sind dieselben Flags auf
  `AttemptThrottleRepository` **schädlich** — dort zerreißen sie den Persistence-Context und
  erzeugen falsche 409er. Also bewusst pro Query entscheiden, nicht pauschal.
- **H2 2.4.240 kennt `INSERT … ON CONFLICT DO NOTHING` nicht.** Deshalb das portable
  Upsert-Muster in `AttemptThrottleRowInitializer` (eigene Bean wegen `REQUIRES_NEW`; Self-
  Invocation würde den Spring-Proxy umgehen).
- **Nach `git am` eines größeren Patches kann der Kotlin-Incremental-Compile-Cache stale
  Fehler werfen**, die mit dem eigenen Diff nichts zu tun haben (`Unresolved reference` auf
  längst vorhandene Funktionen aus einem anderen Modul). `./gradlew compileKotlin --rerun`
  (oder `--rerun-tasks`) einmal fahren, bevor man dem Fehler im eigenen Code hinterherjagt.
- **„Doku hat Vorrang vor Code" gilt nicht blind.** Der D3-Befund unterstellte, `ChannelSession`s
  30-Tage-Retention widerspreche der Doku („bewusst kurzlebig, 24h"). Die Code-Seite war die
  bewusste, im eigenen KDoc begründete Entscheidung (an B3 gekoppelt); `docs/07-betrieb.md` war
  stale — an einer zweiten Stelle in derselben Tabelle sogar nachweisbar (der
  `AttemptThrottle`-Eintrag widersprach dem bereits committeten B3). Vor einer Laufzeitänderung
  wegen eines Doku-Codex-Widerspruchs erst prüfen, welche Seite tatsächlich die aktuelle Absicht
  trägt, statt automatisch die Doku für maßgeblich zu halten.
