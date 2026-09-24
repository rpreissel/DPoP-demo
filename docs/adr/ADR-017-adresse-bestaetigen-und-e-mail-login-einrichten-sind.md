# ADR-17: Adresse bestätigen und E-Mail-Login einrichten sind zwei Schritte

**Status:** umgesetzt.

## Entscheidung

Eine E-Mail-Adresse zu bestätigen ist ein eigenes Tool, `confirm-email`. Es hat die Kategorie
`ToolCategory.ATTEST` (Rolle `ATTESTATION`) und ein eigenes Ergebnis,
`ToolOutcome.Completed.Attested`: mit Claims, aber ohne `enrollmentRef`, ohne `amr` und ohne
Methodeninstanz. Die bestätigte Adresse wird damit Teil des Kontos und kein Verfahren.

`enroll-email` richtet danach nur noch die Anmeldung per E-Mail-Code ein. Es setzt eine bestätigte
Adresse voraus (`ClaimRequirement(EMAIL, PROVEN)`) und braucht deshalb keinen eigenen Code-Austausch
mehr: Die Aktivierung schließt es bereits ab. Wie diese Abhängigkeit über verlangte Angaben
funktioniert, beschreibt [ADR-24](ADR-024-eine-methode-haengt-von-einer-anderen-ab-indem.md).

Die Registrierung läuft in dieser Reihenfolge (`RegisterStrategy`):

1. Adresse bestätigen (`RegisterState.ConfirmingEmail`),
2. ein Anmeldeverfahren einrichten (`Enrolling`),
3. falls das Konto sonst `loa2` nicht erreichen könnte, zusätzlich ein Passwort
   (`RegisterState.PasswordObligation`).

Die Passwortpflicht gilt auf beiden Kanälen und im Experiment „Erst Anmeldeverfahren einrichten“
nach derselben Regel. Sie ist in der
[Orchestrierung](../04-orchestrierung.md#eine-dritte-pflicht-auf-einen-intent-begrenzt) beschrieben.

## Begründung

Die Adresse gehört dem Konto, nicht einem Verfahren (`AttributeType.authority` ist
`AttributeAuthority.Local`). Drei Verfahren finden das Konto über sie, wenn man sich mit der
E-Mail-Adresse anmeldet, und `enroll-password` setzt sie voraus. Sie gehört also zur Grundausstattung
des Kontos. Weil Bestätigen und Einrichten getrennt sind, nimmt das Entfernen des E-Mail-Verfahrens
die Adresse nicht mehr mit.

Früher entstand der Wissensfaktor nebenbei, wenn man die Adresse bestätigte. Seit das nicht mehr so
ist, braucht die Registrierung eine eigene Regel für `loa2`: das Passwort, aber nur dann, wenn das
Konto `loa2` sonst nicht erreicht. Ein Geräteschlüssel deckt Besitz, Wissen und Biometrie zugleich ab
und genügt dafür allein.

**Erwogene Alternative:** alles beim Alten lassen und die Verbindung nur dokumentieren, also
`enroll-email` bestätigt die Adresse *und* legt das Verfahren an.

## Folgen

- Ein Schritt mehr in der Registrierung und ein Tool mehr im Katalog.
- Die frühere Ungleichheit „Passwort nur im Web“ ist weg.

## Geschichte

- Die erste Fassung nannte die Reihenfolge „Adresse, Passwort, Besitzfaktor“. Der Ablauf war aber
  immer Adresse, Verfahren, Passwort; die Passwortpflicht prüft erst am Ende, ob das Konto `loa2`
  sonst erreicht.
- Seit 2026-09-23 prüft auch `RegisterEnrollFirstStrategy` dieselbe Bedingung für `loa2`, vorher
  fragte sie ohne Bedingung. Am Verhalten änderte das nichts: Dort wird ohne Identifizierung jedes
  Verfahren auf `loa1` eingerichtet, und die Anhebung durch ein zweites Verfahren begrenzt
  `enrolledUnderAcr` ([ADR-5](ADR-005-drei-obergrenzen-fuer-das-sicherheitsniveau.md)). `loa2` ist
  dort also aus eigener Kraft nie erreichbar, und das Passwort wird weiter immer verlangt.
- Mit der Einführung mussten mehrere Integrationstests ihre Erwartung ändern
  (`sms + email` → `sms + password`).
