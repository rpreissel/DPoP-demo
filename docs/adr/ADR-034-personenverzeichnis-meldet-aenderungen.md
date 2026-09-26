# ADR-34: Personenverzeichnis – Partnernummer, drei Rollen, Änderungen per Event bis Keycloak

**Status**: umgesetzt.

**Entscheidung**: Das simulierte Fremdsystem heißt **Personenverzeichnis** (Modul
`ext_personenverzeichnis`). Es kennt drei Kennungen je Person und meldet Änderungen per Event bis zu
Keycloak. Die Kennungen und die drei Rollen im Einzelnen beschreibt
[02-domaenenmodell.md](../02-domaenenmodell.md) Abschnitt 6. Entschieden ist hier:

1. **Die Partnernummer ist der Schlüssel jeder Person** (`P` und neun Ziffern, vom Verzeichnis
   zufällig vergeben) und im Konto der Anker `PERSON_ID`. Sie ist bewusst keine laufende Zahl: Sie
   verrät weder Reihenfolge noch Anzahl, und ein Mensch liest sie auf einem Brief und tippt sie ein.
2. **Eine KVNR gibt es nur zusammen mit einer Versicherungsnummer.** Die Regel steht im Schema
   (`ck_person_kvnr_nur_versichert`) und im Verzeichnis selbst. Umgekehrt darf die KVNR zeitweise
   fehlen, weil sie sich ab und zu ändert. Die Versicherungsnummer ist im Konto ein ersetzbarer Anker
   (`VERSNR`), die KVNR ein Claim.
3. **Die Rolle wird abgeleitet, nicht gespeichert**: Versicherter mit Versicherungsnummer, Partner nur
   mit Partnernummer, Interessent ohne zugeordnete Person. Die ID-Claims enthalten dafür `personId`
   und `versnr`, beide bei jeder Abfrage aus dem Verzeichnis gelesen; die App leitet die Rolle daraus
   ab. Ein eigenes Rollenfeld gibt es im Backend nicht.
4. **Zuordnung: erst KVNR, sonst Partnernummer.** `ident-kvnr` (nach eID oder Nect) und `ident-fsc`
   (Brief mit Freischaltcode) nehmen beide Kennungen an: ein Verfahren, zwei Kennungen, kein zweites
   Tool. Der Client fragt zuerst nach der KVNR, erst auf „Ich habe keine Versichertennummer“ nach der
   Partnernummer. Kommen beide an, entscheidet die KVNR; `ident-fsc` hält immer nur eine der beiden
   fest. Die Prüfung dahinter ist dieselbe: erst die Personalien gegen das Verzeichnis abgleichen, dann
   den Anker schreiben. Ein über die Partnernummer zugeordnetes Konto bekommt keinen KVNR-Claim; der
   kommt, falls es eine KVNR gibt, mit der nächsten Änderung aus dem Verzeichnis.
5. **Änderungen gehen per Event an das Konto.** Ändert das Verzeichnis eine Person, veröffentlicht es
   `tool_api.PersonChanged(personId, changed, kvnr, versnr)`. Das Modul `account` übernimmt die
   Änderung und meldet `AccountChanged(accountId, changed)`. Alles läuft über die Event Publication
   Registry ([ADR-29](ADR-029-event-publication-registry-statt-eigener-outbox.md)): Die Ereignisse
   werden gespeichert und bei einem Fehler erneut zugestellt.
6. **Keycloak nur, wenn es Keycloak betrifft.** `AccountChanged.changed` nennt die Arten der geänderten
   Attribute, wenn der Auslöser sie kennt; `null` heißt „alles neu lesen“. Der Abgleich überspringt
   eine Änderung, die nichts Gespiegeltes betrifft. Für ein Konto, das einer Person zugeordnet ist,
   spiegelt er nur die Werte des Verzeichnisses; ein dort geleertes Feld bleibt so leer, statt aus
   alten Claims wieder aufzutauchen.

Name, Vorname, Geburtsdatum und Adresse werden weiter **bei jeder Abfrage** aus dem Verzeichnis gelesen
(`PersonDirectory`). Das Konto speichert davon keinen aktuellen Wert; eine Änderung braucht dort also
keinen Claim.

**Warum das Event in `tool_api` liegt**: Absender (`ext_personenverzeichnis`) und Empfänger (`account`)
kennen einander nicht; beide hängen aber schon an `tool_api` (`PersonDirectory`). Das Event enthält, wie
eine echte Änderungsmitteilung, nur die Person, die Arten der geänderten Attribute und die neuen
Kennungen (KVNR, Versicherungsnummer), weil das Konto genau diese selbst speichert. Andere Stammdaten
enthält es nie.

**Ausdrücklich benannte Ausnahme: Anker ohne Sitzung.** Sonst setzt ein Anker ein Mindestniveau der
Sitzung voraus (`AnchorRule.acrFloor`). Eine Änderung im Verzeichnis hat aber keine Sitzung; für seine
Kennungen ist das Verzeichnis selbst maßgeblich. `AccountService.applyDirectoryChange` schreibt deshalb
den KVNR-Claim und den `VERSNR`-Anker mit dem Niveau des Verzeichnisses (loa2). Das gilt nur auf diesem
einen Weg, nur für diese zwei Arten und nur für das Konto, das über `PERSON_ID` an genau diese Person
gebunden ist. Wie der alte Wert dabei zurückgenommen wird, steht in der Liste der Auslöser in
[ADR-12](ADR-012-ein-widerruf-ist-eine-eigene-zeile-mit-eigenem.md). Eine entfernte Kennung wird nur
zurückgenommen: Eine fehlende KVNR ist ein Zwischenzustand, und ohne Versicherungsnummer ist die Person
Partner. Beim Zuordnen entsteht der `VERSNR`-Anker dagegen auf dem normalen Weg mit der Sitzung; die
Kennung liefert `PersonDirectory.versnrOf`.

**Folgen**:

- Ein Token, das vor der Änderung ausgestellt wurde, trägt die alten Werte bis zur Erneuerung.
- Wer die Versicherungsnummer verliert, verliert den Anker, nicht das Konto (`PERSON_ID` bleibt), und
  wird vom Versicherten zum Partner.
- `personId` ist überall ein String (`P…`), auch im Demo-Feld `demo.personId` des gemeinsamen
  Antwortformats. Das ist ein bewusster Bruch von v1; `api/published/v1.yaml` wurde dafür auf einen
  neuen Stand gehoben (wie schon beim Journey-Trace, [API](../05-api.md)).
- In unserem Code heißt das Verzeichnis englisch, wie der Port: `ClaimSource.PERSON_DIRECTORY`
  (gespeichert als `person_directory`), `AttributeAuthority.PersonDirectory`,
  `RetractionAnchor.PERSON_DIRECTORY`. Deutsch bleibt nur das Fremdsystem selbst
  (`ext_personenverzeichnis`, `Personenverzeichnis`).

**Geschichte**: Das Fremdsystem hieß vorher „Personenregister“ (Modul `ext_stammdaten`); dort war die
KVNR der unveränderliche Schlüssel und die Personen-ID eine laufende Zahl. Die Versicherungsnummer, die
Rolle Partner und die Zuordnung über die Partnernummer kamen am 2026-09-24 hinzu.
