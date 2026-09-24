# ADR-31: Der Freischaltcode liegt im Personenverzeichnis, `id_fsc` fragt es direkt

**Entscheidung** (**umgesetzt**): Die Freischaltcodes liegen in `ext_personenverzeichnis.freischaltcode`,
nicht mehr in `id_fsc.code`. `ext_personenverzeichnis` stellt sie über die öffentliche Klasse
`Freischaltcodes` aus, widerruft sie und prüft sie. `id_fsc` ruft `Freischaltcodes.pruefe` direkt
auf und deklariert dafür `ext_personenverzeichnis` als erlaubte Abhängigkeit.

## Warum

Den Freischaltcode vergibt das Personenverzeichnis und verschickt ihn per Brief. Das Verfahren zur Identifizierung
prüft ihn nur. Solange die Codes im Schema von `id_fsc` lagen, war das Tool zugleich Aussteller und
Prüfer. In der Demo ließ sich das Ausstellen deshalb nicht als Vorgang im Fremdsystem zeigen,
etwa auf der Oberfläche `/personenverzeichnis/`.

## Warum direkt statt über einen Port

Das Muster gibt es im Projekt schon: `auth_kobil` fragt `kobil_mock.KobilSsms` direkt
(`allowedDependencies` enthält `kobil_mock`). So bildet es auch das echte System ab: Das
Verfahren spricht das Personenverzeichnis direkt an, nicht eine Abstraktionsschicht davor. Ein neuer
Port in `tool_api` wäre ein zweiter Weg für dieselbe Art von Abhängigkeit zu einem Fremdsystem gewesen.

`PersonDirectory` bleibt der Port, über den die Person per KVNR oder Partnernummer gefunden, die
Personalien abgeglichen und die Versicherungsnummer gelesen werden (ADR-34). Diese Fragen stellen alle
Verfahren zur Identifizierung, nicht nur `id_fsc`.

## Der Brief

Das Personenverzeichnis hält den Code nur als SHA-256-Hash (`Freischaltcodes.digest`, die einzige Definition;
`demo_seed/V16__testdata.sql` rechnet in SQL dasselbe). Den Klartext trägt der simulierte Brief
(`ext_personenverzeichnis.brief`). Die Demo liest ihn aus dem Briefkasten, statt eine zweite, fest
eingetragene Liste von Codes zu pflegen. Warum der Klartext dort liegen darf, steht in
[ADR-22](ADR-022-der-verwahrte-pin-liegt-im-klartext-demo-rahmen.md).

## Kosten

- Die Migrationen `ext_personenverzeichnis/V1`, `id_fsc/V4` und `demo_seed/V16` wurden direkt
  geändert, statt neue hinzuzufügen. Nach [ADR-16](ADR-016-ein-datenbankschema-je-modul-statt-namenspraefix.md) wird eine bestehende H2-Datei
  dadurch ungültig; `FlywayResetConfig` baut sie lokal neu auf.
- Methodenmodule hängen damit nicht mehr nur an `tool_spi`/`tool_api`. Welche benannten Ausnahmen es
  zu simulierten Fremdsystemen gibt, führt der [Projektrahmen](../08-projektrahmen.md) (M-3) an einer
  Stelle.
