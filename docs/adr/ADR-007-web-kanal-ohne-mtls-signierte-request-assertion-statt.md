# ADR-7: Web-Kanal ohne mTLS, signierte Request-Assertion statt Client-Zertifikat

> **Stand 2026-09-23:** Die Assertion wird mit einem eigenen Schlüssel signiert, nicht mit den
> Schlüsseln, mit denen der Realm Tokens signiert. Der Browser erreicht den Orchestrator nirgends mehr
> direkt. Siehe Nachtrag.

**Entscheidung** (umgesetzt): Die Verbindung von Keycloak zum Orchestrator im Web-Kanal, also von
Server zu Server, wird **ohne mTLS** abgesichert. Statt eines Client-Zertifikats prüft der
Orchestrator eine signierte Assertion, die Keycloak jeder Anfrage beilegt (`PeerAuthValidator`,
[05-api.md](../05-api.md) Abschnitt 3). Der Browser spricht nie direkt mit dem Orchestrator.

Es gibt ein einziges JWT je Anfrage statt eines Access-Tokens mit einem getrennten Nachweis dazu. Bei
der ersten Anmeldung gibt es nämlich noch kein `sub`. Die Assertion sagt stattdessen einfach: „Ich
handle für diesen Kanal-Anker; der Nutzer ist vielleicht noch unbekannt.“

**Erwogene Alternativen**:

- **mTLS** zwischen Keycloak und Orchestrator: Beide Seiten prüfen die Zertifikate der jeweils
  anderen schon beim Verbindungsaufbau.
- **Token Exchange**: verworfen, weil unnötig. Keycloak hält die Sitzung ohnehin und kann die
  Assertion im eigenen Prozess ausstellen.
- **Keycloak hält stellvertretend für das Gerät einen DPoP-Schlüssel**: verworfen. Der Wert von DPoP
  liegt darin, dass der Schlüssel nicht exportierbar auf einem Client liegt, dem man nicht vertraut.
  Hält ein Server ihn, ist er praktisch ein gemeinsames Geheimnis, nur mit mehr Aufwand. Ein solcher
  Schlüssel je Nutzer wäre zusätzlich fatal: `DeviceAccountLink` würde bei jeder Anmeldung im Web
  zutreffen.

**Warum die signierte Assertion**: mTLS bringt Aufwand im Betrieb mit sich: Zertifikate für zwei
Serverdienste müssen verteilt, regelmäßig erneuert und bei Bedarf widerrufen werden. Eine signierte
Assertion auf der Ebene der Anwendung braucht das nicht; ihre Signatur lässt sich mit denselben
Schlüsseln prüfen, die Keycloak ohnehin für Tokens verwendet. Weil der Browser den Orchestrator nie
direkt erreicht, bleibt die Angriffsfläche auf die eine Verbindung zwischen den beiden Servern
beschränkt.

**Kosten**: Die Sicherheit dieser Verbindung hängt ganz an der Prüfung der Signatur in der Anwendung.
Mit mTLS wären die Identität der Gegenseite und die Verschlüsselung schon beim Verbindungsaufbau
erzwungen. Ein übernommenes Keycloak kann jeden Nutzer nachahmen. Das liegt aber schon daran, dass
Keycloak hier an erster Stelle steht, und auch mTLS würde daran nichts ändern.

**Nachtrag (2026-09-23)**:
- *Schlüssel.* Anders als oben unter „Warum“ angenommen, prüft der Orchestrator die Assertion
  **nicht** mit den Schlüsseln, mit denen der Realm Tokens signiert. Die Keycloak-Erweiterung erzeugt
  je Komponente ein eigenes Schlüsselpaar und veröffentlicht es unter
  `/realms/{realm}/orchestrator-jwks/.well-known/jwks.json` (`OrchestratorJwksResourceProvider`,
  [05-api.md](../05-api.md) Abschnitt 3: „ein Schlüsselpaar pro Client“). Damit ist
  die Signatur zwischen den Servern von der Signatur der Tokens getrennt. Der Vorteil gegenüber mTLS
  bleibt, denn es müssen keine Zertifikate verteilt werden: Auch dieser Schlüssel wird über eine
  JWKS-URL bereitgestellt.
- *Browser → Orchestrator.* Ausnahmen gibt es keine mehr. Früher las `KcMeController`
  (`/orchestrator/api/v1/kc/me`) für die Testoberfläche das eigene Journey-Log mit einem echten
  AccessToken von Keycloak. Seit das Journey-Log nur noch auf der Admin-Seite steht, ist er entfernt.

---
