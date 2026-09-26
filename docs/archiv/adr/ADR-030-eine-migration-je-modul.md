# ADR-30: Ein Flyway-Migrationsordner je Modul

**Status:** Aufgegangen in [ADR-16](../../adr/ADR-016-ein-datenbankschema-je-modul-statt-namenspraefix.md)
(„Ein Schema und ein Migrationsordner je Modul“). Die Aufteilung der Versionsnummern und ihre
Reihenfolge stehen in
[`db/migration/KONVENTIONEN.md`](../../../src/main/resources/db/migration/KONVENTIONEN.md).

Kurz: Jedes Modul bringt sein Schema in einem eigenen Ordner `db/migration/<modul>/` mit; die frühere
gemeinsame Datei `V1__schema.sql` ist aufgeteilt.
