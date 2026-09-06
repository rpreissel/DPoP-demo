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

---

## ADR-9: Profilabhängiges Token-Retrieval — Account-Keypair + custom OAuth2-Grant statt geteiltem Admin-Secret

**Entscheidung** (umgesetzt, DPoP-demo-xso): `GET .../{channelSessionId}/token` ist **`APP`-Kanal-
only** — ein `KEYCLOAK`-Kanal hat nie einen `AuthContext` und braucht auch keinen: dessen Client
hält bereits echte Keycloak-Tokens aus dem normalen Browser-Login (`dpop-demo-web`,
authorization_code) und erneuert sie direkt gegen Keycloak, nie über den Orchestrator
(`ChannelService.getToken` weist `KEYCLOAK` explizit mit `409` ab). Für `APP` bleibt der Endpunkt
im Default-Profil das bestehende Mock-JWT (`TokenService`); im `keycloak`-Profil liefert er einen
echten, von Keycloak signierten AccessToken (`KcTokenProvider`, [05-api.md](05-api.md) Abschnitt
2) — gemeinsame `TokenProvider`-Schnittstelle, Umschaltung ausschließlich über Spring-Profile,
kein Laufzeit-Zweig ([04-orchestrierung.md](04-orchestrierung.md) kennt das gleiche Muster für
`ChannelAccessGuard`).

Ein Step-up desselben Accounts mintet innerhalb dieser einen Keycloak-Session ein neues Token,
nicht in einer parallelen: `AccountTokenGrantType` markiert die von ihm angelegte
`UserSessionModel` mit einer eigenen Session-Note und sucht sie bei jedem weiteren Aufruf für
denselben Account zuerst wieder, statt bedingungslos eine neue anzulegen. Ihre Lebensdauer folgt
den normalen realm-weiten `SSO Session Idle`/`SSO Session Max`-Einstellungen (Keycloak-Default 30
Min/10 h) - der Grant gibt dafür (anders als ursprünglich geplant) ein echtes, opakes
`refresh_token` aus (`useRefreshToken() == true`), statt zustandslos zu bleiben: Keycloaks
Standard-`refresh_token`-Grant bumpt `lastSessionRefresh` bereits von sich aus bei jeder Erneuerung
(`TokenManager.generateRefreshToken`), was die Idle-Time genau wie bei einer echten Browser-Session
verlängert, ohne dass dieser Code das selbst nachbauen müsste. `KcTokenProvider` nutzt diesen
Standard-Grant für jede reine Fristverlängerung (`KeycloakAdminClient.refreshAccountToken`) - ohne
Assertion, ohne den Account-Private-Key anzufassen - und geht nur dann erneut über den custom
Account-Token-Grant (mit frischer, signierter Assertion), wenn sich ACR/AMR seit dem letzten Mint
tatsächlich geändert haben. Die absolute `SSO Session Max`-Grenze bleibt davon unberührt - wie bei
jeder Keycloak-Session erzwingt sie irgendwann eine neue Session, unabhängig von der Aktivität.

Die Assertion trägt dafür `acr`/`amr` als eigene, signierte Claims - `AccountTokenGrantType`
kopiert sie unverändert in dieselben `UserSessionModel`-Notes, die `OrchestratorAuthenticator` auf
der WEB-Kanal-Seite ohnehin schon schreibt (`orchestrator_acr`/`orchestrator_amr`), sodass der
bereits registrierte `OrchestratorAcrAmrMapper` (Client-Scope `orchestrator-claims`, jetzt auch
Default-Scope von `orchestrator-admin`, nicht nur von `dpop-demo-web`) sie unverändert in den
echten AccessToken schreibt - der Orchestrator bleibt damit für `APP` wie für `KEYCLOAK` gleichermaßen
die alleinige ACR/AMR-Instanz, nur der Transportweg unterscheidet sich.

Unabhängig vom Profil gilt außerdem: ein Step-up, der die zugrunde liegende `AuthEvidence` verändert
(`AuthEvidenceService.applyEvidence`/`applyEvidenceUpdate`), verwirft aktiv das im `AuthContext`
gecachte Access- UND RefreshToken (`tokenHandle`/`tokenExpiresAt`/`refreshTokenHandle`/
`refreshExpiresAt` auf `null`) — sonst würde `KcTokenProvider`s eigener Refresh-Pfad die alte
Keycloak-Session mit den alten ACR/AMR-Notes einfach weiter verlängern, ohne den Step-up je zu
bemerken. Für `TokenService`s Mock-Pfad ist nur die erste Hälfte sicherheitsrelevant (der Mock
mintet bei jedem Aufruf ohnehin frisch aus der aktuellen Evidenz), das gemeinsame Verwerfen beider
Felder kostet dort lediglich ein überflüssiges neues Mock-RefreshToken.

