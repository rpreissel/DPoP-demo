# ADR-9: Profilabhängiger Token-Abruf — Schlüsselpaar je Konto und eigener OAuth2-Grant statt geteiltem Admin-Secret

**Status**: umgesetzt (DPoP-demo-xso).

**Entscheidung**: Braucht der App-Kanal ein echtes AccessToken von Keycloak, stellt Keycloak es über
einen **eigenen OAuth2-Grant** aus (`urn:dpop-demo:account-token`, `AccountTokenGrantType` in
`keycloak-extension`). Der Orchestrator weist sich dabei nicht mit einem Admin-Secret aus, sondern
mit einer kurzlebigen Assertion, die er mit einem **Schlüsselpaar je Konto** signiert. Das ist der
Grundsatz „Signatur statt gemeinsames Geheimnis“ aus
[ADR-7](ADR-007-web-kanal-ohne-mtls-signierte-request-assertion-statt.md), angewendet auf die Richtung
Orchestrator → Keycloak.

- **Schlüsselpaar je Konto**: Beim Abgleich eines Kontos mit Keycloak erzeugt der Orchestrator ein
  eigenes, asymmetrisches Schlüsselpaar (EC P-256, `orchestrator.keycloak_keypair`). Den öffentlichen
  Schlüssel legt er als echtes Keycloak-`Credential` am Nutzer ab (Typ `orchestrator-public-key`,
  geschrieben über `AccountPublicKeyResource` unter `/admin/realms/{realm}/orchestrator-keys/{accountId}`).
  Bewusst nicht als Attribut: Material, mit dem jemand einen Besitz nachweist, gehört in den Speicher
  für Credentials.
- **Die Assertion**: `sub` ist die accountId, `aud` die URN des Grants, `iat` ist Pflicht, und
  `exp - iat` darf höchstens 60 Sekunden betragen. Diese Obergrenze prüft der Grant selbst
  (`AccountAssertionTimes`), nicht nur der Aussteller. So kann auch ein abhandengekommener Schlüssel
  keine langlebige Assertion erzeugen. Die Assertion trägt `acr` und `amr` als signierte Claims; der
  Grant kopiert sie in die Notes der Keycloak-Sitzung, und `OrchestratorAcrAmrMapper` schreibt sie
  von dort ins AccessToken.
- **Ein Client ohne Rechte**: Der Orchestrator meldet sich dabei als Client `orchestrator-app-token`
  an, der keinerlei Rechte hat, ausdrücklich nicht als `orchestrator-admin`.

Wann der Endpunkt `GET .../token` ein simuliertes und wann ein echtes Token liefert, wann erneuert und
wann neu ausgestellt wird, beschreibt [05-api.md](../05-api.md) Abschnitt 2 („AccessToken“). Dass ein
Step-up die zwischengespeicherten Tokens verwirft, regelt
[ADR-15](ADR-015-nachweise-und-ausgestellte-tokens-in-getrennten-tabellen.md). Welche Werte der
Abgleich außerdem nach Keycloak spiegelt, steht in [07-betrieb.md](../07-betrieb.md) Abschnitt 3a.

**Erwogene Alternativen**:

- **Ein gemeinsames Admin-Secret**: Ein Service-Account holt per Token Exchange oder Impersonation
  direkt ein Token für einen beliebigen Nutzer. Verworfen, denn mit einem gestohlenen Secret ließe
  sich für jedes Konto ein Token ausstellen.
- **Nur die umgekehrte Richtung** (Keycloak ruft den Orchestrator auf, nie umgekehrt): verworfen, weil
  `GET .../token` sofort antworten muss.

**Begründung**: Der Schlüssel gilt nicht je Client oder Server, sondern je Konto, denn genau diesen
Schaden soll er begrenzen: Ein verlorener Schlüssel betrifft höchstens ein Konto. Die Assertion wird
nur bei der ersten Ausstellung oder bei einer tatsächlichen Änderung von `acr` oder `amr` gebraucht;
sonst erneuert Keycloaks eigenes `refresh_token` das Token.

**Grenze der DPoP-Bindung** (entschieden 2026-09-25, Review M-3): Die ausgestellten Keycloak-Tokens
sind Bearer-Tokens ohne `cnf.jkt`. Die DPoP-Bindung endet an `GET …/token`; ein abgegriffenes
AccessToken ist bis zu seinem Ablauf ohne Schlüssel nutzbar. Gebunden bleibt die Sitzung: Neue Tokens
gibt es nur mit gültigem DPoP-Proof, das RefreshToken verlässt das Backend nie
([09-dpop.md](../09-dpop.md) Abschnitt 4).

**Folgen und Kosten**: Es gibt einen weiteren Datensatz je Konto (`orchestrator.keycloak_keypair`) mit
eigenem Lebenszyklus: Er entsteht beim Abgleich und wird bei `AccountDeleted` gelöscht
([07-betrieb.md](../07-betrieb.md) Abschnitt 3). Der Grant ist ein projekteigenes Stück
Keycloak-Erweiterung auf der Schnittstelle aus `keycloak-server-spi-private`; bei jedem Keycloak-Update
muss es mitgeprüft werden. Der private Schlüssel liegt in der Demo im Klartext in der Datenbank
([ADR-22](ADR-022-der-verwahrte-pin-liegt-im-klartext-demo-rahmen.md)).

**Geschichte**: Die Anmeldung der übrigen Clients mit `private_key_jwt` statt `client_secret` war hier
als spätere Härtung zurückgestellt; umgesetzt ist sie mit
[ADR-25](ADR-025-die-keycloak-konfiguration-steht-im-realm-nicht-in.md). Früher beschrieb dieser ADR
auch den Profilschalter und die gespiegelten Attribute; beides steht jetzt in den oben genannten
Kapiteln.
