# ADR-5: Zwei Obergrenzen für das Sicherheitsniveau

**Status**: umgesetzt.

**Entscheidung**: Das Sicherheitsniveau, das ein Verfahren in einer Sitzung liefert, ist an zwei
Stellen nach oben begrenzt:

- **Beim Einrichten** hält jedes Verfahren fest, welches Niveau die Sitzung damals nachgewiesen hatte
  (`account.auth_method.enrolled_under_acr`, gesetzt im `JourneyActionExecutor`). Mehr als dieses
  Niveau kann das Verfahren später nie beitragen.
- **Beim Anmelden** zählt das Kleinere aus zwei Werten: dem, was das Verfahren in dieser Sitzung
  tatsächlich nachgewiesen hat, und seinem `enrolledUnderAcr` (`performAcceptProof`).

Die Identifizierung wirkt dabei über die erste Grenze: Wer sich nur schwach ausgewiesen hat, richtet
jedes Verfahren in einer Sitzung mit niedrigem Niveau ein, und genau dieses Niveau steht dann in
`enrolledUnderAcr`. Eine eigene, kontoweite Grenze aus der Identifizierung gibt es nicht.

Wie das sichtbare Niveau (`acr`) aus dem Nachweis einer Sitzung entsteht, beschreibt
[Orchestrierung](../04-orchestrierung.md) Abschnitt 8: `DefaultAuthPolicy.resolveAcr` trennt dort die
Stärke der Identifizierung (IAL) von der Stärke der Anmeldung (AAL). Das IAL zählt nur den
Identitätsnachweis der **laufenden** Sitzung, weil die Identität in jeder Sitzung neu bewiesen wird.

**Erwogene Alternative**: Das Niveau nur aus dem ableiten, was in der laufenden Sitzung geschehen ist,
ohne zu berücksichtigen, unter welchen Bedingungen ein Verfahren eingerichtet wurde.

**Begründung**: Ohne die Grenze beim Einrichten gäbe es einen Weg nach oben. Wer eine schwache Sitzung
übernimmt (etwa auf `loa1`), könnte darin ein eigenes Verfahren einrichten und damit dauerhaft ein
höheres Niveau vortäuschen. Ebenso könnte eine schwach identifizierte Person über starke
Anmeldeverfahren ein Niveau erreichen, das ihre Identifizierung nie hergab.

**Folgen und Kosten**: Ein Niveau kann an zwei Stellen sinken statt an einer. Welche gerade wirkt, lässt
sich nur mit dem Nachweis der Sitzung (`AuthEvidence`, [ADR-15](ADR-015-nachweise-und-ausgestellte-tokens-in-getrennten-tabellen.md))
und `account.auth_method.enrolled_under_acr` zusammen nachvollziehen. Die Tabelle
`account.identification` mit `achieved_acr` ist nur ein Audit-Nachweis und wird für keine Entscheidung
gelesen.

**Geschichte**: Ursprünglich waren es drei Grenzen; die erste sollte `account.identification.achieved_acr`
sein und begrenzen, was ein Konto überhaupt erreichen kann. Diese Grenze wurde nie gelesen. Seit
2026-09-23 ist festgehalten, dass die Identifizierung nur noch über `enrolledUnderAcr` wirkt. Der
Dateiname trägt noch die alte Zahl.
