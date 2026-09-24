# ADR-11: Kontoübergreifender person_id-Konflikt ist Abweisung, Merge nie automatisiert

> **Stand 2026-09-23:** Die Abweisung gilt weiter; Mechanismus, Meldungstext und eine Ausnahme
> (provisorisches Konto, ADR-20) haben sich geändert. Siehe Nachtrag 4.

**Entscheidung** (**umgesetzt**, [Idee](../ideen/claims-modell-und-vertrauensanker.md)): Beanspruchen zwei Konten denselben `person_id`-Wert, wird die zweite Bindung abgewiesen (409, "Diese Person ist bereits über ein anderes Konto registriert") und nichts adoptiert: keine Claim-Zeile, keine Konsolidierung, keine Anker-Schreibung. Ein Merge ist nie automatisiert, sondern eine operator-getriebene Fähigkeit außerhalb des Claims-Modell-Umfangs. DB-seitig sichert `UNIQUE(person_id)` (partial, `WHERE person_id IS NOT NULL`) dieselbe Semantik für alle Schreibpfade ab.

**Erwogene Alternative**: Die bestätigte Aussage trotzdem loggen und nur die Konsolidierung
verweigern (Konflikt als abfragbarer Zustand); oder eine Review-Queue.

**Warum diese**: Zwei verschiedene Menschen dauerhaft zu verknüpfen ist der teuerste Fehler, den
dieses Modell machen kann. Das zu vermeiden wiegt schwerer als der Verlust der bestätigten Aussage,
die im Journey-Log ohnehin vorübergehend nachweisbar bleibt. Die Rangfolge beim Zusammenführen
(Anker-Klasse vor Aktualität) gilt innerhalb EINES Kontos und endet an der Kontogrenze.

**Kosten**: Der betroffene Nutzer kommt nicht automatisch weiter —
Solange es keine Funktion zum Zusammenführen gibt, bleibt der Fall ein Support-Vorgang, und die
bestätigte Identifikation steht nur im Journey-Log.

**Nachtrag** (Härtung): Drei
Lücken zwischen dieser Entscheidung und ihrer Umsetzung wurden geschlossen. Erstens schrieb
`AccountService.recordAnchor` bei einem fremden Anker die Projektionsspalte trotz Abbruch; der
Anker wird jetzt **vor** der Projektionsspalte geschrieben. Zweitens
verließ sich `IdentityMatchingService.resolveByAnchor` auf die
beliebige Reihenfolge eines `Set` und sortiert jetzt ausdrücklich nach
`AttributeType.anchorRule?.bindingStrength` (`tool_api/AttributeRules.kt`).
Drittens fing `findOrCreateAccount` die
`UNIQUE(person_id)`-Kollision nicht ab; das ist durch die atomare
Claim-Übernahme unten abgelöst.

**Nachtrag 2** ([ideen/account-attribute-und-trust-vereinheitlichen.md](../ideen/account-attribute-und-trust-vereinheitlichen.md), alle 7 Pakete): `person_id` ist seither
kein Sonderfall mehr, sondern ein gewöhnlicher, höchstrangiger `account.anchor`-Eintrag wie `email`
— die kontoübergreifende Abweisung läuft seither
technisch über denselben `recordAnchor`-Pfad statt über einen separaten
`bindPersonId`/`findAccountByPersonId`-Vergleich, und
`MatchedVia.PersonId` ist zugunsten von `MatchedVia.Anchor(PERSON_ID)` entfallen.
Neu dazugekommen, INNERHALB eines Kontos: `AttributeType.anchorRule?.allowsReplacement`
ist für `PERSON_ID` `false` (anders als `email`) — ein zweiter, abweichender `person_id`-Claim für
ein Konto wird ebenfalls per `IdentityConflictException` abgewiesen.

**Nachtrag 3 — atomare Account-Anlage:** `findOrCreateAccount` und `AccountRaceSafeCreator`
entfallen; `findAccountByPersonId`/`findAccountByEmail` bleiben als Extensions auf
`AccountService` und delegieren an `resolveByAnchor`. Ein neues Konto entsteht ungebunden
und erhält seine PersonId ausschließlich über `recordClaims`, gemeinsam mit Log und Anker in
der Journey-Transaktion. Der Verlierer einer konkurrierenden Bindung erhält nach vollständigem
Rollback `409 INVALID_STATE_TRANSITION`.

**KVNR-Zuständigkeit:** Die eindeutige, zeitlich änderbare KVNR wird ausschließlich durch
`ext_personenverzeichnis` verwaltet (Suchpfad KVNR → externe PersonId → lokaler PersonId-Anker → Account)
und ist kein lokaler Account-Ankertyp mehr. E-Mail bleibt
dagegen ein im Account-System bestätigter, wechselbarer Anker.

**Nachtrag 4 (2026-09-23)** — heutiger Stand der Aussagen im Entscheidungsabsatz:
- Die Spalte `person_id` und ihr partieller `UNIQUE`-Index gibt es seit ADR-14 nicht mehr. Die
  Eindeutigkeit sichert `ux_anchor_value UNIQUE (attribute_type, normalized_value)` auf
  `account.anchor` (`account/V2__account.sql`) für alle Anker gleichermaßen; die Kollision wird
  zu `409`.
- Die Meldung lautet heute „Dieser `<typ>`-Wert gehoert bereits zu einem anderen Konto“
  (`AccountService`, Anker-Schreibpfad).
- „Merge nie automatisiert“ hat eine Ausnahme: Ist eines der beiden Konten provisorisch,
  geht es im gefundenen auf ([ADR-20](ADR-020-ein-vorlaeufiges-konto-geht-im-gefundenen-auf-statt.md)).
  Zwischen zwei echten Konten bleibt es bei der Abweisung.
- Die im ersten Nachtrag genannte „Projektionsspalte“ ist mit ADR-14 entfallen; die Härtung
  betrifft heute nur noch die Anker-Zeile.

---
