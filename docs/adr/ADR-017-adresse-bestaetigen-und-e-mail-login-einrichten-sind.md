# ADR-17: Adresse bestätigen und E-Mail-Login einrichten sind zwei Schritte

> **Stand 2026-09-23:** Die Reihenfolge im Entscheidungsabsatz war falsch angegeben; siehe Nachtrag.

**Entscheidung**: Die Bestätigung einer E-Mail-Adresse ist ein eigenes Tool `confirm-email` mit
eigener Kategorie `ToolCategory.ATTEST` (Rolle `ATTESTATION`) und eigener Ergebnisform
`ToolOutcome.Completed.Attested`: mit Claims, aber ohne `enrollmentRef`, mit leerem `amr` und
**ohne** Methodeninstanz. `enroll-email` bleibt daneben bestehen, setzt aber eine bereits
bestätigte Adresse voraus (`ClaimRequirement(EMAIL, PROVEN)`) und ist dadurch ein Ein-Schritt-Tool
ohne Code-Austausch. Weil der Wissensfaktor damit nicht mehr nebenbei beim
Bestätigen der Adresse entsteht, verlangt eine Registrierung auf **jedem** Kanal ein Passwort, aber
nur, wenn das Konto `loa2` sonst nicht erreichen könnte. Ein Geräteschlüssel deckt `POSSESSION`,
`KNOWLEDGE` und `INHERENCE` zugleich ab und genügt dafür also allein. Die Reihenfolge ist Adresse, Passwort, Besitzfaktor.

**Erwogene Alternative**: Alles beim Alten lassen und die Verbindung nur dokumentieren: `enroll-email`
bestätigt die Adresse *und* legt das Verfahren an.

**Warum diese**: Die Adresse gehört dem Konto, nicht dem Verfahren (`AttributeType.authority` ist
`AttributeAuthority.Local`). Drei andere Verfahren finden das Konto über sie, wenn man sich mit der E-Mail-Adresse anmeldet, und
`enroll-password` setzt sie voraus. Sie gehört also zur Grundausstattung des Kontos. Durch die
Trennung kann das Entfernen des E-Mail-Verfahrens die Adresse gar nicht mehr mit entfernen.

**Kosten**: Ein Schritt mehr in der Registrierung, ein Tool mehr im Katalog, und eine Reihe von
Integrationstests musste ihre Erwartung ändern (`sms + email` → `sms + password`). Die Ungleichheit
„Passwort nur im Web“ entfällt.

**Nachtrag (2026-09-23)**:
- *Reihenfolge.* Oben steht „Adresse, Passwort, Besitzfaktor“. Tatsächlich kommt das Passwort
  zuletzt, und der Ablauf erzwingt das auch: `ConfirmingEmail → Enrolling → PasswordObligation`
  (`RegisterStrategy`, [Orchestrierung](../04-orchestrierung.md), „Eine dritte Pflicht“). Die
  Passwortpflicht prüft erst am Ende, ob das Konto `loa2` sonst erreicht.
- *„Erst Anmeldeverfahren einrichten“.* `RegisterEnrollFirstStrategy` prüft seit 2026-09-23
  dieselbe Bedingung für `loa2` wie `RegisterStrategy` (vorher fragte sie ohne Bedingung). Am
  Verhalten ändert das nichts: In diesem Ablauf ist die Identifizierung freiwillig, ein
  Anmeldeverfahren aber Pflicht. Ohne Identifizierung wird jedes Verfahren auf `loa1` eingerichtet,
  und die Anhebung durch ein zweites Verfahren ist durch `enrolledUnderAcr` begrenzt (ADR-5). `loa2`
  ist dort also aus eigener Kraft nie erreichbar, und das Passwort wird weiterhin immer verlangt. So
  gilt eine einzige Regel statt zweier Auslegungen.

---
