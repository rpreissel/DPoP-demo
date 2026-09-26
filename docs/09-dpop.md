# DPoP-Bindung

Dieses Kapitel beschreibt, wie der Kanal kryptografisch an den DPoP-Schlüssel des Geräts gebunden
wird. Der daraus abgeleitete `binding_key_ref` beweist nur, welches GERÄT gerade spricht. Er ist bewusst **kein**
Schlüssel, über den eine `ChannelSession` gefunden oder wiederverwendet wird
([02-domaenenmodell.md](02-domaenenmodell.md)). Eine bestimmte Sitzung erkennt man an ihrer
`channelSessionId`, und die muss sich der Client selbst merken.

---

## 1) Prinzip

Das Frontend erzeugt beim ersten Start ein Schlüsselpaar (ECDSA P-256) und speichert es im Browser
(IndexedDB). Den öffentlichen Schlüssel schickt es als JWK in jedem DPoP-Proof mit. Das Backend
berechnet daraus einen JWK-Thumbprint nach RFC 7638 und führt ihn fachlich als `binding_key_ref`.

Zu den Begriffen: Der DPoP-Thumbprint heißt fachlich überall `binding_key_ref`. Nur die
kryptografische Berechnung im Paket `orchestrator/dpop` verwendet die Begriffe aus RFC 7638
(`JwkThumbprintService`).

Alle Anfragen des App-Kanals tragen den Header `DPoP: <proof>`.

---

## 2) Anforderungen

| ID | Anforderung | Kriterium |
|----|-------------|-----------|
| D-1 | Das Frontend erzeugt ein Schlüsselpaar, das sich für DPoP eignet. | Asymmetrisches Schlüsselpaar (ECDSA P-256) über die Web Crypto API |
| D-2 | Das DPoP-Schlüsselpaar wird im Browser gespeichert. | Es bleibt über das Neuladen der Seite hinweg erhalten |
| D-3 | Der private DPoP-Schlüssel lässt sich nicht exportieren. | Erzeugt mit `extractable=false`; der öffentliche Schlüssel (JWK) bleibt für den Proof-Header exportierbar |
| D-4 | Der öffentliche DPoP-Schlüssel ist im Frontend sichtbar. | Die Oberfläche zeigt den `jwk`-Teil an |
| D-5 | Alle Aufrufe des App-Zugangs sind mit DPoP abgesichert. | Der Header `DPoP` enthält ein gültiges DPoP-Proof-JWT |
| D-6 | DPoP-Proofs lassen sich nicht wiederverwenden. | Wird dieselbe Kombination aus JWK-Thumbprint und `jti` erneut benutzt, antwortet der Server mit `401` |
| D-7 | DPoP-Proofs gelten nur begrenzte Zeit, gemessen an `iat`. | Proofs mit zu altem `iat` werden mit `401` abgewiesen |
| D-8 | Das Zeitfenster für `iat` ist einstellbar. | `max-age-seconds` und `max-clock-skew-seconds` stehen in `application.yml` und werden bei der Prüfung verwendet |

D-6 löst die Tabelle `orchestrator.dpop_proof_replay`. Das Einfügen mit dem Primärschlüssel **ist**
die Prüfung; es wird nicht erst gelesen und dann geschrieben. Die Prüfung überlebt so einen Neustart
und gilt für alle Instanzen gemeinsam. Der Schlüssel ist SHA-256(`thumbprint:jti`) mit fester Länge
(`VARCHAR(64)`). Ein vom Client gewähltes `jti` kann damit weder die Schlüssellänge überschreiten noch
den Index aufblähen, in den dieses System am häufigsten schreibt.

Das Einfügen ist ein ausdrückliches `INSERT` (`DpopProofReplayRepository.insert`), nicht
`save`/`saveAndFlush`. Bei einer selbst vergebenen Id liest Spring Data zuerst und macht aus dem
Schreiben ein `UPDATE`, wenn die Zeile schon existiert – ein wiederholter Proof ginge dann ohne Fehler
durch. Genau das war bis 2026-09-26 der Fall (zweite Bewertung, F-11); `DpopReplayProtectionDbTest`
prüft es jetzt gegen die echte Tabelle, nacheinander und gleichzeitig. Derselbe Schutz gilt für
Geräte-Proofs und die Peer-Auth-Assertions von Keycloak.

