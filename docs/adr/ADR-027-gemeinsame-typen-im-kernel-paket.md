# ADR-27: Gemeinsame Typen liegen im Paket `orchestrator.kernel`

**Entscheidung.** Typen, die mehrere Pakete des Orchestrators brauchen, liegen in
`orchestrator.kernel`. Dieses Paket hat keine Abhängigkeiten innerhalb des Orchestrators. Ein
ArchUnit-Test prüft, dass die Pakete des Orchestrators zyklenfrei bleiben.

> **Nachtrag 2026-09-26:** Das Paket heißt jetzt `orchestrator.domain` und ist zum fachlichen Kern
> gewachsen: Journey-Zustände, `IntentStrategy`, `AuthPolicy` und `AuthEvidence` liegen dort mit.
> Zur Regel „keine Abhängigkeit innerhalb des Orchestrators“ kommt „kein Framework“
> ([ADR-40](ADR-040-fachkern-im-paket-domain.md)).

## Vorher

Spring Modulith prüft die Grenzen zwischen den Modulen und schaut in keines hinein. Der Orchestrator
ist das größte Modul, und innerhalb davon gab es fünf Zyklen:

- `session` ↔ `policy`
- `session` ↔ `journey`
- `session` ↔ `journeytrace`
- `kc` ↔ `dpop`
- `session` → `api.v1`

## Warum die Zyklen entstanden

In fast allen Fällen lag ein Typ im falschen Paket:

| Typ | lag in | ist aber |
|---|---|---|
| `AuthIntent` | `journey` | ein Begriff, den der ganze Orchestrator benutzt |
| `AmrSource` | `session` | eine Aussage über die Herkunft eines Nachweises, also eine Frage der Richtlinie, nicht der Speicherung |
| `AcrLevels` | `session` | dasselbe |
| `OrchestratorException` | `api.v1` | der Fehlertyp aller Schichten, nicht nur der Web-Schicht |

Diese Typen liegen jetzt in `kernel`. Damit sind vier der fünf Zyklen weg, ohne dass sich Verhalten
ändert.

## Zwei Fälle brauchten mehr als einen Umzug

- **`journeytrace`** nahm die Entitäten `ChannelSession` und `AuthJourney` entgegen. Am Aufrufer war
  das bequem, machte aber das Log von den Klassen abhängig, die es protokolliert. Es nimmt jetzt
  zwei kleine Wertklassen (`LoggedChannel`, `LoggedJourney`). Der Aufrufer gibt damit explizit an,
  welche Felder protokolliert werden — das ist zugleich die vollständige Liste dessen, was in der
  Tabelle landet.
- **Das Aufräumen nach Ablauf der Fristen** löscht Sessions *und* Journeys. Es gehört deshalb über
  beide Pakete und nicht in eines hinein: neues Paket `orchestrator.retention`.

## Alternative: Modulith-Substrukturen

Man hätte `@ApplicationModule` auch auf die Unterpakete setzen können, dann prüft
`modules.verify()` mit. Dagegen spricht, dass die Unterpakete damit zu nach außen sichtbaren Modulen
erklärt würden, was sie nicht sind. Eine ArchUnit-Regel sagt dasselbe, ohne diese Zusage zu machen.

## Kosten

Ein Paket mehr und eine Regel, die bei jedem neuen Zyklus fehlschlägt.

Siehe [08-projektrahmen.md](../08-projektrahmen.md) Abschnitt 3 und 7.
