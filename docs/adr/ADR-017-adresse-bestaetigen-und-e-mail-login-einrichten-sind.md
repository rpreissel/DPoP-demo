# ADR-17: Adresse bestätigen und E-Mail-Login einrichten sind zwei Akte

**Entscheidung**: Die Bestätigung einer E-Mail-Adresse ist ein eigenes Tool `confirm-email` mit
eigener Kategorie `ToolCategory.ATTEST` (Rolle `ATTESTATION`) und eigener Ergebnisform
`ToolOutcome.Completed.Attested`: Claims ja, kein `enrollmentRef`, `amr` leer, **keine**
Methodeninstanz. `enroll-email` bleibt daneben bestehen, setzt aber eine bereits
bestätigte Adresse voraus (`ClaimRequirement(EMAIL, PROVEN)`) und ist dadurch ein Ein-Schritt-Tool
ohne Code-Austausch. Weil damit der Wissensfaktor nicht mehr
als Nebenprodukt der Adressbestätigung entsteht, verlangt eine Registrierung auf **jedem** Kanal
ein Passwort — aber nur dann, wenn das Konto `loa2` sonst nicht erreichen könnte. Ein
Gerätecredential trägt `POSSESSION`, `KNOWLEDGE` und `INHERENCE` zugleich, deckt das also allein
ab. Die Reihenfolge ist Adresse, Passwort, Besitzfaktor.

**Erwogene Alternative**: Alles beim Alten lassen und die Kopplung nur dokumentieren — `enroll-email`
bestätigt die Adresse *und* legt die Methode an.

**Warum diese**: Die Adresse gehört dem Konto, nicht dem Verfahren (`AttributeType.authority` ist
`AttributeAuthority.Local`). Drei fremde Lookup-Verfahren lösen das Konto über sie auf, und `enroll-password` setzt sie
voraus — sie ist Infrastruktur. Die Trennung sorgt dafür, dass das
Entfernen der Methode die Adresse gar nicht mehr mitreißen kann.

**Kosten**: Ein Schritt mehr in der Registrierung, ein Tool mehr im Katalog, und eine Reihe von
Integrationstests musste ihre Erwartung umstellen (`sms + email` → `sms + password`). Die
Asymmetrie „Passwort nur im Web" entfällt.

---
