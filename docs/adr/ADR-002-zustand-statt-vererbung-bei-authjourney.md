# ADR-2: Zustand statt Vererbung bei `AuthJourney`

**Entscheidung**: `AuthJourney` ist eine flache Entität ohne Unterklassen und ohne eigene Tabelle je
Intent. Was sich je Intent unterscheidet, steckt in `stateType` (Typkennung) und `state` (JSON) ([Domänenmodell](../02-domaenenmodell.md) Abschnitt 2).

**Erwogene Alternative**: Eine Tabelle je Intent oder eine gemeinsame Tabelle für alle Unterklassen
(Single-Table-Vererbung), in der jedes Feld eines Intents eine eigene Spalte ist.

**Warum diese**: Die Intents brauchen sehr unterschiedliche Felder (`REGISTER` hat andere
Zwischenzustände als `STEP_UP`), und das Verhalten dazu gehört in Services (`AuthPolicy`,
Tool-Katalog). Eine Spalte je Feld ergäbe eine breite Tabelle mit überwiegend leeren Feldern.
`stateType` lässt sich trotzdem abfragen.

**Kosten**: Den Inhalt von `state` kann die Datenbank nicht auswerten. Constraints und
Fremdschlüssel auf einzelne Felder im JSON sind nicht möglich; für die Konsistenz muss die
`IntentStrategy` jedes Intents selbst sorgen.

---
