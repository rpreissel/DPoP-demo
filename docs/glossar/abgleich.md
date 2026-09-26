# Glossar-Abgleich

Dieses Dokument zeigt, wo sich die Begriffe aus [glossar.md](glossar.md) im aktuellen Projekt
wiederfinden. Gibt es eine Entsprechung, steht die Fundstelle dabei; weicht das Projekt bewusst ab,
steht die Begründung dabei. Es richtet sich an den Autor des Glossars und an alle, die prüfen wollen,
ob dieses Projekt die Begriffe des Glossars unterstützt oder nachbildet.

Fundstellen im Code stehen als Datei und Zeile, Fundstellen in der Doku als Datei und Abschnitt.
Alle Pfade im Code beziehen sich auf `src/main/kotlin/com/example/dpop/`, sofern nicht anders
angegeben.

---

## 1) Passt gut

- **Faktortyp Wissen, Besitz, Biometrie**
  - *Fundstelle im Projekt:* `enum class FactorType { KNOWLEDGE, POSSESSION, INHERENCE }` (`tool_spi/ToolDescriptor.kt:309`)
  - *Warum es passt:* Eine direkte Übersetzung mit fast gleichen Definitionen („was der Nutzer weiß, hat, ist“).
- **Authentisierungsfaktor**, auch Mittel mit nur einem Faktor
  - *Fundstelle im Projekt:* Jedes Tool nennt seine Faktortypen selbst: das Passwort nur `KNOWLEDGE` (`auth_password/Descriptors.kt:39`), die SMS nur `POSSESSION` (`auth_sms/Descriptors.kt:32`)
  - *Warum es passt:* Das Passwort ist genau das „klassische“ Mittel mit einem Faktor aus dem Glossar.
- **Authentisierungsmittel**
  - *Fundstelle im Projekt:* „Methode“ bzw. „Anmeldeverfahren“: was im Konto eingerichtet ist und eine Anmeldung ermöglicht, gespeichert als `AccountAuthMethod`. Die Doku nennt den Glossar-Begriff dazu ausdrücklich (`docs/04-orchestrierung.md`, Abschnitt „Begriffe“)
  - *Warum es passt:* Deckt sich nahezu wörtlich mit der Definition im Glossar.
- **Authentisieren** (der Client übermittelt einen Nachweis)
  - *Fundstelle im Projekt:* Der Begriff „Nachweis“ in der ganzen Doku, technisch `ToolOutcome` und die `AuthEvidence` der Sitzung (`orchestrator/session/AuthEvidence.kt:56`)
  - *Warum es passt:* Dieselbe Idee wie der „Nachweis über das Innehaben“ im Glossar.
- **2-Faktor-Authentisierungsmittel**, Beispiel 2 (Schlüssel im Gerät, lokal per PIN oder Biometrie freigegeben), und **Gerätebindung für MFA**
  - *Fundstelle im Projekt:* `enroll-device`/`auth-device`: ein eigenes, nicht exportierbares Schlüsselpaar (`frontend/src/deviceKey.ts:50`), das der Nutzer auf dem Gerät freigibt (`userVerification`, `frontend/src/deviceKey.ts:83`); `docs/06-ablaeufe.md`, Abschnitt „`enroll-device` / `auth-device`“
  - *Warum es passt:* Genau Beispiel 2 des Glossars. Die Faktortypen werden **je Nachweis** aus der tatsächlichen Freigabe abgeleitet (PIN ergibt Besitz und Wissen, Biometrie Besitz und Biometrie, `auth_device/internal/authdevice/AuthDeviceToolHandler.kt:90-93`), nicht pauschal für das Verfahren behauptet. KOBIL macht es ebenso (`auth_kobil/internal/KobilFactors.kt:19-20`).
