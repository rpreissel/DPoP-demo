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
an, bindet Maras `person_id` als Anker und fragt seine `AuthPolicy`: Reicht das schon? Nein —
eine reine Identifikation begründet noch kein Login-Mittel. `next` zeigt jetzt auf `enroll-sms`:
Mara richtet ihre Handynummer als zweites Verfahren ein, bekommt eine TAN per SMS und bestätigt
sie. Erst danach ist ein Login-Mittel vorhanden, und die Journey ist abgeschlossen.

*Konzepte: [`AuthIntent`/Journey](04-orchestrierung.md) Abschnitt 1, [Tool-Vertrag](03-tool-architektur.md),
[`next`/`stepData`](05-api.md), [Registrierungs-Ablauf im Detail](06-ablaeufe.md).*

## 3) Mara bekommt ihr AccessToken

Mit dem abgeschlossenen Login-Mittel stellt der Orchestrator serverseitig ein `AccessToken`
gegen Keycloak aus — ein Standard-OIDC-Tokenfluss, den Mara nie zu Gesicht bekommt, nur das
Ergebnis. Ihr `ChannelSession.state` wechselt auf `AUTHENTICATED`. Ab jetzt ruft die App mit
diesem Token die eigentliche Fachlichkeit — andere Microservices — **direkt** auf, ohne den
Orchestrator dafür je wieder zu brauchen. Registrierung und Login waren nur die Voraussetzung
dafür, nie der eigentliche Zweck.

*Konzepte: [Tokenfluss](01-ueberblick.md), [ADR-9](12-entscheidungen.md).*

## 4) Mara kommt Wochen später wieder

Mara öffnet die App erneut, auf demselben Gerät. Weil ihr Konto beim Enrollment automatisch mit
diesem Gerät verknüpft wurde (`DeviceAccountLink`), bietet der Orchestrator diesmal direkt ihr
stärkstes bekanntes Verfahren an — Gerätebindung, wenn eingerichtet, sonst SMS als Fallback.
Lehnt Mara das bevorzugte Verfahren ab, fällt die Journey auf die nächste Alternative zurück;
das Ziel heißt jetzt `FAST_ACCESS`: möglichst reibungslos anmelden, mit Rückfallebenen, statt
erneut komplett zu identifizieren. Anders als bei `REGISTER` ist hier kein Schritt *Pflicht*
außer dem am Ende tatsächlich erreichten Niveau selbst.

*Konzepte: [Fallback vs. Pflicht, `FAST_ACCESS`](04-orchestrierung.md) Einstieg für Fachexperten.*

## 5) Mara will ihre Anmeldeverfahren verwalten

Mara möchte ein zweites Gerät hinzufügen. Das ist die Journey `MANAGE_AUTH_METHODS` — sie setzt
einen bereits `AUTHENTICATED`-Kanal voraus und verlangt zusätzlich Niveau `loa2`. Mara ist aber
nur mit ihrer SMS-TAN eingeloggt, die allein nur `loa1` trägt (`enrolledUnderAcr`): Die Methode
kann beim Login nie mehr Vertrauen erzeugen, als bei ihrer Einrichtung vorhanden war. Der
Orchestrator verlangt daher einen `STEP_UP` — Mara identifiziert sich einmal erneut per
`ident-fsc`, bevor sie ihre Methoden ändern darf. Erst danach akzeptiert die Journey die
Änderung.

*Konzepte: [Sub-Journey `STEP_UP`](04-orchestrierung.md) Abschnitt 3,
[dreifache Niveau-Deckelung, ADR-5](12-entscheidungen.md).*

## 6) Mara löscht ihr Konto

Ein Jahr später will Mara ihr Konto endgültig löschen. `DELETE_ACCOUNT` verlangt zunächst eine
unbedingte Ja/Nein-Bestätigung, dann `loa2` **und** einen frisch bewiesenen Faktor — nicht
irgendeinen alten Nachweis aus dem laufenden Kanal. Erst wenn beides steht, löscht der
Orchestrator Maras Konto samt aller Anker und Geräte-Verknüpfungen und synchronisiert das nach
Keycloak. Jeder dieser Schritte — welches Verfahren wann angeboten, angenommen oder abgelehnt
wurde — bleibt im Journey-Log nachvollziehbar, ohne dass jemand verteilte Systemlogs
rekonstruieren müsste.

*Konzepte: [`DELETE_ACCOUNT`](04-orchestrierung.md) Abschnitt 3, [Journey-Log](04-orchestrierung.md).*

---

## Was das Beispiel an Konzepten zusammenbindet

| Im Beispiel | Begriff | Im Code |
|---|---|---|
| Maras Verbindung zur App, über Monate hinweg | `ChannelSession` | [02-domaenenmodell.md](02-domaenenmodell.md) |
| „Ich will mich registrieren" / „Ich will nur schnell rein" | `AuthIntent` (`REGISTER`, `FAST_ACCESS`, ...) | [04-orchestrierung.md](04-orchestrierung.md) |
| Der eine Kartenlese-Vorgang, die eine TAN-Eingabe | `Tool` (`ident-fsc`, `enroll-sms`, ...) | [03-tool-architektur.md](03-tool-architektur.md) |
| „Was soll die App jetzt anzeigen?" | `next`/`stepData` | [05-api.md](05-api.md) |
| „Reicht das schon fürs Login/für diese Aktion?" | Niveau (`loa1`/`loa2`/`loa3`) | [04-orchestrierung.md](04-orchestrierung.md) |
| „Ist das wirklich Maras Gerät?" | DPoP-Proof | [09-dpop.md](09-dpop.md) |

Jeder dieser Schritte funktioniert für Maras App-Kanal genauso wie für einen Browser-Login über
Keycloak — nur *wer rendert* und *wie der Request abgesichert ist* unterscheidet sich zwischen
den beiden Kanälen ([05-api.md](05-api.md)).
