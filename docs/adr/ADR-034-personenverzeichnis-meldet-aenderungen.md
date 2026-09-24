# ADR-34: Personenverzeichnis – Partnernummer, drei Rollen, Änderungen per Event bis Keycloak

**Entscheidung** (**umgesetzt**): Das simulierte Fremdsystem heißt **Personenverzeichnis** (vorher
„Personenregister“, Modul `ext_personenverzeichnis`). Ändert es eine Person, veröffentlicht es
`tool_api.PersonChanged(personId, changed, kvnr, versnr)`. Das Konto-Modul folgt der Änderung und
meldet `AccountChanged(accountId, changed)`; der bestehende Keycloak-Syncer spiegelt, wenn dabei
Attribute betroffen sind, die nach Keycloak gehen. Alles läuft über die Event-Publication-Registry
(ADR-29): persistiert, bei Fehler wiederholt.

## Kennungen einer Person

| Kennung | Bedeutung | Änderbar | Im Konto |
|---|---|---|---|
| `personId` | Partnernummer: `P` und neun Ziffern, vom Verzeichnis zufällig vergeben, Schlüssel jeder Person | nein | Anker `PERSON_ID` |
| Versicherungsnummer (`versnr`) | 8 Ziffern, nur wer bei uns versichert ist | ja, auch entfernbar | Anker `VERSNR`, wenn vorhanden |
| KVNR | Krankenversichertennummer, **nur zusammen mit einer Versicherungsnummer** – darf aber zeitweise fehlen (sie ändert sich ab und zu) | ja, auch entfernbar | Claim (Historie, jüngster gilt) |

Die Partnernummer ist bewusst keine laufende Zahl: Sie verrät weder Reihenfolge noch Anzahl, und sie
ist der Wert, den ein Mensch auf einem Brief liest und eintippt. Die Regel „KVNR nur mit
Versicherungsnummer“ steht im Schema (`ck_person_kvnr_nur_versichert`) und im Verzeichnis selbst.

## Drei Rollen

| Rolle | Erkennbar an | Anzeige in der App |
|---|---|---|
| **Versicherter** | Versicherungsnummer vorhanden | „Angemeldet als … (Versicherter)“ |
| **Partner** | Partnernummer bekannt (Konto gebunden), keine Versicherungsnummer | „… (Partner)“ |
| **Interessent** | keine Person zugeordnet | „… (Interessent)“ |

Die ID-Claims tragen dafür `personId` und `versnr` (live aus dem Verzeichnis); die App leitet die
Rolle daraus ab, das Backend kennt keinen eigenen Rollenwert.

## Zuordnung: erst KVNR, sonst Partnernummer

`ident-kvnr` (nach eID/Nect) und `ident-fsc` (Brief mit Freischaltcode) nehmen beide Kennungen – ein
Verfahren, zwei Kennungen, kein zweites Tool. Der Client fragt zuerst nach der KVNR und erst auf
„Ich habe keine Versichertennummer“ nach der Partnernummer (App: umschaltbares Feld, Keycloak:
aufklappbarer Abschnitt). Kommen beide an, entscheidet die KVNR (`findPersonIdByKvnr` vor
`findPersonIdByPartnernr`); `ident-fsc` hält immer nur eine der beiden fest. Ein über die
Partnernummer gebundenes Konto bekommt keinen KVNR-Claim – der kommt, wenn vorhanden, mit der nächsten
Änderung aus dem Verzeichnis. Die Prüfung dahinter ist dieselbe wie bei der KVNR: Personalien gegen
das Verzeichnis (`matchesPersonalien`/`attestedIdentityMatches`), Anker erst danach.

Name, Vorname, Geburtsdatum und Adresse bleiben **live** gelesen (`PersonDirectory`) – das Konto
speichert davon nichts Aktuelles, eine Änderung braucht dort also keinen Claim.

## Warum das Event in `tool_api` liegt

Publisher (`ext_personenverzeichnis`) und Empfänger (`account`) kennen einander nicht; beide hängen
schon an `tool_api` (`PersonDirectory`). Das Event trägt – wie eine echte Änderungsmitteilung – nur
die Person, die Arten der geänderten Attribute und die neuen **Kennungen** (KVNR, Versicherungsnummer),
weil das Konto genau diese selbst speichert; übrige Stammdaten nie.

## Benannter Sonderweg: Anker ohne Sitzung

Anker werden sonst mit dem Sicherheitsniveau der Sitzung bezahlt (`AnchorRule.acrFloor`). Eine
Änderung im Verzeichnis hat keine Sitzung – das Verzeichnis selbst ist die Autorität für seine
Kennungen. `AccountService.applyDirectoryChange` schreibt deshalb KVNR-Claim und `VERSNR`-Anker mit
dem Niveau des Verzeichnisses (loa2): nur dieser Weg, nur diese zwei Arten, nur für das per
`PERSON_ID` an genau diese Person gebundene Konto. Der alte Wert wird zurückgenommen
(`RetractionAnchor.PERSON_DIRECTORY`) und der neue geschrieben; eine entfernte Kennung wird nur
zurückgenommen – eine fehlende
KVNR ist ein Zwischenzustand, ohne Versicherungsnummer ist die Person Partner.
Beim Binden (`ident-kvnr`, `ident-fsc`) entsteht der `VERSNR`-Anker regulär mit der Sitzung;
`PersonDirectory.versnrOf` reicht dafür die Kennung über den Port.

## Keycloak nur, wenn es Keycloak betrifft

`AccountChanged.changed` nennt die Arten, wenn die Ursache sie kennt (heute nur diese); `null` heißt
„alles neu lesen“. Der Syncer überspringt eine benannte Änderung, die nichts Gespiegeltes berührt.
Für ein gebundenes Konto spiegelt er nur Verzeichniswerte – ein im Verzeichnis geleertes Feld bleibt
leer, statt aus alten bezeugten Claims wieder aufzutauchen.

## Folgen

- Ein Token, das vor der Änderung ausgestellt wurde, trägt die alten Werte bis zur Erneuerung.
- Wer die Versicherungsnummer verliert, verliert den Anker, nicht das Konto (`PERSON_ID` bleibt) –
  und wird vom Versicherten zum Partner.
- `personId` ist überall ein String (`P…`), auch im Demo-Feld `demo.personId` der Antworthülle – ein
  bewusster Bruch von v1, `api/published/v1.yaml` wurde dafür neu gehoben (wie beim Journey-Log,
  [API](../05-api.md)).
- Unsere Seite nennt das Verzeichnis englisch wie den Port: `ClaimSource.PERSON_DIRECTORY`
  (Wire `person_directory`), `AttributeAuthority.PersonDirectory`, `RetractionAnchor.PERSON_DIRECTORY`.
  Deutsch bleibt nur das Fremdsystem selbst (`ext_personenverzeichnis`, `Personenverzeichnis`).
