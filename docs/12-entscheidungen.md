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

**Preis**: Mehr Code — 17 Controller statt einem, mit strukturell ähnlichem Aufbau
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

**Entscheidung**: `ChannelSession` hat eine begrenzte Aufbewahrungsfrist (30 Tage, [Betrieb](07-betrieb.md)) und
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

**Nachtrag**: Eine zweite Neubaseline hat die danach wieder aufgelaufenen 37 Migrationen
zusammengeführt — mit Modellbereinigung, siehe ADR-14.

---

## ADR-5: Dreifache Deckelung des Sicherheitsniveaus

**Entscheidung**: Das erreichbare Sicherheitsniveau wird an drei unabhängigen Stellen gedeckelt,
nie nur an einer: das LoA der Identifizierung (`account_identification.achieved_loa`) begrenzt,
was ein Account je erreichen kann; `account_auth_method.enrolled_under_acr` begrenzt, was eine einzelne Methode bei ihrer
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
Deckelung (LoA der Identifizierung) verhindert zusätzlich, dass eine schwach identifizierte
Person nachträglich über starke Auth-Methoden ein Niveau erreicht, das ihre Identifizierung nie
hergab.

**Preis**: Drei Stellen, an denen ein Niveau sinken kann, statt einer — wer nur `achievedAcr`
einer laufenden Session betrachtet, sieht nicht, welche der drei Deckelungen gerade greift; das
muss über `AuthContext`, `account_auth_method.enrolled_under_acr` und
`account_identification.achieved_loa` gemeinsam nachvollzogen werden.

**Nachtrag**: `DefaultAuthPolicy.resolveAcr` berechnet den ersten Deckel (LoA der Identifizierung)
inzwischen nicht mehr implizit über einen undifferenzierten Maximalwert aller Nachweise, sondern
als eigene, explizite Größe (IAL, `identityAssuranceLevel`), getrennt von der reinen
Authentifizierungsstärke (AAL, `authenticatorAssuranceLevel`) — siehe
[Orchestrierung](04-orchestrierung.md) Abschnitt 8, "IAL und AAL: zwei Fragen, eine `acr`-Zahl".
Der nach außen sichtbare `acr`-Wert und diese ADR bleiben unverändert; die Trennung behebt lediglich
einen zuvor bestehenden Fehler, bei dem eine Identifizierung sich mit einem einzelnen artfremden
Auth-Faktor zu einem falschen MFA-Bump verbinden konnte.

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
Default-Scope von `orchestrator-app-token`, nicht nur von `dpop-demo-web`) sie unverändert in den
echten AccessToken schreibt - der Orchestrator bleibt damit für `APP` wie für `KEYCLOAK` gleichermaßen
die alleinige ACR/AMR-Instanz, nur der Transportweg unterscheidet sich.

`KeycloakAdminClient.requestAccountToken`/`refreshAccountToken` authentifizieren sich dafür als
eigener, privilegienloser Client `orchestrator-app-token` (V8), nicht als `orchestrator-admin` -
`AccountTokenGrantType` prüft keinerlei Client-Rolle, braucht also keine der realm-management-
Rechte (`manage-users`/`view-realm`), die `orchestrator-admin` für seine Admin-REST-Sync-Aufgaben
(`upsertUser`, `setPublicKeyCredential`, ...) trägt. Genau das war der ursprüngliche Punkt dieses
ADRs ("statt geteiltem Admin-Secret") - die frühere Implementierung hatte beide Zwecke
versehentlich wieder auf denselben Client-Secret zusammengeführt.

