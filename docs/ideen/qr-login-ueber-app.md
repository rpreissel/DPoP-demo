# Idee: QR-Login am Web-Kanal, bestätigt über die App (`auth-qr`)

Status: **Konzept, nicht umgesetzt**.

---

## 1) Ausgangslage

Der Web-Kanal (Keycloak) bietet heute ausschließlich Verfahren an, die der Nutzer selbst am
Browser durchführt (`auth-sms`, `auth-password`, `auth-email`, `auth-*-lookup`). Wer bereits ein
App-Gerät mit laufender, authentifizierter Sitzung besitzt, soll den Web-Login stattdessen dort
bestätigen können — klassisches „Mit dem Handy einloggen" per QR-Code, analog zu WhatsApp
Web/GitHub-CLI-Device-Flow, hier aber komplett in die bestehende Tool-/Journey-Architektur
eingepasst statt als Sonderweg daneben.

Zwei Eigenschaften unterscheiden diesen Fall von allem bisher Vorhandenen:

1. **Zwei Kanäle sind an einem Tool-Durchlauf beteiligt.** Bisher lebt ein `ToolOutcome`
   ausschließlich innerhalb der `ChannelSession`, die es aktiviert hat ([Tool-Architektur](../03-tool-architektur.md)).
   Hier startet die WEB-Seite eine Anfrage, aber die APP-Seite eines *anderen* Kanals löst sie auf.
2. **Der App-Nutzer bestätigt fremdes Handeln, statt eigenes Handeln nachzuweisen.** Jedes
   bisherige Tool antwortet auf „wer bin ich" / „was kann ich beweisen" für den eigenen Kanal. Hier
   antwortet der Nutzer auf „lass diesen anderen Kanal rein" — strukturell etwas anderes als
   Identifikation, Enrollment oder Auth.

---

## 2) Kernidee

Ein neues Methodenmodul `auth_qr` (`method = "qr"`), das — wie `auth_sms`/`auth_email` — beide
Seiten *einer* Methode trägt, hier aber auf zwei verschiedenen Kanälen:

- **WEB**: `auth-qr` (`IDENTIFIED_AUTH`) und `auth-qr-lookup` (`LOOKUP_AUTH`), beide `maxAcr = loa2`
  und ein Opt-in `enroll-qr` voraussetzend (s. u.). Aktivierung erzeugt einen kurzlebigen
  Pairing-Code, der als QR-Code (und im Demo-Modus zusätzlich als klickbarer Link) gerendert wird.
  Der Web-Client wartet.
- **APP**: `confirm-qr-login` — ein neuer Tool-*Typ* mit einer neuen Rolle (Abschnitt 3), der den
  Pairing-Code entgegennimmt, den Vergleichscode zur Prüfung anzeigt (Abschnitt 6) und
  Annehmen/Ablehnen anbietet. Erfordert vorher `loa2` auf dem App-Kanal.

Verbindendes Element ist eine einzige neue, kleine Persistenz `QrLoginRequest`
(`pairingCode` PK, `verificationCode`, `status`, `resolvingAccountId`, `expiresAt`) — WEB und APP sind derselbe
Orchestrator-Prozess/dieselbe DB, es braucht keinen Cross-Service-Call für die eigentliche
Auflösung. Nur die Web-Seite selbst läuft in Keycloak, einem separaten Deployment — dafür Abschnitt 7.

### `enroll-qr`: expliziter Opt-in statt App-Erkennung

