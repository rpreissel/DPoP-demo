# Architekturentscheidungen

Die großen Entscheidungen dieses Projekts, mit der ernsthaft erwogenen Alternative und dem Preis
der gewählten Lösung. Was die Lösung *ist*, steht in den verlinkten Kapiteln — hier steht nur das
*Warum*.

---

## ADR-1: Ein Controller je Tool, kein generischer Dispatcher

**Entscheidung**: Jedes Tool bekommt einen eigenen, typisierten `@RestController` mit eigenem
Request-DTO. `POST/PATCH/GET` liegen je Tool in einem eigenen Controller ([Tool-Architektur](03-tool-architektur.md)
Abschnitt 2, [Projektrahmen](08-projektrahmen.md) A11).

**Erwogene Alternative**: Ein einziger Controller, der über `toolId` zur Laufzeit dispatcht, mit
einer generischen `Map<String, Any?>` als Request-Body.

**Warum diese**: Ein typisiertes DTO
zeigt am Controller, was ein Tool tatsächlich erwartet — bei einer `Map<String, Any?>` steht das
nur noch im Handler-Code. Ein `toolId`-basierter Runtime-Dispatch wäre
außerdem eine Fehlerquelle, die der Compiler nicht sieht: ein neues Tool ohne passenden
`when`-Zweig fiele erst zur Laufzeit auf.

**Preis**: Mehr Code — 17 Controller statt einem, mit strukturell ähnlichem Aufbau
(Aktivierung/Fortschreiben/Lesen).

---

## ADR-2: Zustand statt Vererbung bei `AuthJourney`

**Entscheidung**: `AuthJourney` ist eine flache Entity ohne Subklassen oder getrennte Tabellen je
Intent. Was sich je Intent unterscheidet, steckt in `stateType` (Diskriminator) plus `state`
(JSON) ([Domänenmodell](02-domaenenmodell.md) Abschnitt 2).

**Erwogene Alternative**: Eine Tabelle je Intent bzw. Single-Table-Vererbung mit spaltenweise
kodierten intent-spezifischen Feldern.

**Warum diese**: Die Menge der Attribute unterscheidet sich stark zwischen Intents (`REGISTER`
braucht andere Zwischenzustände als `STEP_UP`), und das Verhalten dazu gehört in Services
(`AuthPolicy`, Tool-Katalog). Getrennte Spalten je Attribut ergäben eine
breite Tabelle aus überwiegend leeren Feldern. `stateType` bleibt trotzdem abfragbar.

**Preis**: Der `state`-Inhalt ist für die Datenbank selbst intransparent — Constraints und
Fremdschlüssel auf einzelne JSON-Attribute sind nicht möglich, Konsistenz muss die
`IntentStrategy` je Intent selbst sicherstellen.

---

## ADR-3: `ChannelSession` bewusst kurzlebig, Geräte-Identität in `DeviceAccountLink`

**Entscheidung**: `ChannelSession` hat eine begrenzte Aufbewahrungsfrist (30 Tage, [Betrieb](07-betrieb.md)) und
trägt keine langlebige Geräte-Zuordnung. Die einzige dauerhafte Zuordnung Gerät -> Account
(`bindingKeyRef -> accountId`) liegt in `DeviceAccountLink`, einer eigenen Tabelle
([Domänenmodell](02-domaenenmodell.md) Abschnitt 1,
[DPoP-Bindung](09-dpop.md) Abschnitt 3).

**Erwogene Alternative**: `ChannelSession` selbst langlebig machen und die
Geräte-Wiedererkennung darüber lösen — ein wiederkehrendes Gerät würde dieselbe Session
fortsetzen.

**Warum diese**: Eine Session, die ein Gerät über Wochen repräsentiert, vermischt zwei
Lebensdauern in einer Entity: den Kanal-Vorgang (Stunden) und die
Geräte-Identität (dauerhaft). Der `bindingKeyRef` beweist
nur, welches Gerät spricht, nie, welche Session fortzusetzen ist; eine wiederkehrende
`ChannelSession` wird deshalb **immer** neu angelegt und nur mit `accountId` vorbefüllt.

**Preis**: Zwei Konzepte statt eines — die Geräte-Bindung muss explizit über
`DeviceAccountLink` nachgeschlagen werden.

---

## ADR-4: Flyway-Neubaseline statt Migration des Altcodes

**Entscheidung**: `V1__schema.sql` ersetzt die komplette frühere Migrationshistorie (`V1`–`V16`
im ursprünglichen Code) durch einen sauberen Neubau, statt sie fortzuschreiben
([Umsetzungsplan](11-umsetzungsplan.md)).

**Erwogene Alternative**: Den Altcode Schritt für Schritt migrieren — `Attempt`-Terminologie zu
`ToolSession`/`ToolOutcome`, URL-Pfade auf den Tool-Namespace, Klartext-TAN nachträglich hashen.

**Warum diese**: Der Alt-Stand unterschied sich strukturell so stark vom Zielbild (andere
Terminologie, kein `ToolDescriptor`/`AuthPolicy`, unverschlüsselte TANs), dass eine
Schritt-für-Schritt-Migration mehr Zwischenzustände und damit mehr Fehlerfläche erzeugt hätte als
ein Neubau. Für eine Demo mit lokaler H2-Datei entfällt außerdem das übliche Gegenargument
Datenverlust.

**Preis**: Diese Entscheidung ist an den Kontext gebunden — bei echten Bestandsdaten wäre eine
Neubaseline nicht vertretbar.

