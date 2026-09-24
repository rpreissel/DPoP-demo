# Glossar-Abgleich

Dieses Dokument zeigt, wo sich die Begriffe aus [glossar.md](glossar.md) im aktuellen Projekt
wiederfinden. Gibt es eine Entsprechung, steht die Fundstelle dabei; weicht das Projekt bewusst ab,
steht die Begründung dabei. Es richtet sich an den Autor des Glossars und an alle, die prüfen wollen,
ob dieses Projekt die Begriffe des Glossars unterstützt oder nachbildet.

---

## 1) Passt gut

| Glossar-Begriff | Projekt-Fundstelle | Warum es passt |
|---|---|---|
| **Faktortyp Wissen/Besitz/Biometrie** | `enum class FactorType { KNOWLEDGE, POSSESSION, INHERENCE }` (`ToolDescriptor.kt:309`) | Eine direkte Übersetzung mit fast gleichen Definitionen („something the user knows/has/is“). |
| **Authentisierungsmittel** (1-n verknüpfte Faktoren) | `Methode` = „was am Konto eingerichtet ist und einen Login ermöglicht" (`04-orchestrierung.md` Abschnitt 1, „Begriffe“), `AccountAuthMethod` | Deckt sich nahezu wörtlich mit der Glossar-Definition. |
| **2-Faktor-Authentisierungsmittel, Beispiel „Gerät mit lokal per PIN/Biometrie freigeschaltetem Schlüssel"** | `auth-device`/`enroll-device`, `device-proof+jwt` mit `userVerification`-Claim (`pin`/`biometric`, `deviceKey.ts:83`, `06-ablaeufe.md` Abschnitt 5) | Genau Beispiel 2 des Glossars. Außerdem auch im Einzelfall richtig: Die Faktorarten werden **je Nachweis** aus dem tatsächlich genutzten `userVerification` abgeleitet (`AuthDeviceToolHandlerTest.kt:79`: `BIOMETRIC` → `POSSESSION+INHERENCE`) und nicht pauschal für das ganze Verfahren behauptet. |
| **Authentisieren** (Client übermittelt Nachweis) | Durchgehend die Begriffe „Nachweis“ und `ToolOutcome`, dazu `AuthEvidence` | Das Projekt spricht durchgehend von „Nachweis“; das ist dieselbe Idee wie der „Nachweis über das Innehaben“ im Glossar. |
| **Faktortyp Besitz / Gerätebindung / Prinzip des nicht kopierbaren Schlüssels** | `docs/09-dpop.md` Abschnitt 1–3, `bindingKeyRef` als JWK-Thumbprint, `extractable=false` | Trifft den Kern: Ein Gerät ist über einen nicht kopierbaren privaten Schlüssel eindeutig wiederzuerkennen, und die Bindung endet mit dem Schlüssel. |
| **Bescheinigtes vs. unbescheinigtes Attribut** | `TrustLevel { STAMMDATEN(3), PROVEN(2), SELF_REPORTED(1) }`, `ClaimSource.SELF_REPORTED` = „A value the user entered with nothing backing it" (`tool_spi/Claims.kt:84-126`) | Sogar genauer als das Glossar: eine Rangfolge mit drei Stufen statt einer Ja/Nein-Unterscheidung. Die Regel des Glossars „unbescheinigte Attribute dürfen niemals für Zuordnung verwendet werden“ ist technisch erzwungen, und zwar über `ClaimRequirement(attributeType, minTrustLevel)`. So verlangt `enroll-password` `EMAIL` mindestens auf `PROVEN` (`auth_password/Descriptors.kt:43`); ein Wert mit `SELF_REPORTED` reicht nicht (getestet in `DefaultAuthPolicyTest.kt:454`). **Einschränkung:** Kein derzeit ausgeliefertes Tool meldet selbst `SELF_REPORTED`. Der Wert ist vorhanden und wird von der Richtlinie berücksichtigt, wird von den Tools aber noch nicht genutzt. |
| **Identifizierungsmittel-Beispiel „E-Mail-Konto mit implizit vertrauter Fremdauthentifizierung"** | `auth_email` (Verfahren `email`, EMAIL-Anker statt eigenem Credential, `02-domaenenmodell.md` Abschnitt 7, Absatz unter dem Diagramm des Kontos) | Deckt sich mit dem Beispiel des Glossars „E-Mail-Konto, der Server nimmt eine fremde Authentifizierung stillschweigend an“; im Projekt heißt es sogar so (`EMAIL_ANCHOR_ENROLLMENT`). |
| **ID-Server/ID-System** | `ext_personenverzeichnis`/`PersonDirectory` (`02-domaenenmodell.md` Abschnitt 7, Tabelle `ext_personenverzeichnis.person`; `06-ablaeufe.md` Abschnitt 6) | Genau diese Rolle: ein externes System, das Personen kennt (Partnernummer; über KVNR oder Partnernummer zu Name, Geburtsdatum und Adresse) und sie über Zertifizierung bzw. Claims für den Server nutzbar macht. |
| **Identifizierung** (Anreicherung eines bereits wiedererkannten Clients um bescheinigte Attribute) | `AccountClaim`, `ClaimSource.PERSON_DIRECTORY` vs. `ClaimSource.of(toolId)` (`06-ablaeufe.md` Abschnitt 6) | Die Unterscheidung, wer für einen Wert einsteht (das Personenverzeichnis oder das Verfahren selbst), ist genau die Unterscheidung des Glossars zwischen Identifizierungsmittel und nachträglich erhaltenem bescheinigtem Attribut. |
| **Regel für mehrere Faktoren: „nicht verknüpfte Einzelfaktoren zählen nicht als ein Authentisierungsmittel“** | „Faktorvielfalt: verschiedene Faktorarten, nie die Zahl der Tools“ (`04-orchestrierung.md` Abschnitt 8, „AuthPolicy: Mehr-Faktor-Entscheidung“) | Deckt sich mit dem Hinweis des Glossars im Abschnitt „2-Faktor-Authentisierungsmittel“. |

