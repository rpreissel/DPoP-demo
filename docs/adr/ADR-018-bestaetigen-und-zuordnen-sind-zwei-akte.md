# ADR-18: Bestätigen und Zuordnen sind zwei Akte

> **Stand 2026-09-23:** `ident-kvnr` trägt kein IAL bei; der Satz im Entscheidungstext war falsch.
> Siehe Nachtrag 2.

**Entscheidung**: `ident-eid` bestätigt nur noch, was die Karte trägt (Name, Vorname, Geburtsdatum,
Adresse — jedes Kartenfeld ein eigener `AttributeType`, auch die Adressattribute `strasse`/
`hausnummer`/`plz`/`ort`, die dafür aus den unstrukturierten `auditDetails` in echte Claims aufrückten —
auf eigene Autorität `ClaimSource.of(toolId)`) und löst niemanden auf. Die Zuordnung zur
Person im Personenverzeichnis ist ein eigenes Tool `ident-kvnr`: Es fragt die Versichertennummer
(für einen Partner ohne sie die Partnernummer, ADR-34) ab, löst sie
über `PersonDirectory` auf und behauptet erst dann `PERSON_ID`/`KVNR` — beide mit
`ClaimSource.PERSON_DIRECTORY`, denn dort bürgt tatsächlich das Personenverzeichnis. Der zweite Akt wird direkt
angeboten (`RegisterState.Assigning`, `next` zeigt auf `ident-kvnr`) — ohne Ja/Nein-Frage davor,
die nur dieselbe Frage doppelt stellen würde. Wer den Schritt abbricht („Jetzt nicht") oder eine
unbekannte Nummer angibt, endet als vollwertig bestätigter **Interessent** (ADR-10) statt mit einem
Fehler.

**Erwogene Alternative**: Alles in einem Tool lassen und nur die Fehlermeldung verbessern.

**Warum diese**: Die bisherige Aufteilung behauptete etwas Falsches. `IdentEidDescriptor`
deklarierte `ClaimDeclaration(PERSON_ID, ClaimSource.of(toolId))` — das Verfahren bürgte also für
eine PersonId, die es nie von der Karte gelesen hatte, sondern die der Controller vorab per KVNR
nachgeschlagen hatte. Eine echte eID-Karte trägt weder KVNR noch PersonId; die KVNR musste der
Nutzer selbst eintippen, bevor die Karte überhaupt gelesen wurde. Damit war auch der Fall "gültige
eID, aber (noch) kein Registereintrag" nicht abbildbar: Er scheiterte hart, obwohl ADR-10 genau
diesen Kontozustand vorsieht.

`ident-eid` bleibt `IDENTIFICATION`; `ident-kvnr` trägt die eigene Rolle `MethodRole.CORRELATION` (weiterhin Kategorie `IDENT`, denn es gehört zur Identitätsfeststellung und trägt IAL bei) — dasselbe Muster wie `LOOKUP_AUTH` neben `IDENTIFIED_AUTH`: gleiche Kategorie, nie austauschbar. Die Rolle macht explizit, dass das Tool für sich nichts beweist (`factorTypes = {}` ist Folge, nicht Definition), und die Kandidatenpfade prüfen die Rolle statt die Kategorie, damit es nie als (Re-)Identifizierungsweg angeboten wird. `ATTEST` wäre für die Bestätigung falsch — nicht wegen des
Datenbesitzes, sondern weil diese Kategorie per Definition nichts zur ACR/AMR-Bilanz beiträgt
(`AuthEvidence.evidenceAxis()` wirft dafür); eine eID trägt aber sehr wohl IAL bei; sonst würde
der stark bestätigte Interessent am Ende bei `loa1` statt bei `loa3` stehen.

**Sicherheitskern**: `ident-kvnr` beweist für sich **nichts** — eine getippte Nummer ist kein
Nachweis. Zwei Dinge tragen es: `requires` (die bestätigten Identitätsattribute müssen am Konto
vorliegen, sonst ist das Tool nicht einmal aktivierbar) und der Abgleich
`IdentityResolver.attestedIdentityMatches`, der vor dem Ankerschreiben prüft, dass die Stammdaten
hinter der Nummer zu der bereits bestätigten Identität passen. Ohne diesen Abgleich könnte man mit
der eigenen eID eine fremde Versichertennummer eintippen und sich deren `PERSON_ID`-Anker aufs
eigene Konto binden, solange diese Person noch kein Konto hat. Die bestehende Trennung bleibt
dabei erhalten: unbekannte Nummer → Interessent (kein Konflikt), bekannte Nummer mit
widersprechenden Daten → `409`.

**Kosten**: Ein Tool und ein Modul mehr im Katalog, ein Schritt mehr im Ablauf, und
`ToolOutcome.Completed.Identified` musste seinen Pflicht-`PERSON_ID`-Claim aufgeben ("höchstens
einer" statt "genau einer") — eine Lockerung, die jeden Aufrufer zwingt, den Null-Fall zu
behandeln. Im Gegenzug hängt `id_eid` an keinem Auflösungs-Port mehr.

**Nachtrag**: Erst dieser ADR hat `requires` überhaupt funktionsfähig gemacht.
`DefaultAuthPolicy.requiresSatisfied` war hart auf `AttributeType.EMAIL` verdrahtet, jede andere
Anforderung also unerfüllbar; sie prüft jetzt generisch gegen `AccountProfile.establishedClaims`
(Angaben minus Widerrufe, ADR-12). `enroll-password`s E-Mail-Gate ist damit ein Fall der
allgemeinen Regel statt ihrer Definition. Zudem filterte `CandidateTools.forIdentification` gar
nicht auf `requires` — genau der Pfad, über den `ident-kvnr` sonst als eigenständiges
Identifizierungsverfahren in der ersten Auswahl aufgetaucht wäre. Inzwischen prüfen die
Kandidatenpfade (`forIdentification`, `forAssignment`, `reIdentCandidates`) die Rolle statt
die Kategorie — `ident-kvnr` ist damit strukturell nie ein (Re-)Identifizierungsweg, unabhängig
davon, ob seine `requires` erfüllt sind.

**Nachtrag 2 (2026-09-23)**: Oben heißt es, `ident-kvnr` gehöre zur Identitätsfeststellung „und
trägt IAL bei“. Das widerspricht dem eigenen Sicherheitskern („beweist für sich nichts“) und dem
Code: Die Rolle `CORRELATION` hat keine Evidenzachse (`ToolDescriptor.evidenceAxis()` in
`orchestrator/policy/AuthEvidence.kt` liefert `null`), hebt also weder IAL noch AAL. Das IAL eines
bestätigten Interessenten kommt allein aus `ident-eid`. Ebenso „`AuthEvidence.evidenceAxis()` wirft“
für `ATTEST`: Die Funktion ist eine Erweiterung auf `ToolDescriptor` und liefert für `ATTESTATION`
ebenfalls `null`.

**Nachtrag 3 (2026-09-24)**: Die Hausnummer ist kein eigener Claim mehr; `strasse` trägt die ganze
Straßenzeile, wie eID und PID sie liefern. `ident-kvnr` behauptet heute `PERSON_ID` und, soweit
vorhanden, `KVNR` und `VERSNR` (alle `PERSON_DIRECTORY`). Wer über die Partnernummer zugeordnet wird,
bekommt keinen KVNR-Claim (ADR-34).

---
