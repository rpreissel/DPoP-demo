# ADR-16: Ein Schema und ein Migrationsordner je Modul

**Entscheidung** (umgesetzt): Die Modulgrenze gilt auch in der Datenbank, und zwar zweifach.

- **Ein Datenbankschema je Modul.** Jede Tabelle liegt im Schema ihres Moduls: `account.anchor`,
  `auth_sms.enrollment`, `orchestrator.channel_session`. Tabellen, Indizes und Constraints tragen kein
  Modulpräfix (`ux_anchor_value`). Fremdschlüssel gibt es nur innerhalb eines Schemas; ein Verweis in
  ein anderes Modul ist eine Spalte mit Index.
- **Ein Migrationsordner je Modul.** Jedes Modul bringt sein Schema selbst mit, in
  `db/migration/<modul>/`, meist als eine Datei (der Orchestrator hat drei). Im obersten Verzeichnis
  der Migrationen liegt keine SQL-Datei. `ModuleMigrationLocations` findet die Ordner beim Start
  selbst, eine gepflegte Liste gibt es nicht.

Die gemeinsamen Regeln (Namen, Typen, Versionsnummern und ihre Reihenfolge) stehen in
[`db/migration/KONVENTIONEN.md`](../../src/main/resources/db/migration/KONVENTIONEN.md).

**Erwogene Alternativen**:

- Alle Tabellen nebeneinander, mit dem Modulnamen als Präfix (`auth_sms_enrollment`,
  `orchestrator_channel_session`). So war es früher.
- Eine gemeinsame Migrationsdatei für alle Module. So war es bis zur Aufteilung: `V1__schema.sql`
  legte auf 589 Zeilen die Tabellen aller Module an.
- Die gemeinsame Datei als Grundbestand stehen lassen und nur neue Migrationen in Modulordner legen.
  Keine bestehende Datenbank wäre ungültig geworden, aber fast das ganze Schema wäre weiter in einer
  gemeinsamen Datei geblieben.

**Warum diese**: Ein Präfix ist eine Vereinbarung, an die sich jemand halten muss; ein Schema ist eine
Struktur, die sich nicht umgehen lässt. Eine Tabelle kann nicht versehentlich im falschen Modul
entstehen, und die wichtigste Regel dieses Aufbaus, Fremdschlüssel nur innerhalb eines Moduls, steht
in den Tabellendefinitionen selbst. Dasselbe gilt für die Migrationen: Jedes Modul hat seine eigenen
Controller und seine eigene Logik zum Aufräumen, also gehören auch seine Tabellen in seinen eigenen
Ordner. Mit einer gemeinsamen Datei musste jedes neue Methodenmodul eine Datei anfassen, die allen
gehörte. Soll ein Modul später ein eigener Dienst werden, ist klar, wo die Grenze verläuft.

**Kosten**:

- Jede Abfrage, jede `@Table`-Annotation und jedes Verwaltungswerkzeug muss das Schema mit angeben.
  Ein `SELECT ... FROM anchor` ohne Schema findet nichts, weil der Suchpfad auf `PUBLIC` steht (dort
  liegt bewusst auch `flyway_schema_history`).
- Ein Tabellenname ist nur noch je Schema eindeutig: Sechs Module haben eine Tabelle
  `enroll_tool_session`.
- Die Versionsnummern laufen trotzdem über alle Module hinweg durch, weil Flyway eine einzige Historie
  führt.

**Rückblick**: Das Schema wurde dreimal neu aufgesetzt. Zuerst ersetzte ein Neubau die Migrationen
des ursprünglichen Codes ([ADR-4](ADR-004-flyway-neubaseline-statt-migration-des-altcodes.md)). Dann
fasste eine zweite Ausgangsbasis 37 Migrationen zusammen und bereinigte das Kontomodell
([ADR-14](ADR-014-schema-zusammengefuehrt-das-konto-als-sperrpunkt-eine-wahrheit.md)). Zuletzt wurde die
gemeinsame Datei in einen Ordner je Modul aufgeteilt (früher eigene Entscheidung,
[ADR-30](ADR-030-eine-migration-je-modul.md)). Weil sich dabei die Prüfsummen bereits angewendeter
Migrationen ändern, muss jede Datenbank mit altem Stand neu angelegt werden. Lokal übernimmt das
`FlywayResetConfig`, das eine nicht mehr passende H2-Datei löscht und neu aufbaut; die Testdaten kommen
aus `demo_seed/V16__testdata.sql`. Mit Produktivdaten wäre keiner dieser Schritte vertretbar.