- **Faktortyp Besitz**: ein nicht kopierbarer Schlüssel erkennt das Gerät, die Bindung endet mit dem Schlüssel
  - *Fundstelle im Projekt:* DPoP-Schlüssel mit `extractable=false` (`frontend/src/dpop.ts:55-61`), `bindingKeyRef` als Fingerabdruck des Schlüssels (`docs/09-dpop.md`, Abschnitte „Prinzip“ und „Anforderungen“, D-3); beim Umbinden werden alle an den Schlüssel gebundenen Credentials widerrufen (`docs/09-dpop.md`, Abschnitt „Bindung an die ChannelSession“)
  - *Warum es passt:* Trifft den Kern: Das Gerät ist über einen Schlüssel eindeutig wiederzuerkennen, der die Anwendung nicht verlassen kann, und diese Wiedererkennung endet mit dem Schlüssel. Wie sicher dieser Schlüssel im Browser liegt, steht in Abschnitt 2.
- **Gerätebindung**: der Server muss dem Gerät vertrauen, wie es den Nutzer prüft
  - *Fundstelle im Projekt:* Die benannte Ausnahme für `device` und `kobil`: Beide melden Wissen bzw. Biometrie aus der Freigabe auf dem Gerät, die der Server selbst nicht sehen kann (`docs/04-orchestrierung.md`, Abschnitt „AuthPolicy: Entscheidung über mehrere Faktoren“; ADR-21)
  - *Warum es passt:* Das Glossar sagt selbst, dass der Server dem Gerät hier vertrauen muss. Das Projekt schreibt dieses Vertrauen ausdrücklich als Ausnahme fest, statt es stillschweigend vorauszusetzen.
- **Bescheinigtes und unbescheinigtes Attribut**; „es muss erkennbar bleiben, ob ein Attribut bescheinigt ist“
  - *Fundstelle im Projekt:* Jeder Claim speichert seine Quelle (`ClaimSource`) und daraus seinen Rang: `TrustLevel` mit `STAMMDATEN`, `PROVEN`, `SELF_REPORTED` (`tool_spi/Claims.kt:95-126`); Voraussetzungen als `ClaimRequirement`, z. B. verlangt `enroll-password` eine mindestens bestätigte E-Mail-Adresse (`auth_password/Descriptors.kt:43`), ebenso `enroll-email` (`auth_email/Descriptors.kt:67`)
  - *Warum es passt:* Sogar genauer als das Glossar: drei Stufen statt Ja oder Nein. Ein nur behaupteter Wert erfüllt keine Voraussetzung, die einen bestätigten verlangt (getestet in `src/test/kotlin/com/example/dpop/orchestrator/policy/DefaultAuthPolicyTest.kt:452-457`). **Einschränkung:** Kein ausgeliefertes Tool meldet heute selbst `SELF_REPORTED`; die Stufe ist vorhanden und wird geprüft, aber noch nicht genutzt.
- **Identifizierungsmittel mit eindeutigem Bezeichner** („eindeutig für dieses konkrete Identifizierungsmittel“)
  - *Fundstelle im Projekt:* Die `restrictedId` des Online-Ausweises als eigener Anker `EID_RESTRICTED_ID` (`tool_api/AttributeRules.kt`, bezeugt von `ident-eid`, `id_eid/Descriptors.kt:50`); liest Nect die Karte, entsteht Nects eigenes Pseudonym, ein eigener Anker `NECT_RESTRICTED_ID` (`id_nect/internal/IdentNectToolHandler.kt:116`); ADR-19
  - *Warum es passt:* Genau die Forderung des Glossars: Eine Person wird über den Bezeichner des Ausweises wiedererkannt, nicht über Name und Geburtsdatum. Eine neue Karte ersetzt den Wert. Weil das Pseudonym je Diensteanbieter verschieden ist (§ 18 PAuswG), erkennt jeder Weg die Karte nur für sich wieder.
