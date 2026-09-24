# ADR-16: Ein Datenbankschema je Modul statt Namenspräfix

> **Nachtrag.** `V1__schema.sql` ist inzwischen in eine Datei je Modul aufgeteilt, siehe
> [ADR-30](ADR-030-eine-migration-je-modul.md). Die Aussagen unten gelten unverändert; nur die
> Datei, auf die sie sich beziehen, gibt es so nicht mehr.


**Entscheidung**: Jedes Modul bekommt ein eigenes Datenbankschema, und jede Tabelle liegt im
Schema ihres Moduls: `account.anchor`, `auth_sms.enrollment`, `orchestrator.channel_session`.
Tabellennamen tragen kein Modulpräfix mehr. Indizes und
Constraints gehören zum jeweiligen Schema und tragen ebenfalls kein Präfix (`ux_anchor_value`). Die
Arbeitsdaten eines Tool-Durchlaufs heißen `<modul>.<tool-rolle>_tool_session`
([Betrieb](../07-betrieb.md) Abschnitt 6, `db/migration/KONVENTIONEN.md`).

**Erwogene Alternative**: Der bisherige Zustand: alle Tabellen nebeneinander, mit dem Modulnamen als
Präfix (`auth_sms_enrollment`, `orchestrator_channel_session`).

**Warum diese**: Das Präfix war eine Vereinbarung, an die sich jemand halten musste; das Schema ist
eine Struktur, die sich nicht umgehen lässt. Eine Tabelle kann nicht mehr versehentlich im falschen
Modul entstehen. Die wichtigste Regel dieses Aufbaus, Fremdschlüssel nur innerhalb eines Moduls,
steht jetzt in den Tabellendefinitionen selbst und nicht nur in einem Kommentar darüber. Soll ein
Modul später ein eigener Dienst werden, ist außerdem klar, wo die Grenze verläuft.

**Kosten**: Jede Abfrage, jede `@Table`-Annotation und jedes Verwaltungswerkzeug muss das Schema
mit angeben. Ein `SELECT ... FROM anchor` ohne Schema findet nichts mehr, weil der Suchpfad auf
`PUBLIC` steht (dort liegt bewusst auch `flyway_schema_history`).
Und wo ein Tabellenname vorher insgesamt eindeutig war, ist er es jetzt nur noch je Schema: Sechs
Module haben eine Tabelle `enroll_tool_session`.

---
