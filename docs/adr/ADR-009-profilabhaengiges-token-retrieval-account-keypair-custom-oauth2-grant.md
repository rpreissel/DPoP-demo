# ADR-9: Profilabhängiger Token-Abruf — Schlüsselpaar je Konto und eigener OAuth2-Grant statt geteiltem Admin-Secret

**Entscheidung** (umgesetzt, DPoP-demo-xso): `GET .../{channelSessionId}/token` gibt es **nur für den
`APP`-Kanal**. Ein `KEYCLOAK`-Kanal hat nie einen `AuthContext` und braucht auch keinen: Sein Client
hält schon echte Tokens von Keycloak aus der Anmeldung im Browser (`dpop-demo-web`) und erneuert sie
direkt bei Keycloak. `ChannelService.getToken` weist `KEYCLOAK` deshalb ausdrücklich mit `409` ab.

Für `APP` liefert der Endpunkt je nach Spring-Profil etwas anderes. Hinter derselben Schnittstelle
`TokenProvider` stehen zwei Umsetzungen, und umgeschaltet wird ausschließlich über das Profil:

- Im Standardprofil liefert er ein simuliertes JWT (`MockTokenProvider`, das an `TokenService`
  weitergibt).
- Im `keycloak`-Profil liefert er ein echtes, von Keycloak signiertes AccessToken (`KcTokenProvider`,
  [05-api.md](../05-api.md) Abschnitt 2).

**Step-up und Verlängerung.** Macht dasselbe Konto einen Step-up, wird in derselben Sitzung bei
Keycloak ein neues Token ausgestellt. `AccountTokenGrantType` markiert dazu das von ihm angelegte
`UserSessionModel` mit einer Session-Note und findet es darüber später wieder. Wie lange diese
Sitzung lebt, bestimmen die Einstellungen `SSO Session Idle` und `SSO Session Max` des Realms. Der
Grant gibt ein undurchsichtiges `refresh_token` aus (`useRefreshToken() == true`). `KcTokenProvider`
nutzt es jedes Mal, wenn nur die Gültigkeit verlängert werden soll
(`KeycloakAdminClient.refreshAccountToken`). Den eigenen Grant für Konto-Tokens nutzt er nur, wenn
sich ACR oder AMR seit der letzten Ausstellung geändert haben.

**Wie ACR und AMR ins Token kommen.** Die Assertion enthält dafür `acr` und `amr` als eigene, signierte
Claims. `AccountTokenGrantType` kopiert sie in dieselben Notes des `UserSessionModel`, die auch
`OrchestratorAuthenticator` nutzt (`orchestrator_acr`/`orchestrator_amr`). Von dort schreibt der
`OrchestratorAcrAmrMapper` (Client-Scope `orchestrator-claims`) sie in das echte AccessToken.
`KeycloakAdminClient.requestAccountToken` und `refreshAccountToken` melden sich dabei als Client
`orchestrator-app-token` an (Abschnitt „V8“ in `keycloak-migrations/.../V1__realm.kc.kts`). Dieser
Client hat keinerlei Rechte; es ist ausdrücklich nicht `orchestrator-admin`. Genau darum geht es in
diesem ADR („statt geteiltem Admin-Secret“).

Unabhängig vom Profil gilt: Ändert ein Step-up die `AuthEvidence`
(`AuthEvidenceService.applyEvidence`/`applyEvidenceUpdate`), werden die im `AuthContext`
zwischengespeicherten Tokens verworfen. Sonst würde das Erneuern die alte Sitzung bei Keycloak mit den
alten Notes für ACR und AMR einfach weiter verlängern.

**Das Schlüsselpaar je Konto.** Dafür erzeugt der Orchestrator bei jedem Abgleich eines Kontos mit
Keycloak ein eigenes, asymmetrisches Schlüsselpaar je Konto (`orchestrator.keycloak_keypair`,
EC P-256). Den öffentlichen Schlüssel spiegelt er als echtes `Credential` von Keycloak (Typ
`orchestrator-public-key`, `KeycloakAdminClient.setPublicKeyCredential`) auf den Nutzer in Keycloak.
Bewusst nicht als Attribut: Material, mit dem jemand einen Besitz nachweist, gehört in den Speicher
für Credentials. Geschrieben wird es über eine eigene Erweiterung vom Typ
`AdminRealmResourceProvider` (`AccountPublicKeyResource`, erreichbar unter
`/admin/realms/{realm}/orchestrator-keys/{accountId}`).

