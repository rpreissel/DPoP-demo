# ADR-31: Der Freischaltcode liegt im Personenverzeichnis, `id_fsc` fragt es direkt

> **Nachtrag 2026-09-26: `id_fsc` fragt das Register jetzt über einen Port** (`tool_api.ActivationCodes`
> mit `digest` und `isValid`, implementiert von `Freischaltcodes`). Der Grund für „direkt“ trug nicht:
> `id_fsc` erreichte dasselbe Fremdsystem damit auf zwei Wegen, die Person über den Port
> `PersonDirectory`, den Code über die Klasse `Freischaltcodes`. Über die Klasse kam zudem die Sprache
> des Registers (`pruefe`, `juengsterGueltigerCode`) in unseren Code; am Port wird sie übersetzt, wie
> bei `PersonDirectory`. Jetzt gilt je Fremdsystem ein Weg: das Personenverzeichnis nur über Ports,
> die übrigen Mocks (`kobil_mock`, `nect_mock`, `sms_mock`, `mail_mock`) weiter direkt. Die Demo-Personen
> liest der Orchestrator über den eigenen Port `DemoPersonDirectory` statt über `Personenverzeichnis`
> und `Freischaltcodes`. Der Rest dieses ADR beschreibt den Stand vor dem Nachtrag.

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

## Der Code ist bis zum Ablauf wiederverwendbar

Entschieden am 2026-09-25 (Review 2026-09, S-8): Ein Freischaltcode wird durch eine erfolgreiche
Identifizierung **nicht verbraucht**. Er gilt, bis er abläuft oder widerrufen wird
(`Freischaltcode.isValidAt`), und kann in dieser Zeit mehrfach verwendet werden.

Warum: `ident-fsc` ist nicht nur der Weg zur ersten Identifizierung, sondern auch der Notausgang für
MANAGE_METHODS – ein Konto mit nur einem Verfahren erreicht loa2 über die Re-Identifizierung
(`AuthPolicy.reIdentCandidates`). Bei Einmalnutzung bräuchte jede Re-Identifizierung einen neuen
Brief. Das ist für den Nutzer ein Postweg je Verwaltungsvorgang.

Erwogen und verworfen:

- **Einmalnutzung**: schließt das Restrisiko unten, kostet aber den Brief je Re-Identifizierung.
- **Einmalig für Fremde, wiederverwendbar für dasselbe Konto**: zwei Lesarten eines Codes, mehr
  Logik an der Stelle, die über Kontoübernahme entscheidet.

**Restrisiko, bewusst getragen**: Wer den Brief nach der legitimen Nutzung findet (Altpapier,
Mitbewohner), kann sich bis zum Ablauf erneut als diese Person identifizieren – loa2 und damit das
Konto der Person. Begrenzt wird das nur durch die Gültigkeitsdauer, den Widerruf im
Personenverzeichnis und die Ident-Drossel (5 Fehlversuche je Person in 15 Minuten, die gegen Raten
schützt, nicht gegen einen bekannten Code). Wie das Verzeichnis den Code speichert (heute SHA-256
ohne Pepper) ist nach [ADR-35](ADR-035-betriebsanspruch-backend-kern-produktionsreif.md) Sache des
Fremdsystems; das echte System muss ihn so ablegen, dass ein gelesener Hash den Code nicht verrät.

## Kosten

- Die Migrationen `ext_personenverzeichnis/V1`, `id_fsc/V4` und `demo_seed/V16` wurden direkt
  geändert, statt neue hinzuzufügen. Nach [ADR-16](ADR-016-ein-datenbankschema-je-modul-statt-namenspraefix.md) wird eine bestehende H2-Datei
  dadurch ungültig; `FlywayResetConfig` baut sie lokal neu auf.
- Methodenmodule hängen damit nicht mehr nur an `tool_spi`/`tool_api`. Welche benannten Ausnahmen es
  zu simulierten Fremdsystemen gibt, führt der [Projektrahmen](../08-projektrahmen.md) (M-3) an einer
  Stelle.
