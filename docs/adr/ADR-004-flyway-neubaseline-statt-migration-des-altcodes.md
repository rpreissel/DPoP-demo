# ADR-4: Neue Flyway-Ausgangsbasis statt Migration des Altcodes

> **Nachtrag.** `V1__schema.sql` ist inzwischen in eine Datei je Modul aufgeteilt, siehe
> [ADR-30](ADR-030-eine-migration-je-modul.md). Die Aussagen unten gelten unverändert; nur die
> Datei, auf die sie sich beziehen, gibt es so nicht mehr.


**Entscheidung**: `V1__schema.sql` ersetzt alle früheren Migrationen (`V1`–`V16` im ursprünglichen
Code) durch einen sauberen Neubau, statt sie weiterzuführen.

**Erwogene Alternative**: Den alten Code Schritt für Schritt umstellen: die Begriffe rund um
`Attempt` auf `ToolSession`/`ToolOutcome`, die URL-Pfade auf den Bereich der Tools, und TANs im
Klartext nachträglich hashen.

**Warum diese**: Der alte Stand war im Aufbau so weit vom Ziel entfernt (andere Begriffe, kein
`ToolDescriptor` und keine `AuthPolicy`, TANs im Klartext), dass eine schrittweise Umstellung mehr
Zwischenzustände und damit mehr Fehlerquellen erzeugt hätte als ein Neubau. Bei einer Demo mit einer
lokalen H2-Datei entfällt außerdem das übliche Gegenargument, der Verlust von Daten.

**Kosten**: Die Entscheidung gilt nur in diesem Rahmen. Mit echten Bestandsdaten wäre eine neue
Ausgangsbasis nicht vertretbar.

**Nachtrag**: Eine zweite neue Ausgangsbasis hat die danach wieder angesammelten 37 Migrationen
zusammengefasst und dabei das Modell bereinigt, siehe ADR-14.

---