Unabhängig vom Profil gilt außerdem: ein Step-up, der die zugrunde liegende `AuthEvidence` verändert
(`AuthEvidenceService.applyEvidence`/`applyEvidenceUpdate`), verwirft aktiv das im `AuthContext`
gecachte Access- UND RefreshToken (`accessToken`/`accessExpiresAt`/`refreshToken`/
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

---

## ADR-10: Interessent ist Konto-Zustand, kein eigener AuthIntent

**Entscheidung** (Zielbild Claims-Modell, [Idee](ideen/claims-modell-und-vertrauensanker.md); Umsetzung folgt): Es gibt keinen eigenen `AuthIntent.INTERESSENT`. Ein Interessent — ein Konto, das nur über bezeugte Claims identifiziert ist, ohne `person_id`-Bindung — ist eine Beobachtung über den Ausgang einer Identifizierung, kein wählbares Ziel. Die `REGISTER`-Journey (und jeder andere Intent, der Identifizierungen durchläuft) verzweigt auf das Auflösungs-Ergebnis (`Resolution`: `ExistingAccount` / `NewInteressent` / `Ambiguous`): Anker-Treffer bindet wie heute, Claims-only legt bzw. führt das Konto ohne `person_id` fort (Konsolidierung auf `NULL`), mehrdeutig geht an die Journey-Politik.

**Erwogene Alternative**: Ein eigener `AuthIntent` mit eigener Journey, eigenen States und eigener Strategie — begründbar, falls Interessenten eine abweichende Politik bräuchten (kein Stammdaten-Abgleich von Anfang an, andere Enrollment-Angebote).

**Warum diese**: `AuthIntent` benennt nach eigener Definition
([AuthIntent.kt](../src/main/kotlin/com/example/dpop/orchestrator/journey/AuthIntent.kt)) ein Ziel
samt Strategie, nie eine Beschreibung dessen, was ein Lauf geworden ist — "Interessent werden"
ist kein Ziel, sondern das Ergebnis eines ID-Verfahrens, das keinen Anker geliefert hat. Der
Journey-Verlauf ist für beide Ausgänge strukturell identisch: Nach der Identifizierung stehen
dieselben Schritte an (Konto-Auflösung, dann Auth-/Enrollment-Angebote), nur die Konto-Auflösung
selbst unterscheidet sich. Das heutige `ConfirmIdentity` behandelt den Fall `personId == null`
bereits als Verzweigung innerhalb der bestehenden Journey (REGISTER "Enrollment zuerst",
[Orchestrierung](04-orchestrierung.md) Abschnitt 2) — die Verallgemeinerung ändert die Form
nicht, nur die Bezugsquelle (Claims statt dediziertem Feld). Ein eigener Intent verdoppelte
zudem jede künftige Politik-Gabel (`STEP_UP`, `RE_IDENTIFY` auf Interessenten-Konten).

**Preis**: Interessenten-spezifische Politik lebt als Verzweigungen in den bestehenden
Strategien (analog `ConfirmDeviceRebind`) statt in einem eigenen Strategy-Objekt — die
Strategien werden dadurch konditioneller, nicht übersichtlicher.

---

## ADR-11: Kontoübergreifender person_id-Konflikt ist Abweisung, Merge nie automatisiert

**Entscheidung** (Zielbild Claims-Modell, [Idee](ideen/claims-modell-und-vertrauensanker.md); Verhalten entspricht dem heutigen Code): Beanspruchen zwei Konten denselben `person_id`-Wert, wird die zweite Bindung abgewiesen (409, heutige Meldung "Diese Person ist bereits über ein anderes Konto registriert") und nichts adoptiert: keine Claim-Zeile, keine Konsolidierung, keine Anker-Schreibung; der Konflikt ist im Journey-Abort dokumentiert. Ein Merge der Konten ist nie automatisiert — keine Rangfolge, kein "stärkerer Anker gewinnt" über Kontgrenzen hinweg — sondern eine explizite, operator-getriebene Fähigkeit, die bewusst außerhalb des Claims-Modell-Umfangs bleibt. DB-seitig sichert `UNIQUE(person_id)` (partial, `WHERE person_id IS NOT NULL`) dieselbe Semantik für alle Schreibpfade ab.

**Erwogene Alternative**: Die bezeugte Aussage trotzdem loggen und nur die Konsolidierung
verweigern (Konflikt als abfragbarer Zustand, das Konto läuft als Interessent weiter); oder eine
Review-Queue, die aus dem Konflikt einen manuellen Klärungsfall macht.

**Warum diese**: False merge ist die teuerste Fehlerform des Modells — zwei verschiedene
Menschen dauerhaft verknüpft — und sie zu vermeiden wiegt schwerer als der Verlust der
bezeugten Aussage, die ephemer im Journey-Log nachweisbar bleibt. Die Abweisung entspricht dem
heutigen, bewusst so gebauten Verhalten
([State-Diagramme](demo/05-state-diagramme-intents.md), REGISTER-Abbruch bei Person mit
Bestandskonto); die Entscheidung macht daraus ein Modell-Statement statt einen
Implementierungszufall. Die Konsolidierungs-Rangfolge (Anker-Klasse vor Rezenz) gilt sehr wohl —
aber innerhalb EINES Kontos, zwischen Quellen für dasselbe Konto; sie endet an der Kontgrenze.

**Preis**: Der betroffene Nutzer kommt nicht weiter und nicht auf einem automatischen Weg —
bis zu einer (ungebauten) Merge-Fähigkeit bleibt der Fall ein Support-Vorgang. Und die
bezeugte Identifikation bleibt nur im ephemeren Journey-Log, nicht im Account-Log.

**Nachtrag** (Härtung, [13-review-domaenen-db-modell.md](13-review-domaenen-db-modell.md)): Drei
Lücken zwischen dieser Entscheidung und ihrer Umsetzung wurden geschlossen. Erstens brach
`AccountService.recordAnchor` bei einem fremden Anchor zwar ab, schrieb die Projektionsspalte
(z. B. `account.email`) aber trotzdem — genau die Divergenz, gegen die der Anchor eingeführt
wurde. Der Anchor wird jetzt **vor** der Projektionsspalte geschrieben, ein Konflikt bricht also
ab, bevor etwas geschrieben ist, statt nur per Rollback rückgängig gemacht zu werden. Zweitens
verließ sich `IdentityMatchingService.resolveByAnchor` bei mehreren attestierten Ankern auf die
zufällige Iterationsreihenfolge eines `Set`, statt auf die hier beschriebene Rangfolge — er
iteriert jetzt explizit nach `AttributeType.anchorBindingStrength` (`tool_api/AttributeRules.kt`)
absteigend sortiert, die Stärkeordnung ist damit eine Eigenschaft des Codes, nicht mehr der
`Set`-Implementierung des Aufrufers. Drittens fing `findOrCreateAccount` die
`UNIQUE(person_id)`-Kollision selbst nicht ab — der Verlierer eines Rennens zweier gleichzeitiger
Step-up-Kanäle bekam einen Serverfehler statt des inzwischen existierenden Kontos; eine eigene,
in `REQUIRES_NEW` laufende Bean (`AccountRaceSafeCreator.createIfAbsent`) behandelte zunächst
die Konfliktantwort als „existiert bereits". Dieser Zwischenstand ist durch die atomare
Claim-Übernahme unten abgelöst.

**Nachtrag 2** ([ideen/account-attribute-und-trust-vereinheitlichen.md](ideen/account-attribute-und-trust-vereinheitlichen.md), alle 7 Pakete): `person_id` ist seither
kein Sonderfall mehr, sondern hat einen eigenen `account_anchor`-Eintrag, genau wie `email`
(seit ADR-14 ohne zusätzliche Projektionsspalte) — die kontoübergreifende Abweisung dieser Entscheidung läuft seither
technisch über denselben `recordAnchor`-Pfad (Cross-Account-Konflikt: fremder Anchor-Wert bereits
vergeben) statt über einen separaten `bindPersonId`/`findAccountByPersonId`-Vergleich in
`JourneyService`. Neu dazugekommen, INNERHALB eines Kontos: `AttributeType.allowsAnchorReplacement`
ist für `PERSON_ID` `false` (anders als `email`) — ein zweiter, abweichender `person_id`-Claim für
ein Konto, das schon einen anderen Wert gebunden hat, wird ebenfalls per
`IdentityConflictException` abgewiesen, nicht nur der kontoübergreifende Fall. `resolveByAnchor`
kennt `person_id` seither als gewöhnlichen (höchstrangigen) Anker, kein separater
`resolveByPersonIdProjection`-Zweig mehr; `MatchedVia.PersonId` ist entfallen zugunsten von
`MatchedVia.Anchor(PERSON_ID)`.

**Nachtrag 3 — atomare Account-Anlage:** `findOrCreateAccount`, `AccountRaceSafeCreator` und
der direkte Account-Repository-Lookup per PersonId entfallen. `findAccountByPersonId` und
`findAccountByEmail` bleiben als Extensions auf `AccountService` erhalten; beide delegieren
an `resolveByAnchor` und lesen danach das Profil, ohne eigenen Repository-Sonderpfad oder
eigene Transaktionsgrenze. Ein neues Konto entsteht ungebunden
und erhält seine PersonId ausschließlich über `recordClaims`, gemeinsam mit Log und Anker in
der Journey-Transaktion. Der Verlierer einer konkurrierenden Bindung erhält nach vollständigem
Rollback `409 INVALID_STATE_TRANSITION`, statt automatisch das Gewinnerkonto zu übernehmen.
Auch ein Fehler nach erfolgreicher Claim-Übernahme lässt keinen vorab committeten Account zurück.
Ein abgeleiteter `Identified.personId`-Getter für Protokollierung und Drosselung bleibt zulässig:
Er liest den Pflicht-Claim, speichert aber keine zweite Identitätsquelle.

Die ID-Komfortfunktionen `resolveAccountByEmail`, `resolveAccountByPersonId` und
`resolveAccountByKvnr` sind Extensions auf `AccountDirectory`, keine Port-Methoden oder
Service-Overrides. Die Profil-Extensions auf `AccountService` delegieren zuerst an diese
ID-Lookups und laden nur bei Bedarf das Profil. Der ungenutzte `existsByEmail`-Sonderweg
und eine ungenutzte Profil-Erzeugung beim Claim-Schreiben sind entfernt.

**KVNR-Zuständigkeit:** Die eindeutige, zeitlich änderbare KVNR wird ausschließlich durch
`ext_stammdaten` verwaltet. KVNR → externe PersonId → lokaler PersonId-Anker → Account ist
der aktuelle Suchpfad. KVNR ist kein lokaler Account-Ankertyp mehr; historische Claims dürfen
weiter gespeichert, aber nicht als aktuelle KVNR-Zuordnung verwendet werden. E-Mail bleibt
dagegen ein im Account-System bestätigter, wechselbarer Anker.

---

## ADR-12: Retraktion als eigene Widerrufs-Zeile mit eigenem Vertrauensanker

**Entscheidung** (Zielbild Claims-Modell, [Idee](ideen/claims-modell-und-vertrauensanker.md); Umsetzung folgt): Ein zurückgezogener Wert (KVNR abgemeldet, E-Mail verworfen) wird als eigene Zeilenform festgehalten — `account_retraction(account_id, attribute_type, value, trust_anchor, reason, retracted_at)`. Die Retraktion ist selbst eine Behauptung mit eigenem Vertrauensanker: WER ruft zurück (Stammdaten-Backend, Konto-Verwaltung, Operator), plus Grund und Zeitpunkt. Das Log (`account_attribute`) bleibt reine, strikt append-only Behauptungstabelle; die Konsolidierung rechnet "Behauptungen minus Retraktionen" und hält Projektionsspalten und `account_anchor` aktuell (die Anker-Zeile wird gelöscht — die Anker-Tabelle ist Projektion, nicht Log). Retraktionen kommen nie über den Tool-Vertrag: `ToolOutcome` bleibt positiv-only, Quellen sind Konto-Verwaltung und Backend-Sync.

**Erwogene Alternative**: Flag-Spalten (`retracted_at`/`retracted_by`) direkt auf der
Claim-Zeile — eine Tabelle, einfachste Abfrage "gültige Werte", aber die einzige
Nicht-Append-Mutation im Log.

**Warum diese**: Das Integritätsargument des Modells — das Log ist die Quelle der Wahrheit und
wird nie überschrieben — darf keine Ausnahme erhalten; jede In-place-Mutation, auch nur ein
Zeitstempel, schwächt die Rekonstruierbarkeit ("was galt wann"). Eine Retraktions-Zeile trägt
dieselbe Provenanz-Disziplin wie eine Behauptung (Anker, Grund, Zeitpunkt) und hält den
Tool-Vertrag frei von Negativ-Formen: Tools bezeugen nur, Widerrufe sind Konto-Lebenszyklus.

**Preis**: Zwei Formen statt eine — "gültiger Wert" ist immer eine Subtraktion über zwei
Tabellen, und jeder Konsolidierungs- und Abfragepfad muss den Widerruf mitdenken.

---

## ADR-13: Account-Domänentypen über eigene `AttributeConverter`, nicht `@Enumerated`

**Entscheidung** (umgesetzt, [13-review-domaenen-db-modell.md](13-review-domaenen-db-modell.md)
C2): `AccountAttribute.attributeType` (→ `AttributeType`) und `AccountAnchor.anchorType` (→
`AnchorType`) sind über eigene JPA-`AttributeConverter` typisiert (`AttributeTypeConverter`,
`AnchorTypeConverter`), die über `wireName` runden — nicht über `@Enumerated(EnumType.STRING)`,
das der `orchestrator`-Modul für seine eigenen Enums durchgängig nutzt.
`AccountAttribute.trustAnchor` bleibt bewusst `String`.

**Nachtrag**: `AnchorType` ist entfallen, beide Spalten heißen seit ADR-14 `attribute_type` und
nutzen denselben `AttributeTypeConverter`; die Quelle heißt durchgängig `claim_source` /
`AccountAttribute.claimSource` (weiterhin `String`, aus dem unten genannten Grund).

**Erwogene Alternativen**:

- **`@Enumerated(EnumType.STRING)`**, konsistent mit dem `orchestrator`-Modul: verworfen, weil
  `account_attribute.attribute_type`/`account_anchor.attribute_type` seit Jahren Wire-Names in
  Kleinschreibung tragen (`person_id`, `email`). `@Enumerated(STRING)` schreibt/erwartet den
  Enum-Konstantennamen (`PERSON_ID`) und hätte jede Bestandszeile stumm verfehlt — ohne
  Datenmigration nicht anwendbar.
- **`trustAnchor` ebenfalls typisieren** (`TrustAnchor`, eine `@JvmInline value class`): verworfen
  nach einem verifizierten Fehlschlag, nicht aus Vorsicht. Ein echter
  `AttributeConverter<TrustAnchor, String>` ließ Hibernate bei jedem Schreibzugriff mit
  `JpaSystemException: class java.lang.String cannot be cast to class TrustAnchor` scheitern —
  Hibernates Property-Access/Enhancement-Pfad reicht dem Konverter dafür eine rohe
  `String`-Instanz statt der auf JVM-Ebene geboxten Value Class durch. `AttributeType` (ein
  echtes Enum) und `AnchorType` (ein sealed interface aus `object`s) haben dieses Problem nicht.

**Warum diese**: Eine Umbenennung im Wire-Format sollte ein Compilerfehler sein, keine stille
Datenkorruption über eine Laufzeit von 10+ Jahren — genau das leistet ein typisiertes Feld, das
ein reiner `String` nicht kann. Die eigenen Konverter statt `@Enumerated` erhalten dabei exakt
das bestehende, bereits jahrelang geschriebene Wire-Format, ohne Migration der Bestandsdaten.

**Preis**: Zwei verschiedene Typisierungsmuster im selben Modul (`@Convert` hier,
`@Enumerated(STRING)` im `orchestrator`) statt eines einheitlichen — eine Inkonsequenz, die sich
nur auflösen ließe, wenn entweder alle Bestandsdaten migriert würden oder der `orchestrator`
ebenfalls auf Wire-Name-Konverter umgestellt würde. `trustAnchor` bleibt zudem als einziges der
drei ursprünglich benannten Felder ungetypt — eine bekannte, dokumentierte Lücke, keine
übersehene.

---

## ADR-14: Schema-Konsolidierung — Konto als Sperrwurzel, eine Wahrheit je Fakt

**Entscheidung**: Die 37 inkrementellen Migrationen sind in `V1__schema.sql` (+ `V2__testdata.sql`)
zusammengeführt, und das Schema folgt durchgängig deklarierten Regeln (Kopf von `V1__schema.sql`,
[Betrieb](07-betrieb.md) Abschnitt 6). Inhaltlich:

- `account` trägt nur noch `id`, `created_at`, `version` und ist Sperrwurzel für Änderungen am
  aktuellen Kontozustand (`OPTIMISTIC_FORCE_INCREMENT`).
- Aktueller Zustand liegt in Zeilen je Fakt: `account_anchor` (einziger Speicherort von PersonId und
  bestätigter E-Mail), `account_auth_method` (eine Zeile je Methodeninstanz, `EnrollmentRef` als
  Spalten). Historie ist append-only: `account_attribute` (Claims), `account_identification`
  (Nachweise). Die JSON-Listen `identifications`/`authentication_methods` und die Projektionsspalten
  `person_id`/`email`/`email_confirmed_at` entfallen; `ConsolidationStrategy` entfällt, weil „lokal
  konsolidiert" und „ist Anker" dieselbe Aussage geworden sind.
- **Nachtrag**: Ihr zweiter Fall (`ExternalLiveLookup`) ist mit ihr verschwunden, obwohl er nicht
  redundant war — „kein lokaler Anker" deckte danach zwei verschiedene Dinge ab (Stammdaten-Hoheit
  bei `NAME`, Modul-Hoheit bei `PHONE_NUMBER`). Er ist als eigene Eigenschaft
  `AttributeType.authority` (`LOCAL_ANCHOR`/`EXT_STAMMDATEN`/`METHOD_MODULE`, exhaustiv) in
  `tool_api/AttributeRules.kt` zurückgeholt; ein Test bindet sie an `anchorBindingStrength`.
