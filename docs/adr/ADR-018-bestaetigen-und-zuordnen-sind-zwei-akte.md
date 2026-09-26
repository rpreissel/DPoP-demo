# ADR-18: Bestätigen und Zuordnen sind zwei Schritte

**Status:** umgesetzt.

## Entscheidung

Eine Identifizierung mit einem Ausweisdokument besteht aus zwei Schritten mit zwei Tools.

**Bestätigen.** `ident-eid` (und ebenso `ident-nect`) bestätigt nur, was auf dem Dokument steht,
aus eigener Autorität (`ClaimSource.of(toolId)`). Bei `ident-eid` sind das sieben Claims: `NAME`,
`VORNAME`, `GEBURTSDATUM`, `STRASSE` (die ganze Straßenzeile mit Hausnummer), `PLZ`, `ORT` und das
Kartenpseudonym `EID_RESTRICTED_ID`. Eine Person im Personenverzeichnis findet dieser Schritt nicht,
denn ein Ausweis trägt weder KVNR noch Partnernummer.

**Zuordnen.** Die Zuordnung zu einer Person im Personenverzeichnis übernimmt `ident-kvnr`. Es fragt
nach der Versichertennummer (KVNR), ohne sie nach der Partnernummer
([ADR-34](ADR-034-personenverzeichnis-meldet-aenderungen.md)), sucht damit über `PersonDirectory` die
Person und behauptet erst dann:

- `PERSON_ID` (die Partnernummer),
- `KVNR`, wenn eine KVNR angegeben wurde,
- `VERSNR`, wenn die Person bei uns versichert ist.

Alle drei tragen `ClaimSource.PERSON_DIRECTORY`, denn für diese Werte steht das Personenverzeichnis
ein.

Der zweite Schritt folgt direkt (`RegisterState.Assigning`, `next` zeigt auf `ident-kvnr`), ohne
vorherige Ja/Nein-Frage. Wer ihn abbricht („Jetzt nicht“) oder eine unbekannte Nummer angibt, ist
danach ein vollständig bestätigter **Interessent**
([ADR-10](ADR-010-interessent-ist-konto-zustand-kein-eigener-authintent.md)) und bekommt keinen
Fehler.

**Rolle.** `ident-kvnr` hat die eigene Rolle `MethodRole.CORRELATION` in der Kategorie `IDENT`. Die
Rolle sagt ausdrücklich, dass das Tool für sich nichts beweist: Es gehört zu keiner Nachweisart
(`evidenceAxis()` liefert `null`), hebt also weder IAL noch AAL, und `factorTypes` ist leer. Das IAL
eines bestätigten Interessenten stammt allein aus dem Bestätigen. Die Auswahl der Kandidaten
(`CandidateTools.forIdentification`, `forAssignment`, `AuthPolicy.reIdentCandidates`) prüft die
Rolle, nicht die Kategorie. So wird `ident-kvnr` nie als Weg zur (erneuten) Identifizierung
angeboten.

## Worauf die Sicherheit beruht

Eine eingetippte Nummer ist kein Nachweis. `ident-kvnr` wird von zwei Dingen getragen:

- `requires`: `NAME`, `VORNAME` und `GEBURTSDATUM` müssen am Konto bestätigt vorliegen, sonst lässt
  sich das Tool nicht einmal starten.
- `IdentityResolver.attestedIdentityMatches`: Bevor ein Anker geschrieben wird, prüft der Abgleich,
  ob die Stammdaten hinter der Nummer zu der bereits bestätigten Identität passen.

Ohne diesen Abgleich könnte jemand mit der eigenen eID eine fremde Nummer eintippen und den
`PERSON_ID`-Anker dieser Person an das eigene Konto binden. Eine unbekannte Nummer ergibt einen
Interessenten (kein Konflikt), eine bekannte Nummer mit widersprechenden Daten ergibt `409`.

## Begründung

Vorher erledigte `ident-eid` beides und behauptete dabei etwas Falsches:
`ClaimDeclaration(PERSON_ID, ClaimSource.of(toolId))`. Das Verfahren stand für eine PersonId ein, die
es nie von der Karte gelesen hatte; der Controller hatte sie vorher über eine eingetippte KVNR
nachgeschlagen. Außerdem ließ sich „gültige eID, aber kein Eintrag im Personenverzeichnis“ nicht
abbilden: Der Fall scheiterte hart, obwohl ADR-10 genau diesen Zustand eines Kontos vorsieht.

`ATTEST` wäre für das Bestätigen durch die Karte falsch, weil diese Kategorie per Definition nichts
zu ACR und AMR beiträgt. Eine eID trägt aber zum IAL bei; sonst stünde der stark bestätigte
Interessent auf `loa1` statt auf `loa3`.

**Erwogene Alternative:** alles in einem Tool lassen und nur die Fehlermeldung verbessern.

## Folgen

- Ein Tool, ein Modul und ein Schritt mehr.
- `ToolOutcome.Completed.Identified` trägt höchstens einen `PERSON_ID`-Claim statt genau einen. Jeder
  Aufrufer muss deshalb den Fall ohne PersonId behandeln.
- `id_eid` hängt an keinem Port zur Personensuche mehr.

## Namensvetter: ohne Adresse (entschieden 2026-09-26)

Die Zuordnung über die KVNR vergleicht Name, Vorname und Geburtsdatum mit den bezeugten Daten
(`IdentityResolver.attestedIdentityMatches`), bewusst **nicht** die Adresse. Das trennende Merkmal ist
die KVNR selbst: Wer eine fremde Nummer eintippt, muss zusätzlich Name, Vorname und Geburtsdatum
dieser Person bezeugt haben. Ein Namensvetter mit gleichem Geburtsdatum hat eine andere KVNR. Die
Adresse dazuzunehmen hätte vor allem echte Personen abgewiesen: Ausweisdaten und Register veralten
unterschiedlich schnell (Umzug) und schreiben Straßen verschieden. Review 2026-09, Namensvetter-Risiko.

## Geschichte

- Erst dieser ADR machte `requires` wirksam: `DefaultAuthPolicy.requiresSatisfied` war vorher fest
  auf `EMAIL` programmiert, und `CandidateTools.forIdentification` beachtete `requires` gar nicht.
  Heute prüft die Funktion allgemein gegen `AccountProfile.establishedClaims` (Angaben minus
  Widerrufe, [ADR-12](ADR-012-ein-widerruf-ist-eine-eigene-zeile-mit-eigenem.md)).
- Die erste Fassung sagte, `ident-kvnr` trage zum IAL bei, und `evidenceAxis()` werfe bei `ATTEST`
  einen Fehler. Beides stimmte nicht; beide Rollen liefern `null`.
- Die Hausnummer war anfangs ein eigener Claim. Seit 2026-09-24 steht sie in `STRASSE`, so wie eID und
  PID die Straßenzeile liefern.
- Seit ADR-34 ordnet `ident-kvnr` auch über die Partnernummer zu; dann entsteht kein KVNR-Claim.
