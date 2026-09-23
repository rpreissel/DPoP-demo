# ADR-7: Web-Kanal ohne mTLS, signierte Request-Assertion statt Client-Zertifikat

> **Stand 2026-09-23:** Die Assertion wird mit einem eigenen Schlüssel signiert, nicht mit den
> Token-Schlüsseln des Realms; der Browser erreicht den Orchestrator an genau einer Demo-Stelle.
> Siehe Nachtrag.

**Entscheidung** (umgesetzt): Die Server-zu-Server-Strecke Keycloak -> Orchestrator im Web-Kanal
wird **ohne mTLS** abgesichert; statt eines Client-Zertifikats verifiziert der Orchestrator eine
signierte Request-Assertion von Keycloak (`PeerAuthValidator`, [05-api.md](../05-api.md) Abschnitt 3).
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

**Nachtrag (2026-09-23)**:
- *Schlüsselmaterial.* Anders als oben unter „Warum“ angenommen, prüft der Orchestrator die
  Assertion **nicht** mit den Token-Schlüsseln des Realms. Die Extension erzeugt je Komponente ein
  eigenes Schlüsselpaar und veröffentlicht es unter
  `/realms/{realm}/orchestrator-jwks/.well-known/jwks.json` (`OrchestratorJwksResourceProvider`,
  [05-api.md](../05-api.md) Abschnitt 3: „ein Schlüsselpaar pro Client“). Das trennt die
  Server-zu-Server-Signatur von der Token-Signatur; der Vorteil gegenüber mTLS — kein
  Zertifikats-Rollout — bleibt, weil auch dieser Schlüssel über eine JWKS-URL verteilt wird.
- *Browser → Orchestrator.* Genau ein Endpunkt ist davon ausgenommen: `KcMeController`
  (`/orchestrator/api/v1/kc/me`, nur Profil `keycloak`) liest für die Test-Oberfläche das eigene
  Journey-Log mit einem echten Keycloak-AccessToken. Er ist rein lesend, gehört nicht zur
  kc-Facade und trägt keine Login- oder Tool-Ausführung.

---