- Fremdschlüssel nur innerhalb eines Moduls; modulübergreifende Bezüge sind indizierte Spalten,
  aufgeräumt über Modul-APIs.
- Einheitliche Namen (`<modul>_enrollment` = `EnrollmentRef.type`, `<tool_id>_tool_data`,
  `ux_`/`ix_`, PK-Spalte `id`) und Typen (`TIMESTAMP WITH TIME ZONE`, feste Längenraster).
- `dpop_proof_replay` ist über SHA-256(`thumbprint:jti`) mit fester Breite geschlüsselt, statt über
  einen clientbestimmten `VARCHAR(255)`-Schlüssel.
- Jede Retention-Löschung ist ein Bulk-Statement mit Index auf ihrer Stichtagsspalte; auch
  `auth_device` und `auth_qr` (inkl. `auth_qr_login_request`) räumen ihre Arbeitsdaten jetzt auf.

**Erwogene Alternative**: Nur squashen und die Form des Modells unverändert lassen (JSON-Listen auf
der Kontozeile, Projektionsspalten neben den Ankern).

**Warum diese**: Bei ≥ 10 Mio. Konten und langer Lebensdauer sind die JSON-Listen weder abfragbar
(„alle Konten mit Methode X" bei einem Widerrufs- oder Krypto-Wechsel) noch schreibgünstig (jede
Änderung schreibt die ganze Zeile, `identifications` wächst unbegrenzt), und jede Projektionsspalte
neben einem Anker ist eine zweite Eindeutigkeitsautorität für denselben Fakt — genau der Fehler, den
A3 im Review einmal schon beheben musste. Ein Squash ohne diese Bereinigung hätte die
Inkonsistenzen nur in eine einzige Datei verschoben.

