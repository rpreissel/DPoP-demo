# Beispiel: Mara registriert sich, kommt wieder, hebt ihr Niveau an

Ein einziges, durchgängiges Beispiel, an dem die Kernbegriffe (Channel, Journey, Tool, `next`,
Niveau, DPoP) konkret werden — kompakt statt vollständig. Für jede Ausbaustufe verweist der
Text auf das Kapitel, das sie im Detail beschreibt.

---

## 1) Mara installiert die App

Mara öffnet die App zum ersten Mal. Die App erzeugt lokal ein Schlüsselpaar (Web Crypto API,
privater Key nicht exportierbar) und schickt ihren ersten Request mit einem `DPoP`-Proof statt
eines Passworts oder Zertifikats. Das Backend legt daraufhin eine `ChannelSession(APP)` an — den
langlebigen Kanal für Maras Gerät, verankert am Fingerabdruck ihres Schlüssels
(`binding_key_ref`). Noch ist der Kanal `ANONYMOUS`: Es gibt noch kein Konto, das zu diesem
Gerät gehört.

Dieser Schlüssel ist noch **kein Anmeldeverfahren**. Er beweist, dass zwei Requests vom selben
Gerät kommen — nicht, wer Mara ist. Ein Gerät als Anmeldeverfahren richtet sie erst in Kapitel 6
ein, und das ist ein anderer Vorgang.

*Konzepte: [`ChannelSession`](02-domaenenmodell.md), [DPoP-Proof](09-dpop.md).*

## 2) Mara registriert sich

Die App startet die Journey mit dem Ziel `REGISTER` — Maras erklärter Wunsch, sich neu zu
identifizieren, nicht bloß der technische Ablauf dahinter. Der Orchestrator bietet als
Erstes das passende Identifikationsverfahren an: `ident-fsc` (ihre Gesundheitskarte). Die App
weiß nicht von sich aus, dass jetzt `ident-fsc` dran ist — sie folgt nur `next`, einer reinen
Adresse in der Antwort des Backends, und bildet daraus über eine feste, lokale Routing-Tabelle
die passende UI-Komponente ab.

Mara liest ihre Karte. `ident-fsc` (ein eigenes Tool-Modul) prüft das Ergebnis gegen die
Stammdaten und meldet dem Orchestrator nur ein `ToolOutcome.Completed.Identified` mit einem
`PERSON_ID`-Claim — nie die Kartendaten selbst. Der Orchestrator legt daraufhin ein neues Konto
an und bindet Maras `person_id` als Anker. Die Sitzung steht damit auf `loa2`: Das Verfahren
selbst trägt dieses Niveau.

Fertig ist die Registrierung damit nicht, denn eine Identifizierung ist kein Anmeldeverfahren —
sie sagt, **wer** jemand ist, nicht, **womit** er beim nächsten Mal wieder hereinkommt. Es folgen
zwei Pflichtschritte:

1. **Adresse bestätigen** (`confirm-email`). Die bestätigte Adresse ist Konto-Infrastruktur, kein
   Login-Mittel: Drei Lookup-Logins lösen das Konto darüber auf, und `enroll-password` setzt sie
   voraus. Mara bekommt einen Code und gibt ihn ein.
2. **Anmeldeverfahren einrichten.** Mara wählt SMS (`enroll-sms`) und bekommt eine TAN. Damit
   könnte sie sich anmelden — aber nur auf `loa1`: SMS ist ein reiner Besitz-Faktor, und ihre
   Obergrenze ist `loa1`. Ein Konto, das dort stehen bliebe, käme nie wieder an seine eigene
   Verwaltung heran, denn die verlangt `loa2`. Also verlangt die Journey ein zweites Verfahren
   **anderer Art**: `enroll-password` (Wissen). Erst die Kombination aus Besitz und Wissen trägt
   `loa2`.

