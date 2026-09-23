# ADR-4: Flyway-Neubaseline statt Migration des Altcodes

> **Nachtrag.** `V1__schema.sql` ist inzwischen in eine Datei je Modul aufgeteilt, siehe
> [ADR-30](ADR-030-eine-migration-je-modul.md). Die Aussagen unten gelten unverändert; nur die
> Datei, auf die sie sich beziehen, gibt es so nicht mehr.


**Entscheidung**: `V1__schema.sql` ersetzt die komplette frühere Migrationshistorie (`V1`–`V16`
im ursprünglichen Code) durch einen sauberen Neubau, statt sie fortzuschreiben.

**Erwogene Alternative**: Den Altcode Schritt für Schritt migrieren — `Attempt`-Terminologie zu
`ToolSession`/`ToolOutcome`, URL-Pfade auf den Tool-Namespace, Klartext-TAN nachträglich hashen.

**Warum diese**: Der Alt-Stand unterschied sich strukturell so stark vom Zielbild (andere
Terminologie, kein `ToolDescriptor`/`AuthPolicy`, unverschlüsselte TANs), dass eine
Schritt-für-Schritt-Migration mehr Zwischenzustände und damit mehr Fehlerquellen erzeugt hätte als
ein Neubau. Für eine Demo mit lokaler H2-Datei entfällt außerdem das übliche Gegenargument
Datenverlust.

**Kosten**: Diese Entscheidung ist an den Kontext gebunden — bei echten Bestandsdaten wäre eine
Neubaseline nicht vertretbar.

**Nachtrag**: Eine zweite Neubaseline hat die danach wieder aufgelaufenen 37 Migrationen
zusammengeführt — mit Modellbereinigung, siehe ADR-14.

---
