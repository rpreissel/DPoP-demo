# ADR-34: Personenverzeichnis – Partnernummer, drei Rollen, Änderungen per Event bis Keycloak

**Entscheidung** (**umgesetzt**): Das simulierte Fremdsystem heißt **Personenverzeichnis** (vorher
„Personenregister“, Modul `ext_personenverzeichnis`). Ändert es eine Person, veröffentlicht es
`tool_api.PersonChanged(personId, changed, kvnr, versnr)`. Das Modul `account` übernimmt die Änderung und
meldet `AccountChanged(accountId, changed)`. Sind dabei Attribute betroffen, die nach Keycloak
gespiegelt werden, überträgt der bestehende Abgleich sie dorthin. Alles läuft über die Event
Publication Registry (ADR-29): Die Ereignisse werden gespeichert und bei einem Fehler erneut
zugestellt.

## Kennungen einer Person

| Kennung | Bedeutung | Änderbar | Im Konto |
|---|---|---|---|
| `personId` | Partnernummer: `P` und neun Ziffern, vom Verzeichnis zufällig vergeben, Schlüssel jeder Person | nein | Anker `PERSON_ID` |
| Versicherungsnummer (`versnr`) | 8 Ziffern, nur wer bei uns versichert ist | ja, auch entfernbar | Anker `VERSNR`, wenn vorhanden |
| KVNR | Krankenversichertennummer, **nur zusammen mit einer Versicherungsnummer**; sie darf aber zeitweise fehlen, weil sie sich ab und zu ändert | ja, auch entfernbar | Claim (Historie, der jüngste gilt) |

Die Partnernummer ist bewusst keine laufende Zahl: Sie verrät weder Reihenfolge noch Anzahl, und sie
ist der Wert, den ein Mensch auf einem Brief liest und eintippt. Die Regel „KVNR nur mit
Versicherungsnummer“ steht im Schema (`ck_person_kvnr_nur_versichert`) und im Verzeichnis selbst.

## Drei Rollen

| Rolle | Erkennbar an | Anzeige in der App |
|---|---|---|
| **Versicherter** | Versicherungsnummer vorhanden | „Angemeldet als … (Versicherter)“ |
| **Partner** | Partnernummer bekannt (Konto gebunden), keine Versicherungsnummer | „… (Partner)“ |
| **Interessent** | keine Person zugeordnet | „… (Interessent)“ |

Die ID-Claims enthalten dafür `personId` und `versnr`, beide bei jeder Abfrage aus dem Verzeichnis
gelesen. Die App leitet die Rolle daraus ab; einen eigenen Wert für die Rolle kennt das Backend nicht.

## Zuordnung: erst KVNR, sonst Partnernummer

`ident-kvnr` (nach eID oder Nect) und `ident-fsc` (Brief mit Freischaltcode) nehmen beide Kennungen
an: ein Verfahren, zwei Kennungen, kein zweites Tool. Der Client fragt zuerst nach der KVNR. Erst wenn
der Nutzer „Ich habe keine Versichertennummer“ wählt, fragt er nach der Partnernummer (in der App als
umschaltbares Feld, in Keycloak als aufklappbarer Abschnitt). Kommen beide an, entscheidet die KVNR
(`findPersonIdByKvnr` vor `findPersonIdByPartnernr`); `ident-fsc` hält immer nur eine der beiden
fest. Ein Konto, das über die Partnernummer zugeordnet wurde, bekommt keinen KVNR-Claim; der kommt,
falls es eine KVNR gibt, mit der nächsten Änderung aus dem Verzeichnis. Die Prüfung dahinter ist
dieselbe wie bei der KVNR: erst die Personalien gegen das Verzeichnis abgleichen
(`matchesPersonalien`/`attestedIdentityMatches`), danach den Anker schreiben.

Name, Vorname, Geburtsdatum und Adresse werden weiterhin **bei jeder Abfrage** aus dem Verzeichnis
gelesen (`PersonDirectory`). Das Konto speichert davon keinen aktuellen Wert; eine Änderung braucht
dort also keinen Claim.

## Warum das Event in `tool_api` liegt

Absender (`ext_personenverzeichnis`) und Empfänger (`account`) kennen einander nicht; beide hängen
aber schon an `tool_api` (`PersonDirectory`). Das Event enthält, wie eine echte Änderungsmitteilung,
nur die Person, die Arten der geänderten Attribute und die neuen **Kennungen** (KVNR,
Versicherungsnummer), weil das Konto genau diese selbst speichert. Andere Stammdaten enthält es nie.

## Ausdrücklich benannte Ausnahme: Anker ohne Sitzung

Sonst setzt ein Anker ein Mindestniveau der Sitzung voraus (`AnchorRule.acrFloor`). Eine Änderung im
Verzeichnis hat aber keine Sitzung; für seine Kennungen ist das Verzeichnis selbst maßgeblich.
`AccountService.applyDirectoryChange` schreibt deshalb den KVNR-Claim und den `VERSNR`-Anker mit dem
Niveau des Verzeichnisses (loa2). Das gilt nur auf diesem einen Weg, nur für diese zwei Arten und nur
für das Konto, das über `PERSON_ID` an genau diese Person gebunden ist. Der alte Wert wird
zurückgenommen (`RetractionAnchor.PERSON_DIRECTORY`) und der neue geschrieben. Eine entfernte Kennung
wird nur zurückgenommen: Eine fehlende KVNR ist ein Zwischenzustand, und ohne Versicherungsnummer ist
die Person Partner. Beim Zuordnen (`ident-kvnr`, `ident-fsc`) entsteht der `VERSNR`-Anker auf dem
normalen Weg mit der Sitzung; die Kennung liefert dafür `PersonDirectory.versnrOf` über den Port.

## Keycloak nur, wenn es Keycloak betrifft

`AccountChanged.changed` nennt die Arten der geänderten Attribute, wenn der Auslöser sie kennt (heute
nur dieser eine); `null` heißt „alles neu lesen“. Der Abgleich überspringt eine Änderung, deren Arten
nichts betreffen, was nach Keycloak gespiegelt wird. Für ein Konto, das einer Person zugeordnet ist,
spiegelt er nur die Werte des Verzeichnisses. Ein im Verzeichnis geleertes Feld bleibt so leer, statt
aus alten bestätigten Claims wieder aufzutauchen.

## Folgen

- Ein Token, das vor der Änderung ausgestellt wurde, trägt die alten Werte bis zur Erneuerung.
- Wer die Versicherungsnummer verliert, verliert den Anker, nicht das Konto (`PERSON_ID` bleibt) –
  und wird vom Versicherten zum Partner.
- `personId` ist überall ein String (`P…`), auch im Demo-Feld `demo.personId` des gemeinsamen
  Antwortformats. Das ist ein bewusster Bruch von v1; `api/published/v1.yaml` wurde dafür auf einen
  neuen Stand gehoben (wie schon beim Journey-Log, [API](../05-api.md)).
- In unserem Code heißt das Verzeichnis englisch, wie der Port: `ClaimSource.PERSON_DIRECTORY`
  (gespeichert als `person_directory`), `AttributeAuthority.PersonDirectory`,
  `RetractionAnchor.PERSON_DIRECTORY`.
  Deutsch bleibt nur das Fremdsystem selbst (`ext_personenverzeichnis`, `Personenverzeichnis`).
