# Handoff: Fortsetzung Domänen-/DB-Modell-Review (`account`, `orchestrator`)

Dieses Dokument richtet sich an den nächsten Agenten, der diese Sicherheitsarbeit fortsetzt.
Erledigt und committet sind inzwischen **A1–A3** (erster Durchgang) sowie **B3** und der
**A5-Rest** (zweiter Durchgang). Als Nächstes dran ist **B1** — vollständig ausgearbeitet, aber
bewusst nicht angefangen (Begründung unten).

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

Verifiziert: `./gradlew :test` → **BUILD SUCCESSFUL, 568 Tests, 0 Fehler**. `ddl-auto: validate`
läuft durch.

## 2. Was noch offen ist

Aus dem Review-Dokument, empfohlene Reihenfolge:

1. **B1 — als Nächstes dran, fertig ausgearbeitet.** Nicht-sargabler Full-Table-Scan im
   Identifikationspfad (`AccountAttributeRepository.findAccountIdsByTypeAndNormalizedValue`
   vergleicht `lower(trim(value))` ohne nutzbaren Index; drei solche Abfragen pro Versuch,
   Schnittmenge in der Anwendung). Trivial auslösbarer DoS aus einem Pfad vor jeder
   Authentisierung.

   **Warum nicht schon erledigt:** Der Fix ist ohne DDL nicht teilbar — `ddl-auto: validate`
   lässt keine Entity-Spalte ohne passende Migration zu, eine halb angewandte Code-Hälfte färbt
   die gesamte Suite rot. Im zweiten Durchgang verweigerte die Content-Exclusion-Policy jeden
   Schreibzugriff auf `src/main/resources/db/migration/`. Der Befund B1 im Review-Dokument
   enthält deshalb die Migration `V34__account_attribute_normalized_value.sql` im Wortlaut plus
   die drei Code-Schritte (Entity-Hook, eine statt drei Abfragen, Kandidaten-Obergrenze). In
   einer Umgebung ohne diese Sperre ist das reines Anwenden.
2. **C2, D1** — Strukturbereinigung (String- statt Enum-Typisierung zwischen den Modulen;
   `authenticationMethods` als JSON-Liste auf einer versionierten Zeile — teuerste
   Entwurfsentscheidung im Modell, siehe D1 im Dokument für die drei Konsequenzen).

Einzeln klein und jederzeit einschiebbar: A4, A6, A7, B2, B4–B6, C3, C4, D2, D3 — alle mit
Begründung und Lösungsvorschlag im Review-Dokument.

## 3. Arbeitsweise

- Doku (`docs/`) beschreibt das Zielbild; bei Abweichung hat sie Vorrang vor dem Code.
- Nach jeder Änderung `./gradlew :test` — muss grün bleiben (aktuell 568 Tests).
- `bd` (beads) für Aufgaben-Tracking; `bd prime` für Workflow-Details. **Achtung:** In der
  Umgebung des zweiten Durchgangs war `bd` wegen eines Schema-Skews (DB auf v65, Binary kennt
  v53) nicht benutzbar; das Tracking lief deshalb über das Review-Dokument. Vor der Nutzung
  prüfen und ggf. den Recovery-Guide aus der `bd`-Fehlermeldung fahren.
- Konservatives Git-Profil: committen ja, **pushen nur auf ausdrückliche Anweisung**.
- Migrationsdateien unter `src/main/resources/db/migration/` müssen schreibbar sein, sonst ist
  B1 nicht umsetzbar (siehe oben). Vor Beginn kurz prüfen.

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
