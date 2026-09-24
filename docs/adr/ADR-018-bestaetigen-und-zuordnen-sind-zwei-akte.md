# ADR-18: Bestätigen und Zuordnen sind zwei Schritte

> **Stand 2026-09-23:** `ident-kvnr` trägt nichts zum IAL bei; der Satz im Entscheidungstext war
> falsch. Siehe Nachtrag 2.

**Entscheidung**: `ident-eid` bestätigt nur noch, was auf der Karte steht: Name, Vorname,
Geburtsdatum und Adresse. Jedes Feld der Karte ist ein eigener `AttributeType`, auch die Adressfelder
`strasse`, `hausnummer`, `plz` und `ort`, die dafür aus den unstrukturierten `auditDetails` zu echten
Claims wurden. `ident-eid` bestätigt sie aus eigener Autorität (`ClaimSource.of(toolId)`) und findet
keine Person.

Die Zuordnung zu einer Person im Personenverzeichnis übernimmt ein eigenes Tool, `ident-kvnr`. Es
fragt nach der Versichertennummer (für einen Partner ohne sie nach der Partnernummer, ADR-34), sucht
damit über `PersonDirectory` die Person und behauptet erst dann `PERSON_ID` und `KVNR`. Beide Claims
tragen `ClaimSource.PERSON_DIRECTORY`, denn für diese Werte steht tatsächlich das Personenverzeichnis
ein.

Der zweite Schritt wird direkt angeboten (`RegisterState.Assigning`, `next` zeigt auf `ident-kvnr`),
ohne vorherige Ja/Nein-Frage, die nur dieselbe Frage doppelt stellen würde. Wer den Schritt abbricht
(„Jetzt nicht“) oder eine unbekannte Nummer angibt, ist danach ein vollständig bestätigter
**Interessent** (ADR-10) und bekommt keinen Fehler.

**Erwogene Alternative**: Alles in einem Tool lassen und nur die Fehlermeldung verbessern.

**Warum diese**: Die bisherige Aufteilung behauptete etwas Falsches. `IdentEidDescriptor` deklarierte
`ClaimDeclaration(PERSON_ID, ClaimSource.of(toolId))`. Das Verfahren stand also für eine PersonId ein,
die es nie von der Karte gelesen hatte; der Controller hatte sie vorher über die KVNR nachgeschlagen.
Eine echte eID-Karte enthält aber weder KVNR noch PersonId; die KVNR musste der Nutzer selbst
eintippen, bevor die Karte überhaupt gelesen wurde. Damit ließ sich auch der Fall „gültige eID, aber
(noch) kein Eintrag im Personenverzeichnis“ nicht abbilden: Er scheiterte hart, obwohl ADR-10 genau
diesen Zustand eines Kontos vorsieht.

`ident-eid` bleibt `IDENTIFICATION`. `ident-kvnr` hat die eigene Rolle `MethodRole.CORRELATION` und
bleibt in der Kategorie `IDENT`, weil es zur Feststellung der Identität gehört. Das folgt demselben
Muster wie `LOOKUP_AUTH` neben `IDENTIFIED_AUTH`: gleiche Kategorie, aber nie gegeneinander
austauschbar. Die Rolle macht ausdrücklich, dass das Tool für sich allein nichts beweist
(`factorTypes = {}` ist die Folge davon, nicht die Definition). Bei der Auswahl der Kandidaten wird
die Rolle geprüft und nicht die Kategorie, damit das Tool nie als Weg zur (erneuten) Identifizierung
angeboten wird.

`ATTEST` wäre für die Bestätigung durch die Karte falsch. Das liegt nicht daran, wem die Daten
gehören, sondern daran, dass diese Kategorie per Definition nichts zu ACR und AMR beiträgt. Eine eID
trägt aber sehr wohl zum IAL bei. Sonst stünde der stark bestätigte Interessent am Ende auf `loa1`
statt auf `loa3`.

