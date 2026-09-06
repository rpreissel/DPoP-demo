# Architekturentscheidungen

Die großen Entscheidungen dieses Projekts, mit der ernsthaft erwogenen Alternative und dem Preis
der gewählten Lösung. Was die Lösung *ist*, steht in den verlinkten Kapiteln — hier steht nur das
*Warum*, gesammelt an einem Ort statt als Einzeiler über acht Dateien verstreut.

---

## ADR-1: Ein Controller je Tool, kein generischer Dispatcher

**Entscheidung**: Jedes Tool bekommt einen eigenen, typisierten `@RestController` mit eigenem
Request-DTO. `POST/PATCH/GET` liegen je Tool in einem eigenen Controller ([Tool-Architektur](03-tool-architektur.md)
Abschnitt 2, [Projektrahmen](08-projektrahmen.md) A11).

**Erwogene Alternative**: Ein einziger Controller, der über `toolId` zur Laufzeit auf den
passenden Handler dispatcht, mit einer generischen `Map<String, Any?>` als Request-Body.

**Warum diese**: Lesbarkeit hat Vorrang vor maximal generischem API-Wiring. Ein typisiertes DTO
zeigt am Controller, was ein Tool tatsächlich erwartet — bei einer `Map<String, Any?>` steht das
nur noch im Handler-Code, nicht mehr in der Signatur. Ein `toolId`-basierter Runtime-Dispatch wäre
außerdem eine Fehlerquelle, die der Compiler nicht sieht: ein neues Tool ohne passenden
`when`-Zweig fiele erst zur Laufzeit auf.

**Preis**: Mehr Code — zwölf Controller statt einem, mit strukturell ähnlichem Aufbau
(Aktivierung/Fortschreiben/Lesen). Ein neues Tool bringt einen neuen Controller mit, keine
Erweiterung eines bestehenden `when`.

---

## ADR-2: Zustand statt Vererbung bei `AuthJourney`

**Entscheidung**: `AuthJourney` ist eine flache Entity ohne Subklassen oder getrennte Tabellen je
Intent. Was sich je Intent unterscheidet, steckt in `stateType` (Diskriminator) plus `state`
(JSON) — nicht in Spalten oder Fremdschlüsseln auf intent-spezifische Detailtabellen
([Domänenmodell](02-domaenenmodell.md) Abschnitt 2).

**Erwogene Alternative**: Eine Tabelle je Intent (bzw. Single-Table-Vererbung mit spaltenweise
kodierten intent-spezifischen Feldern), analog zu einem klassischen JPA-`@Inheritance`-Modell.

**Warum diese**: Die Menge der Attribute unterscheidet sich stark zwischen Intents (`REGISTER`
braucht andere Zwischenzustände als `STEP_UP`), und das Verhalten dazu gehört ohnehin in Services
(`AuthPolicy`, Tool-Katalog), nicht in eine JPA-Entity. Getrennte Spalten je Attribut ergäben eine
breite Tabelle aus überwiegend leeren Feldern — genau die formlose Ablage, die dieses Modell
vermeiden soll. `stateType` bleibt trotzdem abfragbar, `state` ist nur das JSON der jeweils
gültigen Attributmenge.

**Preis**: Der `state`-Inhalt ist für die Datenbank selbst intransparent — Constraints und
Fremdschlüssel auf einzelne JSON-Attribute sind nicht möglich, Konsistenz muss die
`IntentStrategy` je Intent selbst sicherstellen, nicht das Schema.

---

## ADR-3: `ChannelSession` bewusst kurzlebig, Geräte-Identität in `DeviceAccountLink`

**Entscheidung**: `ChannelSession` hat eine kurze TTL (24 Stunden, [Betrieb](07-betrieb.md)) und
trägt keine langlebige Geräte-Zuordnung. Die einzige dauerhafte Zuordnung Gerät -> Account
(`bindingKeyRef -> accountId`) liegt in `DeviceAccountLink`, einer eigenen, von jeder einzelnen
`ChannelSession` unabhängigen Tabelle ([Domänenmodell](02-domaenenmodell.md) Abschnitt 1,
[DPoP-Bindung](09-dpop.md) Abschnitt 3).