- **Identifizierungsmittel**, Beispiel „E-Mail-Konto“ mit Einmalcode
  - *Fundstelle im Projekt:* `confirm-email` bestätigt die Adresse per Code und schreibt den EMAIL-Anker mit Rang `PROVEN` (`auth_email/Descriptors.kt:41-49`); die Anmeldung per E-Mail nutzt diesen Anker als Zugangsmittel (`EMAIL_ANCHOR_ENROLLMENT`, `tool_api/AccountDirectory.kt:64`)
  - *Warum es passt:* Deckt sich mit dem Beispiel des Glossars: Der Server vertraut stillschweigend der Anmeldung beim E-Mail-Anbieter und prüft nur einen Einmalcode, das „weniger sichere, nicht-kryptographische Prüfverfahren“ des Glossars.
- **Identifizierung** als Anreicherung eines bereits wiedererkannten Clients, auch in einem späteren, eigenen Schritt
  - *Fundstelle im Projekt:* Zwei getrennte Schritte nach ADR-18: `ident-eid` oder `ident-nect` bestätigen, wer jemand ist; `ident-kvnr` ordnet danach die Person im Personenverzeichnis zu (`docs/06-ablaeufe.md`, Abschnitt „`ident-eid` und `ident-kvnr`“). Wer steht für einen Wert ein: das Personenverzeichnis (`ClaimSource.PERSON_DIRECTORY`) oder das Verfahren selbst (`ClaimSource.of(toolId)`)
  - *Warum es passt:* Genau das Beispiel des Glossars mit Bahnticket und Personalausweis: Erst wird die Person wiedererkannt, dann werden weitere bescheinigte Merkmale zugeordnet. Auch ein Konto, das zuerst nur ein Anmeldeverfahren einrichtet und sich später ausweist (Experiment „Erst Anmeldeverfahren einrichten“), folgt diesem Muster.
- **Biometrie**, Beispiel „Ausweis-Foto“
  - *Fundstelle im Projekt:* Nect mit Reisepass (`nect-epass`): Chip und Abgleich mit dem Lichtbild ergeben Besitz und Biometrie (`id_nect/internal/IdentNectToolHandler.kt:146-150`)
  - *Warum es passt:* Das Beispiel des Glossars ist abgedeckt, allerdings über einen simulierten Dienstleister.
- **ID-Server / ID-System**
  - *Fundstelle im Projekt:* Der Orchestrator mit Keycloak: Er verwaltet Konten und ihre Attribute und gibt sie zusammen mit dem Stand der Authentifizierung weiter, als ID-Claims (`orchestrator/session/TokenService.kt:79-95`, u. a. `acr`, `amr`, `personId`, `versnr`) bzw. als Claims im Keycloak-Token (`keycloak-migrations/src/main/resources/keycloak-migrations/V1__realm.kc.kts:312`, `:994`). Die Personen selbst verwaltet das Personenverzeichnis (`ext_personenverzeichnis/Personenverzeichnis.kt:91`, `:108`); ändert es eine Person, folgen die Konten per Ereignis (`Personenverzeichnis.kt:121` → `account/internal/PersonChangeListener.kt:18` → `account/AccountService.kt:673`, ADR-34)
  - *Warum es passt:* Alle drei Aufgaben aus dem Glossar sind da: Identitäten verwalten, Attribute verwalten und beides zusammen mit dem Stand der Authentifizierung im Token nutzbar machen.

- **Authentisierung und Authentifizierung** (Vorgang beim Client, Prüfung beim Server)
  - *Fundstelle im Projekt:* Das Tool erbringt den Nachweis (Client), die `AuthPolicy` prüft ihn (Server). `docs/04-orchestrierung.md`, Abschnitt „Begriffe“, nennt die Unterscheidung des Glossars und erklärt „Authentifizierung“ als bewussten Oberbegriff für beides
  - *Warum es passt:* Die Trennung ist in der Sache vorhanden und jetzt auch als Sprachregelung benannt.
