# Idee: Web-/Keycloak-Kanal als zweite Fassade (`kc`)

Status: **Umgesetzt** (bd-Epic `DPoP-demo-f9o`, inkl. Logout-Semantik und deren
Aufräum-Konsequenz, Abschnitt 11). Das Folge-Epic `DPoP-demo-3yd` (echte Keycloak-Anbindung,
über den ursprünglichen Entwurf hier hinausgehend) hat noch zwei offene Punkte (`3yd.4`, `3yd.6`)
- siehe dessen eigene bd-Beschreibung, nicht Teil dieser Idee. Die tragenden Entscheidungen sind
inzwischen kanonisch dokumentiert:
[02-domaenenmodell.md](../02-domaenenmodell.md) Abschnitt 1 (Kanal-Anker),
[05-api.md](../05-api.md) Abschnitt 3 (Endpunkt, Peer-Auth, `authData`, RestoreData),
[04-orchestrierung.md](../04-orchestrierung.md) Abschnitt 2/3 (`KC_SELECT_METHOD`),
[12-entscheidungen.md](../12-entscheidungen.md) ADR-7/ADR-8 (verworfene Alternativen).
Dieses Dokument bleibt bestehen, weil ~60 Code-Kommentare quer durch Backend und
Keycloak-Extension per Abschnittsnummer darauf verweisen - für neue Referenzen bitte
die kanonischen Dokumente oben verwenden. Abweichungen zwischen ursprünglichem Entwurf
und tatsächlicher Umsetzung sind als Kästen markiert (z. B. Abschnitt 2).

---

## 1) Ausgangslage und Leitplanken

Neben dem bestehenden App-Kanal entsteht eine zweite Fassade, über die Keycloak den
Orchestrator anspricht. Zwei Leitplanken stehen fest:

- **Der Browser spricht nie mit dem Orchestrator.** Die Strecke Keycloak →
  Orchestrator ist reine Server-zu-Server-Kommunikation; der Browser sieht nie eine
  Orchestrator-URL.
- **Kein mTLS.** ("Kein mTLS" heißt nicht "kein TLS" - serverseitiges TLS bleibt
  Voraussetzung, es entfällt nur das Client-Zertifikat.) Die Peer-Authentifizierung
  muss also auf Anwendungsebene stattfinden, produktionsnah.

Eine dritte Randbedingung: **Im Web-Kanal gibt es kein Gerät**, `DeviceAccountLink`
bleibt also APP-only. Identifikation/Authentifizierung ohne Geräte-Bindung läuft über
Keycloaks eigene native Authenticatoren, ergänzt um den bereits gebauten Lookup-Login
(E-Mail + Credential, siehe [Login-ohne-DPoP-Konzept](lookup_based_login_design.md))
für Faktoren, die es nur im Orchestrator gibt.

---

## 2) Kanal-Anker-Modell