**Preis**: Ein Kontoprofil braucht drei indizierte Lesezugriffe statt einem; die E-Mail im Profil
ist die normalisierte Form (Kleinschreibung), die Rohschreibweise steht nur noch im Claim-Log. Wie
bei ADR-4 gilt: Diese Neubaseline ist nur ohne Produktivdaten vertretbar. Ab dem ersten
produktiven Einsatz sind Migrationen ausschließlich additiv.

---

## ADR-15: Nachweise und ausgestellte Tokens in getrennten Tabellen

**Entscheidung**: `auth_evidence` (was auf einem Kanal bewiesen wurde) und `auth_context` (was
daraus an Tokens ausgestellt wurde) sind zwei Tabellen. Die Abhängigkeit ist einseitig:
`auth_context.auth_evidence_id` zeigt auf die Nachweise, nie umgekehrt; mehrere Token-Kontexte
dürfen auf dieselbe Evidenz zeigen (`AuthContextRepository.findByAuthEvidenceId` liefert eine
Liste). Abgeleitete Größen werden in keiner der beiden gespeichert: `currentAcr` berechnet
`AuthPolicy.resolveAcr` bei jedem Lesen neu aus `amr_evidence`
([Domänenmodell](02-domaenenmodell.md) Abschnitt 7).

