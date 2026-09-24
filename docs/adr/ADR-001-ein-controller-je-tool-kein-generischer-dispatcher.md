# ADR-1: Ein Controller je Tool, kein generischer Dispatcher

**Entscheidung**: Jedes Tool bekommt einen eigenen, typisierten `@RestController` mit eigenem
Request-DTO. `POST/PATCH/GET` liegen je Tool in einem eigenen Controller ([Tool-Architektur](../03-tool-architektur.md)
Abschnitt 4, [Projektrahmen](../08-projektrahmen.md) A11).

**Erwogene Alternative**: Ein einziger Controller, der Anfragen zur Laufzeit anhand der `toolId`
verteilt, mit einer allgemeinen `Map<String, Any?>` als Inhalt der Anfrage.

**Warum diese**: Ein typisiertes DTO zeigt schon am Controller, was ein Tool tatsächlich erwartet.
Bei einer `Map<String, Any?>` steht das nur noch im Code des Handlers. Eine Verteilung anhand der
`toolId` zur Laufzeit wäre außerdem eine Fehlerquelle, die der Compiler nicht sieht: Ein neues Tool
ohne passenden `when`-Zweig fiele erst zur Laufzeit auf.

**Kosten**: Mehr Code: ein Controller je Tool statt eines einzigen, jeweils ähnlich aufgebaut
(Starten, Fortsetzen, Lesen).

---