Ohne Gegenprüfung würde `AuthPolicy.candidateTools` `auth-qr` für **jeden** WEB-bekannten Account
anbieten, auch für einen, der nie eine App benutzt hat — der Web-Client zeigt dann einen QR-Code,
den niemand je bestätigen kann. Ob "gerade eine App-Sitzung existiert" ließe sich ohnehin nicht
zuverlässig feststellen (dasselbe Problem wie bei einer Telefonnummer, die technisch registriert,
aber tot ist). Statt das zu versuchen, bekommt `auth-qr`/`auth-qr-lookup` einen ganz normalen
`ENROLLMENT`-Zwilling `enroll-qr`: kein gespeichertes Geheimnis, sondern ein reiner Opt-in-Marker
("dieser Account erlaubt QR-Login grundsätzlich") — der Account trägt damit dieselbe
"hat eine aktive Enrollment"-Auskunft, die `AuthPolicy` für jede andere Methode auch schon abfragt,
keine Sonderprüfung nötig. `enroll-qr` läuft typischerweise auf dem App-Kanal (dort, wo man ohnehin
schon eingeloggt ist, wenn man "künftig per QR am Web anmelden" aktiviert), ist aber nicht
kanalgebunden — wie jede andere Methode auch über `MANAGE_AUTH_METHODS` de-/aktivierbar.

Damit ändert sich die Kandidaten-Rechnung:

- `auth-qr` (Account bereits bekannt): nur Kandidat, wenn der Account eine aktive `enroll-qr`-
  Enrollment hat — exakt dieselbe Regel wie bei `auth-sms`/`auth-password`/`auth-email`.
- `auth-qr-lookup` (Account WEB-seitig noch unbekannt): bleibt immer anbietbar, wie ein
  Identifikationstool — die Prüfung verschiebt sich auf die App-Seite: `confirm-qr-login` darf für
  einen Account nur bestätigen, wenn *dieser* Account `enroll-qr` aktiv hat. Ohne aktives Opt-in
  bricht die Bestätigung ab (`Failed("QR-Login ist für dieses Konto nicht aktiviert")`), statt den
  Web-Login stillschweigend durchzulassen.

---

## 3) Neue Konzepte in `tool_spi`

Keine der bestehenden Rollen/Outcomes passt auf „ich bestätige den Login eines *anderen* Kanals":

```kotlin
enum class ToolCategory { IDENT, ENROLL, AUTH, SIDE_ACTION }

enum class MethodRole(val category: ToolCategory, val defaultStartStep: String) {
    IDENTIFICATION(ToolCategory.IDENT, "input"),
    ENROLLMENT(ToolCategory.ENROLL, "enroll"),
    IDENTIFIED_AUTH(ToolCategory.AUTH, "auth"),
    LOOKUP_AUTH(ToolCategory.AUTH, "auth"),
    /** Bestätigt oder lehnt eine Anfrage eines ANDEREN Kanals ab (z. B. auth-qr's Pairing). */
    PEER_APPROVAL(ToolCategory.SIDE_ACTION, "input"),
}
```

`SIDE_ACTION` statt eines feature-spezifischen `PEER`: die Kategorie benennt die geteilte
Eigenschaft — trägt nichts zur ACR/AMR-Bilanz des *eigenen* Kanals bei, nie Kandidat — nicht dieses
eine Feature. Bewusst **nicht** `MISC`/`OTHER`: ein echter Sammelbegriff würde künftige, tatsächlich
andersartige Tools kommentarlos in dieselben `when`-Zweige stecken und genau den Zweck der
Exhaustivität unterlaufen, den diese Erweiterung erst rechtfertigt.

```kotlin
sealed interface Completed : ToolOutcome {
    // ... bestehende Varianten ...

    /** Ein [PEER_APPROVAL][MethodRole.PEER_APPROVAL]-Tool hat eine fremde Anfrage bestätigt. */
    data class Approved(
        override val amr: List<String> = emptyList(),
        override val achievedAcr: String? = null,
        override val factorTypes: Set<FactorType> = emptySet(),
    ) : Completed
}
```

Ablehnen ist **kein** neuer Fall — `Failed(reason = "Vom Nutzer abgelehnt")` reicht, weil die
bestehende Retry-/Abbruch-Logik dafür schon existiert.

