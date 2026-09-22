# Glossar-Abgleich

Wie sich die Begriffe aus [glossar.md](glossar.md) im aktuellen Projekt wiederfinden — mit
Fundstelle, wo eine Entsprechung besteht, und Begründung, wo bewusst abgewichen wird. Zielgruppe:
der Autor des Glossars und jeder, der prüfen will, ob dieses Projekt dessen Begriffswelt
unterstützt bzw. nachbildet.

---

## 1) Passt gut

| Glossar-Begriff | Projekt-Fundstelle | Warum es passt |
|---|---|---|
| **Faktortyp Wissen/Besitz/Biometrie** | `enum class FactorType { KNOWLEDGE, POSSESSION, INHERENCE }` (`ToolDescriptor.kt:197`) | 1:1-Übersetzung, mit fast identischen Definitionen ("something the user knows/has/is"). |
| **Authentisierungsmittel** (1-n verknüpfte Faktoren) | `Methode` = „was am Konto eingerichtet ist und einen Login ermöglicht" (`04-orchestrierung.md:126`), `AccountAuthMethod` | Deckt sich nahezu wörtlich mit der Glossar-Definition. |
| **2-Faktor-Authentisierungsmittel, Beispiel „Gerät mit lokal per PIN/Biometrie freigeschaltetem Schlüssel"** | `auth-device`/`enroll-device`, `device-proof+jwt` mit `userVerification`-Claim (`pin`/`biometric`, `deviceKey.ts:83`, `06-ablaeufe.md:99`) | Exakt Glossar-Beispiel 2. Zusätzlich dynamisch korrekt: Faktorarten werden **pro Nachweis** aus dem tatsächlich genutzten `userVerification` abgeleitet (`AuthDeviceToolHandlerTest.kt:78`: `BIOMETRIC` → `POSSESSION+INHERENCE`), nicht pauschal für die Methode behauptet. |
| **Authentisieren** (Client übermittelt Nachweis) | Durchgängiges `Nachweis`/`ToolOutcome`-Vokabular, `AuthEvidence` | Projekt nennt es konsequent „Nachweis" — dieselbe Idee wie Glossar-„Nachweis über das Innehaben". |
| **Faktortyp Besitz / Gerätebindung / Prinzip des nicht kopierbaren Schlüssels** | `docs/09-dpop.md` Abschnitt 1–3, `bindingKeyRef` als JWK-Thumbprint, `extractable=false` | Trifft die Kernidee: eindeutig wiedererkennbares Gerät über einen nicht kopierbaren privaten Schlüssel; Bindung stirbt mit dem Schlüssel. |
| **Bescheinigtes vs. unbescheinigtes Attribut** | `TrustLevel { STAMMDATEN(3), PROVEN(2), SELF_REPORTED(1) }`, `ClaimSource.SELF_REPORTED` = „A value the user entered with nothing backing it" (`tool_spi/Claims.kt:62-90`) | Sogar präziser als das Glossar: dreistufige, geordnete Rangfolge statt binärer Unterscheidung. Die Glossar-Regel „unbescheinigte Attribute dürfen niemals für Zuordnung verwendet werden" ist technisch erzwungen über `ClaimRequirement(attributeType, minTrustLevel)` — z. B. verlangt `enroll-password` `EMAIL` mindestens auf `PROVEN` (`auth_password/Descriptors.kt:35`), ein `SELF_REPORTED`-Wert reicht nicht (getestet in `DefaultAuthPolicyTest.kt:454`). **Einschränkung:** Kein aktuell ausgeliefertes Tool deklariert selbst `SELF_REPORTED` — der Wert ist vorhanden und policy-wirksam, aber im Tool-Set noch nicht produktiv genutzt. |
| **Identifizierungsmittel-Beispiel „E-Mail-Konto mit implizit vertrauter Fremdauthentifizierung"** | `auth_email` (Methode `email`, EMAIL-Anker statt eigenes Credential, `02-domaenenmodell.md:236`) | Deckt sich mit dem Glossar-Beispiel „E-Mail-Konto, Server nimmt fremde Authentifizierung implizit an" — im Projekt sogar so benannt (`EMAIL_ANCHOR_ENROLLMENT`). |
| **ID-Server/ID-System** | `ext_stammdaten`/`PersonDirectory` (`02-domaenenmodell.md:226-230`, `06-ablaeufe.md:122`) | Genau die Rolle: externes System, das Identitäten (KVNR→Name/Geburtsdatum) kennt und über Zertifizierung/Claims für den Server nutzbar macht. |
| **Identifizierung** (Anreicherung eines bereits wiedererkannten Clients um bescheinigte Attribute) | `AccountClaim`, `ClaimSource.EXT_STAMMDATEN` vs. `ClaimSource.of(toolId)` (`06-ablaeufe.md:122`) | Die Unterscheidung, wer für einen Wert einsteht (das Register oder das Verfahren selbst), ist exakt die Glossar-Unterscheidung Identifizierungsmittel vs. nachträglich erhaltenes bescheinigtes Attribut. |
| **MFA-Kombinationsregel, „nicht verknüpfte Einzelfaktoren zählen nicht als ein Authentisierungsmittel"** | „Faktorvielfalt: verschiedene Faktorarten, nie Tool-Anzahl" (`04-orchestrierung.md:1037-1043`) | Deckt sich mit dem Glossar-Hinweis im Abschnitt „2-Faktor-Authentisierungsmittel". |

