# ADR-26: Der API-Vertrag wird generiert, nicht dreimal von Hand gepflegt

> **Stand 2026-09-23:** Inzwischen deutlich erweitert (Modul-Dateien, veröffentlichte Version,
> Java-Modelle der Extension); siehe Nachtrag.

**Entscheidung.** `api/openapi.yaml` ist die einzige geschriebene Fassung des API-Vertrags. Ein Test
vergleicht sie mit dem, was der laufende Code ausliefert. Die TypeScript-Typen des Frontends werden
mit dem OpenAPI Generator daraus erzeugt.

## Vorher

Denselben Vertrag lasen drei Stellen, jede für sich von Hand gepflegt:

- die Kotlin-DTOs,
- `frontend/src/types.ts` (209 Zeilen, die die DTOs nachbauten),
- das JSON-Parsing in `keycloak-extension` (`OrchestratorClient`, `OrchestratorNextDispatch`).

springdoc lief mit, aber nichts verband die drei. Wenn sich eine Antwort änderte, merkte das jede
Stelle zu einem anderen Zeitpunkt — die letzte oft erst zur Laufzeit.

## Alternative: prüfen statt generieren

Ein Test hätte `types.ts` gegen die Spec vergleichen können. Das meldet Abweichungen, aber man
pflegt weiter zwei Fassungen und tippt jede Ergänzung zweimal. Generieren schließt die Abweichung
dagegen aus.

## Kosten

- Eine Gradle-Abhängigkeit mehr (`org.openapi.generator`) und ein eingecheckter Generator-Stand.
  Die CI prüft mit `git diff --exit-code`, dass er zur Spec passt.
- Bei jeder gewollten Vertragsänderung muss man `./gradlew updateOpenApiSnapshot` laufen lassen.
  Das ist erwünscht: die Änderung wird im Review sichtbar, statt in einem DTO-Diff unterzugehen.
- `servers` wird aus der Spec entfernt. Der Eintrag enthält den zufälligen Testport und sagt nichts
  über den Vertrag aus.

## Drei Fehler in der Spec, die dabei auffielen

Die Spec beschrieb an drei Stellen nicht das, was tatsächlich übertragen wird. Solange sie niemand
las, fiel das nicht auf:

- `Set<FactorType>` wurde zu einem TypeScript-`Set`. `JSON.parse` liefert aber ein Array. Die
  Wire-DTOs verwenden jetzt `List`; die Mengen-Semantik bleibt im Backend.
- Der `@JsonAnyGetter`-Teil von `DemoInfo` stand als verschachteltes `values` in der Spec, wird aber
  flach eingebettet (`demo.tan`, nicht `demo.values.tan`). Jetzt `additionalProperties`.
- Derselbe Tag-Name hatte an drei bis vier Controllern verschiedene Beschreibungen. Tags werden
  jetzt einmal zentral deklariert. Der Generator hatte die Spec deswegen als ungültig abgelehnt.

## Pflichtfelder

springdoc übernimmt Kotlins Non-Null nicht in `required`. Statt im Client festzulegen, welche Felder
immer vorhanden sind, macht das jetzt `KotlinRequiredModelConverter` an der Quelle: eine
nicht-nullable Property ohne Default wird `required`. Der Client übernimmt das nur.

Siehe [05-api.md](../05-api.md) Abschnitt 1.

## Nachtrag (2026-09-23)

- `api/openapi.yaml` ist nicht „geschrieben“, sondern ein Erzeugnis: `OpenApiSnapshotTest`
  schreibt sie aus dem laufenden Code (`./gradlew updateOpenApiSnapshot`) und prüft sie in `check`.
- Daneben entstehen im selben Lauf `api/modules/<modul>.yaml` zum Reviewen; gemeinsame Schemas
  verweisen dort per `../openapi.yaml#/components/schemas/…` auf den Vertrag statt ihn zu
  kopieren.
- `api/published/v1.yaml` ist der eingefrorene Stand von v1. `checkPublishedApiCompatibility`
  (openapi-diff) prüft jede Änderung dagegen, `publishApiVersion` hebt einen neuen Stand an.
- Der dritte Leser aus „Vorher“ ist teilweise erledigt: `keycloak-extension` erzeugt ihre
  Java-Modelle ebenfalls aus `api/openapi.yaml` (`generateOrchestratorModels`, nicht
  eingecheckt) und liest `ChannelResponse`/`ErrorResponse` getypt. Von Hand geparst sind dort noch
  `restoreData`, die Methodenliste und die Passwortprüfung.
- Der eingecheckte Generator-Stand mit `git diff`-Prüfung gilt nur fürs Frontend. Nach
  `updateOpenApiSnapshot` also zusätzlich `./gradlew generateFrontendApiTypes`.
- Alles Nähere: [05-api.md](../05-api.md) Abschnitt 1.