Beide Verfahren werden mit dem bezahlt, was die Sitzung beim Einrichten bewiesen hatte — hier die
`loa2` der Identifizierung. Dieses Niveau bleibt an ihnen kleben (`enrolledUnderAcr`) und deckelt
später, was ihre Kombination höchstens erreichen kann (ADR-5, keine Selbst-Eskalation). Hätte Mara
statt Passwort und SMS gleich ihr Gerät eingerichtet, wäre der Passwort-Schritt entfallen:
`enroll-device` bringt Besitz und Wissen (oder Biometrie) schon allein mit.

*Konzepte: [`AuthIntent`/Journey](04-orchestrierung.md) Abschnitt 1, [Tool-Vertrag](03-tool-architektur.md),
[`next`/`stepData`](05-api.md), [Registrierungs-Ablauf im Detail](06-ablaeufe.md),
[Adresse ≠ Login-Mittel, ADR-17](12-entscheidungen.md).*

## 3) Mara bekommt ihr AccessToken

Mit dem abgeschlossenen Login-Mittel stellt der Orchestrator serverseitig ein `AccessToken`
gegen Keycloak aus — ein Standard-OIDC-Tokenfluss, den Mara nie zu Gesicht bekommt, nur das
Ergebnis. Ihr `ChannelSession.state` wechselt auf `AUTHENTICATED`. Ab jetzt ruft die App mit
diesem Token die eigentliche Fachlichkeit — andere Microservices — **direkt** auf, ohne den
Orchestrator dafür je wieder zu brauchen. Registrierung und Login waren nur die Voraussetzung
dafür, nie der eigentliche Zweck.

*Konzepte: [Tokenfluss](01-ueberblick.md), [ADR-9](12-entscheidungen.md).*

## 4) Mara kommt Wochen später wieder

Mara öffnet die App erneut, auf demselben Gerät. Beim Einrichten ihrer Verfahren wurde ihr Konto
mit diesem Gerät verknüpft (`DeviceAccountLink`) — eine reine **Wiedererkennung**: Der
Orchestrator weiß dadurch, welches Konto er ansprechen soll, bevor Mara irgendetwas bewiesen hat.
Ein Nachweis ist die Verknüpfung nicht.

Das Ziel heißt jetzt `FAST_ACCESS`: möglichst reibungslos anmelden, mit Rückfallebenen, statt
erneut komplett zu identifizieren. Der Orchestrator bietet zuerst ein gerätegebundenes Verfahren
an, wenn das Konto eines hat — Mara hat noch keines, also bekommt sie die Wahl zwischen SMS und
Passwort. Sie nimmt die SMS und ist drin, auf `loa1`: ein Faktor, ein Niveau. Lehnt sie ein
angebotenes Verfahren ab, fällt die Journey auf die nächste Alternative zurück. Anders als bei
`REGISTER` ist hier kein Schritt *Pflicht* außer dem am Ende tatsächlich erreichten Niveau selbst.

*Konzepte: [Fallback vs. Pflicht](04-orchestrierung.md) Einstieg für Fachexperten,
[`FAST_ACCESS`](04-orchestrierung.md) Abschnitt 3, [Bindung an die ChannelSession](09-dpop.md) Abschnitt 3.*

## 5) Mara will ihre Anmeldeverfahren verwalten

Mara möchte ein zweites Verfahren hinzufügen. Das ist die Journey `MANAGE_AUTH_METHODS` — sie
setzt einen bereits `AUTHENTICATED`-Kanal voraus und verlangt zusätzlich `loa2` **in dieser
Sitzung**. Mara ist aber nur mit ihrer SMS-TAN eingeloggt, also auf `loa1`.

Der Orchestrator verlangt daher einen `STEP_UP`. Zwei Wege führen hinauf, und beide stehen ihr
offen:

- **Zweiter Faktor.** Mara gibt zusätzlich ihr Passwort ein. Zwei verschiedene Verfahren mit zwei
  verschiedenen Faktorarten (Besitz + Wissen) heben das Niveau um eine Stufe — gedeckelt durch das
  Niveau, unter dem die Verfahren selbst eingerichtet wurden. Da beide in ihrer
  `loa2`-Identifizierungssitzung entstanden sind, reicht es.
