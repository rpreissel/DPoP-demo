# ADR-26: Der API-Vertrag wird generiert, nicht dreimal von Hand gepflegt

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
