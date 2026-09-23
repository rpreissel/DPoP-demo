# ADR-16: Ein Datenbankschema je Modul statt Namenspräfix

> **Nachtrag.** `V1__schema.sql` ist inzwischen in eine Datei je Modul aufgeteilt, siehe
> [ADR-30](ADR-030-eine-migration-je-modul.md). Die Aussagen unten gelten unverändert; nur die
> Datei, auf die sie sich beziehen, gibt es so nicht mehr.


**Entscheidung**: Jedes Modul bekommt ein eigenes Datenbankschema, und jede Tabelle liegt im
Schema ihres Moduls: `account.anchor`, `auth_sms.enrollment`, `orchestrator.channel_session`.
Tabellennamen tragen kein Modulpräfix mehr; Indizes und
Constraints sind schema-eigene Objekte und ebenfalls präfixfrei (`ux_anchor_value`). Die
Arbeitsdaten eines Tool-Durchlaufs heißen `<modul>.<tool-rolle>_tool_session`
([Betrieb](07-betrieb.md) Abschnitt 6, Kopf von `V1__schema.sql`).

**Erwogene Alternative**: Der Zustand davor — eine flache Tabellenmenge mit dem Modulnamen als
Präfix (`auth_sms_enrollment`, `orchestrator_channel_session`).

**Warum diese**: Das Präfix war eine Konvention, an die sich jemand halten musste; das Schema ist
eine Struktur, die sich nicht umgehen lässt. Eine Tabelle kann nicht mehr versehentlich im falschen
Modul entstehen, und die tragende Regel dieses Schemas — Fremdschlüssel nur innerhalb eines Moduls
— steht jetzt in der DDL selbst statt nur in einem Kommentar darüber. Eine spätere Aufteilung
eines Moduls in einen eigenen Dienst hat zudem eine klare Schnittlinie.

**Kosten**: Jede Query, jede `@Table`-Annotation und jedes Admin-Werkzeug muss qualifizieren; ein
unqualifiziertes `SELECT ... FROM anchor` findet nichts mehr, weil der Suchpfad auf `PUBLIC` steht
(dort bleibt bewusst auch `flyway_schema_history`).
Und wo vorher ein Tabellenname global eindeutig war, ist er es jetzt nur noch je Schema: Fünf
Module haben ein `enroll_tool_session`.

---