`ToolCategory` ist ein exhaustives `enum`; jede Stelle, die heute per `when` darüber verzweigt
(Kandidatenermittlung, Verfügbarkeit), zwingt der Compiler beim Hinzufügen von `SIDE_ACTION`,
explizit zu entscheiden, was das dort bedeutet — kein stiller Laufzeit-Default (gleiches Prinzip wie
[Orchestrierung](../04-orchestrierung.md) Abschnitt 1, "versiegelte Zustandsmenge"). Praktisch heißt
das: `AuthPolicy.candidateTools`/`enrollmentCandidates` bekommen dafür einen eigenen `SIDE_ACTION`-
Zweig, der schlicht nichts anbietet — `PEER_APPROVAL`-Tools werden nie als Kandidat für eine Lücke
vorgeschlagen, nur explizit per `intent` aktiviert (Abschnitt 4).

### Nebenbefund: `DEVICE_AUTH` → `IDENTIFIED_AUTH`

`auth-qr` legt eine bestehende Ungenauigkeit offen, die nichts mit QR-Login zu tun hat: die heutige
Rolle `MethodRole.DEVICE_AUTH` heißt nach "Gerät", bedeutet laut eigenem Doc-Kommentar aber "beweist
ein Credential für einen über den Kanal bereits bekannten Account" — auf dem WEB-Kanal, der gar
keine Geräteanbindung kennt, ist "Device Auth" irreführend, erst recht für `auth-qr`. Vorschlag:
`DEVICE_AUTH` in `IDENTIFIED_AUTH` umbenennen — passt zur eigentlichen Bedeutung und reiht sich
sauber neben `IDENTIFICATION`/`LOOKUP_AUTH` ein (bereits identifiziert / identifiziert per Lookup).

Das ist eine eigenständige, mechanische, aber **kanalübergreifende** Umbenennung — betrifft
`auth_sms`, `auth_password`, `auth_email`, `auth_device` sowie
[Tool-Architektur](../03-tool-architektur.md)/[Orchestrierung](../04-orchestrierung.md) und die
zugehörigen Tests, unabhängig vom QR-Feature. Dieses Dokument geht davon aus, dass sie **vorab**
als eigener, kleiner Schritt erfolgt — `auth-qr` erfindet sie nicht, setzt sie nur vollständiger
voraus als bisherige Tools (Abschnitt 9, Schritt 0).

---

## 4) Neuer Intent: `CONFIRM_PEER_LOGIN`

```kotlin
enum class AuthIntent {
    // ... bestehende Werte ...

    /** App bestätigt/lehnt einen Web-Login ab, den ein anderer Kanal per QR angestoßen hat. */
    CONFIRM_PEER_LOGIN,
}
```

`AuthIntent.fromRequest` erkennt neue Entry-Intents automatisch am Namen — keine zusätzliche
Wire-Vokabel zu pflegen ([Orchestrierung](../04-orchestrierung.md) Abschnitt 2). Als **Entry-Intent**
muss die Strategie alle drei möglichen Startzustände des scannenden App-Kanals abdecken, nicht nur
den bereits-authentifizierten Fall:

1. **Kanal noch nicht authentifiziert**: NUR der geräte-gebundene Login-Teil von `FAST_ACCESS`s
   Fallback-Kette (`DeviceAccountLink` bekannt → dessen `IDENTIFIED_AUTH`-Kandidaten bis `loa1`) —
   **keine** Identifikation/Registrierung. Ein Peer-Approval darf nie dazu führen, dass jemand ohne
   bestehenden, bereits identifizierten Account sich per Registrierung frisch eine Identität
   verschafft, nur um einen fremden Web-Login zu bestätigen. Ist kein `DeviceAccountLink` vorhanden
   (kalter Start, z. B. App gerade erst installiert), endet die Journey sofort ohne Angebot — der
   Nutzer muss sich zuerst ganz regulär (mit `FAST_ACCESS`/`REGISTER`) einrichten, bevor er als
   Peer bestätigen kann.