- **Mehrstufige Authentifizierung** gegenüber **2FA/MFA** („zwei unabhängig geprüfte Faktoren sind kein 2-Faktor-Mittel“)
  - *Fundstelle im Projekt:* SMS plus Passwort, zwei einzeln geprüfte Mittel, erreichen `loa2` und heißen in der Doku „mehrstufige Authentifizierung“; dazu der Hinweis, dass NIST SP 800-63B (AAL2) sie der MFA gleichstellt (`docs/04-orchestrierung.md`, Abschnitt „IAL und AAL“). „MFA“ steht nur noch für ein einzelnes Mittel mit zwei Faktoren (`device`, `kobil`)
  - *Warum es passt:* Genau die Unterscheidung des Glossars; die Bewertung (beides `loa2`) folgt NIST.
- **Gerätebindung** (die Erstellung eines Faktors Besitz)
  - *Fundstelle im Projekt:* `enroll-device` und `enroll-kobil` richten ein gerätegebundenes Anmeldeverfahren ein (`docs/06-ablaeufe.md`, Abschnitt „`enroll-kobil` / `auth-kobil`“). Die Verknüpfung des DPoP-Schlüssels mit einem Konto (`DeviceAccountLink`) heißt dagegen „Geräteverknüpfung“ und gilt bewusst **nicht** als Anmeldung (`docs/04-orchestrierung.md`, Abschnitt „Begriffe“; `docs/09-dpop.md`, Abschnitt 3)
  - *Warum es passt:* Das Wort steht jetzt nur noch in der Bedeutung des Glossars.

## 2) Passt einigermaßen

- **Challenge-Response** als Weg zu 2FA/MFA — Der Server stellt beim Geräteschlüssel keine eigene Challenge. Der signierte Nachweis ist stattdessen an genau diese Anfrage gebunden (Methode, Adresse, Zeitpunkt, einmalige Kennung) und gegen Wiederholung geschützt (`auth_device/internal/authdevice/AuthDeviceToolHandler.kt:23-25`; `docs/09-dpop.md`, Abschnitt „Anforderungen“). Bei KOBIL löst das Backend die Bestätigung selbst beim Anbieter ein (ADR-21). Beides erfüllt den Zweck, ist aber kein Challenge-Response im engeren Sinn.
- **Sicherer Speicher** (Secure Element, TPM) als Voraussetzung für den Faktor Besitz — Die Schlüssel liegen im Browser: Web Crypto API mit `extractable=false`, gespeichert in IndexedDB (`frontend/src/dpop.ts:55-61`, `frontend/src/deviceKey.ts:50`). Der Schlüsselwert lässt sich über die Programmierschnittstelle nicht auslesen, das kommt der Definition nahe; eine Garantie durch Hardware und eine Zertifizierung gibt es aber nicht. Das Entsperrgeheimnis von KOBIL liegt sogar nur im `localStorage` (`frontend/src/kobilUnlockSecret.ts`). Das Projekt sagt das selbst: eine Demo, kein System für den Produktivbetrieb (`docs/09-dpop.md`, Abschnitt „Wie sicher die Schlüssel im Browser sind“).
- **Server, Client und Person** (Client und Person teilweise gleichbedeutend) — Das Projekt trennt drei Dinge ausdrücklich: das Gerät (`bindingKeyRef`, `DeviceAccountLink`), das Konto (`Account`) und die Person (Partnernummer im Personenverzeichnis). Ein wiedererkanntes Gerät sagt nur, welches Gerät spricht, nicht, wer davor sitzt. Das verfeinert das Glossar, widerspricht ihm aber nicht.
- **Identifizierungsmittel**, Beispiel „Versicherungs-Smartcard mit Versichertennummer“ — Eine Gesundheitskarte gibt es im Projekt nicht. Die KVNR wird eingetippt, nicht bescheinigt. `ident-kvnr` hat deshalb die Rolle `CORRELATION` (`id_kvnr/Descriptors.kt:39`) und darf nur laufen, wenn Name, Vorname und Geburtsdatum bereits bestätigt sind (`id_kvnr/Descriptors.kt:54-56`). Zugeordnet wird erst, wenn die Person hinter der Nummer zu diesen bestätigten Daten passt (`account/internal/IdentityMatchingService.kt:69`, `orchestrator/journey/JourneyActionExecutor.kt:144`). Für die Partnernummer gilt dasselbe.
- **Unbescheinigte Attribute dürfen nie für die Zuordnung zu einem Stammdatensatz dienen** — Beim Freischaltcode (`ident-fsc`) tippt die Person KVNR bzw. Partnernummer, Namen und Geburtsdatum ein, also unbescheinigte Angaben; das Personenverzeichnis prüft sie (`id_fsc/internal/IdentFscToolHandler.kt:83`). Wofür das Personenverzeichnis einsteht, ist aber der Code aus dem Brief, den es selbst an die Person geschickt hat (`ext_personenverzeichnis/Freischaltcodes.kt:53`). Die getippten Angaben finden nur den Datensatz, der Code bestätigt ihn. Das ist der Einmalcode aus dem Glossar, ein schwächeres, nicht kryptographisches Verfahren. Deshalb erreicht `ident-fsc` nur `loa2`, der Online-Ausweis `loa3`.
- **Identifizierungsmittel**, Beispiel „SIM-Karte mit Telefonnummer“ — Die SMS dient nur als Anmeldeverfahren mit dem Faktor Besitz (`auth_sms/Descriptors.kt:32`). Die Telefonnummer wird als Claim festgehalten (`auth_sms/Descriptors.kt:41`), gehört aber dem Methodenmodul (`tool_api/AttributeRules.kt:96`) und dient nie dazu, eine Person zu erkennen. Das Projekt nutzt die SIM-Karte bewusst nicht als Identifizierungsmittel.

