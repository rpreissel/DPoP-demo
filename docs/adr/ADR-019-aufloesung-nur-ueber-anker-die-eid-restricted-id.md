# ADR-19: Konten werden nur über Anker gefunden — auch die `restricted_id` der eID ist einer

> **Nachtrag.** `V1__schema.sql` ist inzwischen in eine Datei je Modul aufgeteilt, siehe
> [ADR-30](ADR-030-eine-migration-je-modul.md). Die Aussagen unten gelten unverändert; nur die
> Datei, auf die sie sich beziehen, gibt es so nicht mehr.


**Entscheidung**: `IdentityMatchingService.resolve` sucht zu einer bestätigten Identität das Konto
**nur noch über lokale Anker** (`resolveByAnchor`, in der Rangfolge von `AnchorRule.bindingStrength`).
Passt kein Anker, lautet das Ergebnis `Unresolved`.

Die bisherige zweite Stufe ist vollständig entfernt. Dort wurde die normalisierte Kombination aus
Name, Vorname und Geburtsdatum mit der Claim-Historie der Konten verglichen, und bei mehreren
Treffern lautete das Ergebnis `Resolution.Ambiguous`. Entfallen sind dafür `Resolution.Ambiguous`,
`MatchedVia.Attributes`, `BindingStrength.ATTRIBUTE_COMBINATION`, `findAccountIdsMatchingAllThree`
und der Index `ix_claim_type_value`.

An ihre Stelle tritt die `restricted_id` der eID-Karte, die `ident-eid` als achten Claim meldet
(heute ist es der siebte, weil die Hausnummer in `strasse` aufgegangen ist). Sie ist ein an die Karte
gebundenes Pseudonym; in der Demo steht sie stellvertretend für den echten Restricted Identifier.
Geführt wird sie als lokaler Anker (`AttributeAuthority.Local`) mit `AnchorAcrFloor(LOA2, LOA2)` und
`allowsReplacement = true`: Eine neue Karte bringt einen neuen Wert, der den alten an derselben Stelle
ersetzt, genau wie bei `EMAIL`. Gehört der Wert einem anderen Konto, wird er weiterhin abgewiesen
(`IdentityConflictException`). Seit dem zweiten Nachtrag zu ADR-12 verliert der ersetzte Wert auch im
Claim-Log seine Gültigkeit: Beim Ersetzen des Ankers wird ein Widerruf für den alten Wert geschrieben,
damit Log und Anker übereinstimmen.

Der Abgleich der drei Attribute Name, Vorname und Geburtsdatum bleibt dort, wo er fachlich
hingehört: als **Prüfung gegen `ext_personenverzeichnis`**, ob die Angaben zusammenpassen, und nie
als Suche über die Claims der Konten.

- `verifyToolAttestedConsistency` prüft die Person, die über die KVNR gefunden wurde, gegen die
  bestätigten Attribute.
- `attestedIdentityMatches` vergleicht, bevor der Anker der Zuordnung geschrieben wird, die
  Stammdaten hinter der Nummer mit der bestätigten Identität.

**Erwogene Alternative**: Die Kombination der Attribute behalten, sie aber nur noch zusammen mit dem
Vergleich der KVNR wirken lassen und ihre Werte gegen `ext_personenverzeichnis` statt gegen die Konten
prüfen.

**Warum diese**:

- Die Claims eines Kontos sagen, woher ein Wert kam; maßgeblich wie das Personenverzeichnis sind sie
  nicht. Ein Treffer in allen drei Attributen kann dieselben Daten in fremden Konten finden und war
  damit schwächer als das, was er ersetzen sollte.
- Der Abgleich der KVNR gegen `ext_personenverzeichnis` ist als Schutz schon vorhanden. Eine zweite
  Suche über die Historie der Konten wäre überflüssig und brächte nur den nie sauber festgelegten
  Ausgang `Ambiguous` in die Journey.
- Die `restricted_id` ist fachlich das richtige Merkmal, um Interessenten mit eID wiederzuerkennen:
  Sie ist an die Karte gebunden, ändert sich mit einem neuen Ausweis, ist aber nie für zwei Personen
  gleich. Genau das leistet ein ersetzbarer Anker.
- Die Richtung zur EUDI-Wallet passt dazu: Die echte PID kommt später als eigene Art von Anker hinzu.

**Kosten**: Bestätigungen aus der Zeit vor diesem ADR, also ohne `restricted_id`, erkennt das System
nicht wieder. Sie enden bei `Unresolved` und damit bei einem neuen Konto. `V1__schema.sql` ändert sich
(`id_eid.ident_tool_session.restricted_id`, `ix_claim_type_value` entfällt); bestehende Datenbanken zum
Entwickeln müssen neu angelegt werden. Die `restricted_id` wird bewusst **nicht** nach Keycloak
gespiegelt: Sie ist kein Stammdatum, sondern nur ein Anker zum Wiedererkennen.

---
