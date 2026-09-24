# ADR-26: Der API-Vertrag wird generiert, nicht dreimal von Hand gepflegt

**Status:** umgesetzt.

## Entscheidung

Die Quelle des API-Vertrags ist der Code. Alles andere wird daraus erzeugt:

- **`api/openapi.yaml`** ist ein eingechecktes Erzeugnis. `OpenApiSnapshotTest` schreibt die Datei
  aus dem laufenden Code (`./gradlew updateOpenApiSnapshot`); `./gradlew checkOpenApiSnapshot` prüft
  in der CI, dass sie dazu passt. Jede gewollte Vertragsänderung wird so im Review als Diff sichtbar.
- **`api/modules/<modul>.yaml`** entsteht im selben Lauf, damit man Änderungen je Modul prüfen kann.
  Gemeinsame Schemas verweisen dort auf `../openapi.yaml#/components/schemas/…`, statt sie zu kopieren.
- **`api/published/v1.yaml`** ist der eingefrorene Stand von v1. `checkPublishedApiCompatibility`
  (openapi-diff) prüft in der CI jede Änderung dagegen; `publishApiVersion` hebt einen bewusst
  gewollten neuen Stand an.
- **Clients werden generiert:** das Frontend mit dem OpenAPI Generator
  (`generateFrontendApiTypes`, eingecheckt unter `frontend/src/generated`, die CI prüft per
  `git diff --exit-code`), die Keycloak-Erweiterung ihre Java-Modelle (`generateOrchestratorModels`,
  nicht eingecheckt).
- **Pflichtfelder** legt die Quelle fest: `KotlinRequiredModelConverter` macht eine Property, die in
  Kotlin nicht `null` sein kann und keinen Standardwert hat, zu `required`.

Einzelheiten: [05-api.md](../05-api.md), Abschnitt 1.

## Begründung

Vorher lasen drei Stellen denselben Vertrag, jede von Hand gepflegt: die Kotlin-DTOs,
`frontend/src/types.ts` (209 Zeilen, die die DTOs nachbauten) und das JSON-Parsing in
`keycloak-extension`. Nichts verband sie; eine geänderte Antwort bemerkte jede Stelle zu einem anderen
Zeitpunkt, die letzte oft erst zur Laufzeit.

**Erwogene Alternative:** prüfen statt generieren, also ein Test, der `types.ts` gegen die Spec
vergleicht. Das meldet Abweichungen, aber man pflegt weiter zwei Fassungen und tippt jede Ergänzung
zweimal. Generieren schließt die Abweichung aus.

## Folgen

- Eine Gradle-Abhängigkeit mehr (`org.openapi.generator`) und ein eingecheckter Stand des
  Frontend-Generators. Nach `updateOpenApiSnapshot` muss man zusätzlich `generateFrontendApiTypes`
  laufen lassen.
- `servers` wird aus der Spec entfernt: Der Eintrag enthielte den zufälligen Testport und sagt nichts
  über den Vertrag.
- In der Keycloak-Erweiterung sind noch `restoreData`, die Methodenliste und die Passwortprüfung von
  Hand geparst.

## Geschichte

- Die erste Fassung nannte `api/openapi.yaml` „die einzige geschriebene Fassung“. Seit dem
  Snapshot-Test ist sie ein Erzeugnis des Codes; Modul-Dateien, die eingefrorene v1 und die Modelle der
  Erweiterung kamen danach dazu.
- Beim ersten Generieren fielen drei Fehler in der damaligen Spec auf: `Set<FactorType>` wurde zu
  einem TypeScript-`Set` (übertragen wird ein Array, die DTOs nutzen jetzt `List`); der
  `@JsonAnyGetter`-Teil von `DemoInfo` stand als verschachteltes `values` statt flach
  (`additionalProperties`); ein Tag hatte an mehreren Controllern verschiedene Beschreibungen (Tags
  werden jetzt zentral deklariert).