- **Erneut identifizieren.** `ident-fsc` trägt `loa2` aus sich heraus, ohne Kombination. Dieser
  Weg ist die Notausgangstür für Konten, die nur ein einziges Verfahren haben — sonst kämen sie
  nie wieder an ihre eigene Verwaltung.

Erst danach akzeptiert die Journey die Änderung.

*Konzepte: [Sub-Journey `STEP_UP`](04-orchestrierung.md) Abschnitt 3,
[dreifache Niveau-Deckelung, ADR-5](12-entscheidungen.md).*

## 6) Mara macht ihr Gerät zum Anmeldeverfahren

Jetzt, auf `loa2`, richtet Mara zwei Dinge ein.

**`enroll-device`**: Die App erzeugt einen zweiten, ausschließlich für das Konto bestimmten
Schlüssel im sicheren Speicher des Geräts und lässt Mara ihn per Face ID freischalten. Was
gespeichert wird, ist nur der öffentliche Teil — der private verlässt das Gerät nie. Anders als
die Gerätebindung aus Kapitel 4 ist das ein echtes Anmeldeverfahren: Es deklariert Besitz **und**
Wissen beziehungsweise Biometrie und trägt damit `loa2` allein. Ab jetzt bietet `FAST_ACCESS` ihr
genau dieses Verfahren zuerst an — ein Griff, ein Blick, fertig, ohne SMS und ohne Passwort.

**`enroll-qr`**: ein reiner Opt-in-Marker ohne Geheimnis (`factorTypes = {}`). Er sagt nur:
„Dieses Konto darf sich per QR an anderen Geräten anmelden." Geprüft wird er dort, wo es darauf
ankommt — beim Bestätigen in Kapitel 7; ohne ihn bestätigt die App nichts.

*Konzepte: [`MANAGE_AUTH_METHODS`](04-orchestrierung.md) Abschnitt 3,
[`factorTypes`/Tool-Katalog](03-tool-architektur.md), [Verfahren einrichten](06-ablaeufe.md).*

## 7) Mara meldet sich am Laptop an — mit dem Handy

Am Abend will Mara am Laptop ins Portal. Der Browser läuft nicht über den App-Kanal, sondern über
den Web-Kanal vor Keycloak: dieselben Journeys, dieselben Tools, nur rendert dort Keycloak statt
der App, und den Request sichert eine Session statt eines DPoP-Proofs.

Der Browser zeigt einen QR-Code (`auth-qr-lookup`) und wartet. Wer Mara ist, weiß dieser Kanal
noch nicht — das verrät erst die Zustimmung vom Handy. Mara scannt den Code mit ihrer App, und
dort startet die Journey `CONFIRM_PEER_LOGIN`. Bevor sie zustimmen darf, verlangt der
Orchestrator zweierlei von der **App**-Sitzung:

1. Sie muss selbst `loa2` erreichen — eine `loa1`-Sitzung kann keinen Login anderswo verbürgen.
   Mara löst das mit einem Blick in die Kamera; ihr Geräteverfahren aus Kapitel 6 trägt `loa2`.
2. Sie muss **frisch** beweisen, dass gerade jetzt Mara am Gerät sitzt und nicht ein alter
   Nachweis von heute Morgen die Zustimmung gibt.

Erst dann erscheint `confirm-qr-login`, Mara tippt auf „Bestätigen" — und der Browser ist
angemeldet, auf `loa2`, ohne dass dort je ein Passwort getippt wurde. Das Niveau ist kein
Geschenk: `auth-qr` darf es nur deshalb behaupten, weil die App-Seite vorher durch ihr eigenes
`loa2`-Tor musste. Der QR-Login reicht durch, was dort schon bewiesen wurde.

*Konzepte: [`CONFIRM_PEER_LOGIN`](04-orchestrierung.md) Abschnitt 3,
[Web-/Keycloak-Fassade](05-api.md) Abschnitt 3, [`PEER_APPROVAL` als eigene Rolle](03-tool-architektur.md).*

