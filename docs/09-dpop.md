# DPoP-Bindung

Wie der Kanal kryptographisch an das Gerät gebunden wird. Der daraus abgeleitete
`binding_key_ref` beweist ausschließlich, welches GERÄT spricht — er ist bewusst **kein**
Schlüssel, über den eine `ChannelSession` gefunden oder wiederverwendet wird
([02-domaenenmodell.md](02-domaenenmodell.md)). Eine konkrete Session wiederzuerkennen ist
Sache der `channelSessionId`, die der Client selbst merken muss.

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

- Der Kanaleinstieg (`POST /orchestrator/api/v1/app/channels`) legt **immer** eine neue `ChannelSession` an — er sucht nie nach einer bestehenden über `binding_key_ref`. Eine bereits laufende Session wiederaufzunehmen ist Sache von `GET /orchestrator/api/v1/channels/{channelSessionId}` mit der vom Client gemerkten `channelSessionId` ([05-api.md](05-api.md)).
- Bei jedem Request gegen eine konkrete `channelSessionId` muss `ChannelSession.bindingKeyRef` mit dem aktuellen DPoP-Ableitungswert übereinstimmen; andernfalls `403` (Binding-Mismatch, siehe [07-betrieb.md](07-betrieb.md)). Das gilt für `GET`/`PATCH`/`cancel`/`logout` gleichermaßen.
- Pro `binding_key_ref` können über die Zeit mehrere `ChannelSession`-Datensätze entstehen (jeder Kanaleinstieg ohne bekannte `channelSessionId` legt einen neuen an, z. B. nach Logout oder wenn der Client seine gemerkte ID verloren hat) — anders als früher gibt es keinen erzwungenen 1:1-Bezug mehr.
- Damit ein bereits registriertes Gerät trotzdem nicht jedes Mal neu `ident-fsc` durchlaufen muss, existiert `DeviceAccountLink` (`binding_key_ref -> accountId`, [02-domaenenmodell.md](02-domaenenmodell.md)) als eigener, von der einzelnen `ChannelSession` unabhängiger Datensatz. Der Kanaleinstieg liest ihn, um eine frische `ChannelSession` direkt mit `accountId` vorzubelegen (-> Login statt Registrierung).
- Zeitpunkt bewusst gewählt, nicht `AUTHENTICATED` und nicht `Identified`: Der Link entsteht/aktualisiert sich, sobald `Completed.Enrolled` ein erstes Auth-Mittel anlegt ([Orchestrierung](04-orchestrierung.md) Abschnitt 1) — nicht erst, wenn der Kanal sein eigenes `requiredAcr` erreicht. Ein Kanal, der z. B. `loa2` verlangt, bricht nach nur einem `loa1`-Mittel noch nicht ab; wird die Session danach abgebrochen, soll ein neues Gerät-Login trotzdem direkt das vorhandene Mittel anbieten, statt auf das (kanalspezifische) Erreichen von `loa2` zu warten. Bei bloßer Identifikation (`Completed.Identified`) entsteht dagegen **kein** Link: Ohne ein angelegtes Auth-Mittel gäbe es nichts, womit ein neuer Kanal die Identität erneut belastbar prüfen könnte — das würde bedeuten, dass der Besitz des DPoP-Keys allein als Login-Nachweis durchginge. Ein Kanal ohne Link nach abgebrochener Registrierung durchläuft deshalb bewusst wieder vollständig `ident-fsc`.
- Ein Wechsel zwischen Registrierung und Anmeldung ändert den Kanal nicht: Innerhalb EINER `ChannelSession` bleibt die `channelSessionId` stabil, nur der interne Prozess wechselt.