## 2) Passt einigermaßen

| Glossar-Begriff | Abweichung |
|---|---|
| **Authentisierung vs. Authentifizierung** (Client-Vorgang vs. Server-Prüfung, bewusst zwei Wörter) | Das Projekt verwendet nur „Authentifizierung“ und „authentifiziert“ (`docs/*.md`, kein einziges „Authentisierung“). Die fachliche Unterscheidung gibt es der Sache nach (Tool = Nachweis durch den Client, `AuthPolicy` = Prüfung durch den Server), sie ist aber nicht in den Begriffen nachgezogen. |
| **Faktortyp Besitz — Anforderung „sicherer Speicher" (Secure Element/TPM)** | Das Glossar setzt ein Secure Element oder TPM voraus. Die Umsetzung von DPoP nutzt einen Schlüssel im Browser über die Web Crypto API und IndexedDB (`09-dpop.md` Abschnitte 1–2, D-1 bis D-3): kein Secure Element, keine Zertifizierung. Das ist für eine Demo bewusst so, wird in `09-dpop.md` aber bisher nicht ausdrücklich begründet, und es erreicht nicht das Sicherheitsniveau, das das Glossar beschreibt. |
| **Identifizierungsmittel-Beispiel „Personalausweis/Ausweis-Foto als Biometrie"** | Das Projekt hat dafür `ident-fsc`, `ident-eid` und `ident-nect`. `id_fsc` deklariert nur `POSSESSION`, `id_eid` `{POSSESSION, KNOWLEDGE}`; `ident-nect` liefert beim Verfahren mit Reisepass (`nect-epass`) `{POSSESSION, INHERENCE}` (`IdentNectToolHandler.kt:148`). Das Beispiel des Glossars mit Biometrie ist damit abgedeckt, allerdings über einen simulierten Dienstleister. |

## 3) Passt gar nicht / andere Abstraktionsebene