**Kein Server-Nonce (`DPoP-Nonce`, RFC 9449 Abschnitt 8) – bewusst.** Der Server gibt keinen Nonce
vor, den der nächste Proof enthalten muss. Ein Proof ist deshalb nicht nur für den Moment gültig, in
dem er entsteht: Wer den privaten Schlüssel kurz nutzen kann (etwa Schadcode im Browser), kann Proofs
für die nächsten gut zwei Minuten im Voraus berechnen (`max-age-seconds` 120 plus Uhrenabweichung
30) und später einsetzen – jeden nur einmal (D-6). Ein Nonce würde das auf einen Rundlauf verkürzen,
kostet aber bei jeder Anfrage eine zusätzliche Antwort mit `use_dpop_nonce` und einen gemeinsamen
Nonce-Speicher über alle Instanzen. Entschieden im Review 2026-09; ein Nonce lässt sich nachrüsten,
ohne dass sich der Vertrag der Clients ändert (sie müssen nur den Header zurückgeben).

Für den Produktivbetrieb bleibt eine Grenze bei der Skalierung: Die Tabelle erhält für jede
angemeldete Anfrage eine neue Zeile; abgelaufene Einträge löscht ein geplanter Job jede Minute. Die
Tabelle nach Zeit zu partitionieren oder durch einen dauerhaften Schlüssel-Wert-Speicher zu
ersetzen, ist eine Entscheidung über die Infrastruktur und bewusst zurückgestellt.

### Wie sicher die Schlüssel im Browser sind

Diese Demo läuft im Browser, und das begrenzt, wie gut ihre Schlüssel geschützt sind. Der
DPoP-Schlüssel und der Schlüssel des Anmeldeverfahrens `device` entstehen über die Web Crypto API
und liegen in IndexedDB. Mit `extractable=false` kann kein Skript den privaten Schlüssel auslesen,
auch kein fremdes. Das Niveau eines **Secure Element** oder **TPM** aus dem
[Glossar](glossar/glossar.md) erreicht das aber nicht: Der Schlüssel liegt in den Daten des
Browsers, nicht in eigener Hardware, und die Prüfung per PIN oder Biometrie simuliert die Demo nur.

Noch schwächer geschützt ist das Entsperrgeheimnis von KOBIL: Es liegt im `localStorage` des
Browsers (`frontend/src/kobilUnlockSecret.ts`) und ist dort für jedes Skript der Seite lesbar. Auf
einem echten Gerät läge es im Schlüsselspeicher hinter einer biometrischen Abfrage.

Das ist eine Demo, kein System für den Produktivbetrieb. Ein Produktivsystem bräuchte an dieser
Stelle eine native App mit hardwaregestütztem Schlüsselspeicher.

---

## 3) Bindung an die ChannelSession

- Der Einstieg in den Kanal (`POST /orchestrator/api/v1/app/channels`) legt **immer** eine neue
  `ChannelSession` an. Er sucht nie über den `binding_key_ref` nach einer bestehenden. Eine bereits
  laufende Sitzung setzt man mit `GET /orchestrator/api/v1/channels/{channelSessionId}` fort, mit
  der `channelSessionId`, die sich der Client gemerkt hat ([05-api.md](05-api.md)).
- Bei jeder Anfrage an eine bestimmte `channelSessionId` muss `ChannelSession.bindingKeyRef` zu dem
  Wert passen, der aus dem aktuellen DPoP-Proof berechnet wird. Sonst antwortet der Server mit `403`
  (Bindung passt nicht, siehe [07-betrieb.md](07-betrieb.md)). Das gilt für `GET`, `PATCH`, `cancel`
  und `logout` gleichermaßen.
- Zu einem `binding_key_ref` können mit der Zeit mehrere `ChannelSession`s entstehen: Jeder Einstieg
  ohne bekannte `channelSessionId` legt eine neue an, z. B. nach dem Abmelden oder wenn der Client
  seine gemerkte ID verloren hat. Eine feste 1:1-Beziehung gibt es nicht.
- Damit ein bereits registriertes Gerät nicht jedes Mal erneut `ident-fsc` durchlaufen muss, gibt es
  die **Geräteverknüpfung** `DeviceAccountLink` (`binding_key_ref -> accountId`,
  [02-domaenenmodell.md](02-domaenenmodell.md)). Sie erkennt das Gerät nur wieder und zählt bewusst
  nicht als Anmeldung, also auch nicht als Faktor Besitz; „Gerätebindung“ heißt in dieser Doku nur
  das Einrichten eines an das Gerät gebundenen Anmeldeverfahrens (`device`, `kobil`).
  Dieser Datensatz ist unabhängig von der einzelnen `ChannelSession`. Der Einstieg in den Kanal liest
  ihn und belegt eine neue `ChannelSession` gleich mit der `accountId` vor. Es geht dann um eine
  Anmeldung statt um eine Registrierung.
