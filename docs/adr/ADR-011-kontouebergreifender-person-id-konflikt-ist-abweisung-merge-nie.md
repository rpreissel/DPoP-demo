# ADR-11: Kontoübergreifender person_id-Konflikt wird abgewiesen, Konten werden nie automatisch zusammengeführt

> **Stand 2026-09-23:** Die Abweisung gilt weiter; Mechanismus, Meldungstext und eine Ausnahme
> (vorläufiges Konto, ADR-20) haben sich geändert. Siehe Nachtrag 4.

**Entscheidung** (**umgesetzt**, [Idee](../ideen/claims-modell-und-vertrauensanker.md)): Beanspruchen zwei Konten denselben `person_id`-Wert, wird die zweite Bindung abgewiesen (409, „Diese Person ist bereits über ein anderes Konto registriert“), und es wird nichts übernommen: keine Claim-Zeile, keine Zusammenfassung, kein Anker. Konten werden nie automatisch zusammengeführt. Das Zusammenführen wäre eine Aufgabe des Betreibers und liegt außerhalb dessen, was das Claims-Modell abdeckt. In der Datenbank sichert `UNIQUE(person_id)` (als Teilindex, `WHERE person_id IS NOT NULL`) dieselbe Regel für alle Wege ab, auf denen geschrieben wird.

**Erwogene Alternative**: Die bestätigte Aussage trotzdem protokollieren und nur verweigern, dass sie
zum gültigen Wert wird (der Konflikt als abfragbarer Zustand); oder eine Warteschlange zur Prüfung
durch Menschen.

**Warum diese**: Zwei verschiedene Menschen dauerhaft zu verknüpfen ist der teuerste Fehler, den
dieses Modell machen kann. Das zu vermeiden wiegt schwerer als der Verlust der bestätigten Aussage,
die im Journey-Log ohnehin vorübergehend nachweisbar bleibt. Die Rangfolge, nach der Werte
zusammengefasst werden (erst die Art des Ankers, dann die Aktualität), gilt innerhalb EINES Kontos und
endet an der Grenze zum nächsten Konto.

**Kosten**: Der betroffene Nutzer kommt nicht von selbst weiter. Solange es keine Funktion zum
Zusammenführen gibt, bleibt der Fall eine Aufgabe für den Support, und die bestätigte Identifizierung
steht nur im Journey-Log.

**Nachtrag** (Härtung): Drei Lücken zwischen dieser Entscheidung und ihrer Umsetzung wurden
geschlossen. Erstens schrieb `AccountService.recordAnchor` bei einem fremden Anker trotz Abbruch die
abgeleitete Spalte; jetzt wird der Anker **vor** dieser Spalte geschrieben. Zweitens verließ sich
`IdentityMatchingService.resolveByAnchor` auf die zufällige Reihenfolge eines `Set`; jetzt sortiert
es ausdrücklich nach `AttributeType.anchorRule?.bindingStrength` (`tool_api/AttributeRules.kt`).
Drittens fing `findOrCreateAccount` den Verstoß gegen `UNIQUE(person_id)` nicht ab; das ist durch
die unteilbare Übernahme der Claims (unten) ersetzt.

**Nachtrag 2** ([ideen/account-attribute-und-trust-vereinheitlichen.md](../ideen/account-attribute-und-trust-vereinheitlichen.md), alle 7 Pakete): `person_id` ist
seitdem kein Sonderfall mehr, sondern ein gewöhnlicher Eintrag in `account.anchor` wie `email`, nur
mit dem höchsten Rang. Die Abweisung über Kontogrenzen hinweg läuft seitdem über denselben Weg
`recordAnchor` und nicht mehr über einen eigenen Vergleich mit `bindPersonId`/`findAccountByPersonId`.
`MatchedVia.PersonId` ist entfallen; an seine Stelle tritt `MatchedVia.Anchor(PERSON_ID)`. Neu hinzu
kam eine Regel INNERHALB eines Kontos: `AttributeType.anchorRule?.allowsReplacement` ist für
`PERSON_ID` `false` (anders als bei `email`). Ein zweiter, abweichender `person_id`-Claim für ein Konto
wird deshalb ebenfalls mit `IdentityConflictException` abgewiesen.

**Nachtrag 3 — Konto in einem Schritt anlegen:** `findOrCreateAccount` und
`AccountRaceSafeCreator` entfallen. `findAccountByPersonId` und `findAccountByEmail` bleiben als
Erweiterungsfunktionen von `AccountService` und rufen `resolveByAnchor` auf. Ein neues Konto entsteht ungebunden
und erhält seine PersonId ausschließlich über `recordClaims`, gemeinsam mit Log und Anker in
der Journey-Transaktion. Binden zwei Vorgänge gleichzeitig, wird der unterlegene vollständig
zurückgerollt und erhält `409 INVALID_STATE_TRANSITION`.

**Wer die KVNR verwaltet:** Die KVNR ist eindeutig, kann sich aber mit der Zeit ändern. Sie wird
ausschließlich von `ext_personenverzeichnis` verwaltet (Suchweg: KVNR → PersonId im Verzeichnis →
PersonId-Anker bei uns → Konto) und ist keine Art von Anker des Kontos mehr. Die E-Mail-Adresse
bleibt dagegen ein Anker, der im Konto bestätigt wird und sich ändern darf. Seit ADR-34 ist die PersonId die
Partnernummer und damit selbst ein zweiter Suchweg; die Versicherungsnummer ist ein lokaler,
ersetzbarer Anker (`VERSNR`), die KVNR bleibt ein Claim.

**Nachtrag 4 (2026-09-23)** — heutiger Stand der Aussagen im Entscheidungsabsatz:
- Die Spalte `person_id` und ihr partieller `UNIQUE`-Index gibt es seit ADR-14 nicht mehr. Die
  Eindeutigkeit sichert `ux_anchor_value UNIQUE (attribute_type, normalized_value)` auf
  `account.anchor` (`account/V2__account.sql`) für alle Anker gleichermaßen; die Kollision wird
  zu `409`.
- Die Meldung lautet heute „Dieser `<typ>`-Wert gehoert bereits zu einem anderen Konto“
  (`AccountService`, beim Schreiben eines Ankers).
- „Nie automatisch zusammengeführt“ hat eine Ausnahme: Ist eines der beiden Konten vorläufig,
  geht es im gefundenen auf ([ADR-20](ADR-020-ein-vorlaeufiges-konto-geht-im-gefundenen-auf-statt.md)).
  Zwischen zwei echten Konten bleibt es bei der Abweisung.
- Die im ersten Nachtrag genannte abgeleitete Spalte ist mit ADR-14 entfallen; die Härtung betrifft
  heute nur noch die Zeile des Ankers.

---