## 2) Passt einigermaßen

| Glossar-Begriff | Abweichung |
|---|---|
| **Authentisierung vs. Authentifizierung** (Client-Vorgang vs. Server-Prüfung, bewusst zwei Wörter) | Das Projekt nutzt ausschließlich „Authentifizierung"/„authentifiziert" (`docs/*.md`, kein einziges „Authentisierung"). Die fachliche Unterscheidung existiert implizit (Tool = Client-Nachweis, `AuthPolicy` = Server-Prüfung), ist aber terminologisch nicht nachgezogen. |
| **Faktortyp Besitz — Anforderung „sicherer Speicher" (Secure Element/TPM)** | Glossar setzt SE/TPM voraus. Die DPoP-Implementierung nutzt einen Browser-Schlüssel via Web Crypto API/IndexedDB (`09-dpop.md:13`, D-1..D-3) — kein Secure Element, keine Zertifizierung. Das ist für eine Demo bewusst so (`09-dpop.md:44` „Infrastrukturentscheidung, bewusst zurückgestellt"), erreicht aber nicht das vom Glossar beschriebene Sicherheitsniveau. |
| **Identifizierungsmittel-Beispiel „Personalausweis/Ausweis-Foto als Biometrie"** | Projekt hat dafür `ident-fsc`/`ident-eid`, aber die Biometrie-Komponente aus dem Glossar-Beispiel fehlt: `id_fsc` deklariert nur `POSSESSION`, `id_eid` `{POSSESSION, KNOWLEDGE}` (kein `INHERENCE`). Konzept passt, das konkrete Biometrie-Beispiel nicht. |

## 3) Passt gar nicht / andere Abstraktionsebene

| Glossar-Begriff | Befund |
|---|---|
| **Kommunikationspartner, Sichere Kommunikation, Sicherer Kommunikationskanal, Nachricht** | Reine Transportschicht-Begriffe (HTTPS, Request/Response). Im Projekt weder als benannte Domänenkonzepte noch in der Doku als Glossarwort geführt — stillschweigende Infrastrukturvoraussetzung, kein Teil des dokumentierten Domänenmodells. Nicht falsch, nur eine andere Abstraktionsebene als das, was `docs/02-04` beschreiben. |
| **Identität (ID) als reine, freistehende Attributsammlung** | Siehe Abschnitt 4 — im Projekt gibt es kein eigenständiges `Identity`-Objekt, sondern eine Projektion über mehrere Bausteine. |

---

## 4) Identität (Glossar) vs. Account/Claims (Projekt)

Das Glossar behandelt „Identität" als **eine** statische Sache: „eine Sammlung von Attributen, die
einem Client bzw. einer Person zugeordnet sind." Das Projekt zerlegt genau das in vier
verschieden lang lebende Bausteine, von denen keiner einzeln der Glossar-„Identität" entspricht:

1. **Keine „Identität"-Tabelle, nur eine Projektion.** Am nächsten kommt `AccountProfile` — eine
   reine Leseprojektion, kein gespeicherter Zustand: `personId`/`email` werden aus
   `AccountAnchor` *gelesen*, nicht aus eigenen Spalten (`02-domaenenmodell.md:139`).
2. **`Account` ist bewusst kein Träger von Identität.** Er trägt nur `id`, `createdAt`, `version`
   — die Identität des Kontos und den Punkt, über den Änderungen gesperrt werden, ausdrücklich
   ohne einen eigenen Fakt (`02-domaenenmodell.md:138,232`).
   Ein Account kann existieren, ohne dass überhaupt eine Glossar-„Identität" daran hängt — der
   „Interessent"-Fall (`isUnidentified`, ADR-10, `02-domaenenmodell.md:139`).
3. **`AccountClaim` ist Historie, nicht Identität.** Ein Log jeder je bestätigten Änderung
   (`claim_source`, `normalized_value`, `AcrLevel`), das nur angefügt und nie überschrieben wird
   (`02-domaenenmodell.md:143`) — die Herkunft, aus der der aktuelle Zustand
   (`AccountAnchor`) abgeleitet und über `AccountRetraction` zeitbasiert korrigiert wird
   (`02-domaenenmodell.md:142`).
4. **`AccountIdentification` ist Audit, nicht Identität.** Protokolliert, dass und wie
   identifiziert wurde, wird aber „für Entscheidungen … nie gelesen" (`02-domaenenmodell.md:141`)
   — bewusst abgekoppelt von dem, was die Identität tatsächlich bestimmt.

