# ADR-9: Profilabhängiger Token-Abruf — eigener OAuth2-Grant, den nur der Orchestrator aufrufen darf

**Status**: umgesetzt (DPoP-demo-xso); 2026-09-26 neu gefasst (zweite Bewertung, F-1, F-5, F-6).

**Entscheidung**: Braucht der App-Kanal ein echtes AccessToken von Keycloak, stellt Keycloak es über
einen **eigenen OAuth2-Grant** aus (`urn:dpop-demo:account-token`, `AccountTokenGrantType` in
`keycloak-extension`).

- **Nur der Orchestrator darf ihn aufrufen.** Der Grant akzeptiert ausschließlich einen vertraulichen
  Client mit dem Attribut `dpop-demo.account-token-grant` (`AccountTokenGrantClients`); die
  Keycloak-Migration V4 setzt es am Client `orchestrator-app-token`. Jeder andere Client, auch der
  öffentliche Browser-Client, bekommt `unauthorized_client`.
- **Der Orchestrator weist sich mit einer Signatur aus, nicht mit einem Geheimnis.** Der Client meldet
  sich per `private_key_jwt` an ([ADR-25](ADR-025-die-keycloak-konfiguration-steht-im-realm-nicht-in.md)):
  Keycloak prüft die Client-Assertion gegen das JWKS des Orchestrators. Das ist der Grundsatz
  „Signatur statt gemeinsames Geheimnis“ aus
  [ADR-7](ADR-007-web-kanal-ohne-mtls-signierte-request-assertion-statt.md), angewendet auf die
  Richtung Orchestrator → Keycloak.
- **Konto, `acr` und `amr` kommen als Parameter** (`account_id`, `acr`, `amr`). Niveau und Nachweise
  legt allein der Orchestrator fest; der Grant schreibt sie in die Notes der Keycloak-Sitzung, und
  `OrchestratorAcrAmrMapper` schreibt sie von dort ins AccessToken.
- **Ein Client ohne Rechte**: `orchestrator-app-token` hat keinerlei Rechte, ausdrücklich nicht die von
  `orchestrator-admin`.

**Annahme** (zweite Bewertung, F-5): Keycloak holt das JWKS des Orchestrators über dessen
`orchestratorBaseUrl`. Außerhalb des Demomodus muss dieser Weg https mit geprüftem Zertifikat sein –
sonst könnte, wer ihn kontrolliert, eigene Schlüssel unterschieben und sich als Orchestrator anmelden.
`ProductionModeCheck` verweigert andernfalls den Start ([07-betrieb.md](../07-betrieb.md) Abschnitt 3c).

Wann der Endpunkt `GET .../token` ein simuliertes und wann ein echtes Token liefert, wann erneuert und
wann neu ausgestellt wird, beschreibt [05-api.md](../05-api.md) Abschnitt 2 („AccessToken“). Dass ein
Step-up die zwischengespeicherten Tokens verwirft, regelt
[ADR-15](ADR-015-nachweise-und-ausgestellte-tokens-in-getrennten-tabellen.md). Welche Werte der
Abgleich außerdem nach Keycloak spiegelt, steht in [07-betrieb.md](../07-betrieb.md) Abschnitt 3a.

**Erwogene Alternativen**:

- **Ein gemeinsames Admin-Secret**: Ein Service-Account holt per Token Exchange oder Impersonation
  direkt ein Token für einen beliebigen Nutzer. Verworfen, denn mit einem gestohlenen Secret ließe
  sich für jedes Konto ein Token ausstellen.
- **Zusätzlich eine Assertion je Konto** (so umgesetzt bis 2026-09-26): Der Orchestrator signierte
  jede Grant-Anfrage mit einem Schlüsselpaar je Konto (`orchestrator.keycloak_keypair`, öffentlicher
  Schlüssel als Keycloak-Credential), damit „ein verlorener Schlüssel höchstens ein Konto betrifft“.
  Gestrichen (zweite Bewertung, F-6), weil das nicht galt: Alle Kontoschlüssel lagen im Klartext in
  derselben Datenbank wie der Client-Schlüssel des Orchestrators, ein HSM ist nicht geplant – wer einen
  hat, hat alle. Der Preis waren ein Datensatz je Konto, ein Credential je Keycloak-Nutzer und eine
  Reihenfolge zwischen Schlüsselerzeugung und erstem Grant-Aufruf. Vor F-1 war diese Assertion das
  einzige Tor, weil der Grant den aufrufenden Client nicht prüfte; seitdem ist der angemeldete Client
  der Vertrauensanker.
- **Nur die umgekehrte Richtung** (Keycloak ruft den Orchestrator auf, nie umgekehrt): verworfen, weil
  `GET .../token` sofort antworten muss.

**Begründung**: Das Tor ist der Client, nicht das Konto: Wer den Client-Schlüssel des Orchestrators
hat, könnte ohnehin jedes Konto bedienen. Der Grant wird nur bei der ersten Ausstellung oder bei einer
tatsächlichen Änderung von `acr` oder `amr` gebraucht; sonst erneuert Keycloaks eigenes
`refresh_token` das Token.

**Grenze der DPoP-Bindung** (entschieden 2026-09-25, Review M-3): Die ausgestellten Keycloak-Tokens
sind Bearer-Tokens ohne `cnf.jkt`. Die DPoP-Bindung endet an `GET …/token`; ein abgegriffenes
AccessToken ist bis zu seinem Ablauf ohne Schlüssel nutzbar. Gebunden bleibt die Sitzung: Neue Tokens
gibt es nur mit gültigem DPoP-Proof, das RefreshToken verlässt das Backend nie
([09-dpop.md](../09-dpop.md) Abschnitt 4).

**Folgen und Kosten**: Der Grant ist ein projekteigenes Stück Keycloak-Erweiterung auf der
Schnittstelle aus `keycloak-server-spi-private`; bei jedem Keycloak-Update muss es mitgeprüft werden.
Der Client-Schlüssel des Orchestrators (`orchestrator.node_signing_key`) liegt in der Demo im Klartext in
der Datenbank ([ADR-22](ADR-022-der-verwahrte-pin-liegt-im-klartext-demo-rahmen.md)).

**Geschichte**: Die Anmeldung der übrigen Clients mit `private_key_jwt` statt `client_secret` war hier
als spätere Härtung zurückgestellt; umgesetzt ist sie mit
[ADR-25](ADR-025-die-keycloak-konfiguration-steht-im-realm-nicht-in.md). Früher beschrieb dieser ADR
auch den Profilschalter und die gespiegelten Attribute; beides steht jetzt in den oben genannten
Kapiteln.