Dafür erzeugt der Orchestrator bei jedem Keycloak-Account-Sync ein eigenes, asymmetrisches
Schlüsselpaar pro Account (`account_keycloak_keypair`, EC P-256) und spiegelt den Public Key als
echtes Keycloak-`Credential` (Typ `orchestrator-public-key`, `KeycloakAdminClient.setPublicKeyCredential`)
auf den Keycloak-User — bewusst nicht als Attribut: Proof-of-possession-Material gehört in den
Credential-Store, nicht neben `email`/`firstName` in dieselben, deutlich leichter versehentlich
exportierten Profildaten. Da Keycloaks Admin-REST-API keinen generischen "beliebigen Credential-Typ
setzen"-Endpunkt kennt (nur den fest verdrahteten Passwort-Reset), schreibt eine eigene
`AdminRealmResourceProvider`-Erweiterung (`AccountPublicKeyResource`, gemountet unter
`/admin/realms/{realm}/orchestrator-keys/{accountId}`) den Credential serverseitig — Keycloaks
eigene Admin-Authentifizierung/-Autorisierung greift dabei automatisch, da dieser Erweiterungspunkt
hinter derselben `/admin/realms/{realm}/...`-Fassade hängt wie jeder eingebaute Endpunkt.
Ein neuer custom Grant-Type in `keycloak-extension/` (`urn:dpop-demo:account-token`,
`AccountTokenGrantType`, Keycloaks offizielle, pluggable `OAuth2GrantType`-SPI in
`keycloak-server-spi-private`, registriert über `META-INF/services` wie schon
`AuthenticatorFactory`) verlangt zusätzlich zur normalen Client-Authentifizierung des
Orchestrators eine mit dem account-spezifischen Private Key signierte, kurzlebige Assertion
(`sub`=accountId, `aud`=Grant-URN, `exp` <= 60s) und mintet erst dann einen echten AccessToken für
diesen Nutzer.

**Erwogene Alternativen**:

- **Ein einzelnes geteiltes Admin-Secret** (der bereits bestehende `keycloak-sync`-Service-Account
  ruft direkt einen Token für einen beliebigen Nutzer ab, z. B. via Token-Exchange oder
  Impersonation): verworfen — ein einziges kompromittiertes Secret könnte dann für JEDEN Account
  einen echten Token ausstellen, nicht nur für den eigenen. Der Blast Radius eines Leaks wäre
  maximal statt auf einen Account begrenzt.
- **Nur die umgekehrte Richtung erlauben** (Keycloak ruft den Orchestrator, nie umgekehrt; der
  Orchestrator triggert Keycloak nur indirekt, z. B. per Redirect/Custom-Flow-Schritt): verworfen
  für diesen konkreten Anwendungsfall — `GET .../token` ist ein synchroner API-Aufruf eines
  Orchestrator-Clients, der eine sofortige Antwort braucht; ein Trigger-und-Warte-Umweg über
  Keycloak würde daran nichts sicherer machen, nur komplizierter.
- **`private_key_jwt`-Client-Authentifizierung statt `client_secret`** für die Admin-/Sync-Strecke
  selbst (RFC 7523, secret-frei wie ADR-7): sinnvolle, unabhängige Härtung derselben Philosophie,
  aber orthogonal zu diesem ADR — betrifft die Account-Sync-Strecke, nicht das Token-Retrieval,
  und wurde bewusst nicht mit umgesetzt, um dieses Feature nicht aufzublähen.

**Warum das Account-Keypair**: Es überträgt dasselbe Prinzip wie ADR-7 (Signatur statt Secret,
asymmetrisch statt geteilt) auf eine zweite Richtung — hier nicht pro Client/Node wie ADR-7,
sondern pro Account, weil genau das der Blast Radius ist, der eingegrenzt werden soll: ein Leak
trifft höchstens einen Account, nie das gesamte System. Die Assertion selbst bleibt bewusst
kurzlebig (`exp` <= 60s) und wird nur beim ERSTEN Mint bzw. bei einer tatsächlichen ACR/AMR-
Änderung gebraucht - jede reine Fristverlängerung dazwischen läuft über das von Keycloak
ausgegebene `refresh_token`, ohne den Account-Private-Key erneut anzufassen.

**Preis**: Ein weiterer, account-gebundener Datensatz (`account_keycloak_keypair`) mit eigenem
Lebenszyklus (erzeugt bei Sync, gelöscht bei `AccountDeleted`, [07-betrieb.md](07-betrieb.md)
Abschnitt 3) sowie ein zusätzliches, projektspezifisches Stück Keycloak-Erweiterung, das bei einem
Keycloak-Versions-Upgrade gegen die `server-spi-private`-Schnittstelle (bewusst als "private"
markiert, keine stabile Public-API-Garantie) mitgeprüft werden muss. Demo-only: Der Private Key
liegt aktuell unverschlüsselt in der Datenbank (bekannte, offene Lücke, kein produktionsreifer
Zustand).