2. **Kanal authentifiziert, aber unter `loa2`**: Step-up-Maschinerie wie bei `MANAGE_AUTH_METHODS`
   ("starting MANAGE with only loa1 session evidence" → `ManageMethodsIntegrationTest`).
3. **Kanal bereits bei `loa2` oder höher**: direkt weiter zu Schritt 4.
4. `confirm-qr-login` aktivieren.
5. Ziel erreicht, sobald das Tool `Completed`/`Failed` meldet — keine Rückkehr in die
   Kandidatenliste danach, die Journey endet mit diesem einen Tool.

Damit kombiniert `CONFIRM_PEER_LOGIN` Bausteine aus zwei bestehenden Intents (`FAST_ACCESS`s
geräte-gebundenem Login-Zweig, ohne dessen Identifikations-/Registrierungs-Ende, plus
`MANAGE_AUTH_METHODS`s Step-up), ist aber keiner der beiden — eine eigene, versiegelte
`JourneyState`-Menge, die beide Wege bis `loa2` offenhält und danach immer auf genau ein Tool
zuläuft.

Der Pairing-Code selbst geht **nicht** über den Kanal-Erzeugungsvertrag (`POST /app/channels`
bleibt unverändert) — er ist ein ganz normales Eingabefeld des ersten `confirm-qr-login`-Schritts,
genau wie `kvnr`/`fsc` bei `ident-fsc`. Der QR-Code (bzw. der Demo-Link) kodiert einen Deep-Link,
der App-seitig `intent=confirm_peer_login` setzt und `pairingCode` vorbefüllt an das Tool
durchreicht.

---

## 5) Modul `auth_qr`

### Datenmodell

```kotlin
enum class QrLoginStatus { PENDING, APPROVED, DENIED, EXPIRED }

@Entity
class QrLoginRequest(
    @Id val pairingCode: String,
    /** Kurzer, für Menschen vergleichbarer Code (Abschnitt 6) — kein Geheimnis, kein Auth-Faktor. */
    val verificationCode: String,
    @Enumerated(EnumType.STRING) var status: QrLoginStatus = QrLoginStatus.PENDING,
    var resolvingAccountId: Long? = null,
    val createdAt: Instant,
    val expiresAt: Instant,
)
```

Eigene Tabelle im `auth_qr`-Modul, keine Kante auf `account` nötig — die Web-Seite braucht nur
`status`/`resolvingAccountId`, keinen echten Domänen-Zusammenhang mit dem Account (anders als
`auth_email`s bestätigte E-Mail, [Tool-Architektur](../03-tool-architektur.md) Abschnitt 2).

`pairingCode` und `verificationCode` haben bewusst unterschiedliche Entropie und unterschiedliche
Aufgaben — Details und Begründung in Abschnitt 6.

### Opt-in (`enroll-qr`)

Ein Schritt (`enroll`), keine Eingabefelder außer einer Bestätigung — meldet
`Completed.Enrolled(enrollmentRef, …)` ohne weitere Nutzdaten; `EnrollmentRef` referenziert nur
die Existenz der Zustimmung, kein Geheimnis. Deaktivierung läuft über den bestehenden
`MANAGE_AUTH_METHODS`-Pfad wie bei jeder anderen Methode.

### WEB-Seite (`auth-qr` / `auth-qr-lookup`)

| nextStep | Bedeutung |
|---|---|
| `waitForApp` | Aktivierung/jedes Poll ohne Auflösung: `stepData = {pairingCode, verificationCode, deepLink, demo}` |

Jedes weitere `PATCH` mit leerem Body (der generische Tool-Patch-Pfad erlaubt das schon —
`OrchestratorAuthenticator.action` baut `fields` auch leer auf) prüft `QrLoginRequest.status`
erneut:

