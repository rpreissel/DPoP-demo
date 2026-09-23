# ADR-1: Ein Controller je Tool, kein generischer Dispatcher

**Entscheidung**: Jedes Tool bekommt einen eigenen, typisierten `@RestController` mit eigenem
Request-DTO. `POST/PATCH/GET` liegen je Tool in einem eigenen Controller ([Tool-Architektur](03-tool-architektur.md)
Abschnitt 2, [Projektrahmen](08-projektrahmen.md) A11).

**Erwogene Alternative**: Ein einziger Controller, der über `toolId` zur Laufzeit dispatcht, mit
einer generischen `Map<String, Any?>` als Request-Body.

**Warum diese**: Ein typisiertes DTO
zeigt am Controller, was ein Tool tatsächlich erwartet — bei einer `Map<String, Any?>` steht das
nur noch im Handler-Code. Ein `toolId`-basierter Runtime-Dispatch wäre
außerdem eine Fehlerquelle, die der Compiler nicht sieht: ein neues Tool ohne passenden
`when`-Zweig würde erst zur Laufzeit auffallen.

**Kosten**: Mehr Code — 17 Controller statt einem, mit strukturell ähnlichem Aufbau
(Aktivierung/Fortschreiben/Lesen).

---