> **Umgesetzt, abweichend vom ursprünglichen Entwurf unten.** Statt zweier separater
> Ankerfelder (`kcAuthSessionId`/`kcSessionId`) trägt `ChannelSession` ein einziges Feld
> `channelAnchor` - immer der eigene `channelSessionId`-Wert DIESES Flow-Durchlaufs, nie
> Keycloaks durables `UserSessionModel` (das hätte zwei GLEICHZEITIGEN Flow-Durchläufen
> derselben SSO-Session, z. B. zwei parallel steppenden Tabs, denselben Anker gegeben).
> Kanonisch jetzt: [02-domaenenmodell.md](../02-domaenenmodell.md) Abschnitt 1 ("Kanal-Anker,
> je Fassade verschieden"). Der ursprüngliche Zwei-Felder-Entwurf blieb hier nur aus
> historischem Interesse stehen (siehe DPoP-demo-3yd.9).

Beide Fassaden tun strukturell dasselbe: Identität nachweisen, dann gegen das prüfen,
womit der Kanal angelegt wurde. Nur der Anker unterscheidet sich:

| Kanal | Anker | Nachweis |
|---|---|---|
| APP | `bindingKeyRef` (Geräteschlüssel) | DPoP-Proof gegen den Thumbprint |
| WEB, initialer Login | `kcAuthSessionId` (noch kein `sub`) | signierte Request-Assertion von Keycloak |
| WEB, Step-up | `kcSessionId` (Nutzer bekannt, `sub` vorhanden) | signierte Request-Assertion von Keycloak |

Der Web-Kanal hat also **zwei** Ausprägungen, die leicht übersehen werden: Beim
initialen Login existiert bei Keycloak nur eine Auth-Session, es gibt noch keinen
Nutzer. Der Account wird erst im Orchestrator-Ablauf ermittelt - genau wie ein
APP-Kanal auch `ANONYMOUS` ohne `accountId` startet und ihn erst durch `ident-fsc`
bzw. Lookup-Login bekommt.

`ChannelSession` bekommt dafür kanaltyp-abhängige Ankerfelder (`kcAuthSessionId` /
`kcSessionId` neben `bindingKeyRef`). `AuthContext.keycloakSessionId`/
`keycloakSubject` existieren im Code bereits, aber ungenutzt - der Anker muss
zusätzlich auf die `ChannelSession`, weil er ohne `AuthEvidence` prüfbar sein muss
(die entsteht erst beim ersten Nachweis - `AuthContext` selbst entsteht beim
WEB-Kanal nie, siehe Abschnitt 6: dort ist es reines App-Token-Bookkeeping). Dieses
Modell ist die Grundlage für den
Guard (Abschnitt 4): Der Kanal-Anker allein autorisiert keinen Aufruf, er muss gegen
die Peer-Auth-Assertion geprüft werden - sonst würde eine geleakte
`channelSessionId`, kombiniert mit irgendeiner gültig signierten Keycloak-Assertion,
zum Kanal-Hijack reichen.

---

## 3) Peer-Authentifizierung ohne mTLS

Zwei Fragen sind zu trennen, ein reines Service-Credential beantwortet nur die erste:

- **Wer ruft?** → Keycloak authentifizieren.
- **Für wen darf er?** → Bindung an kc-Auth-/User-Session und damit an den konkreten
  Kanal.

Sonst wäre ein gestohlenes "Ich bin Keycloak"-Token ein Generalschlüssel für jeden
Kanal jedes Nutzers.

### Design: ein signiertes JWT pro Request

Statt Access Token + separatem Proof: **ein** signiertes JWT pro Request. Grund: Beim
initialen Login gibt es noch kein `sub` - ein Access Token ohne `sub` wäre schräg,
und man bräuchte zwei Anspruchssätze. Eine selbstdefinierte Assertion kann
problemlos sagen "ich handle für Auth-Session ABC, Nutzer noch unbekannt".

Aufbau der Assertion: `iss=keycloak`, `aud=orchestrator`, `htm`/`htu` (dieser
Request), `jti` + `iat` (kurz gültig), plus Kontext (`kcAuthSessionId` bzw.
`kcSessionId`/`sub`). Verifikation gegen Keycloaks JWKS, Rotation über `kid`. Ein
Schlüsselpaar pro Client - nicht pro Nutzer, nicht pro Session; die Trennung
zwischen Nutzern läuft über die Claims. Der bestehende `jti`-Replay-Cache und das
`iat`-Fenster (`max-clock-skew-seconds`) sind eins zu eins wiederverwendbar.

### Verworfene Alternativen

- **Token Exchange** - unnötig, weil Keycloak die Session ohnehin besitzt und
  in-process ausstellen kann, kein HTTP-Roundtrip nötig.
- **Keycloak hält einen DPoP-Key als Geräte-Ersatz** - DPoPs Wert kommt daher, dass
  der Schlüssel nicht exportierbar auf einem unvertrauten Client liegt. Hält ein
  Server ihn, ist es faktisch ein Shared Secret mit asymmetrischer Zeremonie. Pro
  Nutzer wäre es zusätzlich fatal: `DeviceAccountLink` würde bei jedem Web-Login
  treffen, die Geräte-Wiedererkennung sagt still immer ja.

### Grenze, die keine Transportwahl aufhebt

Ein kompromittierter Keycloak kann jeden Nutzer imitieren - das ist der
kc-first-Architektur inhärent, auch mTLS ändert daran nichts. Deshalb lohnt die enge
Bindung pro Session mehr als ein stärkeres Verfahren für ein weiterhin breit
gültiges Dienst-Credential.

---

## 4) `ChannelAccessGuard` als Vertrag mit zwei Implementierungen

Heute prüft der Guard fest den DPoP-Thumbprint: `requireChannel(channelSessionId,
bindingKeyRef)`. Für WEB-Kanäle gibt es keinen - die Prüfung muss fassadenabhängig
werden, während die Ressource dahinter identisch bleibt.

Ein Vertrag "wer spricht hier, und darf er auf diesen Kanal?", zwei
Implementierungen - APP prüft DPoP-Proof gegen `bindingKeyRef`, WEB prüft Keycloaks
Nachweis gegen den kc-Anker (Abschnitt 2). Bewusst so geschnitten, dass das
Nachweisverfahren austauschbar ist: ein späterer Wechsel (z. B. auf mTLS) betrifft
dann genau eine Implementierung, nicht die Kanal-Logik.

---

## 5) `DeviceAccountLink` auf den APP-Kanal begrenzen

Im Web-Kanal gibt es kein Gerät - `bindingKeyRef` kann dort nichts bedeuten.
`ToolOutcomeProcessor` ruft aber an zwei Stellen unbedingt
`linkDeviceToAccount(channelSession.bindingKeyRef!!, accountId)` auf
(`handleEnrolled` und `handleAuthenticated` im Lookup-Fall). Ein WEB-Kanal ohne
`bindingKeyRef` liefe dort in eine NPE - und zwar genau beim ersten erfolgreichen
Enrollment bzw. Lookup-Login, also im Normalfall des Web-Ablaufs.

Der Fall ist aktiv zu unterdrücken, nicht "trifft im Web halt nie" - sonst sammeln
sich pro Session tote Link-Zeilen an. Verlinkung wird an `Channel.APP` gebunden;
`bindingKeyRef` wird damit APP-only und sollte nullable werden bzw. der Kanal einen
kanaltyp-abhängigen Anker bekommen (Abschnitt 2).

---

## 6) Einstiegspunkt: `PATCH /orchestrator/api/v1/kc/channels/{channelSessionId}`

Einziger fassadenspezifischer Endpunkt der kc-Seite - resumt eine bestehende Journey
oder legt sie an, falls sie unter dieser ID noch nicht existiert (Upsert-Semantik).
Request-Body: Account-Referenz (falls Keycloak den Nutzer schon kennt - Step-up,
`sub` vorhanden; der Kanal wird sofort daran gebunden), das ACR/AMR, das Keycloak
selbst bereits nativ erreicht hat (Abschnitt 8), und im Step-up-Fall das Ziel-ACR -
Keycloaks angefragtes LoA-Level, über die Abbildungstabelle aus Abschnitt 9 in einen
Orchestrator-ACR-String übersetzt, bevor es gesendet wird. Ohne dieses Ziel wüsste
die Keycloak-Strategie (Abschnitt 7) nicht, wofür sie Kandidaten filtert -
`CandidateTools.forAuth` nimmt schon heute ein `targetAcr` entgegen, sonst würde
z. B. auch ein Identifikations-Tool wie `ident-fsc` als Step-up-Kandidat angeboten,
was fachlich keinen Sinn ergibt. Response: `channel`/`next`/`stepData` in derselben
Hülle wie beim App-Kanal, ergänzt um `authData` (Abschnitt 8).

Danach läuft die Tool-Steuerung über dieselben fassadenneutralen Endpunkte, die auch
der App-Kanal nutzt: `POST /channels/{id}/tools/{toolId}` zur Aktivierung,
`PATCH /tools/{toolSessionId}/{toolId}` für die Eingaben - keine Duplikate von
tool-activate & Co. unter `/kc/`.

**Warum eine client-gewählte ID statt des App-Musters (Server vergibt, Client merkt
sich die Antwort)?** Drei Vorteile:

- **Idempotente Retries.** Ein wiederholter `POST` (App-Muster) ist nicht
  idempotent - jeder Retry nach einem Netzwerk-Timeout legt einen neuen Kanal an,
  alte bleiben verwaist zurück. Ein `PATCH` mit fester ID auf dieselbe Ressource ist
  dagegen von Natur aus wiederholbar: derselbe Aufruf führt immer zum selben
  Ergebnis, egal ob er zum ersten oder dritten Mal ankommt.
- **Kein zusätzlicher ID-Umlauf.** Beim App-Muster muss der Client die
  server-erzeugte ID erst bekommen und sich für später merken. Hier kann Keycloak
  die ID direkt aus etwas ableiten, das es ohnehin schon hat und über den ganzen
  Flow-Durchlauf eindeutig führt. Kein separates "ID merken und zurückschicken"
  nötig.
- **Natürliche 1:1-Zuordnung.** Ein Keycloak-Flow-Durchlauf hat schon eine eigene,
  eindeutige Identität. Die Orchestrator-Journey darüber laufen zu lassen, statt
  eine zweite, unabhängige ID für dasselbe Konzept zu erzeugen, vermeidet doppelte
  Buchführung "welcher Orchestrator-Kanal gehört zu welchem Keycloak-Flow".

**Wovon die ID abgeleitet wird - immer `insert`, nie `find`.** Keycloak legt für
JEDEN Authentifizierungs-Flow-Durchlauf eine frische `AuthenticationSessionModel`
an, unabhängig von der langlebigen `UserSessionModel` (`kcSessionId`) - und
befördert eine Auth-Session erst ganz am ENDE eines erfolgreichen Durchlaufs zu
einer User-Session. Zu Beginn eines Step-ups existiert also noch keine
`ChannelSession`, die zu genau dieser User-Session gehören könnte - es gäbe schlicht
nichts, das `find` finden würde, ausgenommen der Zufall, dass ein Step-up am
Nachmittag zufällig die `channelSessionId` eines Step-ups vom Vormittag träfe. Jeder
Flow-Durchlauf, Step-up eingeschlossen, bekommt deshalb weiterhin eine eigene,
frische `channelSessionId`, abgeleitet aus der jeweils eigenen, frischen
`kcAuthSessionId` (initialer Login) bzw. verankert an der bekannten `kcSessionId`
(Step-up) - aber immer neu angelegt, nie eine vorherige Zeile wiederverwendet.

**Evidenz-Kontinuität über mehrere Flow-Durchläufe hinweg löst ein eigener
`RestoreData`-Mechanismus, nicht die `ChannelSession` selbst und nicht bloß
`amr`.** `amr` (Abschnitt 8) bleibt, was es schon ist: eine LIVE-Meldung "das habe
ich JETZT, in diesem Durchlauf, gerade bewiesen" - nach jedem nativen Schritt neu
aufgerufen (`UpdateAuthenticator`, Abschnitt 9), zeitlich niemals veraltet, weil es
nie länger als bis zum nächsten Aufruf gilt. Was zwischen zwei GETRENNTEN
Flow-Durchläufen weitergegeben wird, ist dagegen zwangsläufig ein Snapshot, der
schon einige Zeit alt sein kann, und deshalb bewusst über einen eigenen,
expliziten Endpunkt läuft statt an jede gewöhnliche Antwort angehängt zu werden:
`GET .../kc/channels/{channelSessionId}/restore-data?kcSessionId=...`.

Der `OrchestratorAuthenticator`, der beim Flow-Start den Kanal anlegt,
implementiert zusätzlich einen Hook für das Flow-ENDE: Sobald am Ende eines
erfolgreichen Laufs eine `UserSessionModel` existiert, ruft er diesen Endpunkt mit
der frisch entstandenen `kcSessionId` auf und bekommt ein `RestoreData` - bewusst
allgemein benannt, nicht `EvidenceData`: alles, was ein SPÄTERER, unabhängiger
Kanal (ein Step-up) von diesem Durchlauf übernehmen könnte, nicht nur die Evidenz -
und schreibt es in eine `UserSessionModel`-Note. `RestoreData` selbst reist nie als
Klartext-JSON: Der Endpunkt liefert es als signiertes JWT (`RestoreDataCodec`,
symmetrisch, backend-only, NICHT `MockKeycloakKeyProvider`s Schlüssel - der geht ja
absichtlich an den Mock-Frontend-Client raus), dessen `sub`-Claim an genau diese
`kcSessionId` gebunden ist. Keycloak speichert und transportiert also nur ein
Token, das es selbst weder fälschen noch einer anderen User-Session unterschieben
kann.

Bei einem SPÄTEREN Step-up unter derselben, weiterhin gültigen User-Session liest
derselbe Authenticator die Note wieder aus und übergibt das Token unverändert als
`restoreData`-Feld im allerersten `PATCH`-Aufruf des neuen Kanals (Abschnitt 8) -
neben, nicht statt, dem gewöhnlichen `amr`-Feld. `upsertChannel` prüft beim
Empfang, dass das Token zur `kcSessionId` DIESER Anfrage passt (`RestoreDataCodec.
decode`) - alles andere (falsche Session, abgelaufen, manipuliert) kommt als
`null` zurück, nie als Fehler: ein schlechtes Restore-Token bedeutet nur "ohne
Vorlauf starten", nie einen abgebrochenen Aufruf. Passt es, übergibt derselbe Code,
der `amr` verarbeitet (`JourneyService.applyEvidenceUpdate` - facade-neutral, kennt
Keycloak selbst nicht, der Aufrufer reicht `source` explizit durch), auch
`restoreData`s eigenes `amr` an die frisch angelegte Journey - jeder Eintrag trägt
dabei die komplette `AuthEvidence.MethodEvidence` (`enrolledUnderAcr`,
`factorTypes`, UND `source`/`amrSourceId`, docs #8), sonst wäre eine
Wiederherstellung nur teilweise. `source` ist dabei entscheidend: eine Methode, die
VOR dem Restore vom Orchestrator selbst bewiesen wurde, bleibt auch danach als
`orchestrator` markiert, nicht als bloße `kc`-Selbstauskunft - sonst könnte ein
späterer, naiver `kc`-Re-Report genau diese Methode wieder herabstufen, obwohl das
Downgrade-Verbot (Abschnitt 8) das eigentlich verhindern soll. Der neue Kanal
startet damit nicht bei null, obwohl er eine eigene, frische Zeile ist. Auf
Orchestrator-Seite ist dafür sonst nichts Neues nötig - der Vertrag der
facade-neutralen Tool-Endpunkte (Abschnitt 8) bleibt unverändert, nur wer die
Evidenz zwischen zwei Flow-Durchläufen trägt, ändert sich: nicht der Orchestrator
selbst, sondern Keycloaks eigene, dafür vorgesehene User-Session-Note.

**Warum das beim App-Kanal trotzdem nicht geht:** Der App-Client braucht bewusst
eine *unverknüpfbare* neue ID pro Login-Versuch - das Geräte-Signal (DPoP) darf
gerade nicht als Wiedereinstiegs-Schlüssel dienen, sonst könnte ein Kanal-Verlauf
still an ein Gerät andocken, ohne dass das gewollt ist. Bei Keycloak entfällt dieses
Risiko strukturell, weil die Ableitung konsequent an der frischen, einmaligen
Auth-Session hängt und nie an einem langlebigen Bezugspunkt. Und selbst eine
erratene `channelSessionId` allein reicht nie: Der Guard (Abschnitt 4) prüft bei
jedem Aufruf zusätzlich, ob die signierte Assertion tatsächlich zu genau diesem
Anker passt.

**Entscheidung: kc-spezifisch, nicht allgemein.** Die Upsert-Semantik mit
client-gewählter ID bleibt trotzdem auf die eine Fassade begrenzt, die sie
strukturell braucht - eine allgemein verfügbare "erzeuge oder finde per beliebiger
ID"-Fähigkeit würde das oben beschriebene Risiko (still verknüpfbare, wiederverwend­
bare Anker) auf jeden Aufrufer ausweiten, nicht nur auf Keycloak, dessen Anker per
Konstruktion schon eindeutig und geprüft ist.

---

## 7) Journey-Strategie und Tool-Auswahl im Web-Kanal

Eine eigene, "normale" `IntentStrategy` für Keycloak liefert als `next` immer einen
`selectMethod`-Schritt, der alle für Keycloak nutzbaren Tools als Kandidaten
auflistet - kein Sonderfall in der Journey-Maschinerie, eine Strategie wie
`StepUpStrategy` oder `ManageAuthMethodsStrategy` auch.

Der Keycloak-seitige `OrchestratorAuthenticator` (ein SPI-Plugin, das als eine
Flow-Execution eingehängt wird) fragt beim Erreichen den Orchestrator über den
Einstiegspunkt aus Abschnitt 6 ab und rendert je nach `next.tool` das passende
Freemarker-Template (`context.challenge(form)`). `action()` reicht die Eingabe
unverändert als PATCH an den vom Orchestrator benannten Tool-Endpunkt weiter,
signiert mit derselben Peer-Auth-Assertion wie jeder andere kc-Aufruf (Abschnitt 3).
Bei `Failed` erneut `context.challenge` mit Fehlermeldung; bei `InProgress` bleibt
dieselbe Execution aktiv und zeigt den nächsten Schritt; erst wenn der Orchestrator
`Completed` meldet, ruft der Authenticator `context.success()`.

**Statische Vorauswahl statt Nutzerauswahl, wo sie gültig ist.** Eine Execution kann
mit einem festen `toolId` konfiguriert werden - dann aktiviert sie dieses Tool
direkt, statt die `selectMethod`-Auswahl dem Nutzer zu zeigen. Weil die
Keycloak-Strategie immer alle ihre Tools als Kandidaten anbietet, ist eine solche
Vorauswahl immer gültig, sofern sie account-unabhängig ist - Identifikation
(`ident-fsc`) ist für jeden Nutzer gleich. Für Step-up-Faktoren gilt das **nicht**:
Sie sind account-spezifisch (`CandidateTools.forAuth` filtert schon heute nach den
tatsächlich aktiven Methoden des jeweiligen Accounts); eine Execution, die statisch
auf `toolId=auth-sms` konfiguriert ist, scheitert für jeden Nutzer, der SMS nie
aktiviert hat. Für Step-up-Executions bleibt das `toolId` deshalb unkonfiguriert -
der Authenticator rendert dann `selectMethod` ohne Vorauswahl, sein ohnehin
vorhandenes Standardverhalten.

Admin-seitig bleibt jede Execution eine normale Keycloak-Konfiguration (aktivierbar,
in Subflows einbettbar) - nur ihr Inhalt kommt vom Orchestrator statt fest verdrahtet
zu sein.

**Erweiterbar auf Sub-Journeys:** Dasselbe Muster lässt sich später auf
Ziel-Sub-Journeys statt einzelner Tools ausdehnen (z. B. einen
Step-up-/Re-Identifikations-Korridor) - die Authenticator-Konfiguration würde dann
statt eines `toolId` eine Sub-Journey referenzieren, die die Execution beim
Erreichen anstößt, sonst unverändertes Muster. Das an den Orchestrator übergebene
native ACR/AMR (Abschnitt 8) zahlt sich hier aus: Die Sub-Journey-Strategie kann
selbst beurteilen, ob ihr Ziel durch das, was Keycloak nativ schon erreicht hat,
bereits erfüllt ist, statt ungefragt einen weiteren Faktor zu verlangen. Nicht Teil
dieses Entwurfs, aber eine naheliegende Erweiterung.

---

## 8) `authData`: Ergebnisse laufend an Keycloak übertragen

**Kombination von nativem und Orchestrator-ACR/AMR findet im Orchestrator statt**,
nicht in Keycloak: `AuthPolicy`/`AcrLevels` sind schon heute die fachliche Heimat
dieser Logik. Das native ACR/AMR ist dabei eine Selbstauskunft Keycloaks, die der
Orchestrator nicht selbst nachprüfen kann - dieselbe kc-first-Vertrauensgrenze, die
schon in Abschnitt 3 offen benannt ist ("ein kompromittierter Keycloak kann jeden
Nutzer imitieren").

**Automatisch, nicht per Toggle, immer statt nur bei Änderung.** Jede Antwort an
einen `WEB`-Kanal (der Einstiegspunkt aus Abschnitt 6 UND jede Aktivierungs-/
PATCH-Antwort) trägt `authData` (`accountId`/`acr`/`amr`) neben `next`/`stepData` -
nicht als Opt-in-Parameter, den der Aufrufer jedes Mal mitgeben müsste, sondern
serverseitig an den Kanaltyp gebunden: `WEB`-Kanäle bekommen es immer, `APP`-Kanäle
nie (aus demselben Grund, aus dem `ChannelBlock` heute schon Account-Felder vor
unbewiesenen Geräten verbirgt). Und immer, nicht nur bei tatsächlicher Änderung: Ein
Vergleich "hat sich etwas geändert" wäre zusätzlicher Aufwand im Orchestrator für
keinen Gewinn - die Execution überschreibt ihre Keycloak-Note ohnehin nur
idempotent, ob der Wert neu ist oder nicht macht dafür keinen Unterschied.

Der `OrchestratorAuthenticator` schreibt `authData` bei **jeder** Antwort sofort in
die Keycloak-Session-Notes (Abschnitt 10) - jeweils die Execution, die die Antwort
gerade erhalten hat, nicht eine separate, erst am Ende erreichte Instanz. Enthält
`authData` eine `accountId` und ist noch kein Keycloak-Nutzer gesetzt, setzt genau
diese Execution ihn direkt (`context.setUser(...)`) - kein Sonderfall, dasselbe
Muster wie Keycloaks eigene `UsernamePasswordForm`/`RegistrationUserCreation`. Der
Nutzerkontext steht damit sofort auch nachfolgenden Schritten zur Verfügung.

Ob und wann der Flow insgesamt fertig ist, muss keine Execution eigens erkennen:
`context.success()` ruft sie ohnehin, sobald ihr eigenes Tool `Completed` meldet.
Keycloaks Flow-Struktur (wie viele Executions konfiguriert sind) entscheidet allein,
ob weitere Schritte folgen; Required Actions und das Minten der Tokens mit den
zuletzt geschriebenen Claims passieren automatisch, sobald alle konfigurierten
Executions erfolgreich waren - unverändertes Keycloak-Flow-Verhalten.

Weil `authData` nach jedem abgeschlossenen Orchestrator-Schritt sofort geschrieben
wird, sieht ein darauffolgender nativer Schritt - und Keycloaks eigene
LoA-Maschinerie (Abschnitt 9) - immer den aktuellen Stand, und jede spätere
Orchestrator-Execution bekommt beim nächsten Aufruf wiederum das inzwischen
aktuelle native ACR/AMR mit. Der Flow kann deshalb beliebig oft zwischen nativ und
Orchestrator wechseln, nicht nur einmal in eine Richtung. Ein Abbruch mitten im
Flow verliert dadurch auch weniger: ein bereits abgeschlossener Faktor geht nicht
mehr verloren, nur weil der Flow danach abgebrochen wird - er wurde ja schon
geschrieben. Was bleibt: die verwaiste `AuthJourney` im Orchestrator selbst braucht
weiterhin die TTL-basierte Aufräumlogik wie beim App-Kanal.

---

## 9) Step-up im Web-Kanal

**`UpdateAuthenticator`: die Evidenz DIESES Flow-Durchlaufs laufend nachtragen.**
Innerhalb eines einzelnen Flow-Durchlaufs (Abschnitt 6) bleibt die Evidenz des
gerade offenen Kanals nur aktuell, wenn jeder native Schritt, der selbst Evidenz
erzeugen kann (z. B. Keycloaks eigenes Passwort- oder OTP-Formular), sie explizit
nachträgt. Dafür hängt ein separater `UpdateAuthenticator` - konfiguriert direkt
hinter jedem solchen nativen Schritt im Flow - der ausschließlich den
Einstiegspunkt (Abschnitt 6) mit dem gerade erreichten `amr` aufruft, ohne selbst
ein Formular zu rendern. Jeder `amr`-Eintrag nennt dabei nur zwei Ids, nicht
Methode/Loa/Faktortyp direkt: `nativeToolId` (die stabile Authenticator-KONFIG,
z. B. "kc-otp-form") und `amrSourceId` (diese konkrete Ausführung/dieser Beweis) -
Methode, eigene Loa und Faktortypen liefert serverseitig ein
`NativeAuthenticatorDescriptor`, nachgeschlagen über `nativeToolId`, genau wie ein
Orchestrator-Tool seine Evidenz aus dem eigenen `ToolDescriptor` bezieht statt sie
pro Aufruf mitzuschicken. Wichtig: `amr` ist dabei die VOLLSTÄNDIGE, gerade gültige
kc-Menge dieses Aufrufs, kein Delta - `AuthEvidence.replaceForSource` gleicht ab
und entfernt kc-Einträge, die hier fehlen (das bildet die mögliche zeitliche
Lebenszeit nativer Einträge ab: ein `UpdateAuthenticator`, der Keycloaks eigene,
noch gültige Menge kennt, muss sie also jedes Mal vollständig erneut mitschicken,
nicht nur den gerade neu hinzugekommenen Eintrag). Auf Orchestrator-Seite ist dafür
sonst nichts Neues nötig: derselbe `PATCH .../kc/channels/{channelSessionId}` mit
`amr`-Body, den `KcChannelService.upsertChannel` schon heute verarbeitet (Sync in
die Evidenz über `JourneyService.applyEvidenceUpdate` - facade-neutral, der
Aufrufer reicht `source` explizit durch) - der `UpdateAuthenticator` ist rein
Keycloak-seitige Flow-Konfiguration, kein neuer Vertrag. Über einen einzelnen
Flow-Durchlauf hinaus (z. B. bei einem späteren Step-up) trägt nicht dieser
Mechanismus die Evidenz weiter, sondern die in Abschnitt 6 beschriebene
`UserSessionModel`-Note, die der `OrchestratorAuthenticator` selbst am Ende des
vorigen Durchlaufs geschrieben hat.

Keycloaks eingebauter Step-up-Mechanismus funktioniert so: Der Cookie-Authenticator
vergleicht das angeforderte Level (`acr_values`/`claims`) nicht gegen irgendeine
freie Zahl, sondern ausschließlich gegen die Level-Werte, die als `Condition -
Level of Authentication`-Subflows **im Flow selbst konfiguriert** sind - reicht das
in der SSO-Session hinterlegte Level, wird die Session wiederverwendet; sonst
betritt der Flow den passenden, mit dieser Level-Zahl konfigurierten Subflow. Fehlt
für ein angefragtes Level die passende Bedingung im Flow, kann Keycloak die Anfrage
nicht auflösen und bricht mit einem Fehler ab.

**Das lässt sich nicht ersatzlos durch einen einzigen Authenticator ersetzen** - die
native LoA-Struktur bleibt die Instanz, die entscheidet, OB überhaupt
weiterauthentifiziert werden muss und WELCHE Ziel-Stufe angefragt ist; das ist an
Keycloaks eigene Flow-Konfiguration gebunden, nicht verhandelbar. Was sich ersetzen
lässt, ist nur, **was innerhalb** einer (weiterhin nativ vorhandenen) Stufe passiert:
Pro ACR-Level, das ein Client anfordern kann, bleibt ein `Condition - Level of
Authentication`-Subflow im Flow bestehen - genau wie ohne Orchestrator auch.
Innerhalb dieses Subflows steht dann der `OrchestratorAuthenticator` aus Abschnitt 7
(mit unkonfiguriertem `toolId`, siehe dort), der die für diesen Account tatsächlich
verfügbaren Faktoren anbietet. Keycloak übernimmt weiterhin die Auswertung von
`acr_values`/`claims` gegen die konfigurierten Level, den Cookie-Vergleich mit dem
zuletzt erreichten Level und das Betreten des passenden Subflows; der Orchestrator
liefert nur den Inhalt der Stufe - dafür muss die Execution beim Betreten des
Subflows das numerische Level, das dieser Subflow repräsentiert, über die
Abbildungstabelle unten in einen Orchestrator-ACR-String übersetzen und als
Ziel-ACR an den Einstiegspunkt (Abschnitt 6) mitgeben. Ohne dieses Ziel könnte die
Keycloak-Strategie ihre Kandidaten nicht sinnvoll filtern.

**Offene Frage:** Es braucht eine Abbildungstabelle zwischen Keycloaks numerischen
LoA-Stufen (0, 1, 2, ...) und den ACR-Strings des Orchestrators (`loa1`, `loa2`,
...) - keine 1:1-Automatik, weil Keycloaks LoA-Zahlen nur innerhalb eines
Realms/Clients Sinn ergeben, während `AcrLevels` im Orchestrator eine eigene, davon
unabhängige Fachlogik ist. Diese Abbildung ist Teil der Konfiguration des
`OrchestratorAuthenticator`-Plugins, nicht des Orchestrators selbst.

---

## 10) Datenaustausch mit Keycloak: wo die Werte herkommen und hingehen

Standard-Keycloak-SPI, kein neuer Orchestrator-Mechanismus:

- **Lesen:** `context.getAuthenticationSession()` (die `AuthenticationSessionModel`
  der laufenden Anmeldung) trägt Auth-Notes, die über alle Executions eines
  Flow-Durchlaufs erhalten bleiben - dort steht die `channelSessionId` nach der
  ersten Execution. `getAuthenticatedUser()` liefert den bereits bekannten
  `UserModel`/`sub` im Step-up-Fall, aus dessen Nutzer-Attribut sich die
  Orchestrator-`accountId` auslesen lässt. Nativ bereits erreichtes ACR/AMR ist
  nicht automatisch verfügbar - Keycloak trackt AMR nicht eingebaut. Jeder native
  Schritt muss es selbst als Auth-Note ablegen (`setAuthNote("amr", ...)`), damit
  eine spätere Orchestrator-Execution es lesen und mitgeben kann; für ACR existiert
  mit Keycloaks eigenem LoA-Tracking (`AuthenticatorUtil`) bereits eine
  vergleichbare Session-Note.
- **Schreiben, zwei getrennte Zeitpunkte:** Jede Execution setzt `setAuthNote(...)`
  mit dem `authData` ihrer eigenen Antwort - nicht erst eine separate letzte
  Execution.
  - **Nutzer setzen/anlegen** passiert, sobald `authData` erstmals eine `accountId`
    enthält (Erstidentifikation) - `context.setUser(...)`, dasselbe Muster wie
    Keycloaks eigene `UsernamePasswordForm`/`RegistrationUserCreation`. Dabei wird
    auch die Orchestrator-`accountId`/`personId` dauerhaft als Nutzer-Attribut
    geschrieben - genau das liest ein späterer Step-up wieder aus.
  - **ACR/AMR-Claims** materialisieren sich erst, wenn tatsächlich eine
    `UserSessionModel` entsteht (Flow-Abschluss) - die zuletzt gesetzten
    Auth-Notes wandern dann automatisch in die Nutzer-Session, ein eigener
    **Protocol Mapper** liest sie beim Token-Mint aus und schreibt sie in die
    `acr`/`amr`-Claims des ausgestellten Tokens - der Standardweg, mit dem
    Keycloak beliebige eigene Werte in Tokens bekommt.

**Transport zum Orchestrator:** Der `OrchestratorAuthenticator` ist ein normales
Java/Kotlin-SPI-Plugin, das den Orchestrator ausschließlich über HTTP anspricht -
genau wie die App auch, kein direkter Datenbankzugriff.

- **Rein:** Ein HTTP-Client (z. B. `java.net.http.HttpClient`), von der
  `AuthenticatorFactory` einmal gehalten statt pro Execution neu erzeugt
  (Keycloak-SPI-Konvention: der `Authenticator` selbst ist kurzlebig, die Factory
  hält langlebige Ressourcen wie Client und Signaturschlüssel). Jeder Aufruf trägt
  ein frisch erzeugtes, signiertes Peer-Auth-JWT (Abschnitt 3) als
  `Authorization`-Header. Der JSON-Body enthält beim Einstiegspunkt (Abschnitt 6)
  im Step-up-Fall die Account-Referenz/`sub` (aus dem Nutzer-Attribut) sowie das
  native ACR/AMR (aus den Auth-Notes). Bei der Tool-Aktivierung/PATCH sind es die
  vom Nutzer eingegebenen Formularfelder, unverändert weitergereicht.
- **Raus:** JSON in derselben Hülle wie beim App-Kanal (`ChannelResponse` mit
  `channel`/`next`/`stepData`, docs/05-api.md #2), ergänzt um `authData`,
  deserialisiert z. B. mit Jackson in eigene, schlanke DTOs des Plugins - nicht
  zwingend dieselben Klassen wie im Orchestrator, dieselbe Konvention wie sonst im
  Projekt ("jedes Modul/jeder Client eigene DTOs"). `next.toolId`/`next.step`
  bestimmt das zu rendernde Template, `stepData` die Formularfelder;
  `channel.channelSessionId` wird beim ersten Aufruf in die Auth-Note geschrieben.

Server-zu-Server über normales TLS (Leitplanke 2, Abschnitt 1) - kein neues Muster,
genau wie Keycloaks eigene User-Storage- oder Identity-Broker-Provider-SPIs schon
heute externe HTTP-Aufrufe machen.

---

## 11) Logout-Semantik im Web-Kanal

**Entschieden (2026-09-06):** Logout bleibt vollständig bei Keycloak - der `KEYCLOAK`-Kanal ruft
den Orchestrator dafür nie auf, weder `DELETE /channels/{id}` noch ein kc-eigenes Äquivalent. Nach
Keycloaks eigenem Logout wird die zugehörige `ChannelSession` einfach nicht mehr benutzt. In der App
beendet `DELETE /channels/{id}` die Sitzung dagegen aktiv, weil dort der Orchestrator selbst der
Session-Eigentümer ist ([02-domaenenmodell.md](../02-domaenenmodell.md)).

**Umgesetzt (Folge davon, `DPoP-demo-f9o.12`):** `RetentionJob`
(`orchestrator/session/RetentionJob.kt`, [07-betrieb.md](../07-betrieb.md) Abschnitt 3) räumte
bisher rein zeitbasiert auf (`expiresAt` + Retention-Dauer, kanaltyp-unabhängig) - für
`KEYCLOAK`-Kanäle reichte das nicht, weil Keycloak den Orchestrator nie über ein Logout
informiert. Der Job fragt jetzt vor dem Löschen einer bereits abgelaufenen `KEYCLOAK`-Kanal-Zeile
zusätzlich bei Keycloak nach (`KeycloakAdminClient.isSessionAlive`, Admin-REST-API,
`GET .../users/{id}/sessions`) und räumt bei bestätigt beendeter Session sofort auf statt erst
nach der vollen 30-Tage-Frist. `ChannelSession.durableKcSessionId` (neue Spalte, unterscheidet
sich von `channelAnchor` - siehe Abschnitt 2) trägt dafür Keycloaks durable `UserSessionModel`-Id,
geschrieben als Nebeneffekt des ohnehin bei jedem Flow-Ende laufenden
`GET .../restore-data`-Aufrufs (`KcChannelService.restoreData`) - kein neuer
Keycloak-Extension-Code nötig. Eine nicht bestätigbare Antwort (kein Client im aktiven Profil,
keine durable Id bekannt, oder der Admin-API-Aufruf selbst schlägt fehl) führt nie zu einem
Löschen, sondern lässt den Kanal in die normale Zeit-basierte Aufräumung zurückfallen.

---

## 12) Offene Punkte

- ~~**Wie das gemeldete native ACR/AMR in die Orchestrator-Evidenz einfließt**~~ -
  UMGESETZT: `AuthEvidence` (die persistente Entity, nicht mehr `AuthContext` - die
  ist auf reines App-Token-Bookkeeping geschrumpft) trägt pro Methode ein
  `MethodEvidence.source` (`orchestrator`/`kc`), genau die geforderte
  Kenntlichmachung, welcher Teil der Gesamtaussage von wem stammt (relevant z. B.
  für [claims-modell-und-vertrauensanker.md](claims-modell-und-vertrauensanker.md)).
  `AuthPolicy` bleibt dabei bewusst source-agnostisch (liest `source` nie für
  eigene Entscheidungen) - nur die Schreibseite (`AuthEvidence.addAmr`) nutzt es,
  um zu verhindern, dass eine `kc`-Selbstauskunft eine bereits orchestrator-
  geprüfte Methode herabstuft.
- **Abbruch/Timeout mitten im Flow:** Die verwaiste `AuthJourney` im Orchestrator
  braucht dieselbe TTL-basierte Aufräumlogik wie beim App-Kanal (kein
  `DELETE`-Äquivalent von Keycloak-Seite garantiert) - siehe Abschnitt 8 zum
  begrenzten Schaden, weil bereits abgeschlossene Faktoren trotzdem übertragen
  wurden. `AuthJourney`s TTL-Aufräumung ist kanalneutral - kein Web-spezifischer
  Rest mehr offen.
- **Nur noch Abschnitt 11 (Logout-Semantik) ist fachlich unentschieden** - alles
  andere in diesem Dokument ist umgesetzt, siehe Status-Hinweis oben.

---

## 13) Verworfene Alternativen

Drei andere Schnitte wurden geprüft und zugunsten der Lösung oben verworfen.

**Orchestrator als externer OIDC-Identity-Provider** (Keycloak bindet den
Orchestrator per Identity Brokering ein, Browser-Redirect zu einer eigenen
Orchestrator-Web-UI, Rückkehr per Authorization-Code): verletzt die Leitplanke
"Browser spricht nie mit dem Orchestrator" (Abschnitt 1) direkt - der Orchestrator
bräuchte eine eigene, öffentlich erreichbare, gehärtete Web-UI. Der Standardweg,
den man ohne diese Leitplanke nehmen würde, aber innerhalb der aktuellen Prämissen
nicht zulässig.

**Volle Journey-Delegation** (der Orchestrator wäre alleiniger Journey- und
ACR/AMR-Eigentümer, Keycloak würde jedes Formular über einen einzigen generischen
Authenticator rendern, ohne je eigene Authenticatoren zu nutzen): architektonisch
sauber - eine Instanz kennt die ganze Wahrheit, kein Split-Brain-Risiko -, aber
verzichtet komplett auf Keycloaks eingebaute Fähigkeiten (natives Passwort-Login,
OTP/TOTP, WebAuthn/Passkey, Social-Login-Brokering, die gesamte
Conditional-LoA-Maschinerie). Genau das war der entscheidende Nachteil: Der Sinn
einer Keycloak-Anbindung ist, diese Fähigkeiten zu nutzen, nicht sie durch eine
eigene Nachbildung zu ersetzen.

**Zustandslose Einzel-Tool-Aufrufe ohne Journey** (Keycloak bleibt vollständig
Journey-Eigentümer, ruft den Orchestrator nur für einzelne, isolierte Faktoren ohne
begleitende `ChannelSession`): näher an der gewählten Lösung, aber ohne die
persistente Journey verliert der Orchestrator die Fähigkeit, mehrere eigene Tools
im selben Login mit gemeinsamer Evidenz zu verrechnen (`AuthPolicy` sähe nur
isolierte Einzelaufrufe, keine zusammenhängende Journey) - und würde dafür eine
komplett neue, dauerhafte Nebeninfrastruktur brauchen (eine zweite Support-Schicht
parallel zu `ToolControllerSupport`, `ToolSession.journeyId` nullable). Bleibt als
Muster sinnvoll für Touchpoints **außerhalb** eines zusammenhängenden
Login-/Step-up-Flows - eine Keycloak-"Required Action" oder eine
Selbstbedienungs-Aktion in der Account-Konsole, für die keine über mehrere
Schritte lebende Journey-Ressource sinnvoll ist -, aber nicht als Ersatz für den
Login-Flow selbst.

---

## 14) Sinnvolle Umsetzungsreihenfolge

**Historisch - so tatsächlich umgesetzt**, bis auf Schritt 7 (weiterhin offen).

1. **Kanal-Anker-Modell** (Abschnitt 2) - Grundlage für Guard und Peer-Auth.
2. Darauf aufbauend, parallel möglich:
   - `DeviceAccountLink` auf APP begrenzen (Abschnitt 5).
   - `ChannelAccessGuard` zum Zwei-Implementierungen-Vertrag machen (Abschnitt 4).
   - Signierte Request-Assertion verifizieren (Abschnitt 3).
3. **Einstiegspunkt `PATCH /kc/channels/{channelSessionId}`** (Abschnitt 6) -
   braucht Guard-Vertrag und Assertion-Verifikation.
4. **Keycloak-`IntentStrategy` und `OrchestratorAuthenticator`** (Abschnitt 7) -
   braucht den Einstiegspunkt aus Schritt 3.
5. **`authData` und Datenaustausch** (Abschnitte 8, 10) - kann parallel zu 4
   entstehen, da beide denselben Authenticator betreffen.
6. **Step-up-Einordnung** (Abschnitt 9) - braucht die native
   `Condition - Level of Authentication`-Subflow-Struktur je ACR-Level und die
   LoA-zu-ACR-Abbildungstabelle; kann parallel zu 4/5 entstehen.
7. **Logout-Semantik klären und dokumentieren** (Abschnitt 11) - unabhängig,
   niedrigste Priorität.

Der App-Kanal bleibt davon unberührt - er behält sein eigenes, DPoP-gebundenes
Modell; nur die Kanal-Anker- und Guard-Grundlagen (Abschnitte 2-4) werden mit ihm
geteilt.