**Nachtrag**: Eine zweite Neubaseline hat die danach wieder aufgelaufenen 37 Migrationen
zusammengeführt — mit Modellbereinigung, siehe ADR-14.

---

## ADR-5: Dreifache Deckelung des Sicherheitsniveaus

**Entscheidung**: Das erreichbare Sicherheitsniveau wird an drei unabhängigen Stellen gedeckelt:
`account.identification.achieved_loa` begrenzt, was ein Account je erreichen kann;
`account.auth_method.enrolled_under_acr` begrenzt, was eine einzelne Methode beisteuern darf;
`achievedAcr` einer Session ist das Minimum aus tatsächlich Nachgewiesenem und dem, was die
verwendete Methode laut ihrem `enrolledUnderAcr` tragen darf
([Orchestrierung](04-orchestrierung.md) Abschnitt 8, [Überblick](01-ueberblick.md)).

**Erwogene Alternative**: Nur `achievedAcr` aus der aktuellen Session-Historie ableiten, ohne die
Enrollment-Bedingungen der einzelnen Methode rückwirkend zu berücksichtigen.

**Warum diese**: Ohne die `enrolledUnderAcr`-Deckelung gäbe es einen Eskalationspfad: Wer eine
schwache Session übernimmt (z. B. `loa1`), könnte darin eine eigene Methode hinterlegen und
dauerhaft ein höheres Niveau vortäuschen. Die dritte
Deckelung verhindert zusätzlich, dass eine schwach identifizierte
Person über starke Auth-Methoden ein Niveau erreicht, das ihre Identifizierung nie hergab.

**Preis**: Drei Stellen, an denen ein Niveau sinken kann, statt einer; welche gerade greift,
muss über `AuthContext`, `account.auth_method.enrolled_under_acr` und
`account.identification.achieved_loa` gemeinsam nachvollzogen werden.

**Nachtrag**: `DefaultAuthPolicy.resolveAcr` berechnet den ersten Deckel inzwischen als eigene
Größe (IAL, `identityAssuranceLevel`), getrennt von der Authentifizierungsstärke (AAL,
`authenticatorAssuranceLevel`) — siehe [Orchestrierung](04-orchestrierung.md) Abschnitt 8.
Der sichtbare `acr`-Wert und diese ADR bleiben unverändert; die Trennung behebt einen
falschen MFA-Bump.

---

## ADR-6: `next` als reine Adresse, feste Routing-Tabelle statt HATEOAS

**Entscheidung**: Jede API-Antwort enthält ein `next`-Objekt, das ausschließlich adressiert (Typ,
`toolId`/`context`, Step) und nie Inhalt oder Links mitliefert. Der Client bildet daraus über eine
**eigene, lokale, feste Routing-Tabelle** (`(toolId|context, step)` -> UI-Komponente bzw.
Endpunkt) den nächsten Schritt ab
([API](05-api.md) Abschnitt 1, [Frontend](10-frontend.md)).

**Erwogene Alternative**: HATEOAS — die Antwort liefert fertige, klickbare Links (`href`).

**Warum diese**: Die Menge möglicher nächster Schritte ist klein und stabil, und
das Frontend braucht für jeden neuen Schritt ohnehin eine eigene UI-Komponente, ein Link
allein reicht nie. Eine feste Tabelle macht zusätzlich sichtbar, welche Übergänge das
Frontend überhaupt kennt.

**Preis**: Backend und Frontend müssen synchron gehalten werden — ein neuer `next`-Wert ohne
passenden Eintrag in der Frontend-Routing-Tabelle führt zu einem unbehandelten Zustand im Client.

---

## ADR-7: Web-Kanal ohne mTLS, signierte Request-Assertion statt Client-Zertifikat

**Entscheidung** (umgesetzt): Die Server-zu-Server-Strecke Keycloak -> Orchestrator im Web-Kanal
wird **ohne mTLS** abgesichert; statt eines Client-Zertifikats verifiziert der Orchestrator eine
signierte Request-Assertion von Keycloak (`PeerAuthValidator`, [05-api.md](05-api.md) Abschnitt 3).
Der Browser spricht nie direkt mit dem Orchestrator.

Ein einziges JWT pro Request statt Access-Token-plus-separatem-Proof: Beim initialen Login gibt es
noch kein `sub`. Die Assertion sagt stattdessen einfach "ich handle für diesen Kanal-Anker, Nutzer
ggf. noch unbekannt".

**Erwogene Alternativen**:

- **mTLS** zwischen Keycloak und Orchestrator — beiderseitige Zertifikatsprüfung auf
  Transportebene.
- **Token Exchange** — verworfen als unnötig: Keycloak besitzt die Session ohnehin und kann die
  Assertion in-process ausstellen.
- **Keycloak hält einen DPoP-Key als Geräte-Ersatz** — verworfen: DPoPs Wert kommt daher, dass der
  Schlüssel nicht-exportierbar auf einem unvertrauten Client liegt. Hält ein Server ihn, ist es
  faktisch ein Shared Secret mit asymmetrischer Zeremonie. Pro Nutzer wäre es zusätzlich fatal:
  `DeviceAccountLink` würde bei jedem Web-Login treffen.

**Warum die signierte Assertion**: mTLS bringt Betriebsaufwand (Zertifikats-Rollout, -Rotation,
-Widerruf für zwei Serverdienste) mit, den eine signierte Anwendungsebene-Assertion nicht braucht
— die Signatur lässt sich mit demselben Schlüsselmaterial prüfen, das Keycloak ohnehin für Tokens
verwendet. Weil der Browser den Orchestrator nie direkt erreicht, bleibt die Angriffsfläche auf
die eine Server-zu-Server-Strecke beschränkt.

