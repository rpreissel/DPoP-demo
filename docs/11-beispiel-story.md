# Beispiel: Mara registriert sich, kommt wieder, hebt ihr Niveau an

Dieses Kapitel erzählt ein einziges, durchgehendes Beispiel. Daran werden die Grundbegriffe (Kanal,
Journey, Tool, `next`, Niveau, DPoP) greifbar. Es ist bewusst knapp und nicht vollständig. Für jeden
Schritt verweist der Text auf das Kapitel, das ihn genau beschreibt.

---

## 1) Mara installiert die App

Mara öffnet die App zum ersten Mal. Die App erzeugt auf dem Gerät ein Schlüsselpaar (Web Crypto
API; der private Schlüssel lässt sich nicht exportieren). Ihre erste Anfrage schickt sie mit einem
`DPoP`-Proof statt mit einem Passwort oder Zertifikat. Das Backend legt daraufhin eine
`ChannelSession(APP)` an, den Kanal für diese Nutzung. Er ist an den Fingerabdruck ihres Schlüssels
gebunden (`binding_key_ref`). Noch ist der Kanal `ANONYMOUS`, denn es gibt kein Konto, das zu diesem
Gerät gehört.

Dieser Schlüssel ist noch **kein Anmeldeverfahren**. Er beweist, dass zwei Anfragen vom selben Gerät
kommen, aber nicht, wer Mara ist. Ihr Gerät als Anmeldeverfahren richtet sie erst in Kapitel 6 ein,
und das ist ein anderer Vorgang.

*Konzepte: [`ChannelSession`](02-domaenenmodell.md), [DPoP-Proof](09-dpop.md).*

## 2) Mara registriert sich

Die App startet eine Journey mit dem Ziel `REGISTER`. Das Ziel drückt Maras Wunsch aus, sich neu
auszuweisen; es beschreibt nicht nur den technischen Ablauf dahinter. Der Orchestrator bietet zuerst
die Identifizierungsverfahren zur Auswahl an (`ident-fsc`, `ident-eid`, `ident-nect`). Mara wählt
`ident-fsc`, also den Freischaltcode, den ihr das Personenverzeichnis per Brief geschickt hat. Die
App weiß nicht von selbst, was jetzt an der Reihe ist. Sie folgt nur `next`, einer reinen Adresse in
der Antwort des Backends, und sucht dazu über eine feste Tabelle die passende Komponente der
Oberfläche heraus.

Mara gibt ihre Versichertennummer (KVNR), Namen, Vornamen und Geburtsdatum ein und danach den Code
aus dem Brief. `ident-fsc` ist ein eigenes Tool-Modul. Es prüft die Angaben gegen das
Personenverzeichnis und den Code gegen die dort ausgestellten Freischaltcodes. Dem Orchestrator
meldet es ein `ToolOutcome.Completed.Identified` mit einem `PERSON_ID`-Claim (ihrer Partnernummer)
und den geprüften Angaben. Für diese Angaben steht das Personenverzeichnis ein, nicht das Tool. Der
Orchestrator legt daraufhin ein neues Konto an. Er bindet Maras `person_id` und, weil sie bei uns
versichert ist, auch ihre Versicherungsnummer als Anker. Die Sitzung steht damit auf `loa2`, denn
dieses Niveau erreicht das Verfahren allein.

Fertig ist die Registrierung damit nicht. Eine Identifizierung ist kein Anmeldeverfahren: Sie sagt,
**wer** jemand ist, aber nicht, **womit** er beim nächsten Mal wieder hereinkommt. Es folgen zwei
Pflichtschritte:

1. **Adresse bestätigen** (`confirm-email`). Die bestätigte Adresse gehört zur Grundausstattung des
   Kontos, ist aber kein Anmeldeverfahren. Drei Arten der Anmeldung über die E-Mail-Adresse finden
   das Konto über sie, und `enroll-password` setzt sie voraus. Mara bekommt einen Code und gibt ihn
   ein.
2. **Anmeldeverfahren einrichten.** Mara wählt SMS (`enroll-sms`) und bekommt eine TAN. Damit könnte
   sie sich anmelden, aber nur auf `loa1`: SMS beweist nur den Besitz des Telefons und reicht deshalb
   höchstens für `loa1`. Ein Konto, das dort stehen bleibt, kommt nie wieder an seine eigene
   Verwaltung heran, denn die verlangt `loa2`. Deshalb verlangt die Journey ein zweites Verfahren
   **anderer Art**: `enroll-password` (Wissen). Erst Besitz und Wissen zusammen erreichen `loa2`.

