# ADR-17: Adresse bestätigen und E-Mail-Login einrichten sind zwei Akte

> **Stand 2026-09-23:** Die Reihenfolge im Entscheidungsabsatz war falsch angegeben; siehe Nachtrag.

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

**Nachtrag (2026-09-23)**:
- *Reihenfolge.* Oben steht „Adresse, Passwort, Besitzfaktor“. Tatsächlich — und technisch
  erzwungen — kommt das Passwort zuletzt: `ConfirmingEmail → Enrolling → PasswordObligation`
  (`RegisterStrategy`, [Orchestrierung](../04-orchestrierung.md), „Eine dritte Pflicht“). Die
  Passwortpflicht prüft erst am Ende, ob das Konto `loa2` sonst erreicht.
- *„Enrollment zuerst“.* `RegisterEnrollFirstStrategy` prüft seit 2026-09-23 dieselbe
  `loa2`-Bedingung wie `RegisterStrategy` (vorher fragte sie ohne Bedingung). Am Verhalten ändert
  das nichts: In diesem Ablauf ist die Identifizierung freiwillig, ein Zugangsmittel aber Pflicht.
  Ohne Identifizierung wird jede Methode unter `loa1` eingerichtet, und der Zwei-Faktor-Bump ist
  durch `enrolledUnderAcr` gedeckelt (ADR-5) — `loa2` ist dort also nie aus eigener Kraft
  erreichbar, und das Passwort wird weiterhin immer verlangt. Eine Regel statt zweier Lesarten.

---
