# Architekturentscheidungen

Die großen Entscheidungen dieses Projekts, jeweils mit der ernsthaft erwogenen Alternative und
mit dem, was die gewählte Lösung kostet. Was die Lösung *ist*, steht in den verlinkten Kapiteln — hier steht nur das
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
`when`-Zweig würde erst zur Laufzeit auffallen.

**Kosten**: Mehr Code — 17 Controller statt einem, mit strukturell ähnlichem Aufbau
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
(`AuthPolicy`, Tool-Katalog). Getrennte Spalten je Attribut würden eine
breite Tabelle aus überwiegend leeren Feldern ergeben. `stateType` bleibt trotzdem abfragbar.

**Kosten**: Der `state`-Inhalt ist für die Datenbank selbst intransparent — Constraints und
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

**Kosten**: Zwei Konzepte statt eines — die Geräte-Bindung muss explizit über
`DeviceAccountLink` nachgeschlagen werden.

---

## ADR-4: Flyway-Neubaseline statt Migration des Altcodes

**Entscheidung**: `V1__schema.sql` ersetzt die komplette frühere Migrationshistorie (`V1`–`V16`
im ursprünglichen Code) durch einen sauberen Neubau, statt sie fortzuschreiben.

**Erwogene Alternative**: Den Altcode Schritt für Schritt migrieren — `Attempt`-Terminologie zu
`ToolSession`/`ToolOutcome`, URL-Pfade auf den Tool-Namespace, Klartext-TAN nachträglich hashen.

**Warum diese**: Der Alt-Stand unterschied sich strukturell so stark vom Zielbild (andere
Terminologie, kein `ToolDescriptor`/`AuthPolicy`, unverschlüsselte TANs), dass eine
Schritt-für-Schritt-Migration mehr Zwischenzustände und damit mehr Fehlerquellen erzeugt hätte als
ein Neubau. Für eine Demo mit lokaler H2-Datei entfällt außerdem das übliche Gegenargument
Datenverlust.

**Kosten**: Diese Entscheidung ist an den Kontext gebunden — bei echten Bestandsdaten wäre eine
Neubaseline nicht vertretbar.

**Nachtrag**: Eine zweite Neubaseline hat die danach wieder aufgelaufenen 37 Migrationen
zusammengeführt — mit Modellbereinigung, siehe ADR-14.

---

## ADR-5: Drei Obergrenzen für das Sicherheitsniveau

**Entscheidung**: Das erreichbare Sicherheitsniveau ist an drei unabhängigen Stellen begrenzt:
`account.identification.achieved_acr` begrenzt, was ein Account je erreichen kann;
`account.auth_method.enrolled_under_acr` begrenzt, was eine einzelne Methode beisteuern darf;
`achievedAcr` einer Session ist das Minimum aus tatsächlich Nachgewiesenem und dem, was die
verwendete Methode laut ihrem `enrolledUnderAcr` tragen darf
([Orchestrierung](04-orchestrierung.md) Abschnitt 8, [Überblick](01-ueberblick.md)).

**Erwogene Alternative**: Nur `achievedAcr` aus der aktuellen Session-Historie ableiten, ohne die
Enrollment-Bedingungen der einzelnen Methode rückwirkend zu berücksichtigen.

**Warum diese**: Ohne die `enrolledUnderAcr`-Begrenzung gäbe es einen Weg nach oben: Wer eine
schwache Session übernimmt (z. B. `loa1`), könnte darin eine eigene Methode einrichten und damit
dauerhaft ein höheres Niveau vortäuschen. Die dritte Grenze verhindert zusätzlich, dass eine
schwach identifizierte Person über starke Auth-Methoden ein Niveau erreicht, das ihre
Identifizierung nie hergab.

**Kosten**: Drei Stellen, an denen ein Niveau sinken kann, statt einer. Welche davon gerade
greift, lässt sich nur über `AuthContext`, `account.auth_method.enrolled_under_acr` und
`account.identification.achieved_acr` zusammen nachvollziehen.

**Nachtrag**: `DefaultAuthPolicy.resolveAcr` berechnet die erste Obergrenze inzwischen als eigene
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

**Kosten**: Backend und Frontend müssen synchron gehalten werden — ein neuer `next`-Wert ohne
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
  praktisch ein gemeinsames Geheimnis mit asymmetrischem Aufwand. Pro Nutzer wäre es zusätzlich fatal:
  `DeviceAccountLink` würde bei jedem Web-Login treffen.

**Warum die signierte Assertion**: mTLS bringt Betriebsaufwand (Zertifikats-Rollout, -Rotation,
-Widerruf für zwei Serverdienste) mit, den eine signierte Anwendungsebene-Assertion nicht braucht
— die Signatur lässt sich mit demselben Schlüsselmaterial prüfen, das Keycloak ohnehin für Tokens
verwendet. Weil der Browser den Orchestrator nie direkt erreicht, bleibt die Angriffsfläche auf
die eine Server-zu-Server-Strecke beschränkt.

**Kosten**: Die Sicherheit der Strecke hängt vollständig an der Signaturprüfung der Anwendung —
mTLS hätte Peer-Identität und Verschlüsselung bereits auf Transportebene erzwungen. Ein
übernommenes Keycloak kann jeden Nutzer imitieren — das liegt in der kc-first-Architektur selbst,
auch mTLS ändert daran nichts.

---