**Erwogene Alternative**: `ChannelSession` selbst langlebig machen (z. B. 30 Tage) und die
Geräte-Wiedererkennung darüber lösen — ein wiederkehrendes Gerät würde dieselbe Session
fortsetzen statt eine neue anzulegen.

**Warum diese**: Eine Session, die ein Gerät über Wochen repräsentiert, vermischt zwei
unterschiedliche Lebensdauern in einer Entity: den einzelnen Kanal-Vorgang (Stunden) und die
Geräte-Identität (dauerhaft). `DeviceAccountLink` trennt das sauber — der `bindingKeyRef` beweist
nur, welches Gerät spricht, nie, welche Session fortzusetzen ist; eine wiederkehrende
`ChannelSession` wird deshalb **immer** neu angelegt und nur mit `accountId` vorbefüllt, nie
wiederverwendet. Nebeneffekt: Der Session-Cleanup-Job kann `ChannelSession` bedenkenlos löschen,
ohne die Geräte-Wiedererkennung zu gefährden.

**Preis**: Zwei Konzepte statt eines — wer nur `ChannelSession` liest, sieht die Geräte-Bindung
nicht; sie muss explizit über `DeviceAccountLink` nachgeschlagen werden.

---

## ADR-4: Flyway-Neubaseline statt Migration des Altcodes

**Entscheidung**: `V1__schema.sql` ersetzt die komplette frühere Migrationshistorie (`V1`–`V16`
im ursprünglichen Code) durch einen sauberen Neubau, statt sie fortzuschreiben
([Umsetzungsplan](11-umsetzungsplan.md)).

**Erwogene Alternative**: Den Altcode Schritt für Schritt migrieren — `Attempt`-Terminologie zu
`ToolSession`/`ToolOutcome` weiterentwickeln, methodenspezifische URL-Pfade schrittweise auf den
Tool-Namespace umstellen, Klartext-TAN nachträglich hashen.

**Warum diese**: Der Alt-Stand unterschied sich strukturell so stark vom Zielbild (andere
Terminologie, kein `ToolDescriptor`/`AuthPolicy`, unverschlüsselte TANs), dass eine
Schritt-für-Schritt-Migration mehr Zwischenzustände und damit mehr Fehlerfläche erzeugt hätte als
ein Neubau. Für eine Demo-Anwendung mit lokaler H2-Datei ohne produktive Bestandsdaten entfällt
außerdem das übliche Gegenargument (Datenverlust bei bestehenden Kunden).

**Preis**: Diese Entscheidung ist an den Kontext gebunden — eine Demo-App ohne Produktivdaten.
Bei echten Bestandsdaten wäre eine Neubaseline nicht vertretbar; die hier gewählte Lösung ist
kein Vorbild für ein Projekt mit echten Nutzerdaten.

---

## ADR-5: Dreifache Deckelung des Sicherheitsniveaus

**Entscheidung**: Das erreichbare Sicherheitsniveau wird an drei unabhängigen Stellen gedeckelt,
nie nur an einer: `identifications[].loa` begrenzt, was ein Account je erreichen kann;
`authenticationMethods[].enrolledUnderAcr` begrenzt, was eine einzelne Methode bei ihrer
Verwendung beisteuern darf; `achievedAcr` einer Session ist das Minimum aus dem, was tatsächlich
nachgewiesen wurde, und dem, was die verwendete Methode laut ihrem `enrolledUnderAcr` überhaupt
tragen darf ([Orchestrierung](04-orchestrierung.md) Abschnitt 8, [Überblick](01-ueberblick.md)).

**Erwogene Alternative**: Nur `achievedAcr` aus der aktuellen Session-Historie ableiten (was wurde
in dieser Session nachgewiesen?), ohne die Enrollment-Bedingungen der einzelnen Methode
rückwirkend zu berücksichtigen.

**Warum diese**: Ohne die `enrolledUnderAcr`-Deckelung gäbe es einen Eskalationspfad: Wer eine
schwache Session übernimmt (z. B. `loa1`), könnte darin eine eigene Methode hinterlegen und
dauerhaft ein höheres Niveau vortäuschen, als er je nachgewiesen hat — eine Methode darf bei der
Authentifizierung nie mehr Vertrauen erzeugen, als bei ihrer Einrichtung vorhanden war. Die dritte
Deckelung (`identifications[].loa`) verhindert zusätzlich, dass eine schwach identifizierte
Person nachträglich über starke Auth-Methoden ein Niveau erreicht, das ihre Identifizierung nie
hergab.