**Erwogene Alternative**: Eine Tabelle — die Token-Spalten neben den Nachweisen in derselben
Zeile, so wie es vor der kc-Fassade (`07e7156`) auch war.

**Warum diese**: Zwei Gründe, die beide nicht an der Kardinalität hängen.

1. **Nicht jeder Kanal hat Tokens, aber jeder hat Nachweise.** Der KEYCLOAK-Kanal legt nie einen
   `AuthContext` an ([API](05-api.md) Abschnitt 3: Er hat keine App-Tokens zu binden), erbringt
   aber sehr wohl Nachweise. In einer gemeinsamen Tabelle trüge jede Web-Kanal-Zeile vier
   dauerhaft leere Token-Spalten — oder man legte einen „AuthContext" an, der keiner ist, nur um
   die Nachweise unterzubringen. Die Bedeutung der Tabelle hinge dann am Kanaltyp.
2. **Das eine ist Wahrheit, das andere Cache.** Nachweise sind die Eingabe der Policy und wachsen
   nur; der Token ist die Ausgabe an den Client und ist jederzeit verwerfbar. Genau davon lebt
   `AuthEvidenceService.invalidateCachedTokens`: Ein Step-up setzt Access- und RefreshToken auf
   `null`, **während die Nachweise stehen bleiben**. In einer gemeinsamen Zeile wäre „Nachweise
   geändert" und „Token verworfen" ein einziges Update — der Unterschied zwischen „gilt weiter"
   und „ist ungültig geworden" ließe sich nicht mehr ausdrücken.