## ADR-8: Keycloak führt seine eigenen nativen Schritte selbst, statt alles zu delegieren oder über Identity-Brokering zu gehen

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
- **Zustandslose Einzel-Tool-Aufrufe ohne Journey** (Keycloak führt die Journey weiter selbst, ruft den
  Orchestrator nur für isolierte Faktoren ohne begleitende `ChannelSession` auf):
  ohne die persistente Journey verliert der Orchestrator die
  Fähigkeit, mehrere eigene Tools im selben Login zu einem gemeinsamen Nachweis zu verrechnen. Bleibt
  sinnvoll für Touchpoints außerhalb eines zusammenhängenden Flows (eine
  Keycloak-„Required Action", eine Aktion in der Account-Konsole).

**Kosten**: Split-Brain-Risiko zwischen zwei Zustandshaltern — abgefedert dadurch, dass jede Seite
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

Ein Step-up desselben Accounts stellt innerhalb dieser einen Keycloak-Session ein neues Token aus:
`AccountTokenGrantType` markiert die von ihm angelegte
`UserSessionModel` mit einer Session-Note und findet sie später wieder; ihre Lebensdauer folgt
den realm-weiten `SSO Session Idle`/`SSO Session Max`-Einstellungen. Der Grant gibt ein opakes
`refresh_token` aus (`useRefreshToken() == true`), das `KcTokenProvider` für jede reine
Fristverlängerung nutzt (`KeycloakAdminClient.refreshAccountToken`); zum custom
Account-Token-Grant greift er nur, wenn sich ACR/AMR seit der letzten Ausstellung geändert haben.

Die Assertion trägt dafür `acr`/`amr` als eigene, signierte Claims - `AccountTokenGrantType`
kopiert sie in dieselben `UserSessionModel`-Notes wie `OrchestratorAuthenticator`
(`orchestrator_acr`/`orchestrator_amr`), von wo der
`OrchestratorAcrAmrMapper` (Client-Scope `orchestrator-claims`) sie in den
echten AccessToken schreibt. `KeycloakAdminClient.requestAccountToken`/`refreshAccountToken`
authentifizieren sich dabei als
Client `orchestrator-app-token` (V8) ohne jedes Recht, nicht als `orchestrator-admin` -
genau das ist der Punkt dieses ADRs ("statt geteiltem Admin-Secret").

Unabhängig vom Profil verwirft ein Step-up, der die `AuthEvidence` verändert
(`AuthEvidenceService.applyEvidence`/`applyEvidenceUpdate`), die im `AuthContext`
zwischengespeicherten Tokens. Sonst würde der Refresh-Pfad die alte Keycloak-Session mit den
alten ACR/AMR-Notes weiter verlängern.

Dafür erzeugt der Orchestrator bei jedem Keycloak-Account-Sync ein eigenes, asymmetrisches
Schlüsselpaar pro Account (`orchestrator.keycloak_keypair`, EC P-256) und spiegelt den Public Key als
echtes Keycloak-`Credential` (Typ `orchestrator-public-key`, `KeycloakAdminClient.setPublicKeyCredential`)
auf den Keycloak-User — bewusst nicht als Attribut: Proof-of-possession-Material gehört in den
Credential-Store. Geschrieben wird er von einer eigenen
`AdminRealmResourceProvider`-Erweiterung (`AccountPublicKeyResource`, gemountet unter
`/admin/realms/{realm}/orchestrator-keys/{accountId}`).

Der Account-Sync spiegelt daneben Namen und User-Attribute
(`personId`/`kvnr`/`geburtsdatum`/`strasse`/`hausnummer`/`plz`/`ort`)
— je Attribut der Registerwert, sonst der stärkste bestätigte Claim des Kontos (ADR-18: ein voll
bestätigter Interessent trägt NAME/VORNAME/GEBURTSDATUM und die Adressattribute auch ohne
Registerbindung); die
Platzhalternamen bleiben nur für Konten ohne beides („Enrollment zuerst"). `personId`/`kvnr`
existieren nur mit Registerbindung — ein Interessent zeigt sich im Fehlen beider, nie in einem
gepflegten Status-Flag.
Der custom Grant-Type in `keycloak-extension/` (`urn:dpop-demo:account-token`,
`AccountTokenGrantType`, Keycloaks pluggable `OAuth2GrantType`-SPI in
`keycloak-server-spi-private`) verlangt zusätzlich eine damit signierte, kurzlebige Assertion
(`sub`=accountId, `aud`=Grant-URN, `exp` <= 60s) und stellt erst dann einen echten AccessToken aus.

**Erwogene Alternativen**:

- **Ein einzelnes geteiltes Admin-Secret** (der `keycloak-sync`-Service-Account
  ruft via Token-Exchange oder Impersonation direkt einen Token für einen beliebigen Nutzer ab):
  verworfen — ein kompromittiertes Secret könnte für JEDEN Account einen Token ausstellen.
- **Nur die umgekehrte Richtung erlauben** (Keycloak ruft den Orchestrator, nie umgekehrt):
  verworfen — `GET .../token` braucht eine sofortige Antwort.
- **`private_key_jwt`-Client-Authentifizierung statt `client_secret`** für die Admin-/Sync-Strecke
  selbst (RFC 7523, ohne Geheimnis wie ADR-7): damals als unabhängige Härtung zurückgestellt,
  inzwischen umgesetzt — siehe ADR-25.

**Warum das Account-Keypair**: Es überträgt dasselbe Prinzip wie ADR-7 (Signatur statt Secret)
auf eine zweite Richtung — nicht pro Client oder Knoten, sondern pro Account, weil genau das der Schaden ist, der
eingegrenzt werden soll: Ein verlorener Schlüssel trifft höchstens einen Account. Die Assertion
bleibt kurzlebig (`exp` <= 60s) und wird nur bei der ERSTEN Ausstellung oder bei einer tatsächlichen
ACR/AMR-Änderung gebraucht.

**Kosten**: Ein weiterer, account-gebundener Datensatz (`orchestrator.keycloak_keypair`) mit eigenem
Lebenszyklus (erzeugt bei Sync, gelöscht bei `AccountDeleted`, [07-betrieb.md](07-betrieb.md)
Abschnitt 3) sowie ein projektspezifisches Stück Keycloak-Erweiterung, das bei jedem
Keycloak-Upgrade gegen die `server-spi-private`-Schnittstelle mitgeprüft werden muss.
Demo-only: Der Private Key liegt unverschlüsselt in der Datenbank.

---

## ADR-10: Interessent ist Konto-Zustand, kein eigener AuthIntent

**Entscheidung** (**umgesetzt**, [Idee](ideen/claims-modell-und-vertrauensanker.md)): Es gibt keinen eigenen `AuthIntent.INTERESSENT`. Ein Interessent — ein Konto, das nur über bestätigte Claims identifiziert ist, ohne `person_id`-Bindung — ist eine Beobachtung über den Ausgang einer Identifizierung, kein wählbares Ziel. Die `REGISTER`-Journey (und jeder andere Intent, der Identifizierungen durchläuft) verzweigt auf das Auflösungs-Ergebnis (`Resolution`: `ExistingAccount` / `Unresolved`): Anker-Treffer bindet wie heute, ohne Anker-Treffer führt das Konto ohne `person_id` fort (seit ADR-19 gibt es keinen dritten Ausgang mehr).

**Erwogene Alternative**: Ein eigener `AuthIntent` mit eigener Journey, eigenen States und eigener Strategie — begründbar, falls Interessenten eine abweichende Politik bräuchten.

**Warum diese**: `AuthIntent` benennt nach eigener Definition
([AuthIntent.kt](../src/main/kotlin/com/example/dpop/orchestrator/journey/AuthIntent.kt)) ein Ziel
samt Strategie, nie eine Beschreibung dessen, was ein Lauf geworden ist. Der
Journey-Verlauf ist für beide Ausgänge strukturell identisch, nur die Konto-Auflösung
selbst unterscheidet sich. Der heutige `Action.RecordIdentification`-Handler behandelt den Fall `personId == null`
bereits als Verzweigung innerhalb der bestehenden Journey (REGISTER "Enrollment zuerst",
[Orchestrierung](04-orchestrierung.md) Abschnitt 2). Ein eigener Intent würde zudem jede künftige Verzweigung doppelt führen (`STEP_UP`,
`RE_IDENTIFY` auf Interessenten-Konten).

**Kosten**: Was nur für Interessenten gilt, steht als Verzweigung in den bestehenden Strategien
(analog `ConfirmDeviceRebind`) statt in einem eigenen Strategy-Objekt — die Strategien bekommen
dadurch mehr Fallunterscheidungen.

---

## ADR-11: Kontoübergreifender person_id-Konflikt ist Abweisung, Merge nie automatisiert

**Entscheidung** (**umgesetzt**, [Idee](ideen/claims-modell-und-vertrauensanker.md)): Beanspruchen zwei Konten denselben `person_id`-Wert, wird die zweite Bindung abgewiesen (409, "Diese Person ist bereits über ein anderes Konto registriert") und nichts adoptiert: keine Claim-Zeile, keine Konsolidierung, keine Anker-Schreibung. Ein Merge ist nie automatisiert, sondern eine operator-getriebene Fähigkeit außerhalb des Claims-Modell-Umfangs. DB-seitig sichert `UNIQUE(person_id)` (partial, `WHERE person_id IS NOT NULL`) dieselbe Semantik für alle Schreibpfade ab.

**Erwogene Alternative**: Die bestätigte Aussage trotzdem loggen und nur die Konsolidierung
verweigern (Konflikt als abfragbarer Zustand); oder eine Review-Queue.

**Warum diese**: Zwei verschiedene Menschen dauerhaft zu verknüpfen ist der teuerste Fehler, den
dieses Modell machen kann. Das zu vermeiden wiegt schwerer als der Verlust der bestätigten Aussage,
die im Journey-Log ohnehin vorübergehend nachweisbar bleibt. Die Rangfolge beim Zusammenführen
(Anker-Klasse vor Aktualität) gilt innerhalb EINES Kontos und endet an der Kontogrenze.

**Kosten**: Der betroffene Nutzer kommt nicht automatisch weiter —
Solange es keine Funktion zum Zusammenführen gibt, bleibt der Fall ein Support-Vorgang, und die
bestätigte Identifikation steht nur im Journey-Log.

**Nachtrag** (Härtung): Drei
Lücken zwischen dieser Entscheidung und ihrer Umsetzung wurden geschlossen. Erstens schrieb
`AccountService.recordAnchor` bei einem fremden Anker die Projektionsspalte trotz Abbruch; der
Anker wird jetzt **vor** der Projektionsspalte geschrieben. Zweitens
verließ sich `IdentityMatchingService.resolveByAnchor` auf die
beliebige Reihenfolge eines `Set` und sortiert jetzt ausdrücklich nach
`AttributeType.anchorRule?.bindingStrength` (`tool_api/AttributeRules.kt`).
Drittens fing `findOrCreateAccount` die
`UNIQUE(person_id)`-Kollision nicht ab; das ist durch die atomare
Claim-Übernahme unten abgelöst.

**Nachtrag 2** ([ideen/account-attribute-und-trust-vereinheitlichen.md](ideen/account-attribute-und-trust-vereinheitlichen.md), alle 7 Pakete): `person_id` ist seither
kein Sonderfall mehr, sondern ein gewöhnlicher, höchstrangiger `account.anchor`-Eintrag wie `email`
— die kontoübergreifende Abweisung läuft seither
technisch über denselben `recordAnchor`-Pfad statt über einen separaten
`bindPersonId`/`findAccountByPersonId`-Vergleich, und
`MatchedVia.PersonId` ist zugunsten von `MatchedVia.Anchor(PERSON_ID)` entfallen.
Neu dazugekommen, INNERHALB eines Kontos: `AttributeType.anchorRule?.allowsReplacement`
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

## ADR-12: Ein Widerruf ist eine eigene Zeile mit eigenem Vertrauensanker

**Entscheidung** (**umgesetzt**, [Idee](ideen/claims-modell-und-vertrauensanker.md)): Ein zurückgezogener Wert (KVNR abgemeldet, E-Mail verworfen) wird als eigene Zeilenform festgehalten — `account.retraction(account_id, attribute_type, normalized_value, trust_anchor, reason, retracted_at)` — und ist selbst eine Angabe mit eigenem Vertrauensanker: WER ruft zurück, plus Grund und Zeitpunkt. Das Log (`account.claim`) bleibt strikt append-only; die Konsolidierung rechnet "Angaben minus Widerrufe" und hält Projektionsspalten und `account.anchor` aktuell (die Anker-Zeile wird gelöscht — die Anker-Tabelle ist Projektion, nicht Log). Widerrufe kommen nie über den Tool-Vertrag: `ToolOutcome` kennt nur positive Ergebnisse.

**Erwogene Alternative**: Flag-Spalten (`retracted_at`/`retracted_by`) direkt auf der
Claim-Zeile — eine Tabelle, einfachste Abfrage, aber die einzige Nicht-Append-Mutation im Log.

**Warum diese**: Das Log ist die Quelle der Wahrheit und wird nie überschrieben — diese Regel
darf keine Ausnahme bekommen. Jede Änderung an einer bestehenden Zeile macht es schwerer,
nachträglich zu sagen, was wann galt. Eine Widerrufszeile trägt ihre Herkunft genauso nachweisbar
wie eine Angabe und hält den Tool-Vertrag frei von negativen Ergebnissen.

**Kosten**: Zwei Formen statt eine — "gültiger Wert" ist immer eine Subtraktion über zwei
Tabellen, und jeder Konsolidierungs- und Abfragepfad muss den Widerruf mitdenken; heute ist das
genau ein Pfad (der Attributabgleich in `IdentityMatchingService`), dort ein `not exists` über
einen eigenen Index.

**Nachtrag zur Umsetzung**: Damit ein Widerruf weiß, *was* er zurückzieht,
trägt jede Claim-Zeile die `auth_method_id` der Methodeninstanz, bei deren Einrichtung sie
entstanden ist (`null` bei Identifizierungs-Tools). Beim
Entfernen einer Methode (`AccountDeletionService.revokeMethod`) zieht `retractClaimsOf` genau
deren Angaben zurück — aber **nur die mit `AttributeAuthority.MethodModule`**: Ein Anker
(`EMAIL`) oder ein Stammdaten-Attribut (`NAME`) überlebt das Credential, sonst hätte das
Entfernen der E-Mail-Methode den Passwort-Login
(`ClaimRequirement(EMAIL, PROVEN)`) zerstört. Der Widerruf selbst ist
`RetractionAnchor.ACCOUNT_MANAGEMENT` — bewusst von `ClaimSource` getrennt, weil
ein Tool nie widerrufen darf.

**Zweiter Nachtrag zur Umsetzung**: Zwei Ergänzungen, die das Log praktikabel halten:
Erstens ist `account.claim` ein Change-Log, kein Run-Log — `recordClaims` überspringt eine
Angabe, die identisch bereits gilt (gleicher Typ, normalisierter Wert, Quelle und
Methodeninstanz); die Methodeninstanz bleibt Teil des Schlüssels, damit ein Neu-Enrollment eines
bekannten Werts trotzdem loggt (sonst würde die Revokation der alten Instanz den Wert verlieren).
Zweitens widerruft seit ADR-19 auch das Ersetzen eines Ankers an derselben Stelle (`EMAIL`,
`EID_RESTRICTED_ID`) den alten Wert (`ACCOUNT_MANAGEMENT`, „anker-ersetzt") — sonst würde ein
ersetzter Wert für immer als gültig im Log stehen. Der Anti-Join in `findEstablished` vergleicht dafür
die Zeitpunkte (`r.retracted_at >= a.established_at`): Ein Widerruf entkräftet nur Angaben, die
vor ihm liegen; ein danach neu bestätigter Wert gilt wieder (die Folge a → b → a endet bei a).

**Offen und bewusst nicht mitentschieden**: Der Widerruf macht einen Wert *ungültig*, er
*löscht* ihn nicht, und mit dem normalisierten Wert entsteht in der Widerrufszeile eine zweite
lesbare Kopie. Naheliegend wäre eine Aufbewahrungsfrist, nach der Claim-
und Widerrufszeile gemeinsam gelöscht werden; der Audit-Nachweis hängt nicht daran,
`account.identification` hält Verfahren, LoA und Zeitpunkt ohne die Attributwerte fest.


---

## ADR-13: Account-Domänentypen über eigene `AttributeConverter`, nicht `@Enumerated`

**Entscheidung** (umgesetzt): `AccountClaim.attributeType` und `AccountAnchor.attributeType` sind
über einen gemeinsamen JPA-`AttributeConverter` typisiert (`AttributeTypeConverter`), der über
`wireName` hin- und zurückkonvertiert — nicht über `@Enumerated(EnumType.STRING)`, das der `orchestrator`-Modul für
seine eigenen Enums nutzt. Die Quelle einer Angabe heißt `AccountClaim.claimSource: String`
(bewusst ungetypt, siehe Kosten); der Widerruf trägt dagegen ein eigenes, typisiertes
`AccountRetraction.trustAnchor: RetractionAnchor`.

**Erwogene Alternativen**:

- **`@Enumerated(EnumType.STRING)`**, konsistent mit dem `orchestrator`-Modul: verworfen, weil
  `account.claim.attribute_type`/`account.anchor.attribute_type` seit Jahren Wire-Names in
  Kleinschreibung tragen (`person_id`, `email`), `@Enumerated(STRING)` aber den
  Enum-Konstantennamen (`PERSON_ID`) schreibt und damit jede Bestandszeile unbemerkt nicht mehr
  gefunden hätte.
- **`claimSource` ebenfalls typisieren** (eine `@JvmInline value class`): verworfen
  nach einem verifizierten Fehlschlag — Hibernate scheiterte bei jedem Schreibzugriff mit
  `JpaSystemException: class java.lang.String cannot be cast to class ...`, weil der
  Property-Access-Pfad dem Konverter eine rohe
  `String`-Instanz statt der geboxten Value Class durchreicht. `AttributeType` (ein
  echtes Enum) hat dieses Problem nicht.

**Warum diese**: Eine Umbenennung im Wire-Format soll der Compiler melden, statt über Jahre
unbemerkt Daten zu beschädigen. Der eigene Konverter statt `@Enumerated`
erhält dabei exakt das bestehende Wire-Format, ohne Migration der Bestandsdaten.

**Kosten**: Zwei verschiedene Typisierungsmuster im selben Modul (`@Convert` hier,
`@Enumerated(STRING)` im `orchestrator`) statt eines einheitlichen — auflösbar nur durch
Datenmigration oder Umstellung des `orchestrator`. `claimSource` bleibt ungetypt — eine bekannte,
dokumentierte Lücke.

---

## ADR-14: Schema zusammengeführt — das Konto als Sperrpunkt, eine Wahrheit je Fakt

**Entscheidung**: Die 37 inkrementellen Migrationen sind in `V1__schema.sql` (+ `V2__testdata.sql`)
zusammengeführt, und das Schema folgt durchgängig deklarierten Regeln (Kopf von `V1__schema.sql`,
[Betrieb](07-betrieb.md) Abschnitt 6). Inhaltlich:

- `account` trägt nur noch `id`, `created_at`, `version`. Über diese Zeile werden Änderungen am
  aktuellen Kontozustand gesperrt (`OPTIMISTIC_FORCE_INCREMENT`).

- Aktueller Zustand liegt in Zeilen je Fakt: `account.anchor` (einziger Speicherort von PersonId und
  bestätigter E-Mail), `account.auth_method` (eine Zeile je Methodeninstanz, `EnrollmentRef` als
  Spalten). Die Historie wird nur angefügt: `account.claim`, `account.identification`.
  Die JSON-Listen `identifications`/`authentication_methods`, die Projektionsspalten
  `person_id`/`email`/`email_confirmed_at` und `ConsolidationStrategy` entfallen, weil „lokal
  konsolidiert" und „ist Anker" dieselbe Aussage geworden sind.
- **Nachtrag**: Ihr zweiter Fall (`ExternalLiveLookup`) verschwand mit ihr, obwohl „kein lokaler
  Anker" danach Stammdaten-Hoheit (`NAME`) und Modul-Hoheit (`PHONE_NUMBER`) zugleich abdeckte;
  er ist als `AttributeType.authority` (`Local`/`ExtStammdaten`/`MethodModule`, vollständig
  aufgezählt) in `tool_api/AttributeRules.kt` zurückgeholt. **Nachtrag**: `AttributeAuthority` ist
  inzwischen ein `sealed interface`, und `Local` trägt seine `AnchorRule` selbst. Vorher standen
  Eigentümer und Ankerregeln als Flag plus nullable Feld nebeneinander, obwohl sie nie getrennt
  vorkommen — zusammengehalten von einem Test statt vom Typ. Dasselbe Muster wie bei
  `ToolDescriptor.keyBinding` ([Tool-Architektur](03-tool-architektur.md) Abschnitt 1).
- Fremdschlüssel nur innerhalb eines Moduls; modulübergreifende Bezüge sind indizierte Spalten.
- Einheitliche Namen (`<modul>_enrollment` = `EnrollmentRef.type`, `<modul>_<tool-rolle>_data`,
  `ux_`/`ix_`, PK-Spalte `id`) und Typen (`TIMESTAMP WITH TIME ZONE`, feste Längenraster).
  **Nachtrag**: Diese Namenskonventionen sind von ADR-16 abgelöst.

**Erwogene Alternative**: Die Migrationen nur zusammenfassen und die Form des Modells unverändert lassen (JSON-Listen auf
der Kontozeile, Projektionsspalten neben den Ankern).

**Warum diese**: Bei ≥ 10 Mio. Konten und langer Lebensdauer lassen sich die JSON-Listen weder
abfragen („alle Konten mit Methode X") noch günstig schreiben (jede Änderung schreibt die ganze
Zeile). Und jede Projektionsspalte neben einem Anker ist eine zweite Stelle, die für denselben
Fakt Eindeutigkeit garantieren müsste — genau der Fehler, den A3 im Review einmal schon beheben
musste.

**Kosten**: Ein Kontoprofil braucht drei indizierte Lesezugriffe statt einem; die E-Mail im Profil
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
   gemeinsamen Tabelle hätte jede Web-Kanal-Zeile vier dauerhaft leere Token-Spalten.
2. **Das eine ist Wahrheit, das andere Cache.** Davon lebt
   `AuthEvidenceService.invalidateCachedTokens`: Ein Step-up setzt Access- und RefreshToken auf
   `null`, **während die Nachweise stehen bleiben**.

**Kosten**: Zwei Tabellen, die sich äußerlich stark ähneln (beide mit `account_id`, `version`,
`updated_at`) und im APP-Kanal praktisch immer gemeinsam entstehen — die erlaubte 1:n-Beziehung
ist heute durchgehend eine 1:1-Beziehung. Der Grund steht in den KDocs von
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

**Kosten**: Jede Query, jede `@Table`-Annotation und jedes Admin-Werkzeug muss qualifizieren; ein
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

**Warum diese**: Die Adresse gehört dem Konto, nicht dem Verfahren (`AttributeType.authority` ist
`AttributeAuthority.Local`). Drei fremde Lookup-Verfahren lösen das Konto über sie auf, und `enroll-password` setzt sie
voraus — sie ist Infrastruktur. Die Trennung sorgt dafür, dass das
Entfernen der Methode die Adresse gar nicht mehr mitreißen kann.

**Kosten**: Ein Schritt mehr in der Registrierung, ein Tool mehr im Katalog, und eine Reihe von
Integrationstests musste ihre Erwartung umstellen (`sms + email` → `sms + password`). Die
Asymmetrie „Passwort nur im Web" entfällt.

---

## ADR-18: Bestätigen und Zuordnen sind zwei Akte

**Entscheidung**: `ident-eid` bestätigt nur noch, was die Karte trägt (Name, Vorname, Geburtsdatum,
Adresse — jedes Kartenfeld ein eigener `AttributeType`, auch die Adressattribute `strasse`/
`hausnummer`/`plz`/`ort`, die dafür aus den unstrukturierten `auditDetails` in echte Claims aufrückten —
auf eigene Autorität `ClaimSource.of(toolId)`) und löst niemanden auf. Die Zuordnung zur
Registerperson ist ein eigenes Tool `ident-kvnr`: Es fragt die Versichertennummer ab, löst sie
über `PersonDirectory` auf und behauptet erst dann `PERSON_ID`/`KVNR` — beide mit
`ClaimSource.EXT_STAMMDATEN`, denn dort bürgt tatsächlich das Register. Der zweite Akt wird direkt
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

---

## ADR-19: Auflösung nur über Anker — die eID-`restricted_id` wird einer

**Entscheidung**: `IdentityMatchingService.resolve` löst eine bestätigte Identität **nur noch über
lokale Anker** auf (`resolveByAnchor`, Rangfolge nach `AnchorRule.bindingStrength`); ohne
Anker-Treffer ist das Ergebnis `Unresolved`. Die bisherige zweite Schicht — normalisierte
Attributkombination Name+Vorname+Geburtsdatum gegen die Claim-Historie mit
`Resolution.Ambiguous` als Mehrdeutigkeits-Ergebnis — ist komplett entfernt
(`Resolution.Ambiguous`, `MatchedVia.Attributes`, `BindingStrength.ATTRIBUTE_COMBINATION`,
`findAccountIdsMatchingAllThree`, Index `ix_claim_type_value`). An ihre Stelle tritt die
`restricted_id` der eID-Karte als achter Claim von `ident-eid`: ein kartengebundenes Pseudonym
(in der Demo ein Platzhalter für den echten Restricted Identifier). Sie wird als lokaler Anker geführt (`AttributeAuthority.Local`), mit `AnchorAcrFloor(LOA2, LOA2)` und `allowsReplacement = true`: Eine neue Karte bringt
einen neuen Wert, der den alten an derselben Stelle ersetzt — wie bei `EMAIL`. Hält ein anderes
Konto den Wert, bleibt es bei der Abweisung (`IdentityConflictException`). Der ersetzte Wert verfällt seit dem
zweiten ADR-12-Nachtrag auch im Claim-Log: Der Anker-Ersatz schreibt einen Widerruf für den
alten Wert, damit das Log mit dem Anker übereinstimmt.

Der Abgleich dreier Attribute (Name/Vorname/Geburtsdatum) bleibt genau dort, wo er
fachlich hingehört: als **Konsistenzprüfung gegen `ext_stammdaten`**, nie als Auflösungsschicht
über Account-Claims. `verifyToolAttestedConsistency` prüft die KVNR-aufgelöste Person gegen die
bestätigten Attribute; `attestedIdentityMatches` vergleicht vor dem Korrelations-Anker die
Stammdaten hinter der Nummer mit der bestätigten Identität.

**Erwogene Alternative**: Die Attributkombination behalten, aber nur noch im Zusammenhang mit
dem KVNR-Vergleich wirken lassen und ihre Werte gegen `ext_stammdaten` statt gegen den Account
prüfen.

**Warum diese**: Account-Claims sagen, woher ein Wert kam, sie sind keine Register-Wahrheit — ein
Dreifach-Treffer darauf kann denselben Datensatz in fremden Konten finden und war damit schwächer
als das, was er ersetzen sollte. Der KVNR-Abgleich gegen `ext_stammdaten` ist bereits als Guard
vorhanden; eine zweite Matching-Schicht über Account-Historie ist redundant und erzeugt nur den
nie sauber spezifizierten `Ambiguous`-Kanal in der Journey. Die `restricted_id` ist das fachlich
richtige Wiedererkennungsmerkmal für eid-Interessenten: an die Karte gebunden, mit einem neuen
Ausweis ein neuer Wert, aber nie über Personen hinweg gleich — genau das, was ein ersetzbarer Anker
leistet. Die EUDI-Richtung passt dazu: Die echte PID kommt später als eigener Ankertyp hinzu.

**Kosten**: Bestätigungen aus der Zeit vor diesem ADR, also ohne `restricted_id`, erkennt das
System nicht wieder — sie laufen auf `Unresolved` und damit auf ein neues Konto. `V1__schema.sql` ändert
sich (`id_eid.ident_tool_session.restricted_id`, Wegfall `ix_claim_type_value`), bestehende
Dev-Datenbanken sind neu anzulegen. Die `restricted_id` wird bewusst **nicht** nach Keycloak
gespiegelt (kein Stammdatum, nur Wiedererkennungsanker).

---

## ADR-20: Ein vorläufiges Konto geht im gefundenen auf, statt den Lauf abzuweisen

**Entscheidung** (**umgesetzt**): Findet ein Identifizierungsschritt ein **anderes** Konto als das,
mit dem die Journey gerade arbeitet, dann geht das vorläufige der beiden Konten im anderen auf —
auf welcher Seite es steht, ist egal:

- Ist das Konto **der Journey** vorläufig, wechselt die Journey zum gefundenen Konto und nimmt die
  Bestätigung mit. Das ist der ident-first-Fall: `ident-eid` bestätigt, findet niemanden,
  `performIdentified` legt dafür ein Konto an — und der `ident-kvnr`-Schritt danach findet das
  echte Konto.
- Ist das **gefundene** Konto vorläufig, bleibt die Journey, wo sie ist, und übernimmt dessen
  Daten. Das ist der Fall bei „Enrollment zuerst": Die Journey arbeitet mit dem echten Konto, in
  dem gerade die Zugangsmittel entstanden sind, und der eID-Lauf findet über den
  `restricted_id`-Anker einen Rest aus einem früheren, abgebrochenen Versuch.
- Ist **keines** von beiden vorläufig, bleibt es beim `409`. Zwei echte Konten werden nicht
  nebenbei zusammengelegt.

Dieselbe Regel gilt für eine **bestätigte Adresse**, nicht nur für eine Identifizierung:
`confirm-email` beweist den Besitz eines Werts, über den das Kontomodell Konten auflöst
(`resolveByAnchor` — genau der Weg, über den `auth-email-lookup` jemanden anmeldet). Wer also mit
einem vorläufigen Konto in der Hand seine eigene Adresse bestätigt, hat damit gesagt, welches
Konto ihm gehört. Eine Vorabprüfung „Adresse schon vergeben?" gibt es deshalb nicht mehr: Vor dem
Code ist nichts bewiesen, sondern nur getippt, und die Ablehnung traf regelmäßig genau den
Richtigen.

Eine Bedingung kommt hier dazu, die eine Identifizierung nicht braucht: **Die bestätigte Identität
muss zum aufgelösten Konto passen.** Der Besitz eines Postfachs sagt „dieses Postfach gehört mir",
niemals „ich bin diese Person". Hat das Zielkonto eine Registerperson, wird die in dieser Sitzung
bestätigte Identität gegen deren Stammdaten geprüft (`IdentityResolver.attestedIdentityMatches`,
derselbe Wächter, den ADR-18 vor den Korrelationsschritt stellt) — sonst könnte, wer ein fremdes
Postfach kontrolliert, seine eigenen eID-Claims an ein fremdes Konto hängen. Hat das Zielkonto
keine Person gebunden, gibt es nichts zu prüfen, und „eines von beiden ist vorläufig" ist die ganze
Bedingung.

Was „vorläufig" heißt, steht als benannte Regel am `AccountProfile` und nicht als Bedingung an
mehreren Stellen: `isProvisional` = `isUnidentified` (keine PersonId, ADR-10) **und** es wurde nie
ein Zugangsmittel eingerichtet. Deaktivierte zählen mit: Bei einer widerrufenen Instanz hängt weiterhin die Herkunft von Claims
(`account.claim.auth_method_id`, ADR-12), und die darf man weder mitnehmen noch wegwerfen. Dieselbe Regel entscheidet, ob eine abgebrochene Journey ihr Konto löschen darf
(`JourneyService.deleteIfAbandonedUnidentified`): eine Regel, zwei Folgen, statt zweier
handgeschriebener Bedingungen, die auseinanderlaufen können.

Die Übernahme (`AccountService.absorbProvisionalAccount`) ist ausdrücklich **kein** allgemeines
„Identitätsdaten zwischen Konten verschieben". Sie verlangt ein vorläufiges Quellkonto, und genau
das macht sie harmlos. Ihre Reihenfolge gehört zur Entscheidung und ist kein Implementierungsdetail:
`account.anchor` ist je (Typ, Wert) global eindeutig (`ux_anchor_value`), also müssen die Anker der
Quelle **weg sein, bevor** dieselben Werte am Zielkonto geschrieben werden — lesen, freigeben,
löschen, schreiben. Geschrieben wird über den normalen `recordClaim`-Pfad, Claim für Claim in der
ursprünglichen Reihenfolge; Konfliktprüfung, ACR-Floors und Widerrufsregeln gelten am Zielkonto
damit unverändert, und ein Anker, den das Ziel mit demselben Wert schon hat, bleibt wirkungslos, wie
schon vorher.

Für den Kanal gilt dasselbe in umgekehrter Richtung: Nachweise und Gerätebindung werden umgehängt,
**bevor** das alte Konto gelöscht wird (`AuthEvidenceService.rebindToAccount`,
`linkDeviceToAccount`). Die Nachweise werden dabei nicht zurückgesetzt — was diese Sitzung bewiesen
hat, hat sie bewiesen; es wandert nur der Zeiger auf das Konto, die zwischengespeicherten Tokens
fallen weg. Nach dem Wechsel läuft die Registrierung noch einmal durch
`RegisterStrategy.afterIdentification`, damit das neue Konto dieselben zwei Fragen durchläuft wie
auf jedem anderen Weg dorthin: Ist dieses Gerät schon an ein anderes Konto gebunden? Und kann das
Konto einfach eine vorhandene Methode beweisen, statt eine neue einzurichten?

**Erwogene Alternative**: Die eID-Claims bis zur Bindung nur in der Journey halten (JSON-Spalte an
`auth_journey`, Overlay über ein synthetisches `AccountProfile`, Materialisierung beim ersten
schreibenden Akt). Verworfen, und nicht nur wegen des Umfangs: `IdentKvnrDescriptor.requires`
(NAME/VORNAME/GEBURTSDATUM `PROVEN`) wird gegen `ctx.account` geprüft — ohne geschriebenes Konto
lässt sich der Zuordnungsschritt gar nicht anbieten, das Overlay wäre also nicht optional, sondern
Pflicht. Das ist viel Aufwand gegen einen Fehler, der aus einem einzigen `409` besteht.

**Warum diese**: Das vorläufige Konto ist ein Nebenprodukt der Journey und gehört dem Nutzer nicht
— außer der Bestätigung, die gerade entstanden ist, trägt es nichts. Es im gefundenen Konto aufgehen
zu lassen kostet nichts und rettet genau diese Bestätigung. Es stehen zu lassen erzeugt dagegen einen
Konflikt, den der Nutzer weder verursacht hat noch auflösen kann.

**Kosten / bewusst offen**: Zwei echte Konten zusammenzulegen bleibt ungelöst und bleibt beim `409`.
Hat das Zielkonto eine andere E-Mail oder eine andere `restricted_id`, greifen die normalen
Ankerregeln: ersetzen samt Widerruf (`EMAIL`, `EID_RESTRICTED_ID`) oder abweisen (`PERSON_ID`) —
die Übernahme kopiert nichts an diesen Regeln vorbei. Zurückgezogene Claims kommen nicht mit, und
die übernommenen Claim-Zeilen tragen den Zeitpunkt der Übernahme. Wann Identität bewiesen wurde,
steht weiterhin in den `account.identification`-Zeilen; die wandern mit ihrem ursprünglichen
`identified_at` und einem `absorbedFromAccountId`-Vermerk mit.

---

## ADR-21: Der KOBIL-PIN liegt im Backend — und das Zugangsmittel zählt trotzdem

**Entscheidung** (**umgesetzt**): Beim Verfahren `kobil` wird der PIN nicht vom Nutzer vergeben und
nicht von ihm eingetippt, sondern vom Tool-Backend erzeugt, dort verwahrt und pro Anmeldung
freigegeben, nachdem der Client sich lokal entsperrt hat — per biometriegeschütztem Gerätegeheimnis
oder per Kontopasswort. Der Entsperrweg ist das `userVerification` dieses Verfahrens (`pin` bzw.
`biometric`), kein zweiter Nachweis; beide Wege erreichen `loa2`, genau wie bei `auth_device`.

**Keiner der beiden Wege ist Pflicht, und welche existieren, rechnet der Server aus.** Die
Biometrie entsteht nur bei Zustimmung (dann gibt es einen `unlock_secret_hash`, sonst NULL), das
Passwort nur, solange das Konto eines hält. `auth-kobil` nennt im `stepData` die tatsächlich
vorhandenen Wege (`unlockOptions`), statt beide anzubieten: Ein Weg, den es nicht gibt, hätte genau
einen möglichen Ausgang — einen Fehlversuch, der den Login-Throttle belastet. Das kostet etwas: Die Antwort
verrät, ob das Konto ein Passwort hat. Vertretbar, weil dieser Schritt nur für einen Aufrufer läuft,
dessen Schlüssel schon zu einem Credential dieses Kontos passt, und weil derselbe Aufrufer direkt
danach `activeMethods` sieht. Eine leere Liste ist möglich und wird auch so gesagt, statt verdeckt.

**Erwogene Alternative**: Den KOBIL-Standardweg beibehalten, also den Nutzer einen PIN vergeben und
eingeben lassen. Verworfen, weil das Verfahren hier gerade zeigen soll, wie ein Dienstleister für Gerätebindung
eingebunden wird, ohne dem Nutzer ein weiteres Geheimnis abzuverlangen.

**Zweite erwogene Alternative**: `docs/04-orchestrierung.md` Abschnitt 8 wörtlich nehmen („nur
Faktoren melden, die dem Server nachweisbar sind") und nur `{possession}` melden; der
Biometrie-Weg würde dann bei `loa1` landen. Verworfen, obwohl sie strenger und in einem Punkt richtiger ist:
Wie entsperrt wurde, kann kein Server sehen. Eine strengere Regel hätte aber zur Folge, dass dieselbe Geste
in zwei Verfahren unterschiedlich viel kostet, ohne dass der Nutzer den Grund sieht — und dass
`auth_device`, das `inherence` von Anfang an aus derselben Client-Angabe meldet, zur unerklärten
Ausnahme würde.

**Was das kostet**: Die Ausnahme von der „nur nachweisbare Faktoren"-Regel gilt damit für
zwei Verfahren. Sie steht deshalb dort als **Ausnahme** notiert, nicht als zwei Einzelfälle. Was
bei `kobil` dagegen stärker belegt ist als überall sonst: Der Besitzfaktor beruht auf einer
Assertion, die das Backend selbst beim Anbieter einlöst, nicht auf einer Client-Signatur (ADR-23).

Zwei Dinge, die aus dieser Entscheidung folgen und im Code als Typ stehen, nicht als Kommentar:
`KobilUnlockCredential` ist ein `sealed interface` (Gerätegeheimnis **oder** Passwort — beides oder
nichts ist nicht konstruierbar) und trägt seine Faktorart selbst. Und das Kontopasswort meldet
`pin`, nie `password`: Ein amr-Eintrag dieses Namens würde dem Lauf über
`findActiveMethod(accountId, "password")` den Enrollment-Datensatz der echten Passwortmethode
anhängen und sie damit doppelt zählen.

---

## ADR-22: Der verwahrte PIN liegt im Klartext — Demo-Rahmen, benannt statt verschwiegen

**Entscheidung** (**umgesetzt**): `auth_kobil.enrollment.pin` ist eine Klartextspalte. Ein Hash ist
ausgeschlossen, weil der Wert herausgegeben werden muss; verschlüsselt wird er nicht.

**Erwogene Alternative**: AES-256-GCM unter einem in `dpop.secrets` konfigurierten Schlüssel.
Verworfen für diese Demo: Sie schützt gegen einen gestohlenen Datenbankstand, nicht gegen Zugriff
auf den Anwendungsprozess — und der simulierte Anbieter (`kobil_mock`) hält denselben PIN ohnehin im
Klartext, so wie das echte KOBIL es tun müsste. Verschlüsselung auf nur einer der beiden Seiten
sähe nach Schutz aus, ohne einer zu sein.

**Zweite erwogene Alternative**: Den PIN pro Anmeldung neu setzen (KOBIL kann das) und danach
verwerfen. Reizvoll, weil dann nichts dauerhaft gespeichert bleibt. Es hängt aber jeden Login an den
Management-Pfad des Anbieters und öffnet ein Zeitfenster, in dem PIN-Wechsel und SDK-Login
einander überholen können.

**Kosten**: Die H2-Konsole bleibt im Projekt bewusst offen, und ihr Kommentar zählt auf, was dort
lesbar ist. Diese Liste wächst um „jeder lebende KOBIL-PIN". Vertretbar nur, solange es so
dasteht. Denselben Fall gibt es im Projekt schon: `AccountKeycloakKeypair.privateKeyJwk` („Demo-only:
plaintext, not encrypted at rest"). Verwandt, aber nicht dasselbe:
`docs/ideen/verschluesselung-differenzierte-aufbewahrung.md` entwirft Envelope Encryption für das
Claim-Log — einem Vorhaben, dem hier nicht vorgegriffen wird.

Zusätzlich liegen während einer laufenden Einrichtung PIN **und** Unlock-Secret im Klartext in
`auth_kobil.enroll_tool_session` — dort absichtlich, damit ein Neuladen der Seite den Ablauf nicht abbricht,
und mit der 24-Stunden-Frist des `AuthKobilRetentionJob` als Gegengewicht.

---

## ADR-23: Der Client trägt eine Einmalkennung, nicht die Assertion

**Entscheidung** (**umgesetzt**): Bei `auth-kobil` läuft der Nachweis nicht durch den Client. Die
App erhält vom KOBIL-SDK nur ein One-Time-Password; die Geräte-Assertion samt Gerätekennung und
Risikosignalen löst das Backend selbst beim Anbieter ein.

**Erwogene Alternative**: Die Assertion (signiert) durch den Client weiterreichen und serverseitig
prüfen — das Muster von `auth_device`s `device-proof+jwt`. Funktioniert, verlangt aber ein
Vertrauensanker- und Signaturformat, das die öffentliche KOBIL-Dokumentation nicht nennt. Es hier
zu erfinden würde bedeuten, ein selbst gebautes Format als das echte auszugeben.

**Kosten und Gewinn**: Eine Server-zu-Server-Abhängigkeit im Anmeldepfad — ist der Anbieter nicht
erreichbar, ist das Verfahren nicht nutzbar. Dafür kann ein manipulierter Client hier nichts
behaupten: Er kann eine Kennung zurückhalten oder wiederholen, und beides endet in derselben
Antwort („Bestaetigung nicht erkannt"), weil eine Assertion genau einmal einlösbar ist.

Zwei Folgen, die im Ablauf sichtbar sind: Die Gerätekennung wird **bei KOBIL erfragt**, nie vom
Client übernommen — sie ist der Vergleichsanker jeder späteren Anmeldung. Und eine Risiko-Ablehnung
trägt einen **eigenen** Fehlergrund („Geraet als unsicher gemeldet"), weil das keine Verwechslung
des Nutzers ist, sondern eine Aussage über das Gerät; in einem „nicht erkannt" würde ein echter
Befund verschwinden. Bewusst in Kauf genommen: Diese Ablehnung belastet den Login-Throttle wie ein falsches
Passwort, ein gerootetes Telefon kann seinen Besitzer also aussperren.

---

## ADR-24: Eine Methode hängt von einer anderen ab, indem sie deren Angabe verlangt

**Entscheidung** (**umgesetzt**): Abhängigkeiten zwischen Verfahren brauchen keine eigenen
Begriffe. Ein Modul schreibt beim Einrichten einen Claim, ein anderes verlangt ihn per
`ClaimRequirement` — und `requires` entscheidet damit nicht mehr nur über das **Angebot**, sondern
gilt **dauerhaft**: Fällt die Angabe weg, fällt das Credential, das sie verlangte, mit. Das wirkt
weiter über alles, was seinerseits daran hängt, bis sich nichts mehr ändert
(`JourneyActionExecutor.dependentsOfLostClaims`).

Die heute lebende Kette ist die Adresse: `enroll-password` verlangt `ClaimRequirement(EMAIL,
PROVEN)`, also nimmt eine zurückgenommene Adresse das Passwort mit. `enroll-password` behauptet
zusätzlich `PASSWORD_EXISTS` — ein Claim, den derzeit **niemand** verlangt. Er bleibt trotzdem
deklariert: Mit ihm ließe sich eine Abhängigkeit vom Passwort ausdrücken, und die Alternative wäre,
beim nächsten Bedarf einen zweiten Mechanismus daneben zu stellen.

Damit das überhaupt greifen kann, hat der Widerruf einen dritten Auslöser bekommen: ein
Attribut lässt sich jetzt **direkt** zurücknehmen (`AccountService.retractAttribute`,
`DELETE /channels/{id}/attributes/{attribute}`). Vorher konnte eine bestätigte Adresse gar nicht
verloren gehen — `confirm-email` schreibt seine Angabe als ATTESTATION, also ohne
`auth_method_id`, und EMAIL gehört ohnehin dem Konto selbst; kein Methodenwiderruf erreichte sie.

**Erwogene Alternative**: Eine eigene Descriptor-Eigenschaft `dependsOnMethods: Set<String>`, die
Methodennamen nennt. Zuerst so gebaut und wieder zurückgenommen. Sie hätte dasselbe für den
direkten Fall geleistet, aber eine zweite Abhängigkeitssprache neben dem Claim-Modell eingeführt —
und die Kette über die Adresse hätte sie gar nicht erfasst, weil dort keine Methode beteiligt ist.

**Zurückgenommen**: `enroll-kobil` verlangte zunächst `PASSWORD_EXISTS`, das Kontopasswort war also
Pflicht für eine KOBIL-Bindung. Das war eine Verfahrensentscheidung, keine technische — und die
falsche: Es machte ein Verfahren von einem anderen abhängig, ohne dass der Ablauf das verlangt, und
sperrte KOBIL aus jeder Registrierung aus, die noch kein Passwort angelegt hatte. Der Mechanismus
blieb, die Kopplung fiel. Was an ihre Stelle trat, steht in ADR-21: `auth-kobil` bietet nur die
Entsperrwege an, die es für dieses Credential wirklich gibt.

**Zweite erwogene Alternative**: Beim Widerruf einfach alle aktiven Methoden neu gegen ihr
`requires` prüfen. Genau das passiert — nur muss die Vorschau dafür mit dem Schreibvorgang übereinstimmen: Welche
Typen eine widerrufene Instanz mitnimmt, ermittelt `AccountService.claimedTypesOf` mit **derselben**
Abfrage und demselben `MethodModule`-Filter wie der Widerruf selbst. Eine Vorhersage, die vom
Schreibvorgang abweicht, wäre schlimmer als keine.

**Was das kostet:**
- `requires` bedeutet jetzt mehr als vorher. Jede bestehende Angabe erbt die neue Semantik —
  heute unkritisch, weil die einzige Angabe auf einen Anker (`EMAIL`) nur über den neuen,
  ausdrücklichen Widerrufs-Endpunkt verloren gehen kann, nie versehentlich. Der Ankertausch
  („anker-ersetzt") behauptet im selben Zug neu und löst deshalb nichts aus.
- Ein Attribut zurückzunehmen hat damit weite Folgen: Die Adresse nimmt das Passwort mit. Das
  ist gewollt und wird vorher geprüft — die Mindestniveau-Prüfung rechnet **mit** allem, was
  mitfällt, und die Ablehnung nennt es beim Namen.
- `AttributeType` trägt mit `PASSWORD_EXISTS` erstmals eine Aussage, die nichts über die **Person**
  sagt, sondern über die Credentials des Kontos. Bewusst dort und nicht in einem zweiten
  Mechanismus: Das Claim-Log verwaltet ohnehin genau die Lebensdauer, um die es hier geht.

## ADR-25: Die Keycloak-Konfiguration steht im Realm, nicht in der Container-Umgebung

**Entscheidung** (umgesetzt): Die Keycloak-Extension liest ihre Laufzeitkonfiguration aus den
Config-Properties der `orchestrator`-Komponente des Realms (`OrchestratorSettings`), nicht mehr aus
Umgebungsvariablen des Keycloak-Containers. Fehlt die Komponente oder ein Wert, scheitert der
Aufruf sofort und sichtbar — es gibt keinen Fallback.

Vorgelagert dazu beschreibt **ein** Wertobjekt die ganze Umgebung (`KeycloakSetup`, gewählt über
`keycloak-setup.variant`): aus ihm speist sich sowohl der Realm-Aufbau durch die Migration als auch
der laufende Betrieb des Orchestrators (Account-Sync, Peer-Auth-JWKS, OIDC-Prüfung).

**Warum**: Aufbau und Betrieb meinen dasselbe Realm und dieselben Clients. Solange Migration und
Laufzeit ihre Werte aus getrennten Quellen zogen — `System.getenv` im Migrationsskript, Env-Vars am
Keycloak-Container, Spring-Properties am Orchestrator —, genügte ein Tippfehler für „Migration gegen Realm A,
Sync gegen Realm B", und der Fehler zeigte sich erst als 401 tief im Betrieb. Zugleich war
die Extension-Konfiguration im laufenden Keycloak nirgends sichtbar: weder in der Admin-Console
noch im Realm-Export.

**Zwei Hälften, eine Konsequenz**: `RealmSetup` ist, was ins Realm geschrieben wird;
`KeycloakAccess` nur, wie man das fertige Realm erreicht. Ändert sich ein Wert der ersten Hälfte,
baut der `MigrationRunner` das Realm neu auf — dieselbe Folge wie bei einer geänderten
Migrationsdatei, und aus demselben Grund: Die erledigten Schritte fassen den Wert nie wieder an,
das Realm würde unbemerkt mit dem alten weiterlaufen. Das Migrationsskript bekommt deshalb
ausschließlich `RealmSetup` zu sehen — ein Schritt kann gar nicht erst einen Wert verbauen, den der
Reset nicht überwacht.

### Kein geteiltes Geheimnis mehr

Im selben Zug entfallen die Client-Secrets: `orchestrator-admin` und `orchestrator-app-token`
authentifizieren sich per **`private_key_jwt`** (RFC 7523, die in ADR-9 erwogene Härtung). Der
Orchestrator signiert jeden Token-Request mit seinem eigenen Schlüssel, Keycloak holt den
öffentlichen Teil unter `jwks.url` ab — spiegelbildlich zu der Assertion, mit der sich Keycloak
beim Orchestrator ausweist (ADR-7). Beide Richtungen tragen damit dasselbe Prinzip, und in
Konfiguration, Compose-Datei und Realm steht kein geteiltes Geheimnis mehr.

Beide Signaturschlüssel liegen jetzt **in einer Datenbank** statt im Prozessspeicher: der des
Orchestrators in `orchestrator.node_signing_key`, der der Extension als Property der
`orchestrator`-Komponente, also in Keycloaks eigener DB. Vorher entstand je Seite ein Paar pro
JVM-Lauf — das reichte nur, solange genau ein Knoten lief: Ein zweiter hätte mit einem Schlüssel
signiert, den das JWKS des ersten nie nennt.

**Kosten**, bewusst getragen:

- Ohne die `orchestrator`-Komponente läuft die Extension nicht. Das ist gewollt: Ein
  stillschweigender Standardwert wäre genau die Fehlkonfiguration, die vorher erst am seltsamen
  Verhalten eines Logins auffiel.
- Ein geänderter Wert wirft die Keycloak-Seite weg und baut sie neu. Verkraftbar, weil die
  Orchestrator-DB keine Keycloak-Ids speichert — die Nutzer entstehen über
  `orchestratorAccountId` beim nächsten Sync neu.
- Keycloak muss den Orchestrator erreichen können, um dessen JWKS zu holen — dieselbe Strecke, die
  die Extension ohnehin für jeden kc-facade-Aufruf braucht (`orchestratorBaseUrl`), also keine
  neue Abhängigkeit, aber eine zweite Stelle, an der sie sichtbar wird.
- Die privaten Schlüssel liegen im Klartext in ihrer jeweiligen Datenbank — derselbe Demo-Rahmen,
  den ADR-22 für den verwahrten PIN benennt.

---

---

## Erkannte, bewusst zurückgestellte Verbesserungen

Bekannte Befunde, die bewusst **nicht** vollständig umgesetzt sind — jeder davon ist eine
Architektur- oder Infrastrukturentscheidung, kein Fix, der an einer Stelle abzuschließen wäre:

- **`orchestrator.dpop_proof_replay`-Skalierung** (siehe auch [09-dpop.md](09-dpop.md) Abschnitt 2): Der
  Schlüssel ist seit ADR-14 ein fester SHA-256-Hash. Offen bleibt die Zeitpartitionierung bzw. ein
  separater persistenter KV-Store — eine Entscheidung für den Produktivstack.
- **Konto-Lebenszyklus und Merge-Pfad**: `Account` kennt keinen Status und kein
  `merged_into`. ADR-11 weist einen `person_id`-Konflikt
  bewusst ab, statt zu mergen — über die angestrebte Lebensdauer wird ein Merge aber
  zwangsläufig nötig, und ohne `merged_into` gibt es dann keinen verlustfreien Weg dorthin.

Beide verdienen einen eigenen, sorgfältig geplanten Durchgang mit Entwurfsentscheidung vorab.
