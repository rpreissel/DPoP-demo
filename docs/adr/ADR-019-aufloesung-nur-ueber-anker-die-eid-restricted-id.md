# ADR-19: Auflösung nur über Anker — die eID-`restricted_id` wird einer

> **Nachtrag.** `V1__schema.sql` ist inzwischen in eine Datei je Modul aufgeteilt, siehe
> [ADR-30](ADR-030-eine-migration-je-modul.md). Die Aussagen unten gelten unverändert; nur die
> Datei, auf die sie sich beziehen, gibt es so nicht mehr.


**Entscheidung**: `IdentityMatchingService.resolve` löst eine bestätigte Identität **nur noch über
lokale Anker** auf (`resolveByAnchor`, Rangfolge nach `AnchorRule.bindingStrength`); ohne
Anker-Treffer ist das Ergebnis `Unresolved`. Die bisherige zweite Schicht — normalisierte
Attributkombination Name+Vorname+Geburtsdatum gegen die Claim-Historie mit
`Resolution.Ambiguous` als Mehrdeutigkeits-Ergebnis — ist komplett entfernt
(`Resolution.Ambiguous`, `MatchedVia.Attributes`, `BindingStrength.ATTRIBUTE_COMBINATION`,
`findAccountIdsMatchingAllThree`, Index `ix_claim_type_value`). An ihre Stelle tritt die
`restricted_id` der eID-Karte als achter Claim von `ident-eid` (heute der siebte: die Hausnummer ging
in `strasse` auf): ein kartengebundenes Pseudonym
(in der Demo ein Platzhalter für den echten Restricted Identifier). Sie wird als lokaler Anker geführt (`AttributeAuthority.Local`), mit `AnchorAcrFloor(LOA2, LOA2)` und `allowsReplacement = true`: Eine neue Karte bringt
einen neuen Wert, der den alten an derselben Stelle ersetzt — wie bei `EMAIL`. Hält ein anderes
Konto den Wert, bleibt es bei der Abweisung (`IdentityConflictException`). Der ersetzte Wert verfällt seit dem
zweiten ADR-12-Nachtrag auch im Claim-Log: Der Anker-Ersatz schreibt einen Widerruf für den
alten Wert, damit das Log mit dem Anker übereinstimmt.

Der Abgleich dreier Attribute (Name/Vorname/Geburtsdatum) bleibt genau dort, wo er
fachlich hingehört: als **Konsistenzprüfung gegen `ext_personenverzeichnis`**, nie als Auflösungsschicht
über Account-Claims. `verifyToolAttestedConsistency` prüft die KVNR-aufgelöste Person gegen die
bestätigten Attribute; `attestedIdentityMatches` vergleicht vor dem Korrelations-Anker die
Stammdaten hinter der Nummer mit der bestätigten Identität.

**Erwogene Alternative**: Die Attributkombination behalten, aber nur noch im Zusammenhang mit
dem KVNR-Vergleich wirken lassen und ihre Werte gegen `ext_personenverzeichnis` statt gegen den Account
prüfen.

**Warum diese**: Account-Claims sagen, woher ein Wert kam, sie sind keine Wahrheit des Personenverzeichnisses — ein
Dreifach-Treffer darauf kann denselben Datensatz in fremden Konten finden und war damit schwächer
als das, was er ersetzen sollte. Der KVNR-Abgleich gegen `ext_personenverzeichnis` ist bereits als Guard
vorhanden; eine zweite Matching-Schicht über Account-Historie ist redundant und erzeugt nur den
nie sauber spezifizierten `Ambiguous`-Kanal in der Journey. Die `restricted_id` ist das fachlich
richtige Wiedererkennungsmerkmal für eid-Interessenten: an die Karte gebunden, mit einem neuen
Ausweis ein neuer Wert, aber nie über Personen hinweg gleich — genau das, was ein ersetzbarer Anker
leistet. Die EUDI-Richtung passt dazu: Die echte PID kommt später als eigener Ankertyp hinzu.

**Kosten**: Bestätigungen aus der Zeit vor diesem ADR, also ohne `restricted_id`, erkennt das
System nicht wieder — sie laufen auf `Unresolved` und damit auf ein neues Konto. `V1__schema.sql` ändert
sich (`id_eid.ident_tool_session.restricted_id`, Wegfall `ix_claim_type_value`), bestehende
Dev-Datenbanken sind neu anzulegen. Die `restricted_id` wird bewusst **nicht** nach Keycloak
gespiegelt (kein Stammdatum, nur Wiedererkennungsanker).

---
