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

D-6 wird über `orchestrator.dpop_proof_replay` gelöst: Der Primärschlüssel-Insert **ist** die Prüfung
(kein Read-then-Write), überlebt einen Neustart und gilt über Replicas hinweg. Der Schlüssel ist
SHA-256(`thumbprint:jti`) mit fester Breite (`VARCHAR(64)`): ein clientgewählter `jti` kann damit
weder die Schlüssellänge sprengen noch den Index aufblähen, in den dieses System am häufigsten
schreibt. Für den Produktivbetrieb bleibt eine Skalierungsgrenze bestehen: Die Tabelle bekommt
pro authentifiziertem Request eine neue Zeile; abgelaufene Einträge entfernt ein geplanter Job minütlich. Sie nach Zeit zu partitionieren oder durch einen
dauerhaften Key-Value-Store zu ersetzen, ist eine Infrastrukturentscheidung und bewusst
zurückgestellt.

---

## 3) Bindung an die ChannelSession

- Der Kanaleinstieg (`POST /orchestrator/api/v1/app/channels`) legt **immer** eine neue `ChannelSession` an — er sucht nie nach einer bestehenden über `binding_key_ref`. Eine bereits laufende Session wiederaufzunehmen ist Sache von `GET /orchestrator/api/v1/channels/{channelSessionId}` mit der vom Client gemerkten `channelSessionId` ([05-api.md](05-api.md)).
- Bei jedem Request gegen eine konkrete `channelSessionId` muss `ChannelSession.bindingKeyRef` mit dem aktuellen DPoP-Ableitungswert übereinstimmen; andernfalls `403` (Binding-Mismatch, siehe [07-betrieb.md](07-betrieb.md)). Das gilt für `GET`/`PATCH`/`cancel`/`logout` gleichermaßen.
- Pro `binding_key_ref` können über die Zeit mehrere `ChannelSession`-Datensätze entstehen (jeder Kanaleinstieg ohne bekannte `channelSessionId` legt einen neuen an, z. B. nach Logout oder wenn der Client seine gemerkte ID verloren hat); ein erzwungener 1:1-Bezug besteht nicht.
- Damit ein bereits registriertes Gerät trotzdem nicht jedes Mal neu `ident-fsc` durchlaufen muss, existiert `DeviceAccountLink` (`binding_key_ref -> accountId`, [02-domaenenmodell.md](02-domaenenmodell.md)) als eigener, von der einzelnen `ChannelSession` unabhängiger Datensatz. Der Kanaleinstieg liest ihn, um eine frische `ChannelSession` direkt mit `accountId` vorzubelegen (-> Login statt Registrierung).
- Zeitpunkt bewusst gewählt, nicht `AUTHENTICATED` und nicht `Identified`: Der Link entsteht/aktualisiert sich, sobald `Completed.Enrolled` ein erstes Auth-Mittel anlegt ([Orchestrierung](04-orchestrierung.md) Abschnitt 1) — nicht erst, wenn der Kanal sein eigenes `requiredAcr` erreicht. Ein Kanal, der z. B. `loa2` verlangt, bricht nach nur einem `loa1`-Mittel noch nicht ab; wird die Session danach abgebrochen, soll ein neues Gerät-Login trotzdem direkt das vorhandene Mittel anbieten, statt auf das (kanalspezifische) Erreichen von `loa2` zu warten. Bei bloßer Identifikation (`Completed.Identified`) entsteht dagegen **kein** Link: Ohne ein angelegtes Auth-Mittel hat ein neuer Kanal nichts, womit er die Identität erneut zuverlässig prüfen kann. Der Besitz des DPoP-Schlüssels allein würde dann als Login-Nachweis gelten. Ein Kanal ohne Link nach abgebrochener Registrierung durchläuft deshalb bewusst wieder vollständig `ident-fsc`.
- **Drei Schlüssel, drei Rollen — nicht zu verwechseln.** (1) Der **DPoP-Kanalschlüssel**: bindet Requests an diesen Kanal, und sein Ableitungswert (`bindingKeyRef`) ist das, woran `DeviceAccountLink` und jedes `keyBinding` hängen. (2) Das **`auth_device`-Credential**: ein eigenes, nicht-extrahierbares Schlüsselpaar, mit dem der Client seinen Besitz selbst signiert. (3) Das **KOBIL-Unlock-Secret**: kein Schlüssel im Kryptosinn, sondern ein Geheimnis, das die App biometriegeschützt hält und vorzeigt, damit das Backend den KOBIL-PIN freigibt ([Abläufe](06-ablaeufe.md) Abschnitt 7). Nur (1) bindet den Kanal; (2) und (3) sind Credentials, und bei (3) liegt der eigentliche Gerätebeweis nicht hier, sondern beim Anbieter — dessen eigene Gerätekennung `GET .../app/channels/device-link` in `boundCredentials` mitliefert, neben dem `device`-Credential-Schlüssel.
- **Beim Umbinden muss auch der Client aufräumen.** Serverseitig widerruft `JourneyActionExecutor.linkDeviceTo` jedes schlüsselgebundene Credential des vorherigen Kontos. Für `device` reicht das: Der lokale Schlüssel wird dadurch wertlos, ein Versuch damit scheitert. Für `kobil` nicht — dort liegt im Browser ein **Geheimnis**, das den PIN freigeben würde. Der Client löscht es deshalb, sobald `device-link` keine `kobil`-Bindung mehr aufführt (`tools/kobil/localData.ts`) — ein Geheimnis, das nichts mehr freigibt, bleibt nicht im Browser liegen.
- Ein Wechsel zwischen Registrierung und Anmeldung ändert den Kanal nicht: Innerhalb EINER `ChannelSession` bleibt die `channelSessionId` stabil, nur der interne Prozess wechselt.
- **Umbinden nur nach Zustimmung — dafür sorgt der Aufbau, nicht die Sorgfalt im Einzelfall.** Es gibt zwei Wege zu einer Gerätebindung, und nur einer darf umbinden: Der *implizite* Weg (ein Ablauf war erfolgreich, `AuthIntent.bindsDeviceImplicitly`) bindet ausschließlich, wenn der Schlüssel frei ist oder schon auf dasselbe Konto zeigt — zeigt er auf ein fremdes, passiert gar nichts. Der *explizite* Weg (`Action.LinkDevice` nach einem Prompt) darf umbinden und widerruft dabei. Ob hier unbemerkt umgebunden wird, hängt damit nicht daran, ob die jeweilige Strategie an den Fall gedacht hat. `RegisterEnrollFirstStrategy` fragt deshalb an anderer Stelle als `RegisterStrategy`: Letztere identifiziert zuerst und kann direkt danach fragen, Erstere bindet beim ersten Enrollment, und dort ist das Konto gerade erst entstanden und trägt noch keine Identität — sie fragt daher erst am **Ende** der Journey (`EnrollFirstConfirmDeviceRebind`), nach der optionalen Identifizierung. Ablehnen beendet die Registrierung ohne Gerätebindung; das Konto bleibt über die Lookup-Verfahren voll nutzbar.
- `DeviceAccountLink` ist immer 1:1: ein `binding_key_ref` zeigt zu jedem Zeitpunkt auf höchstens ein Konto. Identifiziert sich auf einem bereits verlinkten Gerät jemand anderes neu (`intent=register`, "Zweitaccount", [04-orchestrierung.md](04-orchestrierung.md) "REGISTER"), wird die Bindung nicht unbemerkt überschrieben, sondern erst nach Rückfrage übertragen (`RegisterState.ConfirmDeviceRebind`). Bei Zustimmung wird zusätzlich **jedes** schlüsselgebundene Credential des bisherigen Kontos für diesen Schlüssel deaktiviert und sein Enrollment-Datensatz gelöscht (`AccountDeletionService.revokeMethod`); heute sind das `device` und `kobil`, und `JourneyActionExecutor` findet sie über `keyBinding != null`, nie über eine `toolId`-Liste. Bei Ablehnung bricht die Journey ab und die alte Bindung bleibt bestehen. Unabhängig davon gilt beim Ermitteln der Kandidaten ohnehin: Ein geräte-gebundenes Credential (`ToolDescriptor.usableByCaller`, [03-tool-architektur.md](03-tool-architektur.md)) ist nur nutzbar, solange `DeviceAccountLink` für seinen Schlüssel noch auf genau das Konto zeigt, dem es gehört.
