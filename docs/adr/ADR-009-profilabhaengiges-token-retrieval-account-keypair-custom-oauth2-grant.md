# ADR-9: Profilabhängiges Token-Retrieval — Account-Keypair + custom OAuth2-Grant statt geteiltem Admin-Secret

**Entscheidung** (umgesetzt, DPoP-demo-xso): `GET .../{channelSessionId}/token` ist **`APP`-Kanal-
only** — ein `KEYCLOAK`-Kanal hat nie einen `AuthContext` und braucht auch keinen: dessen Client
hält bereits echte Keycloak-Tokens aus dem Browser-Login (`dpop-demo-web`) und erneuert sie direkt
gegen Keycloak (`ChannelService.getToken` weist `KEYCLOAK` explizit mit `409` ab). Für `APP` bleibt
der Endpunkt im Default-Profil das Mock-JWT (`MockTokenProvider`, delegiert an `TokenService`); im `keycloak`-Profil liefert er einen
echten, von Keycloak signierten AccessToken (`KcTokenProvider`, [05-api.md](../05-api.md) Abschnitt
2) — gemeinsame `TokenProvider`-Schnittstelle, Umschaltung ausschließlich über Spring-Profile.

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
Client `orchestrator-app-token` (Abschnitt „V8“ in `keycloak-migrations/.../V1__realm.kc.kts`) ohne jedes Recht, nicht als `orchestrator-admin` -
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
(`personId`/`kvnr`/`versnr`/`geburtsdatum`/`strasse`/`plz`/`ort`; `strasse` ist die ganze
Straßenzeile) — für ein gebundenes Konto nur die Werte des Personenverzeichnisses (seit ADR-34 ohne
Rückfall auf alte Claims), für einen Interessenten der stärkste bestätigte Claim des Kontos (ADR-18:
ein voll bestätigter Interessent trägt NAME/VORNAME/GEBURTSDATUM und die Adressattribute auch ohne
Registerbindung); die
Platzhalternamen bleiben nur für Konten ohne beides („Enrollment zuerst"). `personId`/`kvnr`/`versnr`
existieren nur mit Registerbindung — ein Interessent zeigt sich im Fehlen von `personId`, ein Partner
im Fehlen von `versnr` (ADR-34), nie in einem gepflegten Status-Flag.
Der custom Grant-Type in `keycloak-extension/` (`urn:dpop-demo:account-token`,
`AccountTokenGrantType`, Keycloaks pluggable `OAuth2GrantType`-SPI in
`keycloak-server-spi-private`) verlangt zusätzlich eine damit signierte, kurzlebige Assertion
(`sub`=accountId, `aud`=Grant-URN, `iat` Pflicht, `exp - iat` <= 60s, geprüft in
`AccountAssertionTimes`) und stellt erst dann einen echten AccessToken aus. Die Obergrenze prüft
der Grant selbst, nicht nur der Aussteller: Ein abgeflossener Account-Schlüssel kann so keine
langlebige Assertion prägen.

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
Lebenszyklus (erzeugt bei Sync, gelöscht bei `AccountDeleted`, [07-betrieb.md](../07-betrieb.md)
Abschnitt 3) sowie ein projektspezifisches Stück Keycloak-Erweiterung, das bei jedem
Keycloak-Upgrade gegen die `server-spi-private`-Schnittstelle mitgeprüft werden muss.
Demo-only: Der Private Key liegt unverschlüsselt in der Datenbank.

---