Beide Verfahren übernehmen das Niveau, das die Sitzung beim Einrichten nachgewiesen hatte, hier also
die `loa2` der Identifizierung. Dieses Niveau bleibt dauerhaft an ihnen gespeichert
(`enrolledUnderAcr`) und begrenzt später, was ihre Kombination höchstens erreichen kann (ADR-5:
Niemand soll sich selbst höher einstufen). Hätte Mara statt Passwort und SMS gleich ihr Gerät
eingerichtet, wäre der Schritt mit dem Passwort entfallen: `enroll-device` bringt Besitz und Wissen
(oder Biometrie) schon allein mit.

*Konzepte: [`AuthIntent`/Journey](04-orchestrierung.md) Abschnitt 1, [Tool-Vertrag](03-tool-architektur.md),
[`next`/`stepData`](05-api.md), [Registrierung im Detail](06-ablaeufe.md),
[Adresse ≠ Anmeldeverfahren, ADR-17](12-entscheidungen.md).*

## 3) Mara bekommt ihr AccessToken

Sobald die Anmeldung abgeschlossen ist, lässt der Orchestrator auf dem Server ein `AccessToken` von
Keycloak ausstellen. Das ist ein gewöhnlicher OIDC-Tokenfluss. Mara sieht davon nichts, nur das
Ergebnis. Ihr `ChannelSession.state` wechselt auf `AUTHENTICATED`. Ab jetzt ruft die App mit diesem
Token die eigentlichen Fachdienste auf, also andere Microservices, und zwar **direkt**, ohne den
Orchestrator dafür je wieder zu brauchen. Registrierung und Anmeldung waren nur die Voraussetzung
dafür, nie der eigentliche Zweck.

*Konzepte: [Tokenfluss](01-ueberblick.md), [ADR-9](12-entscheidungen.md).*

## 4) Mara kommt Wochen später wieder

Mara öffnet die App erneut, auf demselben Gerät. Als sie ihre Verfahren eingerichtet hat, wurde ihr
Konto mit diesem Gerät verknüpft (`DeviceAccountLink`). Das ist nur eine **Wiedererkennung**: Der
Orchestrator weiß dadurch, um welches Konto es geht, bevor Mara irgendetwas bewiesen hat. Einen
Nachweis stellt die Verknüpfung nicht dar.

Das Ziel heißt jetzt `FAST_ACCESS`: möglichst bequem anmelden, mit Ausweichwegen, statt sich wieder
ganz neu auszuweisen. Hat das Konto ein an das Gerät gebundenes Verfahren, bietet der Orchestrator
dieses zuerst an. Mara hat noch keines, also bekommt sie die Wahl zwischen SMS und Passwort. Sie
nimmt die SMS und ist angemeldet, auf `loa1`: ein Verfahren, ein Niveau. Lehnt sie ein angebotenes
Verfahren ab, weicht die Journey auf die nächste Möglichkeit aus. Anders als bei `REGISTER` ist hier
kein Schritt *Pflicht*; es zählt nur das Niveau, das am Ende tatsächlich erreicht ist.

*Konzepte: [Ausweichweg vs. Pflicht](04-orchestrierung.md) Einstieg für Fachexperten,
[`FAST_ACCESS`](journeys/fast-access.md), [Bindung an die ChannelSession](09-dpop.md) Abschnitt 3.*

## 5) Mara will ihre Anmeldeverfahren verwalten

Mara möchte ein zweites Verfahren hinzufügen. Dafür gibt es die Journey `MANAGE_AUTH_METHODS`. Sie
setzt einen Kanal voraus, der bereits `AUTHENTICATED` ist, und verlangt zusätzlich `loa2` **in dieser
Sitzung**. Mara ist aber nur mit ihrer SMS-TAN angemeldet, also auf `loa1`.

Der Orchestrator verlangt deshalb einen `STEP_UP`. Zwei Wege führen nach oben, und beide stehen ihr
offen:

