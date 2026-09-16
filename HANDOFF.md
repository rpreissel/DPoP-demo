# Handoff: Fortsetzung Domänen-/DB-Modell-Review (`account`, `orchestrator`)

Dieses Dokument richtet sich an den nächsten Agenten (Claude Opus), der diese Sicherheitsarbeit
fortsetzt. Der vorherige Durchgang (A1–A3, plus Cascade-Prüfung für A5) ist abgeschlossen und
committet (lokal, `main`, noch nicht gepusht — der Nutzer holt sich die Änderungen selbst per
`git pull`).

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
- **A5 (teilweise)** — die vermutete Lücke „fehlendes `ON DELETE CASCADE` auf
  `account_attribute`/`account_anchor`" wurde geprüft und **widerlegt**: `V30`/`V31` setzen beide
  bereits `ON DELETE CASCADE`. Offen bleibt nur der DSGVO-Rest (siehe unten).

Verifiziert: `./gradlew :test` → **BUILD SUCCESSFUL, 565 Tests, 0 Fehler** (Baseline und nach V33
identisch grün). `ddl-auto: validate` läuft durch.

## 2. Was noch offen ist

Aus dem Review-Dokument, empfohlene Reihenfolge:

1. **B1** — nicht-sargabler Full-Table-Scan im Identifikationspfad
   (`AccountAttributeRepository.findAccountIdsByTypeAndNormalizedValue` vergleicht
   `lower(trim(value))` ohne passenden Index). Trivial auslösbarer DoS aus einem Pfad vor jeder
   Authentisierung. Empfehlung im Dokument: `normalized_value`-Spalte + Index, Schnittmenge in
   einer SQL-Abfrage, harte Kandidaten-Obergrenze.
2. **B3** — `journey_log` hat keine Aufbewahrungsgrenze; `RetentionJob` deckt alle anderen
   Tabellen ab, diese nicht. Größte Tabelle des Systems, enthält identitätsnahe Daten.
3. **A5-Rest** — `AccountDeletionService` löscht `journey_log`/`attempt_throttle` für die
   gelöschte `accountId` nicht mit (DSGVO). Kein fehlender Fremdschlüssel, sondern eine fehlende
   explizite Aufräum-Query — hängt inhaltlich mit B3 zusammen.
4. **C2, D1** — Strukturbereinigung (String- statt Enum-Typisierung zwischen den Modulen;
   `authenticationMethods` als JSON-Liste auf einer versionierten Zeile — teuerste
   Entwurfsentscheidung im Modell, siehe D1 im Dokument für die drei Konsequenzen).

Einzeln klein und jederzeit einschiebbar: A4, A6, A7, B2, B4–B6, C3, C4, D2, D3 — alle mit
Begründung und Lösungsvorschlag im Review-Dokument.

## 3. Arbeitsweise

- Doku (`docs/`) beschreibt das Zielbild; bei Abweichung hat sie Vorrang vor dem Code.
- Nach jeder Änderung `./gradlew :test` — muss grün bleiben (aktuell 565 Tests).
- `bd` (beads) für Aufgaben-Tracking; `bd prime` für Workflow-Details. Beim Anlegen der offenen
  Befunde als Issues: Reihenfolge B1 vor B3 vor A5-Rest (siehe oben).
- Konservatives Git-Profil: committen ja, **pushen nur auf ausdrückliche Anweisung**.
- Migrationsdateien unter `src/main/resources/db/migration/` sind in dieser Umgebung lesbar und
  schreibbar — keine Content-Exclusion-Policy wie in der Ausgangsumgebung des ersten Agenten.