| Glossar-Begriff | Befund |
|---|---|
| **Kommunikationspartner, Sichere Kommunikation, Sicherer Kommunikationskanal, Nachricht** | Reine Begriffe der Transportschicht (HTTPS, Anfrage/Antwort). Im Projekt gibt es sie weder als benannte fachliche Konzepte noch als Glossarwörter in der Doku. Sie werden als Infrastruktur stillschweigend vorausgesetzt und gehören nicht zum dokumentierten Domänenmodell. Das ist nicht falsch, nur eine andere Abstraktionsebene als das, was `docs/02-04` beschreiben. |
| **Identität (ID) als reine, freistehende Attributsammlung** | Siehe Abschnitt 4: Im Projekt gibt es kein eigenständiges `Identity`-Objekt, sondern eine Sicht, die sich aus mehreren Bausteinen zusammensetzt. |

---

## 4) Identität (Glossar) vs. Account/Claims (Projekt)

Das Glossar behandelt „Identität" als **eine** statische Sache: „eine Sammlung von Attributen, die
einem Client bzw. einer Person zugeordnet sind." Das Projekt zerlegt genau das in vier
verschieden lang lebende Bausteine, von denen keiner einzeln der Glossar-„Identität" entspricht:

1. **Keine Tabelle „Identität“, nur eine abgeleitete Sicht.** Am nächsten kommt `AccountProfile`, eine
   reine Sicht zum Lesen ohne eigenen gespeicherten Zustand: `personId`/`email` werden aus
   `AccountAnchor` *gelesen*, nicht aus eigenen Spalten (`02-domaenenmodell.md` Abschnitt 6).
2. **`Account` ist bewusst kein Träger von Identität.** Er trägt nur `id`, `createdAt`, `version`
   Das ist die Identität des Kontos und die Stelle, über die Änderungen gesperrt werden, ausdrücklich
   ohne einen eigenen Fakt (`02-domaenenmodell.md` Abschnitt 6 und Abschnitt 7).
   Ein Konto kann existieren, ohne dass überhaupt eine „Identität“ im Sinne des Glossars daran hängt:
   der Fall des Interessenten (`isUnidentified`, ADR-10, `02-domaenenmodell.md` Abschnitt 6).
3. **`AccountClaim` ist Historie, nicht Identität.** Es ist ein Log jeder je bestätigten Änderung
   (`claim_source`, `normalized_value`, `AcrLevel`), das nur ergänzt und nie überschrieben wird
   (`02-domaenenmodell.md` Abschnitt 6). Aus dieser Herkunft wird der aktuelle Zustand
   (`AccountAnchor`) abgeleitet und über `AccountRetraction` anhand der Zeitpunkte korrigiert
   (`02-domaenenmodell.md` Abschnitt 6).
4. **`AccountIdentification` ist Audit, nicht Identität.** Es hält fest, dass und wie identifiziert
   wurde, wird aber „für Entscheidungen … nie gelesen“ (`02-domaenenmodell.md` Abschnitt 6). Es ist
   also bewusst getrennt von dem, was die Identität tatsächlich bestimmt.

**Einordnung:** Der aktuelle Zustand der Anker (`AccountAnchor`/`AccountProfile`) entspricht zu jedem
Zeitpunkt genau der „Identität“ des Glossars. Claims, Widerrufe und das Audit der Identifizierungen
sind keine andere Sicht auf dasselbe, sondern beantworten die Fragen, die das Glossar
voraussetzt, ohne sie auszuformulieren: *woher* kommt der aktuelle Stand, *wann* galt ein Attribut als
bescheinigt, *wie* wird ein Konflikt zwischen zwei Bestätigungen aufgelöst
(`IdentityMatchingService.resolve`, `resolveByAnchor`, ADR-19/20). Das ist eine Verfeinerung, kein
Widerspruch.

---

## 5) Argumentation für den Glossar-Autor

### Unbedingt ändern (billig, hoher Vertrauensgewinn)

1. **Authentisierung und Authentifizierung ausdrücklich unterscheiden:** eine Zeile in
   `04-orchestrierung.md` Abschnitt 1 ergänzen: Nachweis durch ein Tool = Authentisierung (Seite des
   Clients), Prüfung durch `AuthPolicy` = Authentifizierung (Seite des Servers); das Projekt nutzt „Authentifizierung"
   trotzdem als Oberbegriff. Ohne diese Zeile wirkt es wie Unwissen über die Unterscheidung, mit
   ihr wie eine bewusste Sprachregelung.