- **Ein zweites Verfahren.** Mara gibt zusätzlich ihr Passwort ein. Zwei verschiedene Verfahren mit
  zwei verschiedenen Faktortypen (Besitz und Wissen) heben das Niveau um eine Stufe. Begrenzt ist das
  durch das Niveau, auf dem die Verfahren selbst eingerichtet wurden. Beide sind in ihrer Sitzung mit
  `loa2` entstanden, deshalb reicht es.
- **Erneut ausweisen.** `ident-fsc` erreicht `loa2` allein, ohne Kombination. Dieser Weg ist der
  Ausweg für Konten mit nur einem einzigen Verfahren, die sonst nie wieder an ihre eigene Verwaltung
  herankämen.

Erst danach nimmt die Journey die Änderung an.

*Konzepte: [Sub-Journey `STEP_UP`](journeys/step-up.md),
[Begrenzung des Niveaus, ADR-5](12-entscheidungen.md).*

## 6) Mara macht ihr Gerät zum Anmeldeverfahren

Jetzt, auf `loa2`, richtet Mara zwei Dinge ein.

**`enroll-device`**: Die App erzeugt einen zweiten Schlüssel, nur für dieses Konto, im sicheren
Speicher des Geräts, und Mara schaltet ihn mit Face ID frei. Gespeichert wird nur der öffentliche
Teil; der private verlässt das Gerät nie. Anders als die Wiedererkennung des Geräts aus Kapitel 4 ist
das ein echtes Anmeldeverfahren: Es weist Besitz **und** Wissen bzw. Biometrie nach und erreicht
damit allein `loa2`. Ab jetzt bietet `FAST_ACCESS` ihr genau dieses Verfahren zuerst an: Gerät in die
Hand, ein Blick, fertig, ohne SMS und ohne Passwort.

**`enroll-qr`**: eine reine Zustimmung ohne Geheimnis (`factorTypes = {}`). Sie sagt nur: „Dieses
Konto darf Anmeldungen auf anderen Geräten per QR-Code bestätigen.“ Geprüft wird sie dort, wo es
darauf ankommt, nämlich beim Bestätigen in Kapitel 7. Ohne sie bestätigt die App nichts.

*Konzepte: [`MANAGE_AUTH_METHODS`](journeys/manage-auth-methods.md),
[`factorTypes`/Tool-Katalog](03-tool-architektur.md), [Verfahren einrichten](06-ablaeufe.md).*

## 7) Mara meldet sich am Laptop an, mit dem Handy

Am Abend will Mara sich am Laptop im Kundenportal anmelden. Der Browser nutzt nicht den App-Kanal,
sondern den Web-Kanal mit Keycloak davor. Es sind dieselben Journeys und dieselben Tools. Nur zeigt
dort Keycloak die Oberfläche statt der App, und die Anfragen sichert eine Sitzung statt eines
DPoP-Proofs.

Der Browser zeigt einen QR-Code (`auth-qr-lookup`) und wartet. Wer Mara ist, weiß dieser Kanal noch
nicht; das stellt sich erst heraus, wenn das Handy zustimmt. Mara scannt den Code mit ihrer App, und
dort startet die Journey `CONFIRM_PEER_LOGIN`. Bevor sie zustimmen darf, verlangt der Orchestrator
zweierlei von der Sitzung in der **App**:

1. Die Sitzung muss selbst `loa2` erreichen. Eine Sitzung auf `loa1` darf nicht für eine Anmeldung
   anderswo einstehen. Mara erledigt das mit einem Blick in die Kamera, denn ihr Geräteverfahren aus
   Kapitel 6 erreicht `loa2`.
2. Sie muss **neu** beweisen, dass gerade jetzt Mara am Gerät sitzt. Ein alter Nachweis von heute
   Morgen darf die Zustimmung nicht geben.

Erst dann erscheint `confirm-qr-login`. Mara tippt auf „Bestätigen“, und der Browser ist angemeldet,
auf `loa2`, ohne dass dort je ein Passwort eingegeben wurde. Geschenkt ist das Niveau dabei nicht:
`auth-qr` darf es nur melden, weil die App vorher selbst die Schwelle `loa2` erreichen musste. Die
Anmeldung per QR-Code gibt nur weiter, was in der App schon bewiesen wurde.

*Konzepte: [`CONFIRM_PEER_LOGIN`](journeys/confirm-peer-login.md),
[Web-Zugang über Keycloak](05-api.md) Abschnitt 3, [`PEER_APPROVAL` als eigene Rolle](03-tool-architektur.md).*