**Preis**: Drei Stellen, an denen ein Niveau sinken kann, statt einer — wer nur `achievedAcr`
einer laufenden Session betrachtet, sieht nicht, welche der drei Deckelungen gerade greift; das
muss über `AuthContext`, `authenticationMethods[].enrolledUnderAcr` und `identifications[].loa`
gemeinsam nachvollzogen werden.

---

## ADR-6: `next` als reine Adresse, feste Routing-Tabelle statt HATEOAS

**Entscheidung**: Jede API-Antwort enthält ein `next`-Objekt, das ausschließlich adressiert (Typ,
`toolId`/`context`, Step) und nie Inhalt oder Links mitliefert. Der Client bildet daraus über eine
**eigene, lokale, feste Routing-Tabelle** (`(toolId|context, step)` -> UI-Komponente bzw.
Endpunkt) den nächsten Schritt ab — nicht durch Auswertung von URLs oder Links aus der Antwort
([API](05-api.md) Abschnitt 1, [Frontend](10-frontend.md)).

**Erwogene Alternative**: HATEOAS — die Antwort liefert dem Client fertige, klickbare Links
(`href`) für den nächsten Schritt; der Client folgt ihnen, statt sie selbst aus `next` abzuleiten.

**Warum diese**: Die Menge möglicher nächster Schritte ist bei diesem Fachprozess klein und stabil
(Tool-Katalog plus eine Handvoll Orchestrator-eigene Seiten) — der Flexibilitätsgewinn von
HATEOAS (Server kann Übergänge ändern, ohne dass der Client es "weiß") lohnt hier den Mehraufwand
nicht: das Frontend braucht für jeden neuen Schritt ohnehin eine eigene UI-Komponente, ein Link
allein reicht nie. Eine feste Tabelle macht zusätzlich explizit sichtbar, welche Übergänge das
Frontend überhaupt kennt — bei HATEOAS wäre das erst zur Laufzeit ersichtlich.

**Preis**: Backend und Frontend müssen synchron gehalten werden — ein neuer `next`-Wert ohne
passenden Eintrag in der Frontend-Routing-Tabelle führt zu einem unbehandelten Zustand im Client,
den der Server nicht verhindern kann.

---

## ADR-7: Web-Kanal ohne mTLS, signierte Request-Assertion statt Client-Zertifikat

**Entscheidung** (umgesetzt): Die Server-zu-Server-Strecke Keycloak -> Orchestrator im Web-Kanal
wird **ohne mTLS** abgesichert; statt eines Client-Zertifikats verifiziert der Orchestrator eine
signierte Request-Assertion von Keycloak (`PeerAuthValidator`, [05-api.md](05-api.md) Abschnitt 3).
Der Browser spricht dabei nie direkt mit dem Orchestrator.

Ein einziges JWT pro Request statt Access-Token-plus-separatem-Proof: Beim initialen Login gibt es
noch kein `sub` — ein Access Token ohne `sub` wäre schräg, und man bräuchte zwei
Anspruchssätze. Die Assertion sagt stattdessen einfach "ich handle für diesen Kanal-Anker, Nutzer
ggf. noch unbekannt".

**Erwogene Alternativen**:

- **mTLS** zwischen Keycloak und Orchestrator — beiderseitige Zertifikatsprüfung auf
  Transportebene.
- **Token Exchange** — verworfen als unnötig: Keycloak besitzt die Session ohnehin und kann die
  Assertion in-process ausstellen, kein zusätzlicher HTTP-Roundtrip nötig.
- **Keycloak hält einen DPoP-Key als Geräte-Ersatz** — verworfen: DPoPs Wert kommt daher, dass der
  Schlüssel nicht-exportierbar auf einem unvertrauten Client liegt. Hält ein Server ihn, ist es
  faktisch ein Shared Secret mit asymmetrischer Zeremonie. Pro Nutzer wäre es zusätzlich fatal:
  `DeviceAccountLink` würde bei jedem Web-Login treffen, die Geräte-Wiedererkennung sagt still
  immer ja.