## 3) Passt gar nicht / andere Abstraktionsebene

- **Kommunikationspartner, Sichere Kommunikation, Sicherer Kommunikationskanal** — Begriffe der Transportschicht (HTTPS). Das Projekt setzt sie als Infrastruktur voraus und beschreibt sie nicht als eigene fachliche Begriffe. Das ist nicht falsch, nur eine andere Ebene als das, was `docs/02` bis `docs/04` beschreiben.
- **Nachricht** (eine Anfrage, optional eine Antwort) — Am nächsten kommt der DPoP-Nachweis: Jede Anfrage trägt ihren eigenen signierten Nachweis, der nur für diese Anfrage gilt und nicht wiederholt werden kann (`docs/09-dpop.md`, Abschnitt „Prinzip“). Einen eigenen Begriff „Nachricht“ gibt es aber nicht.
- **Identität (ID)** als freistehende Sammlung von Attributen — Siehe Abschnitt 4: Es gibt kein eigenes Objekt „Identität“, sondern eine Sicht, die sich aus mehreren Bausteinen zusammensetzt.

---

## 4) Identität (Glossar) vs. Konto und Claims (Projekt)

Das Glossar behandelt „Identität“ als **eine** feste Sache: „eine Sammlung von Attributen, die einem
Client bzw. einer Person zugeordnet sind.“ Das Projekt verteilt genau das auf mehrere Bausteine, die
unterschiedlich lange leben. Keiner davon entspricht allein der Identität des Glossars:

1. **Keine Tabelle „Identität“, nur eine abgeleitete Sicht.** Am nächsten kommt `AccountProfile`,
   eine reine Lesesicht ohne eigenen gespeicherten Zustand. `personId` und `email` werden aus den
   Ankern gelesen, nicht aus eigenen Spalten (`account/AccountService.kt:710`;
   `docs/02-domaenenmodell.md`, Abschnitt „Konto-Identität: Claims, Anker, Konsolidierung“). Anker
   gibt es für die Partnernummer (`PERSON_ID`), die Versicherungsnummer (`VERSNR`), die Kennung des
   Online-Ausweises (`EID_RESTRICTED_ID`, über Nect `NECT_RESTRICTED_ID`) und die E-Mail-Adresse (`EMAIL`).
2. **Das Konto trägt bewusst keine Identität.** `Account` hat nur `id`, `createdAt` und `version`
   (`account/internal/Account.kt:23-40`). Es ist die Identität des Kontos und die Stelle, über die
   Änderungen gesperrt werden, ausdrücklich ohne eigenen Fakt.
