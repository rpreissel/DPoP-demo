# ADR-7: Web-Kanal ohne mTLS, signierte Request-Assertion statt Client-Zertifikat

**Status**: umgesetzt.

**Entscheidung**: Die Verbindung von Keycloak zum Orchestrator im Web-Kanal, also von Server zu
Server, wird **ohne mTLS** abgesichert. Keycloak legt jeder Anfrage eine signierte Assertion bei, und
der Orchestrator prüft sie (`PeerAuthValidator`, [05-api.md](../05-api.md) Abschnitt 3). Der Browser
erreicht den Orchestrator nirgends direkt.

Es gibt genau ein JWT je Anfrage, nicht ein Access-Token mit einem getrennten Nachweis dazu. Bei der
ersten Anmeldung gibt es nämlich noch kein `sub`. Die Assertion sagt deshalb nur: „Ich handle für
diesen Kanal-Anker; der Nutzer ist vielleicht noch unbekannt.“

Signiert wird mit einem **eigenen Schlüsselpaar** der Keycloak-Erweiterung, nicht mit den Schlüsseln,
mit denen der Realm Tokens signiert. Die Erweiterung veröffentlicht den öffentlichen Schlüssel unter
`/realms/{realm}/orchestrator-jwks/.well-known/jwks.json` (`OrchestratorJwksResourceProvider`); der
Orchestrator holt ihn von dort.

**Grundsatz: Signatur statt gemeinsames Geheimnis.** Wo zwei Server einander vertrauen müssen,
beweist die eine Seite ihre Identität mit einer Signatur, deren öffentlichen Schlüssel die andere
Seite über eine JWKS-URL abruft. Ein gemeinsames Geheimnis, das beide Seiten kennen und das man
verteilen und schützen müsste, gibt es nicht. Dieser ADR legt den Grundsatz für die Richtung Keycloak
→ Orchestrator fest. [ADR-9](ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md)
wendet ihn auf die Richtung Orchestrator → Keycloak beim Ausstellen von Tokens an,
[ADR-25](ADR-025-die-keycloak-konfiguration-steht-im-realm-nicht-in.md) auf die Verwaltung des Realms.

**Erwogene Alternativen**:

- **mTLS** zwischen Keycloak und Orchestrator: Beide Seiten prüfen die Zertifikate der jeweils anderen
  schon beim Verbindungsaufbau.
- **Token Exchange**: unnötig. Keycloak hält die Sitzung ohnehin und kann die Assertion im eigenen
  Prozess ausstellen.
- **Keycloak hält stellvertretend für das Gerät einen DPoP-Schlüssel**: verworfen. Der Wert von DPoP
  liegt darin, dass der Schlüssel nicht exportierbar auf einem Client liegt, dem man nicht vertraut.
  Hält ein Server ihn, ist er praktisch ein gemeinsames Geheimnis, nur mit mehr Aufwand. Ein solcher
  Schlüssel je Nutzer wäre zudem fatal: `DeviceAccountLink` würde bei jeder Anmeldung im Web zutreffen.

**Begründung**: mTLS bringt Aufwand im Betrieb: Zertifikate für zwei Serverdienste müssen verteilt,
regelmäßig erneuert und bei Bedarf widerrufen werden. Eine signierte Assertion braucht das nicht;
auch ihr Schlüssel wird über eine JWKS-URL bereitgestellt. Ein eigenes Schlüsselpaar trennt die
Signatur zwischen den Servern von der Signatur der Tokens. Weil der Browser den Orchestrator nie direkt
erreicht, bleibt die Angriffsfläche auf die eine Verbindung zwischen den beiden Servern beschränkt.

**Folgen und Kosten**: Die Sicherheit dieser Verbindung hängt ganz an der Prüfung der Signatur in der
Anwendung. Mit mTLS wären die Identität der Gegenseite und die Verschlüsselung schon beim
Verbindungsaufbau erzwungen. Ein übernommenes Keycloak kann jeden Nutzer nachahmen. Das liegt aber
schon daran, dass Keycloak hier an erster Stelle steht; auch mTLS würde daran nichts ändern.

**Geschichte**: Ursprünglich war angenommen, die Assertion werde mit den Token-Schlüsseln des Realms
geprüft; umgesetzt wurde von Anfang an ein eigenes Schlüsselpaar. Eine Zeit lang gab es eine Ausnahme
vom Grundsatz „kein direkter Zugriff aus dem Browser“: `KcMeController` las für die Testoberfläche das
eigene Journey-Log mit einem echten AccessToken. Seit das Journey-Log nur noch auf der Admin-Seite
steht, ist er entfernt (2026-09-23).