**Worauf die Sicherheit beruht**: `ident-kvnr` beweist für sich **nichts**; eine eingetippte Nummer
ist kein Nachweis. Getragen wird das Tool von zwei Dingen:

- `requires`: Die bestätigten Attribute der Identität müssen am Konto vorliegen, sonst lässt sich das
  Tool nicht einmal starten.
- Der Abgleich `IdentityResolver.attestedIdentityMatches`: Bevor ein Anker geschrieben wird, prüft er,
  ob die Stammdaten hinter der Nummer zu der bereits bestätigten Identität passen.

Ohne diesen Abgleich könnte jemand mit der eigenen eID eine fremde Versichertennummer eintippen und
den `PERSON_ID`-Anker dieser Person an das eigene Konto binden, solange sie selbst noch kein Konto
hat. Die bisherige Unterscheidung bleibt dabei erhalten: Eine unbekannte Nummer ergibt einen
Interessenten (kein Konflikt), eine bekannte Nummer mit widersprechenden Daten ergibt `409`.

**Kosten**: Ein Tool und ein Modul mehr im Katalog und ein Schritt mehr im Ablauf. Außerdem musste
`ToolOutcome.Completed.Identified` den Pflicht-Claim `PERSON_ID` aufgeben („höchstens einer“ statt
„genau einer“). Diese Lockerung zwingt jeden Aufrufer, den Fall ohne PersonId zu behandeln. Dafür
hängt `id_eid` an keinem Port zur Personensuche mehr.

**Nachtrag**: Erst dieser ADR hat `requires` überhaupt wirksam gemacht.
`DefaultAuthPolicy.requiresSatisfied` war fest auf `AttributeType.EMAIL` programmiert; jede andere
Anforderung war also unerfüllbar. Heute prüft die Funktion allgemein gegen
`AccountProfile.establishedClaims` (Angaben minus Widerrufe, ADR-12). Dass `enroll-password` eine
bestätigte E-Mail-Adresse verlangt, ist damit ein Anwendungsfall der allgemeinen Regel und nicht
mehr ihre Definition. Außerdem berücksichtigte `CandidateTools.forIdentification` `requires` gar
nicht. Genau über diesen Weg wäre `ident-kvnr` sonst als eigenständiges Verfahren zur
Identifizierung in der ersten Auswahl aufgetaucht. Inzwischen prüfen die Auswahlfunktionen
(`forIdentification`, `forAssignment`, `reIdentCandidates`) die Rolle statt der Kategorie. Damit ist
`ident-kvnr` schon durch den Aufbau nie ein Weg zur (erneuten) Identifizierung, egal ob seine
`requires` erfüllt sind.

**Nachtrag 2 (2026-09-23)**: Oben heißt es, `ident-kvnr` gehöre zur Feststellung der Identität „und
trägt IAL bei“. Das widerspricht dem eigenen Sicherheitskern („beweist für sich nichts“) und dem Code:
Die Rolle `CORRELATION` gehört zu keiner Art von Nachweis (`ToolDescriptor.evidenceAxis()` in
`orchestrator/policy/AuthEvidence.kt` liefert `null`) und hebt deshalb weder IAL noch AAL. Das IAL
eines bestätigten Interessenten stammt allein aus `ident-eid`. Ebenso stimmt die frühere Aussage
nicht, `AuthEvidence.evidenceAxis()` werfe bei `ATTEST` einen Fehler: Die Funktion ist eine
Erweiterungsfunktion von `ToolDescriptor` und liefert für `ATTESTATION` ebenfalls `null`.

**Nachtrag 3 (2026-09-24)**: Die Hausnummer ist kein eigener Claim mehr; `strasse` enthält die ganze
Straßenzeile, so wie eID und PID sie liefern. `ident-kvnr` behauptet heute `PERSON_ID` und, soweit
vorhanden, `KVNR` und `VERSNR` (alle mit `PERSON_DIRECTORY`). Wer über die Partnernummer zugeordnet
wird, bekommt keinen KVNR-Claim (ADR-34).

---
