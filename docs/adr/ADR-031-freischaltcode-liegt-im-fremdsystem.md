# ADR-31: Der Freischaltcode liegt im Personenverzeichnis, `id_fsc` fragt es direkt

**Entscheidung** (**umgesetzt**): Die Freischaltcodes liegen in `ext_personenverzeichnis.freischaltcode`,
nicht mehr in `id_fsc.code`. `ext_personenverzeichnis` stellt sie über die öffentliche Klasse
`Freischaltcodes` aus, widerruft sie und prüft sie. `id_fsc` ruft `Freischaltcodes.pruefe` direkt
auf und deklariert dafür `ext_personenverzeichnis` als dritte erlaubte Abhängigkeit.

## Warum

Den Freischaltcode vergibt das Personenverzeichnis und verschickt ihn per Brief. Das Ident-Verfahren prüft
ihn nur. Solange die Codes im Schema von `id_fsc` lagen, war das Tool zugleich Aussteller und
Prüfer. In der Demo ließ sich das Ausstellen deshalb nicht als Vorgang im Fremdsystem zeigen,
etwa auf der Oberfläche `/personenverzeichnis/`.

## Warum direkt statt über einen Port

Das Muster gibt es im Projekt schon: `auth_kobil` fragt `kobil_mock.KobilSsms` direkt
(`allowedDependencies` enthält `kobil_mock`). So bildet es auch das Originalsystem ab: Das
Ident-Verfahren spricht das Personenverzeichnis an, nicht eine Abstraktion davor. Ein neuer Port in `tool_api`
wäre ein zweiter Weg für dieselbe Art Fremdsystem-Kante gewesen.

`PersonDirectory` bleibt der Port für die Auflösung per KVNR oder Partnernummer, den
Personalien-Abgleich und die Versicherungsnummer (ADR-34). Diese Fragen stellen alle
Ident-Verfahren, nicht nur `id_fsc`.

## Der Brief

Das Personenverzeichnis hält den Code nur als SHA-256-Hash (`Freischaltcodes.digest`, die einzige Definition;
`demo_seed/V16__testdata.sql` rechnet in SQL dasselbe). Den Klartext trägt der simulierte Brief
(`ext_personenverzeichnis.brief`). Auch in der echten Welt gibt es diesen Klartext, nämlich auf Papier. Die
Demo liest ihn aus dem Briefkasten, statt eine zweite fest verdrahtete Code-Liste zu pflegen.
Demo-Rahmen wie bei [ADR-22](ADR-022-der-verwahrte-pin-liegt-im-klartext-demo-rahmen.md): benannt,
nicht verschwiegen.

## Kosten

- Die Migrationen `ext_personenverzeichnis/V1`, `id_fsc/V4` und `demo_seed/V16` wurden in place
  umgeschrieben. Nach [ADR-30](ADR-030-eine-migration-je-modul.md) wird eine bestehende H2-Datei
  dadurch ungültig; `FlywayResetConfig` baut sie lokal neu auf.
- Die Aussage „Methodenmodule hängen nur an `tool_spi`/`tool_api`" (Projektrahmen M-3) hat jetzt
  zwei benannte Ausnahmen, beide zu einem simulierten Fremdsystem: `auth_kobil → kobil_mock` und
  `id_fsc → ext_personenverzeichnis`. **Nachtrag:** Inzwischen sind es drei, alle zu einem
  simulierten Fremdsystem: dazu `id_nect → nect_mock` ([Idee ident-nect](../ideen/ident-nect.md),
  Abschnitt 12). Seit ADR-33 hängt außerdem jedes Modul mit Nutzertexten an `texts`; „dritte
  erlaubte Abhängigkeit“ oben meint den Stand vor ADR-33.
