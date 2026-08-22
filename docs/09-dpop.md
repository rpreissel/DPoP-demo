# DPoP-Bindung

Wie der Kanal kryptographisch an das Gerät gebunden wird. Der daraus abgeleitete
`binding_key_ref` ist der Schlüssel, über den eine `ChannelSession` wiedererkannt wird
([02-domaenenmodell.md](02-domaenenmodell.md)).

---

## 1) Prinzip

Das Frontend erzeugt beim ersten Start ein ECDSA-P-256-Schlüsselpaar und persistiert es im
Browser (IndexedDB). Der öffentliche Schlüssel wird als JWK in jedem DPoP-Proof übertragen.
Das Backend leitet daraus einen JWK-Thumbprint nach RFC 7638 ab und führt ihn fachlich als
`binding_key_ref`.

Terminologie: Der DPoP-Thumbprint heißt fachlich durchgängig `binding_key_ref`. Die
Kryptografie-Berechnung bleibt im Paket `orchestrator/dpop` unter RFC-7638-Begriffen
(`JwkThumbprintService`).

Alle Requests des App-Kanals tragen den Header `DPoP: <proof>`.

---

## 2) Anforderungen

| ID | Anforderung | Kriterium |
|----|-------------|-----------|
| D-1 | Im Frontend wird ein DPoP-fähiges Schlüsselpaar erzeugt. | Asymmetrisches Keypair (ECDSA P-256) mit Web Crypto API |
| D-2 | Das DPoP-Keypair wird im Browser persistiert. | Wiederverwendung über Seitenneuladungen hinweg |
| D-3 | Der private DPoP-Schlüssel ist nicht exportierbar. | Erzeugung mit `extractable=false`; die öffentliche JWK bleibt für den Proof-Header exportierbar |
| D-4 | Der öffentliche DPoP-Schlüssel ist im Frontend einsehbar. | Anzeige des `jwk`-Teils im UI |
| D-5 | Alle Aufrufe der App-Fassade werden mit DPoP abgesichert. | Header `DPoP` enthält ein valides DPoP-Proof-JWT |
| D-6 | DPoP-Proofs werden gegen Replay-Angriffe abgesichert. | Wiederverwendung derselben Kombination aus JWK-Thumbprint und `jti` wird mit HTTP `401` abgewiesen |
| D-7 | DPoP-Proofs haben eine begrenzte Gültigkeit über `iat`. | Proofs mit zu altem `iat` werden mit HTTP `401` abgewiesen |
| D-8 | Das `iat`-Zeitfenster ist konfigurierbar. | `max-age-seconds` und `max-clock-skew-seconds` werden über `application.yml` gesetzt und im Validator verwendet |

---

## 3) Bindung an die ChannelSession

- Der Kanaleinstieg (`POST /orchestrator/api/v1/app/channels`) nutzt den `binding_key_ref` als Schlüssel: Eine bestehende `ChannelSession` wird wiederverwendet, andernfalls neu angelegt.
- Pro `binding_key_ref` existiert genau ein aktiver Kanalbezug.
- Bei jedem Folgerequest muss `ChannelSession.bindingKeyRef` mit dem aktuellen DPoP-Ableitungswert übereinstimmen; andernfalls `403` (Binding-Mismatch, siehe [07-betrieb.md](07-betrieb.md)).
- Ein Wechsel zwischen Registrierung und Anmeldung ändert den Kanal nicht: Die `channelSessionId` bleibt stabil, nur der interne Prozess wechselt.