**Warum die signierte Assertion**: mTLS bringt in vielen Deployments zusätzlichen Betriebsaufwand (Zertifikats-
Rollout, -Rotation, -Widerruf für zwei Serverdienste) mit, den eine signierte Anwendungsebene-
Assertion nicht braucht — die Signatur lässt sich mit demselben Schlüsselmaterial prüfen, das
Keycloak ohnehin für Tokens verwendet, ohne eine zweite PKI für Transportzertifikate zu
etablieren. Weil der Browser den Orchestrator nie direkt erreicht, bleibt die Angriffsfläche auf
die eine Server-zu-Server-Strecke beschränkt, für die die Signaturprüfung ausreicht.

**Preis**: Die Sicherheit der Strecke hängt vollständig an der Signaturprüfung der Anwendung
(korrekte Schlüsselverwaltung, Ablaufprüfung, Replay-Schutz) — mTLS hätte einen Teil davon
(Peer-Identität, Verschlüsselung) bereits auf Transportebene erzwungen, unabhängig von
Anwendungscode. Ein kompromittiertes Keycloak kann jeden Nutzer imitieren — das ist der
kc-first-Architektur inhärent, auch mTLS ändert daran nichts.

---

## ADR-8: Keycloak bleibt Journey-Eigentümer seiner eigenen nativen Schritte, statt vollständiger Delegation oder Identity-Brokering

**Entscheidung** (umgesetzt): Der Web-Kanal lässt Keycloak seine eigene, native
Authentifizierungs-Flow-Konfiguration (Conditional-LoA-Subflows, natives Passwort-Login) fahren
und ruft den Orchestrator nur innerhalb einer bereits laufenden, persistenten `AuthJourney` für die
Schritte auf, die Keycloak selbst nicht kann (`KC_SELECT_METHOD`, [04-orchestrierung.md](04-orchestrierung.md)
Abschnitt 3). Der Orchestrator bleibt für die Dauer eines Flow-Durchlaufs alleinige, kombinierende
ACR/AMR-Instanz ([05-api.md](05-api.md) Abschnitt 3).

**Erwogene Alternativen**:

- **Orchestrator als externer OIDC-Identity-Provider** (Keycloak bindet den Orchestrator per
  Identity Brokering ein, Browser-Redirect zu einer eigenen Orchestrator-Web-UI): verletzt die
  Leitplanke "Browser spricht nie mit dem Orchestrator" direkt — der Orchestrator bräuchte eine
  eigene, öffentlich erreichbare, gehärtete Web-UI.
- **Volle Journey-Delegation** (der Orchestrator wäre alleiniger Journey- und ACR/AMR-Eigentümer,
  Keycloak würde jedes Formular über einen einzigen generischen Authenticator rendern, nie eigene
  Authenticatoren nutzen): architektonisch sauber, verzichtet aber komplett auf Keycloaks
  eingebaute Fähigkeiten (natives Passwort-Login, OTP/TOTP, WebAuthn/Passkey,
  Social-Login-Brokering, die gesamte Conditional-LoA-Maschinerie) — genau die Fähigkeiten, derentwegen
  eine Keycloak-Anbindung überhaupt Sinn ergibt.
- **Zustandslose Einzel-Tool-Aufrufe ohne Journey** (Keycloak bleibt vollständig Journey-Eigentümer,
  ruft den Orchestrator nur für einzelne, isolierte Faktoren ohne begleitende `ChannelSession` auf):
  näher an der gewählten Lösung, aber ohne die persistente Journey verliert der Orchestrator die
  Fähigkeit, mehrere eigene Tools im selben Login mit gemeinsamer Evidenz zu verrechnen. Bleibt als
  Muster sinnvoll für Touchpoints außerhalb eines zusammenhängenden Login-/Step-up-Flows (eine
  Keycloak-„Required Action", eine Selbstbedienungs-Aktion in der Account-Konsole), aber nicht als
  Ersatz für den Login-Flow selbst.

**Preis**: Split-Brain-Risiko zwischen zwei Zustandshaltern (Keycloaks Flow-Struktur, der
Orchestrator-Journey) — abgefedert dadurch, dass jede Seite klar eine eigene, nicht überlappende
Zuständigkeit trägt (Keycloak entscheidet OB und WELCHES ACR-Level angefragt ist, der Orchestrator
WAS innerhalb einer Stufe passiert und wie sich mehrere Nachweise zu einem Gesamt-ACR
kombinieren).