- **Wann die Verknüpfung entsteht, ist bewusst gewählt** – weder beim Erreichen von `AUTHENTICATED`
  noch bei `Identified`. Sie entsteht oder ändert sich, sobald `Completed.Enrolled` das erste
  Anmeldeverfahren anlegt ([Orchestrierung](04-orchestrierung.md) Abschnitt 1), und nicht erst, wenn
  der Kanal sein eigenes `requiredAcr` erreicht. Ein Kanal, der zum Beispiel `loa2` verlangt, ist
  nach einem einzigen `loa1`-Verfahren noch nicht fertig. Bricht die Sitzung danach ab, soll eine
  neue Anmeldung auf dem Gerät trotzdem gleich das vorhandene Verfahren anbieten. Sie soll nicht
  warten, bis `loa2` erreicht ist, denn dieses Ziel gilt nur für diesen einen Kanal.
  Nach einer bloßen Identifizierung (`Completed.Identified`) entsteht dagegen **keine** Verknüpfung.
  Ohne eingerichtetes Anmeldeverfahren hätte ein neuer Kanal nichts, womit er die Identität erneut
  zuverlässig prüfen kann; allein der Besitz des DPoP-Schlüssels würde dann als Anmeldung gelten. Ein
  Kanal ohne Verknüpfung nach einer abgebrochenen Registrierung durchläuft deshalb bewusst wieder das
  ganze `ident-fsc`.
- **Drei Schlüssel mit drei Aufgaben, die man nicht verwechseln darf:**
  1. Der **DPoP-Schlüssel des Kanals** bindet die Anfragen an diesen Kanal. An dem daraus berechneten
     Wert (`bindingKeyRef`) hängen `DeviceAccountLink` und jedes `keyBinding`.
  2. Das **Credential von `auth_device`** ist ein eigenes, nicht exportierbares Schlüsselpaar. Damit
     signiert der Client den Nachweis, dass er das Gerät besitzt.
  3. Das **Entsperrgeheimnis von KOBIL** ist kein Schlüssel im kryptografischen Sinn, sondern ein
     Geheimnis. Die App verwahrt es (auf einem echten Gerät hinter Biometrie, in dieser Demo im
     `localStorage`, siehe Abschnitt 2) und legt es vor, damit das Backend den KOBIL-PIN freigibt
     ([Abläufe](06-ablaeufe.md) Abschnitt 7).

  Nur der erste bindet den Kanal. Die beiden anderen sind Credentials. Beim dritten liegt der
  eigentliche Nachweis über das Gerät nicht bei uns, sondern beim Anbieter. Dessen eigene
  Gerätekennung liefert `GET .../app/channels/device-link` in `boundCredentials` mit, neben dem
  Schlüssel des `device`-Credentials.
- **Wird das Gerät neu verknüpft, muss auch der Client aufräumen.** Auf dem Server widerruft
  `JourneyActionExecutor.linkDeviceTo` jedes an den Schlüssel gebundene Credential des bisherigen
  Kontos. Für `device` reicht das: Der Schlüssel im Browser wird dadurch wertlos, und jeder Versuch
  damit scheitert. Für `kobil` reicht es nicht, denn dort liegt im Browser ein **Geheimnis**, das den
  PIN freigeben würde. Der Client löscht es deshalb, sobald `device-link` kein `kobil`-Credential mehr
  aufführt (`tools/kobil/localData.ts`). Ein Geheimnis, das nichts mehr freigibt, bleibt so nicht im
  Browser liegen.
- Wechselt der Ablauf zwischen Registrierung und Anmeldung, bleibt der Kanal derselbe: Innerhalb
  EINER `ChannelSession` bleibt die `channelSessionId` gleich; nur der Vorgang dahinter wechselt.