**Was außerdem gespiegelt wird.** Der Abgleich spiegelt daneben Namen und Attribute des Nutzers
(`personId`/`kvnr`/`versnr`/`geburtsdatum`/`strasse`/`plz`/`ort`; `strasse` ist die ganze
Straßenzeile):

- Für ein Konto, das einer Person zugeordnet ist, spiegelt er nur die Werte aus dem
  Personenverzeichnis. Seit ADR-34 greift er dabei nicht mehr auf alte Claims zurück.
- Für einen Interessenten spiegelt er den stärksten bestätigten Claim des Kontos. Nach ADR-18 hat ein
  voll bestätigter Interessent NAME, VORNAME, GEBURTSDATUM und die Adressattribute auch ohne
  Zuordnung zu einer Person.
- Die Platzhalternamen bleiben nur für Konten, die weder das eine noch das andere haben (aus dem
  Experiment „Erst Anmeldeverfahren einrichten“).

`personId`, `kvnr` und `versnr` gibt es nur, wenn das Konto einer Person zugeordnet ist. Einen
Interessenten erkennt man am fehlenden `personId`, einen Partner am fehlenden `versnr` (ADR-34). Ein
eigens gepflegtes Statusfeld gibt es dafür nicht.

**Der eigene Grant.** Der eigene Grant-Typ in `keycloak-extension/` (`urn:dpop-demo:account-token`,
`AccountTokenGrantType`, gebaut auf der erweiterbaren `OAuth2GrantType`-SPI aus
`keycloak-server-spi-private`) verlangt zusätzlich eine mit diesem Schlüssel signierte, kurzlebige
Assertion: `sub` ist die accountId, `aud` die URN des Grants, `iat` ist Pflicht, und `exp - iat` darf
höchstens 60 Sekunden betragen (geprüft in `AccountAssertionTimes`). Erst dann stellt er ein echtes
AccessToken aus. Die Obergrenze prüft der Grant selbst und nicht nur der Aussteller der Assertion. So
kann auch ein abhandengekommener Schlüssel eines Kontos keine langlebige Assertion erzeugen.

**Erwogene Alternativen**:

- **Ein einziges gemeinsames Admin-Secret**: Der Service-Account `keycloak-sync` holt per Token
  Exchange oder Impersonation direkt ein Token für einen beliebigen Nutzer. Verworfen, denn mit einem
  gestohlenen Secret ließe sich für JEDES Konto ein Token ausstellen.
- **Nur die umgekehrte Richtung erlauben** (Keycloak ruft den Orchestrator auf, nie umgekehrt):
  verworfen, weil `GET .../token` sofort antworten muss.
- **Anmeldung des Clients mit `private_key_jwt` statt `client_secret`** für die Verbindung zur
  Verwaltung und zum Abgleich selbst (RFC 7523, ohne gemeinsames Geheimnis wie in ADR-7): damals als
  eigene Härtung zurückgestellt, inzwischen umgesetzt, siehe ADR-25.

**Warum das Schlüsselpaar je Konto**: Es überträgt das Prinzip aus ADR-7 (Signatur statt Geheimnis)
auf eine zweite Richtung. Der Schlüssel gilt nicht je Client oder Server, sondern je Konto, denn genau
diesen Schaden soll er begrenzen: Ein verlorener Schlüssel betrifft höchstens ein Konto. Die Assertion
bleibt kurzlebig (`exp` ≤ 60 s) und wird nur bei der ERSTEN Ausstellung oder bei einer tatsächlichen
Änderung von ACR oder AMR gebraucht.

**Kosten**: Es gibt einen weiteren Datensatz je Konto (`orchestrator.keycloak_keypair`) mit eigenem
Lebenszyklus: Er entsteht beim Abgleich und wird bei `AccountDeleted` gelöscht
([07-betrieb.md](../07-betrieb.md) Abschnitt 3). Außerdem gibt es ein projekteigenes Stück
Keycloak-Erweiterung, das bei jedem Keycloak-Update gegen die Schnittstelle aus `server-spi-private`
mitgeprüft werden muss. Nur in der Demo: Der private Schlüssel liegt unverschlüsselt in der
Datenbank.

---
