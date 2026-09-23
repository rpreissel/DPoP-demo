# ADR-28: Demo-Werte lassen sich abschalten

**Entscheidung.** Nur `DemoDisclosure` erzeugt ein `DemoInfo`. Ob es diese Bean gibt, entscheidet
`demo.disclosure` beim Start. Eine ArchUnit-Regel stellt sicher, dass es die einzige Stelle bleibt.

## Vorher

Im `demo`-Block der Antwort stehen die TAN im Klartext, das feste Demo-Passwort und von jeder
Testperson KVNR, Name, Adresse und Freischaltcode. Dass das „nie Teil des Produktionsvertrags" ist,
stand als Kommentar an `tool_spi.DEMO_DATA_KEY`. Zwei Stellen bauten den Block aber ohne jede
Bedingung. Ein Deployment, das diese Werte nicht ausliefern darf, hatte nichts zum Abschalten.

## Alternative: ein Flag an den beiden Stellen

`if (!demoEnabled) return null` wäre kürzer gewesen. Dagegen spricht: man müsste dann per Test
absichern, dass wirklich jede Stelle das Flag prüft, und eine dritte Stelle könnte den
bedingungslosen Pfad wieder einführen. Mit einer profilgebundenen Bean gibt es den Pfad nicht, über
den die Werte hinausgehen könnten.

## Kosten

Eine Indirektion: die beiden Aufrufer übergeben ihre Bestandteile, statt das `DemoInfo` selbst zu
bauen. Dazu eine ArchUnit-Regel.

## Nicht entschieden

Der Default bleibt eingeschaltet. Das Projekt ist eine Demo, und die Werte sind ihr Zweck.
Entschieden ist nur, dass man sie mit einer Einstellung abschalten kann.

Siehe [05-api.md](../05-api.md) Abschnitt 2 (`demo`).
