# ADR-2: Zustand statt Vererbung bei `AuthJourney`

**Entscheidung**: `AuthJourney` ist eine flache Entity ohne Subklassen oder getrennte Tabellen je
Intent. Was sich je Intent unterscheidet, steckt in `stateType` (Diskriminator) plus `state`
(JSON) ([Domänenmodell](02-domaenenmodell.md) Abschnitt 2).

**Erwogene Alternative**: Eine Tabelle je Intent bzw. Single-Table-Vererbung mit spaltenweise
kodierten intent-spezifischen Feldern.

**Warum diese**: Die Menge der Attribute unterscheidet sich stark zwischen Intents (`REGISTER`
braucht andere Zwischenzustände als `STEP_UP`), und das Verhalten dazu gehört in Services
(`AuthPolicy`, Tool-Katalog). Getrennte Spalten je Attribut würden eine
breite Tabelle aus überwiegend leeren Feldern ergeben. `stateType` bleibt trotzdem abfragbar.

**Kosten**: Der `state`-Inhalt ist für die Datenbank selbst intransparent — Constraints und
Fremdschlüssel auf einzelne JSON-Attribute sind nicht möglich, Konsistenz muss die
`IntentStrategy` je Intent selbst sicherstellen.

---
