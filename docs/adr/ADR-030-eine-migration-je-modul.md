# ADR-30: Ein Flyway-Migrationsordner je Modul

**Entscheidung.** Jedes Modul bringt sein Schema in einem eigenen Ordner
`db/migration/<modul>/` mit, meist als eine Datei (der Orchestrator hat drei). Im obersten
Verzeichnis der Migrationen liegt keine SQL-Datei mehr.
`ModuleMigrationLocations` findet die Ordner beim Start selbst.

## Vorher

`V1__schema.sql` legte auf 589 Zeilen die Schemas und Tabellen aller Module an, dazu kamen
`V2__testdata.sql` und `V4__node_signing_key.sql` im obersten Verzeichnis. Nur `auth_kobil` hatte
bereits eine eigene Datei.

Das passte nicht zum Rest: Jedes Modul hat sein eigenes Datenbankschema
([ADR-16](ADR-016-ein-datenbankschema-je-modul-statt-namenspraefix.md)), seine eigenen Controller
und seine eigene Logik zum Aufräumen, aber die Tabellen dazu standen in einer Datei, die sich alle
Module teilten. Ein neues Methodenmodul musste sie anfassen. Das war die letzte Stelle, an der die
Modulgrenze im Code nicht galt.

## Was es kostet

Flyway prüft Migrationen über ihre Prüfsumme. Eine bereits angewendete Migration aufzuteilen
ändert diese Prüfsummen, und Flyway lehnt den Start gegen eine bestehende Datenbank ab.
Jede Datenbank mit dem alten Stand muss neu angelegt werden. Lokal passiert das von selbst:
`orchestrator.schema.FlywayResetConfig` löscht eine H2-Datei, die nicht mehr passt, und baut sie
neu auf — die Testdaten kommen aus `demo_seed/V16__testdata.sql`. Bei jeder anderen Datenbank
bricht Flyway ab, und dort wäre derselbe Schritt nicht billig.

[ADR-4](ADR-004-flyway-neubaseline-statt-migration-des-altcodes.md) hat aus demselben Grund schon
einmal eine neue Ausgangsbasis geschaffen. Insofern wird hier eine bereits getroffene Entscheidung
zum zweiten Mal angewendet und keine neue getroffen.

## Aufteilung und Reihenfolge

Die Versionen laufen über alle Module hinweg durch, weil Flyway eine einzige Historie führt. Die
Reihenfolge folgt den Fremdschlüsseln: `ext_personenverzeichnis` (V1), `account` (V2), `orchestrator` (V3),
danach die Ident- und Auth-Module, dann die Demo-Daten (V16), die Personen und Freischaltcodes
zugleich brauchen. Später hinzugekommene Module hängen sich mit der nächsten freien Nummer an
(`nect_mock` V17, `id_nect` V18).

Fremdschlüssel gibt es weiterhin nur innerhalb eines Schemas (ADR-16). Ein Verweis in ein anderes
Modul ist eine Spalte mit Index, keine Fremdschlüsselbeziehung. Genau deshalb ist die Aufteilung
überhaupt möglich, ohne dass die Reihenfolge empfindlich wird.

## Alternative: V1 als Grundbestand stehen lassen

Neue Migrationen wären ab jetzt in die Modulordner gegangen, die alten wären geblieben. Keine
Datenbank wäre ungültig geworden. Dagegen sprach, dass der Grundbestand, also fast das ganze
Schema, weiter in einer gemeinsamen Datei gestanden hätte und die Aufteilung nur für Neues gegolten
hätte.

## Wo die gemeinsamen Regeln stehen

Der Kopf von `V1__schema.sql` trug die Konventionen für alle Tabellen. Die stehen jetzt in
`db/migration/KONVENTIONEN.md`, damit sie nicht in der Datei eines einzelnen Moduls landen.
