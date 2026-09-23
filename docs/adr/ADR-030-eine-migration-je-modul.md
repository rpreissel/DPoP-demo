# ADR-30: Eine Flyway-Migration je Modul

**Entscheidung.** Jedes Modul bringt sein Schema in einer eigenen Datei unter
`db/migration/<modul>/` mit. Im Wurzelverzeichnis der Migrationen liegt keine SQL-Datei mehr.
`ModuleMigrationLocations` findet die Ordner beim Start selbst.

## Vorher

`V1__schema.sql` legte auf 589 Zeilen die Schemas und Tabellen aller Module an, dazu kamen
`V2__testdata.sql` und `V4__node_signing_key.sql` im Wurzelverzeichnis. Nur `auth_kobil` hatte
bereits eine eigene Datei.

Das passte nicht zum Rest: Jedes Modul hat sein eigenes Datenbankschema
([ADR-16](ADR-016-ein-datenbankschema-je-modul-statt-namenspraefix.md)), seine eigenen Controller
und seine eigene Aufräum-Logik — aber die Tabellen dazu standen in einer Datei, die alle Module
teilten. Ein neues Methodenmodul musste sie anfassen. Das war die letzte Stelle, an der die
Modulgrenze im Code nicht galt.

## Was es kostet

Flyway prüft Migrationen über ihre Prüfsumme. Eine bereits angewendete Migration aufzuteilen
ändert diese Prüfsummen, und Flyway lehnt den Start gegen eine bestehende Datenbank ab.
**Jede Datenbank, die den alten Stand hat, muss gelöscht und neu angelegt werden** — lokal also
`data/`, dazu ein etwaiges Podman-Volume. Für diese Demo ist das billig: Die Testdaten kommen aus
`demo_seed/V16__testdata.sql`, die Anwendung baut sich beim ersten Start wieder auf.
Für ein echtes Deployment wäre derselbe Schritt nicht billig.

[ADR-4](ADR-004-flyway-neubaseline-statt-migration-des-altcodes.md) hat aus demselben Grund schon
einmal neu baseliniert; insofern ist das hier die zweite Anwendung einer bereits getroffenen
Entscheidung, nicht eine neue.

## Aufteilung und Reihenfolge

Die Versionen laufen über alle Module hinweg durch, weil Flyway eine einzige Historie führt. Die
Reihenfolge folgt den Fremdschlüsseln: `ext_stammdaten` (V1), `account` (V2), `orchestrator` (V3),
danach die Ident- und Auth-Module, zuletzt die Demo-Daten (V16), die Personen und Freischaltcodes
zugleich brauchen.

Fremdschlüssel gibt es weiterhin nur innerhalb eines Schemas (ADR-16). Ein Verweis in ein anderes
Modul ist eine indizierte Spalte, keine Fremdschlüsselbeziehung. Genau deshalb ist die Aufteilung
überhaupt möglich, ohne die Reihenfolge fragil zu machen.

## Alternative: V1 als Grundbestand stehen lassen

Neue Migrationen wären ab jetzt in die Modulordner gegangen, die alten wären geblieben. Keine
Datenbank wäre ungültig geworden. Dagegen sprach, dass der Grundbestand — also fast das ganze
Schema — weiter in einer geteilten Datei gestanden hätte und die Aufteilung nur für Neues gegolten
hätte.

## Wo die gemeinsamen Regeln stehen

Der Kopf von `V1__schema.sql` trug die Konventionen für alle Tabellen. Die stehen jetzt in
`db/migration/KONVENTIONEN.md`, damit sie nicht in der Datei eines einzelnen Moduls landen.
