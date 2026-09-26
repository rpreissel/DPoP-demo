# ADR-28: Demo-Werte lassen sich abschalten

**Entscheidung.** Nur `DemoDisclosure` erzeugt ein `DemoInfo`. Ob es diese Bean gibt, entscheidet
`demo.mode` beim Start. Eine ArchUnit-Regel stellt sicher, dass es die einzige Stelle bleibt.

> **Nachtrag 2026-09-26 (zweite Bewertung, A-4):** Der eigene Schalter `demo.disclosure` ist
> entfallen. Er folgte ohnehin dem Demomodus, und einen Demomodus ohne Demo-Werte oder einen Betrieb
> mit ihnen braucht niemand. Ein Schalter weniger heißt auch eine Prüfung weniger in
> `ProductionModeCheck`: Außerhalb des Demomodus gibt es die Bean, die Demo-Werte baut, gar nicht.

## Vorher

Im `demo`-Block der Antwort stehen die TAN im Klartext, das feste Demo-Passwort und von jeder
Testperson KVNR, Name, Adresse und Freischaltcode. Dass das „nie Teil des Produktionsvertrags" ist,
stand als Kommentar an `tool_spi.DEMO_DATA_KEY`. Zwei Stellen bauten den Block aber ohne jede
Bedingung. Eine Installation, die diese Werte nicht ausliefern darf, hatte nichts, womit sie sie abschalten
konnte.

## Alternative: ein Schalter an den beiden Stellen

`if (!demoEnabled) return null` wäre kürzer gewesen. Dagegen spricht: Man müsste dann per Test
absichern, dass wirklich jede Stelle den Schalter prüft, und eine dritte Stelle könnte den Weg ohne
Bedingung wieder einführen. Mit einer Bean, die es nur abhängig von der Property `demo.mode`
gibt (`@ConditionalOnProperty`, zwei Umsetzungen in `DemoDisclosure.kt`), gibt es gar keinen Weg,
über den die Werte hinausgehen könnten.

## Kosten

Ein Umweg mehr: Die beiden Aufrufer übergeben ihre Bestandteile, statt das `DemoInfo` selbst zu
bauen. Dazu kommt eine ArchUnit-Regel.

## Nicht entschieden

Voreingestellt bleiben die Werte eingeschaltet. Das Projekt ist eine Demo, und die Werte sind ihr Zweck.
Entschieden ist nur, dass man sie mit einer Einstellung abschalten kann.

Siehe [05-api.md](../05-api.md) Abschnitt 2 (`demo`).
