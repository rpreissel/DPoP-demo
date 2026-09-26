# ADR-19: Konten werden nur über Anker gefunden — auch die `restricted_id` der eID ist einer

**Status**: umgesetzt.

**Entscheidung**: `IdentityMatchingService.resolve` sucht zu einer bestätigten Identität das Konto
**nur über lokale Anker** (`resolveByAnchor`, in der Rangfolge von `AnchorRule.bindingStrength`). Lokale
Anker sind heute `PERSON_ID`, `INSURANCE_NUMBER`, `EID_RESTRICTED_ID`, `NECT_RESTRICTED_ID` (das Kartenpseudonym, wenn Nect die Karte liest, § 18 PAuswG) und `EMAIL`. Passt keiner, lautet das
Ergebnis `Unresolved`.

Die frühere zweite Stufe, die die Kombination aus Name, Vorname und Geburtsdatum mit der
Claim-Historie aller Konten verglich, ist entfernt, samt ihrem Ergebnis `Resolution.Ambiguous`.

Zum Wiedererkennen von Interessenten mit eID dient stattdessen die `restricted_id` der Karte, die
`ident-eid` als Claim meldet. Sie ist ein an die Karte gebundenes Pseudonym; in der Demo steht sie
stellvertretend für den echten Restricted Identifier. Sie ist ein lokaler Anker
(`AttributeAuthority.Local`) mit `AnchorAcrFloor(LOA2, LOA2)` und `allowsReplacement = true`: Eine neue
Karte bringt einen neuen Wert, der den alten an derselben Stelle ersetzt, genau wie bei `EMAIL`. Der
ersetzte Wert wird dabei widerrufen (Auslöser „Anker ersetzt“ in
[ADR-12](ADR-012-ein-widerruf-ist-eine-eigene-zeile-mit-eigenem.md)). Gehört der Wert einem anderen
Konto, wird er abgewiesen ([ADR-11](ADR-011-kontouebergreifender-person-id-konflikt-ist-abweisung-merge-nie.md)).

Der Abgleich von Name, Vorname und Geburtsdatum bleibt dort, wo er fachlich hingehört: als **Prüfung
gegen `ext_personenverzeichnis`**, ob die Angaben zur gefundenen Person passen, nie als Suche über die
Claims der Konten.

- `verifyToolAttestedConsistency` prüft die über KVNR oder Partnernummer gefundene Person gegen die
  bestätigten Attribute.
- `attestedIdentityMatches` vergleicht, bevor der Anker der Zuordnung geschrieben wird, die Stammdaten
  hinter der Nummer mit der bestätigten Identität.

**Erwogene Alternative**: Die Kombination der Attribute behalten, sie aber nur zusammen mit dem
Vergleich der KVNR wirken lassen und gegen `ext_personenverzeichnis` statt gegen die Konten prüfen.

**Begründung**:

- Die Claims eines Kontos sagen, woher ein Wert kam; maßgeblich wie das Personenverzeichnis sind sie
  nicht. Ein Treffer in allen drei Attributen kann dieselben Daten in fremden Konten finden und war
  damit schwächer als das, was er ersetzen sollte.
- Der Abgleich gegen `ext_personenverzeichnis` schützt schon. Eine zweite Suche über die Historie der
  Konten wäre überflüssig und brächte nur den nie sauber festgelegten Ausgang `Ambiguous` in die
  Journey.
- Die `restricted_id` ist das richtige Merkmal, um Interessenten mit eID wiederzuerkennen: an die Karte
  gebunden, mit einem neuen Ausweis neu, aber nie für zwei Personen gleich. Genau das leistet ein
  ersetzbarer Anker.
- Die Richtung zur EUDI-Wallet passt dazu: Die echte PID käme als eigene Art von Anker hinzu.

**Folgen und Kosten**: Bestätigungen aus der Zeit vor diesem ADR, also ohne `restricted_id`, erkennt
das System nicht wieder; sie enden bei `Unresolved` und damit bei einem neuen Konto. Die `restricted_id`
wird bewusst **nicht** nach Keycloak gespiegelt: Sie ist kein Stammdatum, nur ein Anker zum
Wiedererkennen.

**Geschichte**: Entfernt wurden mit diesem ADR `Resolution.Ambiguous`, `MatchedVia.Attributes`,
`BindingStrength.ATTRIBUTE_COMBINATION`, `findAccountIdsMatchingAllThree` und der Index
`ix_claim_type_value`. Die Hausnummer, anfangs ein eigener Claim, ist inzwischen in `STREET_ADDRESS`
aufgegangen. `INSURANCE_NUMBER` kam mit [ADR-34](ADR-034-personenverzeichnis-meldet-aenderungen.md) als weiterer
lokaler Anker hinzu.