3. **Drei Rollen statt „Identität ja oder nein“.** Ein Konto ist **Interessent**, solange ihm keine
   Person zugeordnet ist, auch wenn es schon einen bestätigten Namen hat (ADR-10). Mit einer Person
   im Personenverzeichnis ist es **Partner**, mit Versicherungsnummer **Versicherter** (ADR-34). Die
   Rolle wird nicht gespeichert, sondern aus Partnernummer und Versicherungsnummer abgeleitet
   (`orchestrator/session/TokenService.kt:95`, `frontend/src/accountRole.ts:8`). Das Personenverzeichnis
   sichert die Grundlage im Schema: eine KVNR nur zusammen mit einer Versicherungsnummer
   (`ck_person_kvnr_nur_versichert`,
   `src/main/resources/db/migration/ext_personenverzeichnis/V1__ext_personenverzeichnis.sql:28`).
4. **Stammdaten bleiben im Personenverzeichnis.** Name, Geburtsdatum und Adresse einer zugeordneten
   Person liest das Projekt immer aktuell aus dem Personenverzeichnis
   (`AttributeAuthority.PersonDirectory`, `tool_api/AttributeRules.kt:89-95`). Im Konto stehen sie nur
   als Historie.
5. **`AccountClaim` ist Historie, nicht Identität.** Ein Protokoll jeder bestätigten Änderung mit
   Quelle und Niveau, das nur ergänzt und nie überschrieben wird. Daraus ergibt sich der aktuelle
   Stand der Anker; Widerrufe (`AccountRetraction`) nehmen Werte zurück, auch wenn das
   Personenverzeichnis eine KVNR oder Versicherungsnummer ändert (ADR-34).
6. **`AuditEvent` (IDENTIFIED) ist Nachweis für die Prüfung, nicht Identität.** Es hält fest, dass und
   wie identifiziert wurde, wird aber für Entscheidungen nie gelesen.

**Einordnung:** Der aktuelle Stand der Anker entspricht zu jedem Zeitpunkt der „Identität“ des
Glossars, ergänzt um die Stammdaten aus dem Personenverzeichnis. Claims, Widerrufe und das Protokoll
der Identifizierungen beantworten Fragen, die das Glossar voraussetzt, aber nicht ausformuliert:
*woher* kommt der aktuelle Stand, *wann* galt ein Attribut als bescheinigt, *wie* wird ein Konflikt
zwischen zwei Bestätigungen gelöst (`account/internal/IdentityMatchingService.kt:51`, `:117`;
ADR-19, ADR-20). Das ist eine Verfeinerung, kein Widerspruch.

---

## 5) Argumentation für den Glossar-Autor

### Unbedingt ändern (billig, hoher Vertrauensgewinn)

Alle fünf Punkte aus dem letzten Abgleich sind erledigt (2026-09-25):

1. **Authentisierung und Authentifizierung** unterscheidet `docs/04-orchestrierung.md`, Abschnitt
   „Begriffe“, jetzt ausdrücklich; „Authentifizierung“ ist dort als bewusster Oberbegriff benannt.
2. **Die Wörter des Glossars** stehen in der Doku: Authentisierungsmittel, Identifizierungsmittel,
   bescheinigtes Attribut und Faktortyp sind im selben Abschnitt erklärt und in
   `02-domaenenmodell.md` und `03-tool-architektur.md` aufgegriffen. „Faktorart“ heißt überall
   „Faktortyp“. Im Code bleibt es beim Englischen.
3. **Wie sicher die Schlüssel im Browser sind**, sagt `docs/09-dpop.md` jetzt selbst: kein Niveau
   eines Secure Elements oder TPM, das Entsperrgeheimnis von KOBIL im `localStorage`, eine Demo.
4. **„Gerätebindung“** meint nur noch das Einrichten eines gerätegebundenen Anmeldeverfahrens; die
   Verknüpfung über `DeviceAccountLink` heißt „Geräteverknüpfung“.