## 8) Mara löscht ihr Konto

Ein Jahr später will Mara ihr Konto endgültig löschen. `DELETE_ACCOUNT` verlangt zunächst eine
unbedingte Ja/Nein-Bestätigung, dann `loa2` **und** einen frisch bewiesenen Faktor — nicht
irgendeinen alten Nachweis aus dem laufenden Kanal, dieselbe Vorsicht wie beim QR-Login. Erst wenn
beides steht, löscht der Orchestrator Maras Konto samt aller Anker, Verfahren und
Geräte-Verknüpfungen und synchronisiert das nach Keycloak. Jeder dieser Schritte — welches
Verfahren wann angeboten, angenommen oder abgelehnt wurde — bleibt im Journey-Log
nachvollziehbar, ohne dass jemand verteilte Systemlogs rekonstruieren müsste.

*Konzepte: [`DELETE_ACCOUNT`](04-orchestrierung.md) Abschnitt 3, [Journey-Log](04-orchestrierung.md).*

---

## Maras Verfahren, und was sie tragen

| Verfahren | Faktorart | Trägt allein | Kam ins Spiel |
|---|---|---|---|
| `ident-fsc` (Karte) | Identifizierung, kein Login-Mittel | `loa2` | Kapitel 2, erneut in 5 |
| bestätigte Adresse | keine — Konto-Infrastruktur | — | Kapitel 2 |
| `sms` | Besitz | `loa1` | Kapitel 2 |
| `password` | Wissen | `loa1` | Kapitel 2 |
| `sms` + `password` zusammen | Besitz + Wissen | `loa2` | Kapitel 5 |
| `device` | Besitz + Wissen/Biometrie | `loa2` | Kapitel 6 |
| `qr` | Besitz + Wissen (durchgereicht) | `loa2` | Kapitel 6/7 |
| DPoP-Schlüssel / `DeviceAccountLink` | keine — Wiedererkennung | — | Kapitel 1/4 |

## Was das Beispiel an Konzepten zusammenbindet

| Im Beispiel | Begriff | Im Code |
|---|---|---|
| Maras Verbindung zur App, über Monate hinweg | `ChannelSession` | [02-domaenenmodell.md](02-domaenenmodell.md) |
| „Ich will mich registrieren" / „Ich will nur schnell rein" | `AuthIntent` (`REGISTER`, `FAST_ACCESS`, ...) | [04-orchestrierung.md](04-orchestrierung.md) |
| Der eine Kartenlese-Vorgang, die eine TAN-Eingabe | `Tool` (`ident-fsc`, `enroll-sms`, ...) | [03-tool-architektur.md](03-tool-architektur.md) |
| „Was soll die App jetzt anzeigen?" | `next`/`stepData` | [05-api.md](05-api.md) |
| „Reicht das schon fürs Login/für diese Aktion?" | Niveau (`loa1`/`loa2`/`loa3`) | [04-orchestrierung.md](04-orchestrierung.md) |
| „Besitz, Wissen, Biometrie — wie viele davon?" | `factorTypes`, MFA-Kombination | [04-orchestrierung.md](04-orchestrierung.md) |
| „Ist das wirklich Maras Gerät?" | DPoP-Proof | [09-dpop.md](09-dpop.md) |
| „Darf dieses Handy einen Login anderswo verbürgen?" | `CONFIRM_PEER_LOGIN`, `PEER_APPROVAL` | [04-orchestrierung.md](04-orchestrierung.md) |

Jeder dieser Schritte funktioniert für Maras App-Kanal genauso wie für einen Browser-Login über
Keycloak — nur *wer rendert* und *wie der Request abgesichert ist* unterscheidet sich zwischen
den beiden Kanälen ([05-api.md](05-api.md)). Kapitel 7 zeigt beide zugleich: Derselbe Nutzer,
zwei Kanäle, und der eine verbürgt den anderen.