- **Neu verknüpft wird nur mit Zustimmung, und das stellt der Aufbau sicher, nicht die Sorgfalt im
  Einzelfall.** Es gibt zwei Wege zu einer Geräteverknüpfung, und nur einer darf ein Gerät neu
  verknüpfen:
  - Der *implizite* Weg (ein Ablauf war erfolgreich, `AuthIntent.bindsDeviceImplicitly`) verknüpft nur,
    wenn der Schlüssel noch frei ist oder schon auf dasselbe Konto zeigt. Zeigt er auf ein fremdes
    Konto, geschieht gar nichts.
  - Der *ausdrückliche* Weg (`Action.LinkDevice` nach einer Rückfrage) darf neu verknüpfen und
    widerruft dabei.

  Ob unbemerkt neu verknüpft wird, hängt damit nicht davon ab, ob die jeweilige Strategie an diesen Fall
  gedacht hat. `RegisterEnrollFirstStrategy` fragt deshalb an einer anderen Stelle als
  `RegisterStrategy`. `RegisterStrategy` identifiziert zuerst und kann direkt danach fragen.
  `RegisterEnrollFirstStrategy` verknüpft beim ersten eingerichteten Verfahren; zu diesem Zeitpunkt ist
  das Konto gerade erst entstanden und hat noch keine Identität. Sie fragt deshalb erst am **Ende**
  der Journey (`EnrollFirstConfirmDeviceRebind`), nach der freiwilligen Identifizierung. Lehnt der
  Nutzer ab, endet die Registrierung ohne Geräteverknüpfung. Das Konto bleibt über die Anmeldung per
  E-Mail-Adresse voll nutzbar.
- `DeviceAccountLink` ist immer 1:1: Ein `binding_key_ref` zeigt zu jedem Zeitpunkt auf höchstens ein
  Konto. Weist sich auf einem bereits verknüpften Gerät jemand anderes neu aus (`intent=register`,
  „Zweitaccount“, siehe [`REGISTER`](journeys/register.md)), wird die Verknüpfung nicht unbemerkt
  überschrieben. Sie wird erst nach einer Rückfrage übertragen (`RegisterState.ConfirmDeviceRebind`).
  Stimmt der Nutzer zu, wird zusätzlich **jedes** an diesen Schlüssel gebundene Credential des
  bisherigen Kontos deaktiviert, und sein Datensatz im Methodenmodul wird gelöscht
  (`AccountDeletionService.revokeMethod`). Heute sind das `device` und `kobil`; `JourneyActionExecutor`
  findet sie über `keyBinding != null` und nie über eine Liste von `toolId`s. Lehnt der Nutzer ab,
  bricht die Journey ab, und die alte Verknüpfung bleibt bestehen. Unabhängig davon gilt ohnehin bei der
  Auswahl der Kandidaten: Ein gerätegebundenes Credential (`ToolDescriptor.usableByCaller`,
  [03-tool-architektur.md](03-tool-architektur.md)) ist nur nutzbar, solange `DeviceAccountLink` für
  seinen Schlüssel noch auf genau das Konto zeigt, dem es gehört.

---

## 4) Wo die Bindung endet

Die DPoP-Bindung schützt die Aufrufe des App-Kanals an den Orchestrator – bis einschließlich
`GET …/token`. Die Keycloak-Tokens, die dieser Endpunkt ausgibt (und die der Web-Kanal von Keycloak
direkt bekommt), sind **nicht** an den DPoP-Schlüssel gebunden: Sie tragen kein `cnf.jkt`, und ein
Resource-Server verlangt zu ihnen keinen DPoP-Proof. Wer ein solches AccessToken abgreift, kann es
bis zu seinem Ablauf bei jedem Resource-Server verwenden, der Keycloak-Tokens annimmt.

Das ist entschieden, nicht übersehen (Review 2026-09, M-3; [ADR-9](adr/ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md)):

- **Was bleibt gebunden:** Die Sitzung selbst. Ein neues Token gibt es nur über `GET …/token` mit
  gültigem DPoP-Proof; das RefreshToken verlässt das Backend nie.
- **Was den Schaden begrenzt:** die kurze Laufzeit des AccessTokens und das Ende der Keycloak-Sitzung
  beim Abmelden oder Ablauf (`JourneyService.endSession`).
- **Was eine Bindung bräuchte:** Keycloak-DPoP für den Grant und `cnf.jkt` mit dem Thumbprint des
  Kanals in jedem Token – und Resource-Server, die den Proof prüfen. Das betrifft jeden Dienst, der
  die Tokens annimmt, nicht nur dieses Projekt.