**Preis**: Die Sicherheit der Strecke hängt vollständig an der Signaturprüfung der Anwendung —
mTLS hätte Peer-Identität und Verschlüsselung bereits auf Transportebene erzwungen. Ein
kompromittiertes Keycloak kann jeden Nutzer imitieren — das ist der
kc-first-Architektur inhärent, auch mTLS ändert daran nichts.

---

## ADR-8: Keycloak bleibt Journey-Eigentümer seiner eigenen nativen Schritte, statt vollständiger Delegation oder Identity-Brokering

**Entscheidung** (umgesetzt): Der Web-Kanal lässt Keycloak seine eigene, native
Authentifizierungs-Flow-Konfiguration (Conditional-LoA-Subflows, natives Passwort-Login) fahren
und ruft den Orchestrator nur innerhalb einer laufenden, persistenten `AuthJourney` für die
Schritte auf, die Keycloak nicht kann (`KC_SELECT_METHOD`, [04-orchestrierung.md](04-orchestrierung.md)
Abschnitt 3). Der Orchestrator bleibt für die Dauer eines Flow-Durchlaufs alleinige, kombinierende
ACR/AMR-Instanz ([05-api.md](05-api.md) Abschnitt 3).

**Erwogene Alternativen**:

- **Orchestrator als externer OIDC-Identity-Provider** (Identity Brokering, Browser-Redirect zu
  einer eigenen Orchestrator-Web-UI): verletzt die
  Leitplanke "Browser spricht nie mit dem Orchestrator" direkt.
- **Volle Journey-Delegation** (Keycloak rendert jedes Formular über einen einzigen generischen
  Authenticator): architektonisch sauber, verzichtet aber komplett auf Keycloaks
  eingebaute Fähigkeiten (natives Passwort-Login, OTP/TOTP, WebAuthn/Passkey,
  Social-Login-Brokering, Conditional-LoA) — genau die Fähigkeiten, derentwegen
  eine Keycloak-Anbindung überhaupt Sinn ergibt.