- `PENDING`, nicht abgelaufen → wieder `InProgress("waitForApp", …)`.
- `APPROVED` → `Completed.Authenticated(amr = listOf("qr"), achievedAcr = "loa2", …)` — `accountId`
  wird dabei nur von `auth-qr-lookup` gesetzt (`= request.resolvingAccountId`), `auth-qr` lässt ihn
  `null` (Account bereits über den Kanal bekannt).
- `DENIED` → `Failed("Vom Nutzer abgelehnt")`.
- `EXPIRED`/nicht gefunden → `Failed("QR-Code abgelaufen")`.

### APP-Seite (`confirm-qr-login`)

| nextStep | Bedeutung |
|---|---|
| `input` | Erster Schritt, erwartet Feld `pairingCode` (aus Deep-Link vorbefüllt) |
| `confirm` | Anfrage geladen — zeigt `verificationCode` zum Abgleich mit dem WEB-Bildschirm (Abschnitt 6) —, erwartet `decision: accept\|reject` |

Unbekannter, abgelaufener oder bereits entschiedener `pairingCode` am `input`-Schritt:
`Failed("Anfrage nicht gefunden oder abgelaufen")` — bleibt auf `input`, normale Retry-Logik greift,
kein Sonderfall.

`accept` schreibt `status = APPROVED`, `resolvingAccountId = <accountId des App-Kanals>`, meldet
`Completed.Approved`. `reject` schreibt `status = DENIED`, meldet
`Failed("Vom Nutzer abgelehnt")`. Beide Schreibvorgänge sind atomare, bedingte Übergänge — Details
in Abschnitt 6.

---

## 6) Sicherheit

### QR-Jacking: Vergleichscode statt bloßem Vertrauen auf den Scan

Diese Tool-Form hat einen bekannten Angriff (u. a. bei WhatsApp Web bekannt geworden): ein
Angreifer öffnet selbst die WEB-Login-Seite, fängt deren QR-Code/Pairing-Code ab und bringt das
Opfer dazu, ihn mit dessen eigener, bereits authentifizierter App zu bestätigen — Ergebnis: die
Browser-Sitzung des Angreifers wird als das Opfer angemeldet, ohne dass das Opfer etwas Falsches
"gesehen" hätte, wenn die App-Seite nur ein anonymes "Login bestätigen?" zeigt.

Gegenmaßnahme: `verificationCode` — ein kurzer, für Menschen vergleichbarer Code (dreistellige Zahl,
000–999 — mehr Stellen brauchts nicht, siehe unten),
den `auth-qr`/`auth-qr-lookup` bei Aktivierung zusammen mit dem Pairing-Code erzeugt und **groß,
gut sichtbar neben dem QR-Bild** auf der WEB-Seite zeigt. `confirm-qr-login` zeigt denselben Code
auf dem `confirm`-Schritt erneut an. Der Nutzer bestätigt nur, wenn beide Bildschirme denselben Code
zeigen — ein reiner Blick-Abgleich, kein Feld, das je übertragen oder eingegeben wird. Das entwertet
den oben beschriebenen Angriff, solange Opfer und Angreifer nicht gleichzeitig denselben
Bildschirminhalt sehen können.

Bewusst benannte Grenze: das schützt nicht gegen einen Angreifer, der in Echtzeit *beide* Seiten
kontrolliert (Live-Relay/MITM zwischen echtem WEB-Login und der App) — derselbe grundsätzliche
Vorbehalt gilt für jeden Cross-Device-Abgleich dieser Art (z. B. auch bei FIDO/Passkey-QR-Flows) und
wird hier nicht vorgetäuscht gelöst, nur benannt.

### Pairing-Code: Entropie statt Rate-Limit

`pairingCode` und `verificationCode` haben unterschiedliche Aufgaben und deshalb unterschiedliche
Entropie-Anforderungen:

