# ADR-14: Das Konto als gemeinsame Sperre, jeder Fakt an genau einer Stelle

**Entscheidung** (umgesetzt): Das Kontomodell folgt zwei Regeln.

- **Die Kontozeile ist nur Identität und Sperre.** `account.account` hat nur `id`, `created_at` und
  `version`. Wer den aktuellen Zustand eines Kontos ändert, lädt diese Zeile mit
  `OPTIMISTIC_FORCE_INCREMENT` (`AccountRepository`). Schreiben zwei Vorgänge gleichzeitig, bekommt der
  zweite `409 CONCURRENT_MODIFICATION`.
- **Jeder Fakt steht an genau einer Stelle.**
  - Aktueller Zustand, der die Version erhöht: `account.anchor` (einziger Speicherort der lokal
    geführten Kennungen wie PersonId, Versicherungsnummer und bestätigte E-Mail-Adresse) und
    `account.auth_method` (eine Zeile je Methodeninstanz, `EnrollmentRef` als eigene Spalten).
  - Historie, die nur angefügt wird und die Version nie erhöht: `account.claim` (wer was wann
    bestätigt hat), `account.change_log` (IDENTIFIED) (jede Identifizierung) und `account.retraction`
    (Widerrufe, [ADR-12](ADR-012-ein-widerruf-ist-eine-eigene-zeile-mit-eigenem.md)).

Welche Attribute das Konto selbst führt und welche es nur liest, beschreibt
[Domänenmodell](../02-domaenenmodell.md), Abschnitt 6 (`AttributeType.authority`).

**Erwogene Alternative**: Das Modell so lassen, wie es war: Listen als JSON in der Kontozeile
(`identifications`, `authentication_methods`) und abgeleitete Spalten (`person_id`, `email`,
`email_confirmed_at`) neben den Ankern.

**Warum diese**: Bei zehn Millionen Konten und mehr und langer Laufzeit lassen sich JSON-Listen weder
abfragen („alle Konten mit Verfahren X“) noch günstig schreiben, denn jede Änderung schreibt die ganze
Zeile. Und jede abgeleitete Spalte neben einem Anker ist eine zweite Stelle, die für denselben Fakt
Eindeutigkeit garantieren müsste. Genau diesen Fehler musste ein früherer Review-Befund (A3) schon
einmal beheben. Mit einer Zeile je Fakt bedeuten „wird zu einem gültigen Wert zusammengefasst“ und
„ist ein Anker“ dasselbe.

**Kosten**: Ein Kontoprofil braucht drei Lesezugriffe über einen Index statt einem. Die E-Mail-Adresse
im Profil ist die normalisierte Form; die ursprüngliche Schreibweise steht nur noch im Claim-Log.

**Rückblick**: Eingeführt wurde das Modell zusammen mit einer neuen Ausgangsbasis für die Migrationen,
die 37 aufeinander aufbauende Migrationen zusammenfasste. Wie die Migrationen heute aufgeteilt sind,
regelt [ADR-16](ADR-016-ein-datenbankschema-je-modul-statt-namenspraefix.md).