- **Zustandslose Einzel-Tool-Aufrufe ohne Journey** (Keycloak bleibt Journey-Eigentümer, ruft den
  Orchestrator nur für isolierte Faktoren ohne begleitende `ChannelSession` auf):
  ohne die persistente Journey verliert der Orchestrator die
  Fähigkeit, mehrere eigene Tools im selben Login mit gemeinsamer Evidenz zu verrechnen. Bleibt
  sinnvoll für Touchpoints außerhalb eines zusammenhängenden Flows (eine
  Keycloak-„Required Action", eine Aktion in der Account-Konsole).

**Preis**: Split-Brain-Risiko zwischen zwei Zustandshaltern — abgefedert dadurch, dass jede Seite
eine nicht überlappende Zuständigkeit trägt (Keycloak entscheidet OB und WELCHES ACR-Level
angefragt ist, der Orchestrator WAS innerhalb einer Stufe passiert und wie sich mehrere Nachweise
zu einem Gesamt-ACR kombinieren).

---

## ADR-9: Profilabhängiges Token-Retrieval — Account-Keypair + custom OAuth2-Grant statt geteiltem Admin-Secret

**Entscheidung** (umgesetzt, DPoP-demo-xso): `GET .../{channelSessionId}/token` ist **`APP`-Kanal-
only** — ein `KEYCLOAK`-Kanal hat nie einen `AuthContext` und braucht auch keinen: dessen Client
hält bereits echte Keycloak-Tokens aus dem Browser-Login (`dpop-demo-web`) und erneuert sie direkt
gegen Keycloak (`ChannelService.getToken` weist `KEYCLOAK` explizit mit `409` ab). Für `APP` bleibt
der Endpunkt im Default-Profil das Mock-JWT (`TokenService`); im `keycloak`-Profil liefert er einen
echten, von Keycloak signierten AccessToken (`KcTokenProvider`, [05-api.md](05-api.md) Abschnitt
2) — gemeinsame `TokenProvider`-Schnittstelle, Umschaltung ausschließlich über Spring-Profile
([04-orchestrierung.md](04-orchestrierung.md) kennt das gleiche Muster für
`ChannelAccessGuard`).

Ein Step-up desselben Accounts mintet innerhalb dieser einen Keycloak-Session ein neues Token:
`AccountTokenGrantType` markiert die von ihm angelegte
`UserSessionModel` mit einer Session-Note und findet sie später wieder; ihre Lebensdauer folgt
den realm-weiten `SSO Session Idle`/`SSO Session Max`-Einstellungen. Der Grant gibt ein opakes
`refresh_token` aus (`useRefreshToken() == true`), das `KcTokenProvider` für jede reine
Fristverlängerung nutzt (`KeycloakAdminClient.refreshAccountToken`); zum custom
Account-Token-Grant greift er nur, wenn sich ACR/AMR seit dem letzten Mint geändert haben.

Die Assertion trägt dafür `acr`/`amr` als eigene, signierte Claims - `AccountTokenGrantType`
kopiert sie in dieselben `UserSessionModel`-Notes wie `OrchestratorAuthenticator`
(`orchestrator_acr`/`orchestrator_amr`), von wo der
`OrchestratorAcrAmrMapper` (Client-Scope `orchestrator-claims`) sie in den
echten AccessToken schreibt. `KeycloakAdminClient.requestAccountToken`/`refreshAccountToken`
authentifizieren sich dabei als
privilegienloser Client `orchestrator-app-token` (V8), nicht als `orchestrator-admin` -
genau das ist der Punkt dieses ADRs ("statt geteiltem Admin-Secret").

Unabhängig vom Profil verwirft ein Step-up, der die `AuthEvidence` verändert
(`AuthEvidenceService.applyEvidence`/`applyEvidenceUpdate`), die im `AuthContext`
gecachten Tokens — sonst verlängerte der Refresh-Pfad die alte
Keycloak-Session mit den alten ACR/AMR-Notes weiter.

Dafür erzeugt der Orchestrator bei jedem Keycloak-Account-Sync ein eigenes, asymmetrisches
Schlüsselpaar pro Account (`orchestrator.keycloak_keypair`, EC P-256) und spiegelt den Public Key als
echtes Keycloak-`Credential` (Typ `orchestrator-public-key`, `KeycloakAdminClient.setPublicKeyCredential`)
auf den Keycloak-User — bewusst nicht als Attribut: Proof-of-possession-Material gehört in den
Credential-Store. Geschrieben wird er von einer eigenen
`AdminRealmResourceProvider`-Erweiterung (`AccountPublicKeyResource`, gemountet unter
`/admin/realms/{realm}/orchestrator-keys/{accountId}`).
Der custom Grant-Type in `keycloak-extension/` (`urn:dpop-demo:account-token`,
`AccountTokenGrantType`, Keycloaks pluggable `OAuth2GrantType`-SPI in
`keycloak-server-spi-private`) verlangt zusätzlich eine damit signierte, kurzlebige Assertion
(`sub`=accountId, `aud`=Grant-URN, `exp` <= 60s) und mintet erst dann einen echten AccessToken.

**Erwogene Alternativen**:

- **Ein einzelnes geteiltes Admin-Secret** (der `keycloak-sync`-Service-Account
  ruft via Token-Exchange oder Impersonation direkt einen Token für einen beliebigen Nutzer ab):
  verworfen — ein kompromittiertes Secret könnte für JEDEN Account einen Token ausstellen.
- **Nur die umgekehrte Richtung erlauben** (Keycloak ruft den Orchestrator, nie umgekehrt):
  verworfen — `GET .../token` braucht eine sofortige Antwort.
- **`private_key_jwt`-Client-Authentifizierung statt `client_secret`** für die Admin-/Sync-Strecke
  selbst (RFC 7523, secret-frei wie ADR-7): sinnvolle, aber orthogonale Härtung.

**Warum das Account-Keypair**: Es überträgt dasselbe Prinzip wie ADR-7 (Signatur statt Secret)
auf eine zweite Richtung — nicht pro Client/Node, sondern pro Account, weil genau das der Blast
Radius ist, der eingegrenzt werden soll: ein Leak trifft höchstens einen Account. Die Assertion
bleibt kurzlebig (`exp` <= 60s) und wird nur beim ERSTEN Mint bzw. bei einer tatsächlichen
ACR/AMR-Änderung gebraucht.

**Preis**: Ein weiterer, account-gebundener Datensatz (`orchestrator.keycloak_keypair`) mit eigenem
Lebenszyklus (erzeugt bei Sync, gelöscht bei `AccountDeleted`, [07-betrieb.md](07-betrieb.md)
Abschnitt 3) sowie ein projektspezifisches Stück Keycloak-Erweiterung, das bei jedem
Keycloak-Upgrade gegen die `server-spi-private`-Schnittstelle mitgeprüft werden muss.
Demo-only: Der Private Key liegt unverschlüsselt in der Datenbank.

---

## ADR-10: Interessent ist Konto-Zustand, kein eigener AuthIntent

**Entscheidung** (Zielbild Claims-Modell, [Idee](ideen/claims-modell-und-vertrauensanker.md); Umsetzung folgt): Es gibt keinen eigenen `AuthIntent.INTERESSENT`. Ein Interessent — ein Konto, das nur über bezeugte Claims identifiziert ist, ohne `person_id`-Bindung — ist eine Beobachtung über den Ausgang einer Identifizierung, kein wählbares Ziel. Die `REGISTER`-Journey (und jeder andere Intent, der Identifizierungen durchläuft) verzweigt auf das Auflösungs-Ergebnis (`Resolution`: `ExistingAccount` / `NewInteressent` / `Ambiguous`): Anker-Treffer bindet wie heute, Claims-only führt das Konto ohne `person_id` fort, mehrdeutig geht an die Journey-Politik.

**Erwogene Alternative**: Ein eigener `AuthIntent` mit eigener Journey, eigenen States und eigener Strategie — begründbar, falls Interessenten eine abweichende Politik bräuchten.

**Warum diese**: `AuthIntent` benennt nach eigener Definition
([AuthIntent.kt](../src/main/kotlin/com/example/dpop/orchestrator/journey/AuthIntent.kt)) ein Ziel
samt Strategie, nie eine Beschreibung dessen, was ein Lauf geworden ist. Der
Journey-Verlauf ist für beide Ausgänge strukturell identisch, nur die Konto-Auflösung
selbst unterscheidet sich. Das heutige `ConfirmIdentity` behandelt den Fall `personId == null`
bereits als Verzweigung innerhalb der bestehenden Journey (REGISTER "Enrollment zuerst",
[Orchestrierung](04-orchestrierung.md) Abschnitt 2). Ein eigener Intent verdoppelte
zudem jede künftige Politik-Gabel (`STEP_UP`, `RE_IDENTIFY` auf Interessenten-Konten).

**Preis**: Interessenten-spezifische Politik lebt als Verzweigungen in den bestehenden
Strategien (analog `ConfirmDeviceRebind`) statt in einem eigenen Strategy-Objekt — die
Strategien werden dadurch konditioneller.

---

## ADR-11: Kontoübergreifender person_id-Konflikt ist Abweisung, Merge nie automatisiert

**Entscheidung** (Zielbild Claims-Modell, [Idee](ideen/claims-modell-und-vertrauensanker.md); Verhalten entspricht dem heutigen Code): Beanspruchen zwei Konten denselben `person_id`-Wert, wird die zweite Bindung abgewiesen (409, "Diese Person ist bereits über ein anderes Konto registriert") und nichts adoptiert: keine Claim-Zeile, keine Konsolidierung, keine Anker-Schreibung. Ein Merge ist nie automatisiert, sondern eine operator-getriebene Fähigkeit außerhalb des Claims-Modell-Umfangs. DB-seitig sichert `UNIQUE(person_id)` (partial, `WHERE person_id IS NOT NULL`) dieselbe Semantik für alle Schreibpfade ab.

**Erwogene Alternative**: Die bezeugte Aussage trotzdem loggen und nur die Konsolidierung
verweigern (Konflikt als abfragbarer Zustand); oder eine Review-Queue.

**Warum diese**: False merge ist die teuerste Fehlerform des Modells — zwei verschiedene
Menschen dauerhaft verknüpft — und sie zu vermeiden wiegt schwerer als der Verlust der
bezeugten Aussage, die ephemer im Journey-Log nachweisbar bleibt
([State-Diagramme](demo/05-state-diagramme-intents.md)). Die Konsolidierungs-Rangfolge
(Anker-Klasse vor Rezenz) gilt innerhalb EINES Kontos; sie endet an der Kontgrenze.

**Preis**: Der betroffene Nutzer kommt nicht automatisch weiter —
bis zu einer (ungebauten) Merge-Fähigkeit bleibt der Fall ein Support-Vorgang, und die
bezeugte Identifikation bleibt nur im ephemeren Journey-Log.

**Nachtrag** (Härtung, [13-review-domaenen-db-modell.md](13-review-domaenen-db-modell.md)): Drei
Lücken zwischen dieser Entscheidung und ihrer Umsetzung wurden geschlossen. Erstens schrieb
`AccountService.recordAnchor` bei einem fremden Anchor die Projektionsspalte trotz Abbruch; der
Anchor wird jetzt **vor** der Projektionsspalte geschrieben. Zweitens
verließ sich `IdentityMatchingService.resolveByAnchor` auf die
zufällige Iterationsreihenfolge eines `Set` und sortiert jetzt explizit nach
`AttributeType.anchorBindingStrength` (`tool_api/AttributeRules.kt`).
Drittens fing `findOrCreateAccount` die
`UNIQUE(person_id)`-Kollision nicht ab; das ist durch die atomare
Claim-Übernahme unten abgelöst.

**Nachtrag 2** ([ideen/account-attribute-und-trust-vereinheitlichen.md](ideen/account-attribute-und-trust-vereinheitlichen.md), alle 7 Pakete): `person_id` ist seither
kein Sonderfall mehr, sondern ein gewöhnlicher, höchstrangiger `account.anchor`-Eintrag wie `email`
— die kontoübergreifende Abweisung läuft seither
technisch über denselben `recordAnchor`-Pfad statt über einen separaten
`bindPersonId`/`findAccountByPersonId`-Vergleich, und
`MatchedVia.PersonId` ist zugunsten von `MatchedVia.Anchor(PERSON_ID)` entfallen.
Neu dazugekommen, INNERHALB eines Kontos: `AttributeType.allowsAnchorReplacement`
ist für `PERSON_ID` `false` (anders als `email`) — ein zweiter, abweichender `person_id`-Claim für
ein Konto wird ebenfalls per `IdentityConflictException` abgewiesen.

**Nachtrag 3 — atomare Account-Anlage:** `findOrCreateAccount` und `AccountRaceSafeCreator`
entfallen; `findAccountByPersonId`/`findAccountByEmail` bleiben als Extensions auf
`AccountService` und delegieren an `resolveByAnchor`. Ein neues Konto entsteht ungebunden
und erhält seine PersonId ausschließlich über `recordClaims`, gemeinsam mit Log und Anker in
der Journey-Transaktion. Der Verlierer einer konkurrierenden Bindung erhält nach vollständigem
Rollback `409 INVALID_STATE_TRANSITION`.

**KVNR-Zuständigkeit:** Die eindeutige, zeitlich änderbare KVNR wird ausschließlich durch
`ext_stammdaten` verwaltet (Suchpfad KVNR → externe PersonId → lokaler PersonId-Anker → Account)
und ist kein lokaler Account-Ankertyp mehr. E-Mail bleibt
dagegen ein im Account-System bestätigter, wechselbarer Anker.

---

## ADR-12: Retraktion als eigene Widerrufs-Zeile mit eigenem Vertrauensanker

**Entscheidung** (Zielbild Claims-Modell, [Idee](ideen/claims-modell-und-vertrauensanker.md); **umgesetzt**): Ein zurückgezogener Wert (KVNR abgemeldet, E-Mail verworfen) wird als eigene Zeilenform festgehalten — `account.retraction(account_id, attribute_type, normalized_value, trust_anchor, reason, retracted_at)` — und ist selbst eine Behauptung mit eigenem Vertrauensanker: WER ruft zurück, plus Grund und Zeitpunkt. Das Log (`account.attribute`) bleibt strikt append-only; die Konsolidierung rechnet "Behauptungen minus Retraktionen" und hält Projektionsspalten und `account.anchor` aktuell (die Anker-Zeile wird gelöscht — die Anker-Tabelle ist Projektion, nicht Log). Retraktionen kommen nie über den Tool-Vertrag: `ToolOutcome` bleibt positiv-only.

**Erwogene Alternative**: Flag-Spalten (`retracted_at`/`retracted_by`) direkt auf der
Claim-Zeile — eine Tabelle, einfachste Abfrage, aber die einzige Nicht-Append-Mutation im Log.

**Warum diese**: Das Integritätsargument des Modells — das Log ist die Quelle der Wahrheit und
wird nie überschrieben — darf keine Ausnahme erhalten; jede In-place-Mutation schwächt die
Rekonstruierbarkeit ("was galt wann"). Eine Retraktions-Zeile trägt
dieselbe Provenanz-Disziplin wie eine Behauptung und hält den
Tool-Vertrag frei von Negativ-Formen.

**Preis**: Zwei Formen statt eine — "gültiger Wert" ist immer eine Subtraktion über zwei
Tabellen, und jeder Konsolidierungs- und Abfragepfad muss den Widerruf mitdenken; heute ist das
genau ein Pfad (der Attributabgleich in `IdentityMatchingService`), dort ein `not exists` über
einen eigenen Index.

**Nachtrag zur Umsetzung**: Damit ein Widerruf weiß, *was* er zurückzieht,
trägt jede Claim-Zeile die `auth_method_id` der Methodeninstanz, deren Einrichtung sie
aufgestellt hat (`null` bei Identifizierungs-Tools). Beim
Entfernen einer Methode (`AccountDeletionService.revokeMethod`) zieht `retractClaimsOf` genau
deren Behauptungen zurück — aber **nur die mit `AttributeAuthority.METHOD_MODULE`**: Ein Anker
(`EMAIL`) oder ein Stammdaten-Attribut (`NAME`) überlebt das Credential, sonst hätte das
Entfernen der E-Mail-Methode den Passwort-Login
(`ClaimRequirement(EMAIL, PROVEN)`) zerstört. Der Widerruf selbst ist
`RetractionAnchor.ACCOUNT_MANAGEMENT` — bewusst von `ClaimSource` getrennt, weil
ein Tool nie widerrufen darf.

**Offen und bewusst nicht mitentschieden**: Die Retraktion macht einen Wert *ungültig*, sie
*löscht* ihn nicht, und die Widerrufs-Zeile legt mit dem normalisierten Wert eine
zweite lesbare Kopie an. Naheliegend wäre eine Aufbewahrungsfrist, nach der Claim-
und Retraktions-Zeile gemeinsam gelöscht werden; der Audit-Nachweis hängt nicht daran,
`account.identification` hält Verfahren, LoA und Zeitpunkt ohne die Attributwerte fest.


---

## ADR-13: Account-Domänentypen über eigene `AttributeConverter`, nicht `@Enumerated`

**Entscheidung** (umgesetzt, [13-review-domaenen-db-modell.md](13-review-domaenen-db-modell.md)
C2): `AccountAttribute.attributeType` (→ `AttributeType`) und `AccountAnchor.anchorType` (→
`AnchorType`) sind über eigene JPA-`AttributeConverter` typisiert (`AttributeTypeConverter`,
`AnchorTypeConverter`), die über `wireName` runden — nicht über `@Enumerated(EnumType.STRING)`,
das der `orchestrator`-Modul für seine eigenen Enums nutzt. `AccountAttribute.trustAnchor` bleibt
bewusst `String`.

**Nachtrag**: `AnchorType` ist entfallen, beide Spalten heißen seit ADR-14 `attribute_type` und
nutzen denselben `AttributeTypeConverter`; die Quelle heißt durchgängig `claim_source` /
`AccountAttribute.claimSource` (weiterhin `String`).

**Erwogene Alternativen**:

- **`@Enumerated(EnumType.STRING)`**, konsistent mit dem `orchestrator`-Modul: verworfen, weil
  `account.attribute.attribute_type`/`account.anchor.attribute_type` seit Jahren Wire-Names in
  Kleinschreibung tragen (`person_id`, `email`), `@Enumerated(STRING)` aber den
  Enum-Konstantennamen (`PERSON_ID`) schreibt und jede Bestandszeile stumm verfehlt hätte.
- **`trustAnchor` ebenfalls typisieren** (`TrustAnchor`, eine `@JvmInline value class`): verworfen
  nach einem verifizierten Fehlschlag — Hibernate scheiterte bei jedem Schreibzugriff mit
  `JpaSystemException: class java.lang.String cannot be cast to class TrustAnchor`, weil der
  Property-Access-Pfad dem Konverter eine rohe
  `String`-Instanz statt der geboxten Value Class durchreicht. `AttributeType` (ein
  echtes Enum) und `AnchorType` (ein sealed interface aus `object`s) haben dieses Problem nicht.

**Warum diese**: Eine Umbenennung im Wire-Format sollte ein Compilerfehler sein, keine stille
Datenkorruption über eine Laufzeit von 10+ Jahren. Die eigenen Konverter statt `@Enumerated`
erhalten dabei exakt das bestehende Wire-Format, ohne Migration der Bestandsdaten.

**Preis**: Zwei verschiedene Typisierungsmuster im selben Modul (`@Convert` hier,
`@Enumerated(STRING)` im `orchestrator`) statt eines einheitlichen — auflösbar nur durch
Datenmigration oder Umstellung des `orchestrator`. `trustAnchor` bleibt zudem als einziges der
drei ursprünglich benannten Felder ungetypt — eine bekannte, dokumentierte Lücke.

---

## ADR-14: Schema-Konsolidierung — Konto als Sperrwurzel, eine Wahrheit je Fakt

**Entscheidung**: Die 37 inkrementellen Migrationen sind in `V1__schema.sql` (+ `V2__testdata.sql`)
zusammengeführt, und das Schema folgt durchgängig deklarierten Regeln (Kopf von `V1__schema.sql`,
[Betrieb](07-betrieb.md) Abschnitt 6). Inhaltlich:

- `account` trägt nur noch `id`, `created_at`, `version` und ist Sperrwurzel für Änderungen am
  aktuellen Kontozustand (`OPTIMISTIC_FORCE_INCREMENT`).

- Aktueller Zustand liegt in Zeilen je Fakt: `account.anchor` (einziger Speicherort von PersonId und
  bestätigter E-Mail), `account.auth_method` (eine Zeile je Methodeninstanz, `EnrollmentRef` als
  Spalten). Historie ist append-only: `account.attribute`, `account.identification`.
  Die JSON-Listen `identifications`/`authentication_methods`, die Projektionsspalten
  `person_id`/`email`/`email_confirmed_at` und `ConsolidationStrategy` entfallen, weil „lokal
  konsolidiert" und „ist Anker" dieselbe Aussage geworden sind.
- **Nachtrag**: Ihr zweiter Fall (`ExternalLiveLookup`) verschwand mit ihr, obwohl „kein lokaler
  Anker" danach Stammdaten-Hoheit (`NAME`) und Modul-Hoheit (`PHONE_NUMBER`) zugleich abdeckte;
  er ist als `AttributeType.authority` (`LOCAL_ANCHOR`/`EXT_STAMMDATEN`/`METHOD_MODULE`,
  exhaustiv) in `tool_api/AttributeRules.kt` zurückgeholt.
- Fremdschlüssel nur innerhalb eines Moduls; modulübergreifende Bezüge sind indizierte Spalten.
- Einheitliche Namen (`<modul>_enrollment` = `EnrollmentRef.type`, `<modul>_<tool-rolle>_data`,
  `ux_`/`ix_`, PK-Spalte `id`) und Typen (`TIMESTAMP WITH TIME ZONE`, feste Längenraster).
  **Nachtrag**: Diese Namenskonventionen sind von ADR-16 abgelöst.

**Erwogene Alternative**: Nur squashen und die Form des Modells unverändert lassen (JSON-Listen auf
der Kontozeile, Projektionsspalten neben den Ankern).

**Warum diese**: Bei ≥ 10 Mio. Konten und langer Lebensdauer sind die JSON-Listen weder abfragbar
(„alle Konten mit Methode X") noch schreibgünstig (jede
Änderung schreibt die ganze Zeile), und jede Projektionsspalte
neben einem Anker ist eine zweite Eindeutigkeitsautorität für denselben Fakt — genau der Fehler, den
A3 im Review einmal schon beheben musste.

**Preis**: Ein Kontoprofil braucht drei indizierte Lesezugriffe statt einem; die E-Mail im Profil
ist die normalisierte Form, die Rohschreibweise steht nur noch im Claim-Log. Wie
bei ADR-4 gilt: Diese Neubaseline ist nur ohne Produktivdaten vertretbar, danach sind Migrationen
ausschließlich additiv.

---

## ADR-15: Nachweise und ausgestellte Tokens in getrennten Tabellen

**Entscheidung**: `orchestrator.auth_evidence` (was auf einem Kanal bewiesen wurde) und `orchestrator.auth_context` (was
daraus an Tokens ausgestellt wurde) sind zwei Tabellen mit einseitiger Abhängigkeit:
`orchestrator.auth_context.auth_evidence_id` zeigt auf die Nachweise, nie umgekehrt; mehrere Token-Kontexte
dürfen auf dieselbe Evidenz zeigen (`AuthContextRepository.findByAuthEvidenceId` liefert eine
Liste). Abgeleitete Größen werden in keiner gespeichert: `currentAcr` berechnet
`AuthPolicy.resolveAcr` bei jedem Lesen neu aus `amr_evidence`
([Domänenmodell](02-domaenenmodell.md) Abschnitt 7).

**Erwogene Alternative**: Eine Tabelle — die Token-Spalten neben den Nachweisen in derselben
Zeile, so wie es vor der kc-Fassade (`07e7156`) auch war.

**Warum diese**: Zwei Gründe, die beide nicht an der Kardinalität hängen.

1. **Nicht jeder Kanal hat Tokens, aber jeder hat Nachweise.** Der KEYCLOAK-Kanal legt nie einen
   `AuthContext` an ([API](05-api.md) Abschnitt 3); in einer
   gemeinsamen Tabelle trüge jede Web-Kanal-Zeile vier dauerhaft leere Token-Spalten.
2. **Das eine ist Wahrheit, das andere Cache.** Davon lebt
   `AuthEvidenceService.invalidateCachedTokens`: Ein Step-up setzt Access- und RefreshToken auf
   `null`, **während die Nachweise stehen bleiben**.

**Preis**: Zwei Tabellen, die einander äußerlich stark ähneln (beide mit `account_id`, `version`,
`updated_at`) und im APP-Kanal praktisch immer gemeinsam entstehen — das erlaubte
1:n ist heute durchgehend 1:1. Der Grund steht in den KDocs von
`AuthContext`/`AuthEvidence` und hier.

---

## ADR-16: Ein Datenbankschema je Modul statt Namenspräfix

**Entscheidung**: Jedes Modul bekommt ein eigenes Datenbankschema, und jede Tabelle liegt im
Schema ihres Moduls: `account.anchor`, `auth_sms.enrollment`, `orchestrator.channel_session`.
Tabellennamen tragen kein Modulpräfix mehr; Indizes und
Constraints sind schema-eigene Objekte und ebenfalls präfixfrei (`ux_anchor_value`). Die
Arbeitsdaten eines Tool-Durchlaufs heißen `<modul>.<tool-rolle>_tool_session`
([Betrieb](07-betrieb.md) Abschnitt 6, Kopf von `V1__schema.sql`).

**Erwogene Alternative**: Der Zustand davor — eine flache Tabellenmenge mit dem Modulnamen als
Präfix (`auth_sms_enrollment`, `orchestrator_channel_session`).

**Warum diese**: Das Präfix war eine Konvention, an die sich jemand halten musste; das Schema ist
eine Struktur, die sich nicht umgehen lässt. Eine Tabelle kann nicht mehr versehentlich im falschen
Modul entstehen, und die tragende Regel dieses Schemas — Fremdschlüssel nur innerhalb eines Moduls
— steht jetzt in der DDL selbst statt nur in einem Kommentar darüber. Eine spätere Aufteilung
eines Moduls in einen eigenen Dienst hat zudem eine klare Schnittlinie.

**Preis**: Jede Query, jede `@Table`-Annotation und jedes Admin-Werkzeug muss qualifizieren; ein
unqualifiziertes `SELECT ... FROM anchor` findet nichts mehr, weil der Suchpfad auf `PUBLIC` steht
(dort bleibt bewusst auch `flyway_schema_history`).
Und wo vorher ein Tabellenname global eindeutig war, ist er es jetzt nur noch je Schema: Fünf
Module haben ein `enroll_tool_session`.

---

## ADR-17: Adresse bestätigen und E-Mail-Login einrichten sind zwei Akte

**Entscheidung**: Die Bestätigung einer E-Mail-Adresse ist ein eigenes Tool `confirm-email` mit
eigener Kategorie `ToolCategory.ATTEST` (Rolle `ATTESTATION`) und eigener Ergebnisform
`ToolOutcome.Completed.Attested`: Claims ja, kein `enrollmentRef`, `amr` leer, **keine**
Methodeninstanz. `enroll-email` bleibt daneben bestehen, setzt aber eine bereits
bestätigte Adresse voraus (`ClaimRequirement(EMAIL, PROVEN)`) und ist dadurch ein Ein-Schritt-Tool
ohne Code-Austausch. Weil damit der Wissensfaktor nicht mehr
als Nebenprodukt der Adressbestätigung entsteht, verlangt eine Registrierung auf **jedem** Kanal
ein Passwort — aber nur dann, wenn das Konto `loa2` sonst nicht erreichen könnte. Ein
Gerätecredential trägt `POSSESSION`, `KNOWLEDGE` und `INHERENCE` zugleich, deckt das also allein
ab. Die Reihenfolge ist Adresse, Passwort, Besitzfaktor.

**Erwogene Alternative**: Alles beim Alten lassen und die Kopplung nur dokumentieren — `enroll-email`
bestätigt die Adresse *und* legt die Methode an.

**Warum diese**: Die Adresse gehört dem Konto, nicht dem Verfahren (`AttributeType.authority ==
LOCAL_ANCHOR`). Drei fremde Lookup-Verfahren lösen das Konto über sie auf, und
`enroll-password` ist auf sie gegated — sie ist Infrastruktur. Die Trennung sorgt dafür, dass das
Entfernen der Methode die Adresse gar nicht mehr mitreißen kann.

**Preis**: Ein Schritt mehr in der Registrierung, ein Tool mehr im Katalog, und eine Reihe von
Integrationstests musste ihre Erwartung umstellen (`sms + email` → `sms + password`). Die
Asymmetrie „Passwort nur im Web" entfällt.

---

## Erkannte, bewusst zurückgestellte Verbesserungen

Befunde aus [13-review-domaenen-db-modell.md](13-review-domaenen-db-modell.md), die bewusst
**nicht** vollständig umgesetzt sind — jeweils eine
Architektur-/Infrastrukturentscheidung, kein lokal abschließbarer Fix:

- **`orchestrator.dpop_proof_replay`-Skalierung** (B5, siehe auch [09-dpop.md](09-dpop.md) Abschnitt 2): Der
  Schlüssel ist seit ADR-14 ein fester SHA-256-Hash. Offen bleibt die Zeitpartitionierung bzw. ein
  separater persistenter KV-Store — eine Entscheidung für den Produktivstack.
- **Konto-Lebenszyklus und Merge-Pfad** (D2): `Account` kennt keinen Status und kein
  `merged_into`. ADR-11 weist einen `person_id`-Konflikt
  bewusst ab, statt zu mergen — über die angestrebte Lebensdauer entsteht Merge-Bedarf aber
  zwangsläufig, und ohne `merged_into` gibt es dann keinen verlustfreien Weg dorthin.

Beide verdienen einen eigenen, sorgfältig geplanten Durchgang mit Entwurfsentscheidung vorab —
Details stehen im Review-Dokument. D1 (Methoden als eigene Tabelle) ist mit ADR-14 umgesetzt.