**Preis**: Zwei Tabellen, die einander äußerlich stark ähneln (beide mit `account_id`, `version`,
`updated_at`) und im APP-Kanal praktisch immer gemeinsam entstehen — das vom Repository erlaubte
1:n ist in den heutigen Abläufen durchgehend 1:1. Wer nur ins Schema schaut, sieht deshalb zwei
fast gleiche Tabellen und den Grund nicht; er steht in den KDocs von `AuthContext`/`AuthEvidence`
und seit diesem ADR hier.

---

## Erkannte, bewusst zurückgestellte Verbesserungen

Befunde aus [13-review-domaenen-db-modell.md](13-review-domaenen-db-modell.md), die
identifiziert, ausformuliert und bewusst **nicht** vollständig umgesetzt sind — jeweils eine
Architektur-/Infrastrukturentscheidung, kein lokal abschließbarer Fix:

- **`dpop_proof_replay`-Skalierung** (B5, siehe auch [09-dpop.md](09-dpop.md) Abschnitt 2): Der
  Schlüssel ist seit ADR-14 ein fester SHA-256-Hash. Offen bleibt die Zeitpartitionierung bzw. ein
  separater persistenter KV-Store — eine Entscheidung für den Produktivstack, nicht für diese
  H2-Demo-Umgebung.
- **Konto-Lebenszyklus und Merge-Pfad** (D2): `Account` kennt keinen Status (gesperrt,
  deaktiviert, verstorben) und kein `merged_into`. ADR-11 weist einen `person_id`-Konflikt
  bewusst ab, statt zu mergen — über die angestrebte Lebensdauer entsteht Merge-Bedarf aber
  zwangsläufig, und ohne `merged_into` gibt es dann keinen verlustfreien Weg dorthin.

Beide verdienen einen eigenen, sorgfältig geplanten Durchgang mit Entwurfsentscheidung vorab —
Details und Begründung stehen im Review-Dokument. D1 (Methoden als eigene Tabelle) ist mit ADR-14
umgesetzt.