- `pairingCode`: **8 Zeichen** aus einem verwechslungsarmen Alphabet (Crockford-Base32-artig,
  `0123456789ABCDEFGHJKMNPQRSTVWXYZ`, ohne `I`/`L`/`O`/`U`), angezeigt gruppiert als `XXXX-XXXX` —
  bequem abtippbar, da manuelle Eingabe hier **kein Fallback**, sondern ein gleichwertiger Weg neben
  QR/Deep-Link ist. ~40 Bit Entropie sind für eine kurzlebige (Minuten), einmal verwendbare Anfrage
  in Kombination mit dem Rate-Limit unten ausreichend, aber bewusst *nicht* so hochentropisch wie
  ein reiner API-Token — dieser Code muss ein Mensch fehlerfrei abschreiben können. Seine Aufgabe
  ist Zugriffskontrolle: wer ihn kennt, kann die Anfrage laden.
- `verificationCode`: eine **dreistellige Zahl** (000–999) reicht bewusst — niedrige Entropie ist
  hier **unkritisch**, weil er keine Zugriffsentscheidung trifft, sondern nur verglichen wird (siehe
  oben) — ihn zu erraten schaltet nichts frei. Kürzer als `pairingCode`, weil er nie eingegeben,
  sondern nur auf zwei Bildschirmen gleichzeitig gelesen wird.

Weil manuelle Eingabe ein regulärer Weg ist (nicht nur "Kamera nicht verfügbar"), braucht der
`input`-Schritt von `confirm-qr-login` einen eigenen, IP-/anonymen Zähler auf fehlgeschlagene
`pairingCode`-Lookups — nicht den bestehenden pro-`ToolSession`-Retry-Zähler, der hier ins Leere
liefe: es existiert an dieser Stelle noch kein Account, an den sich ein Zähler hängen ließe
(dieselbe Lücke wie `[[account_level_rate_limiting]]`, hier aber gegen anonymes Erraten statt gegen
ein bekanntes Konto). Bei 8 Zeichen aus einem 32er-Alphabet ist das kein optionales Add-on mehr,
sondern Voraussetzung dafür, dass manuelle Eingabe überhaupt sicher angeboten werden darf.

### Nebenläufigkeit: atomarer Zustandsübergang

`accept`/`reject` müssen als bedingtes Update implementiert werden, nicht als Lesen-dann-Schreiben:

```sql
UPDATE qr_login_request
SET status = :newStatus, resolving_account_id = :accountId
WHERE pairing_code = :pairingCode AND status = 'PENDING'
```

Betroffene Zeilen prüfen: `0` heißt, die Anfrage wurde zwischenzeitlich bereits entschieden oder ist
abgelaufen — das Tool meldet dann `Failed("Anfrage wurde bereits bearbeitet oder ist abgelaufen")`,
statt den Schreibvorgang zu wiederholen oder (schlimmer) zwei Accounts gleichzeitig als
`resolvingAccountId` gelten zu lassen.

---

## 7) Keycloak-Seite

Ein neues Stück im `keycloak-extension`-Modul, nach etabliertem Muster:

- **`WebToolRenderer` für `auth-qr`/`auth-qr-lookup`** (`webtool/qr/`, wie `webtool/email/` etc.):
  rendert `stepData.pairingCode` als QR-Bild (neue Abhängigkeit: ZXing) plus `verificationCode` und
  den Demo-Link, und bettet ein kleines JS ein, das die Wartephase pollt.

**Kein separater Statusproxy.** Ursprünglich hier vorgesehen (eigener `RealmResourceProvider` +
neuer, schlanker Orchestrator-Endpunkt, um die generische Tool-Patch-Maschinerie vor
Sekundentakt-Zugriffen zu schützen) — bei genauerem Blick unnötig: `JourneyService`/
`ToolControllerSupport` laden weder den Attempt-Zähler (`chargeAttempt`) noch den Login-Throttle
(`chargeThrottles`) für ein `InProgress`-Ergebnis, beide reagieren ausschließlich auf
`Failed`/`Completed`. Ein leeres `PATCH` auf `auth-qr`/`auth-qr-lookup`, solange die Anfrage noch
`PENDING` ist, kostet also nirgends etwas. Das Browser-JS sendet deshalb einfach das bestehende
Formular alle paar Sekunden erneut ab (`setTimeout` + `form.submit()`) und nutzt damit den ohnehin
vorhandenen generischen Tool-Patch-Endpunkt direkt — kein neuer Endpunkt auf keiner der beiden
Seiten nötig.