## 8) Mara löscht ihr Konto

Ein Jahr später will Mara ihr Konto endgültig löschen. `DELETE_ACCOUNT` verlangt zuerst eine
Bestätigung mit Ja oder Nein, dann `loa2` **und** einen neu erbrachten Nachweis. Ein alter Nachweis
aus dem laufenden Kanal genügt nicht; das ist dieselbe Vorsicht wie bei der Anmeldung per QR-Code.
Erst wenn beides erfüllt ist, löscht der Orchestrator Maras Konto mit allen Ankern, Verfahren,
Verknüpfungen zu Geräten und ihrem Journey-Log. Er meldet alle ihre Sitzungen ab und überträgt die
Löschung nach Keycloak. Bis dahin ließ sich jeder Schritt im Journey-Log nachvollziehen: welches
Verfahren wann angeboten, angenommen oder abgelehnt wurde, ohne dass jemand verteilte Logs mehrerer
Systeme zusammensuchen musste. Nach der Löschung bleibt davon bewusst nichts übrig.

*Konzepte: [`DELETE_ACCOUNT`](journeys/delete-account.md), [Journey-Log](04-orchestrierung.md).*

---

## Maras Verfahren und was sie erreichen

| Verfahren | Faktortyp | Erreicht allein | Kam ins Spiel |
|---|---|---|---|
| `ident-fsc` (Freischaltcode per Brief) | Identifizierung, kein Anmeldeverfahren | `loa2` | Kapitel 2, erneut in 5 |
| bestätigte Adresse | keine – gehört zur Grundausstattung des Kontos | — | Kapitel 2 |
| `sms` | Besitz | `loa1` | Kapitel 2 |
| `password` | Wissen | `loa1` | Kapitel 2 |
| `sms` + `password` zusammen | Besitz + Wissen | `loa2` | Kapitel 5 |
| `device` | Besitz + Wissen/Biometrie | `loa2` | Kapitel 6 |
| `qr` | Besitz + Wissen (aus der App übernommen) | `loa2` | Kapitel 6/7 |
| DPoP-Schlüssel / `DeviceAccountLink` | keine – nur Wiedererkennung | — | Kapitel 1/4 |

## Welche Begriffe das Beispiel verbindet

| Im Beispiel | Begriff | Im Code |
|---|---|---|
| Maras Verbindung zur App während einer Nutzung (das Gerät selbst merkt sich `DeviceAccountLink`) | `ChannelSession` | [02-domaenenmodell.md](02-domaenenmodell.md) |
| „Ich will mich registrieren“ / „Ich will nur schnell rein“ | `AuthIntent` (`REGISTER`, `FAST_ACCESS`, …) | [04-orchestrierung.md](04-orchestrierung.md) |
| Die eine Eingabe des Freischaltcodes, die eine Eingabe der TAN | `Tool` (`ident-fsc`, `enroll-sms`, …) | [03-tool-architektur.md](03-tool-architektur.md) |
| „Was soll die App jetzt anzeigen?“ | `next`/`stepData` | [05-api.md](05-api.md) |
| „Reicht das schon für die Anmeldung oder für diese Aktion?“ | Niveau (`loa1`/`loa2`/`loa3`) | [04-orchestrierung.md](04-orchestrierung.md) |
| „Besitz, Wissen, Biometrie – wie viele davon?“ | `factorTypes`, Kombination mehrerer Faktoren | [04-orchestrierung.md](04-orchestrierung.md) |
| „Ist das wirklich Maras Gerät?“ | DPoP-Proof | [09-dpop.md](09-dpop.md) |
| „Darf dieses Handy für eine Anmeldung anderswo einstehen?“ | `CONFIRM_PEER_LOGIN`, `PEER_APPROVAL` | [04-orchestrierung.md](04-orchestrierung.md) |

Jeder dieser Schritte funktioniert für Maras App-Kanal genauso wie für eine Anmeldung im Browser über
Keycloak. Zwischen den beiden Kanälen unterscheidet sich nur, *wer die Oberfläche zeigt* und *wie die
Anfrage abgesichert ist* ([05-api.md](05-api.md)). Kapitel 7 zeigt beide zugleich: dieselbe Nutzerin,
zwei Kanäle, und der eine steht für den anderen ein.