2. **Glossar-Wörter in den Docs zitieren.** Aktuell taucht kein Glossar-Wort (Authentisierungsmittel,
   Faktortyp, Identifizierungsmittel, bescheinigtes Attribut …) wörtlich in `docs/*.md` auf, obwohl
   die Konzepte fast alle da sind. Die Wörter in den Docs nachzuziehen (nicht im Code — dort ist
   Englisch Konvention) kostet wenig und wirkt viel.
3. **Offen sagen, wie sicher der Schlüssel im Browser ist.** In `09-dpop.md` ergänzen, dass der
   Schlüssel im Browser (Web Crypto API und IndexedDB) ausdrücklich **nicht** das Niveau eines Secure
   Elements oder TPM aus dem Glossar erreicht: Es ist eine Demo, kein System für den Produktivbetrieb.
   Besser, das Projekt sagt es selbst, als dass ein Prüfer es findet.

### Mit Argumenten verteidigen (nicht zurückbauen)

1. **Account ohne Identität (Interessent).** Das Glossar setzt voraus, dass Identität = Attributsammlung
   ist, macht aber keine Aussage darüber, *ob* diese Zuordnung schon besteht. Das Projekt trennt
   bewusst „kann sich dieses Gerät wieder anmelden" von „wissen wir, wer das ist" — eine
   Verfeinerung, kein Widerspruch: Sobald eine Identität zugeordnet wird, verhält sich das Konto
   exakt wie vom Glossar beschrieben.
2. **Claims, Anker und Widerrufe statt einer einzigen Attributsammlung.** Zu jedem Zeitpunkt fällt
   der aktuelle Zustand genau mit der „Identität“ des Glossars zusammen. Claims und Widerrufe
   beschreiben, wie dieser Zustand über die Zeit entstanden ist. Das ist notwendig für revisionssichere Antworten auf „warum galt
   zum Zeitpunkt X dieser Wert als bescheinigt". Für einen regulierten Kontext (Gesundheitswesen)
   ist das ein Gewinn und keine Abweichung.
3. **Graduierte Autorität (`AttributeAuthority`, `TrustLevel`) statt binär bescheinigt/unbescheinigt.**
   Das Glossar deutet selbst mehrere Bescheinigungswege unterschiedlicher Stärke an (Zertifizierung
   vs. „weniger sichere, nicht-kryptographische Prüfverfahren"), ohne sie zu benennen. `TrustLevel`
   (`STAMMDATEN`/`PROVEN`/`SELF_REPORTED`) macht diese Abstufung ausdrücklich und maschinell
   auswertbar (`AnchorRule.acrFloor`, `02-domaenenmodell.md` Abschnitt 6). Das führt folgerichtig fort,
   was das Glossar bereits andeutet.
4. **Aktive Identitätsauflösung (`IdentityMatchingService`).** Das Glossar beschreibt Identifizierung
   als Ergebnis, nicht als Prozess — es sagt nichts darüber, was bei einer Kollision zwischen neuer
   Bestätigung und bestehendem Konto passiert. Das Projekt liefert diese fehlende Beschreibung des
   Vorgangs (`resolveByAnchor`, ADR-19/20). Das ist ein Beitrag zum Glossar, kein Verstoß dagegen.
5. **Faktorarten je Nachweis statt fest je Verfahren.** Das entspricht bereits dem Glossar
   (Abschnitt 1) und belegt eine sorgfältige Umsetzung; darüber muss man nicht diskutieren.

### Nächster Schritt

Dieses Dokument ist selbst die Übersetzungshilfe, die man dem Autor des Glossars gibt: Begriff →
Entsprechung im Projekt (Datei:Zeile) → gegebenenfalls Begründung der Abweichung. Es verschiebt die Diskussion
von „ist es da?" zu „ist die Abweichung gerechtfertigt?".