---

## 8) Offene Fragen

1. **Ablauf-Timeout** des Pairing-Codes — aktuell 5 Minuten (`QR_LOGIN_TTL`), Größenordnung an
   bestehenden TAN-Timeouts orientiert (`enroll-sms`/`auth-sms`), aber nicht weiter validiert.
2. ~~QR-Encoder-Abhängigkeit~~ — entschieden: ZXing.
3. **Demo-Link-Form**: klartext klickbarer Link direkt neben dem QR-Bild, oder nur sichtbar wenn
   ein Demo-Flag gesetzt ist (analog zu `DemoInfo`/`response.demo()` bei den Mock-TANs)?
4. ~~Mehrfach-Polling-Schutz~~ — entschieden: keiner nötig, siehe Abschnitt 7 (`InProgress` kostet
   nirgends etwas).
5. **Kandidaten-Sichtbarkeit**: soll `auth-qr` (nach aktivem `enroll-qr`) in der normalen
   `selectMethod`-Auswahl neben `auth-sms`/`auth-password`/`auth-email` auftauchen, oder ist es
   (zumindest zunächst) nur über eine dedizierte "Mit App anmelden"-Aktion erreichbar? Berührt
   `WebToolAvailability` und die Web-seitige `orchestrator-select.ftl`.
6. **Fehlendes Opt-in bei der Bestätigung**: wenn `confirm-qr-login` einen Account ohne aktives
   `enroll-qr` lädt — hart abbrechen (wie oben skizziert), oder direkt anbieten, `enroll-qr`
   inline nachzuholen und danach fortzufahren?

---

## 9) Nächste Schritte (falls Umsetzung gewünscht)

0. **Vorab, unabhängig vom Feature**: `MethodRole.DEVICE_AUTH` → `IDENTIFIED_AUTH` umbenennen
   (Abschnitt 3) — betrifft `auth_sms`/`auth_password`/`auth_email`/`auth_device`, die zugehörigen
   Docs und Tests.
1. `ToolCategory.SIDE_ACTION` / `MethodRole.PEER_APPROVAL` / `Completed.Approved` in `tool_spi`
   ergänzen; jede bestehende exhaustive `when`-Verzweigung über `ToolCategory` explizit erweitern.
2. `AuthIntent.CONFIRM_PEER_LOGIN` ergänzen, Journey-Strategie bauen, die alle drei Startzustände
   (nicht authentifiziert / `loa1` / `loa2`+) abdeckt — kombiniert Bausteine aus `FAST_ACCESS`s
   Fallback-Kette und `MANAGE_AUTH_METHODS`s Step-up (Abschnitt 4).
3. Modul `auth_qr` anlegen: `QrLoginRequest`-Entity (inkl. `verificationCode`), Descriptors
   (`enroll-qr`, WEB + APP), Handler mit atomarem `accept`/`reject`-Übergang (Abschnitt 6),
   Controller.
4. `keycloak-extension`: `WebToolRenderer` + Template für `auth-qr`/`auth-qr-lookup` (QR-Bild **und**
   `verificationCode` gut sichtbar, Auto-Resubmit-JS für die Wartephase).
5. App-Frontend: Deep-Link-Handling für `intent=confirm_peer_login&pairingCode=…`, Consent-Screen
   mit `verificationCode`-Anzeige.
6. Offene Fragen (Abschnitt 8) klären, insbesondere Timeout und Sichtbarkeit in der Methodenauswahl.