5. **SMS plus Passwort** heißt „mehrstufige Authentifizierung“, mit dem Hinweis auf NIST SP 800-63B
   (AAL2).

Auch die Oberfläche folgt dieser Wortwahl: Ein Geräteschlüssel oder KOBIL heißt dort „an das Gerät
gebunden“, die Geräteverknüpfung „Verknüpfung“. Die Übersetzungsvorgabe (`prompts/de.md`) hält beide
auseinander.

### Mit Argumenten verteidigen (nicht zurückbauen)

1. **Ein Konto ohne zugeordnete Person (Interessent) und drei Rollen.** Das Glossar setzt voraus,
   dass Identität eine Sammlung von Attributen ist, sagt aber nicht, *ob* diese Zuordnung schon
   besteht. Das Projekt trennt bewusst „kann sich dieses Gerät wieder anmelden“ von „wissen wir, wer
   das ist“ und unterscheidet danach, ob die Person bei uns versichert ist. Sobald eine Person
   zugeordnet ist, verhält sich das Konto so, wie das Glossar es beschreibt.
2. **Claims, Anker und Widerrufe statt einer einzigen Sammlung von Attributen.** Der aktuelle Stand
   entspricht zu jedem Zeitpunkt der Identität des Glossars. Claims und Widerrufe beschreiben, wie
   dieser Stand entstanden ist. Das braucht es für nachprüfbare Antworten auf die Frage „warum galt
   zum Zeitpunkt X dieser Wert als bescheinigt?“, im Gesundheitswesen ein Gewinn, keine Abweichung.
3. **Abgestufte Bescheinigung (`AttributeAuthority`, `TrustLevel`) statt nur bescheinigt oder
   unbescheinigt.** Das Glossar deutet selbst Wege unterschiedlicher Stärke an (Zertifizierung
   gegenüber „weniger sicheren, nicht-kryptographischen Prüfverfahren“), ohne sie zu benennen.
   `TrustLevel` macht diese Abstufung ausdrücklich und maschinell prüfbar.
4. **Aktive Auflösung von Identitäten (`IdentityMatchingService`).** Das Glossar beschreibt die
   Identifizierung als Ergebnis, nicht als Vorgang. Es sagt nicht, was geschieht, wenn eine neue
   Bestätigung auf ein bestehendes Konto trifft. Das Projekt beschreibt genau diesen Vorgang: Konten
   werden nur über Anker gefunden, nie automatisch zusammengeführt (ADR-11, ADR-19, ADR-20).
5. **Faktortypen je Nachweis statt fest je Verfahren.** Das entspricht schon dem Glossar (Abschnitt 1)
   und zeigt eine sorgfältige Umsetzung.
6. **Vertrauen in die Freigabe auf dem Gerät.** Dass `device` und `kobil` Wissen oder Biometrie aus
   einer Freigabe melden, die der Server nicht sieht, ist kein Mangel, sondern genau das Vertrauen in
   das Gerät, das das Glossar bei der Gerätebindung selbst verlangt. Das Projekt schreibt es als
   benannte Ausnahme fest (ADR-21).
7. **Freischaltcode trotz getippter Angaben.** Die getippten Angaben beim Freischaltcode finden nur
   den Datensatz; was bescheinigt, ist der Code, den das Personenverzeichnis an die Person geschickt
   hat. Dass dieses Verfahren schwächer ist als der Online-Ausweis, bildet das Projekt im Niveau ab
   (`loa2` statt `loa3`).

### Nächster Schritt

Dieses Dokument ist selbst die Übersetzungshilfe für den Autor des Glossars: Begriff → Entsprechung
im Projekt (mit Fundstelle) → gegebenenfalls Begründung der Abweichung. Die Punkte, die sich an der
Doku ändern ließen, sind umgesetzt. In der Diskussion geht es damit nur noch um die Abweichungen
unter „Mit Argumenten verteidigen“: ob sie gerechtfertigt sind.