**Einordnung:** Der aktuelle Anker-Zustand (`AccountAnchor`/`AccountProfile`) entspricht bei einer
Momentaufnahme exakt der Glossar-„Identität". Claims, Retractions und der Identification-Audit
sind keine andere Sicht auf dasselbe, sondern beantworten die Fragen, die das Glossar
voraussetzt, ohne sie auszuformulieren: *woher* kommt der aktuelle Stand, *wann* galt ein Attribut als
bescheinigt, *wie* wird ein Konflikt zwischen zwei Bestätigungen aufgelöst
(`IdentityMatchingService.resolve`, `resolveByAnchor`, ADR-19/20). Das ist eine Verfeinerung, kein
Widerspruch.

---

## 5) Argumentation für den Glossar-Autor

### Unbedingt ändern (billig, hoher Vertrauensgewinn)

1. **Authentisierung/Authentifizierung explizit machen** — eine Zeile in
   `04-orchestrierung.md` Abschnitt 1 ergänzen: Tool-Nachweis = Authentisierung (Client-Seite),
   `AuthPolicy`-Prüfung = Authentifizierung (Server-Seite); das Projekt nutzt „Authentifizierung"
   trotzdem als Oberbegriff. Ohne diese Zeile wirkt es wie Unwissen über die Unterscheidung, mit
   ihr wie eine bewusste Sprachregelung.
2. **Glossar-Wörter in den Docs zitieren.** Aktuell taucht kein Glossar-Wort (Authentisierungsmittel,
   Faktortyp, Identifizierungsmittel, bescheinigtes Attribut …) wörtlich in `docs/*.md` auf, obwohl
   die Konzepte fast alle da sind. Die Wörter in den Docs nachzuziehen (nicht im Code — dort ist
   Englisch Konvention) kostet wenig und wirkt viel.
3. **Ehrlichkeit zum Sicherheitsniveau des Browser-Schlüssels.** In `09-dpop.md` ergänzen, dass der
   Browser-Schlüssel (Web Crypto API/IndexedDB) explizit **nicht** das Secure-Element/TPM-Niveau
   des Glossars erreicht — Demo, kein Produktivstack. Besser, das Projekt sagt es zuerst, als dass
   ein Reviewer es findet.

### Mit Argumenten verteidigen (nicht zurückbauen)

1. **Account ohne Identität (Interessent).** Das Glossar setzt voraus, dass Identität = Attributsammlung
   ist, macht aber keine Aussage darüber, *ob* diese Zuordnung schon besteht. Das Projekt trennt
   bewusst „kann sich dieses Gerät wieder anmelden" von „wissen wir, wer das ist" — eine
   Verfeinerung, kein Widerspruch: Sobald eine Identität zugeordnet wird, verhält sich das Konto
   exakt wie vom Glossar beschrieben.
2. **Claims/Anchor/Retraction statt einer einzigen Attributsammlung.** In einer Momentaufnahme
   fällt der aktuelle Zustand genau mit der Glossar-„Identität" zusammen. Claims und Retractions sind die
   Herleitung dieses Zustands über Zeit — notwendig für revisionssichere Antworten auf „warum galt
   zum Zeitpunkt X dieser Wert als bescheinigt". Für einen regulierten Kontext (Gesundheitswesen)
   ist das ein Mehrwert, kein Abweichen.
3. **Graduierte Autorität (`AttributeAuthority`, `TrustLevel`) statt binär bescheinigt/unbescheinigt.**
   Das Glossar deutet selbst mehrere Bescheinigungswege unterschiedlicher Stärke an (Zertifizierung
   vs. „weniger sichere, nicht-kryptographische Prüfverfahren"), ohne sie zu benennen. `TrustLevel`
   (`STAMMDATEN`/`PROVEN`/`SELF_REPORTED`) macht diese Abstufung explizit und maschinell auswertbar
   (`AnchorRule.acrFloor`, `02-domaenenmodell.md:145`) — die konsequente Weiterentwicklung dessen,
   was das Glossar bereits andeutet.
4. **Aktive Identitätsauflösung (`IdentityMatchingService`).** Das Glossar beschreibt Identifizierung
   als Ergebnis, nicht als Prozess — es sagt nichts darüber, was bei einer Kollision zwischen neuer
   Bestätigung und bestehendem Konto passiert. Das Projekt liefert diese fehlende Prozessbeschreibung
   (`resolveByAnchor`, ADR-19/20) — ein Beitrag zum Glossar, kein Verstoß dagegen.
5. **Faktorarten dynamisch pro Nachweis statt statisch pro Methode.** Bereits glossar-konform
   (Abschnitt 1) und ein Beleg für sorgfältige Umsetzung, kein Diskussionspunkt.

### Nächster Schritt

Dieses Dokument selbst ist die Begriffsbrücke, die man dem Glossar-Autor gibt: Begriff →
Projekt-Entsprechung (Datei:Zeile) → ggf. Begründung der Abweichung. Es verschiebt die Diskussion
von „ist es da?" zu „ist die Abweichung gerechtfertigt?".
