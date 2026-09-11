# Frontend

Anforderungen an die Demo-Oberfläche und die Regel, nach der sie navigiert.

Die zugrundeliegende API beschreibt [05-api.md](05-api.md), die Schlüsselerzeugung
[09-dpop.md](09-dpop.md).

---

## 0) Drei eigenständige Apps

Das Frontend ist **kein** einzelnes SPA mehr, sondern drei eigene React-Apps mit eigenem
HTML-Entry-Point/URL, die sich Code (Komponenten, Tools, `api.ts`, ...) nur als gemeinsame
Bibliothek teilen:

- **Willkommen** (`/`) — statisch, keine Channel-Logik, kein DPoP-Key. Landing-Page mit Links zu
  den beiden Kanälen.
- **App-Kanal** (`/app/`) — der DPoP-gebundene Orchestrator-Ablauf, inkl. eigener
  Demo/Journey-Log/Einstellungen-Tabs.
- **Web-Kanal** (`/web/`) — der echte Keycloak-Browser-Ablauf, inkl. eigener
  Demo/Mock-Keycloak/Journey-Log/Einstellungen-Tabs.

Journey-Log und Einstellungen leben jeweils **im eigenen Kanal**, nicht bei Willkommen und nicht
als vierte App — beide Ansichten hängen inhaltlich am jeweiligen Kanal-Kontext (DPoP-Channel bzw.
Keycloak-Tokens), eine kanal-lose Variante hätte keinen Datenkontext.

Navigation zwischen den drei Apps ist **echte Browser-Navigation**, kein Client-Routing — Back/
Forward funktionieren dafür ohne eigenen Code. Willkommens Links zu den Kanälen öffnen einen
**benannten** Tab (`target="dpop-demo-app-kanal"`/`target="dpop-demo-web-kanal"`, bewusst ohne
`rel="noopener"`, das die Wiederverwendung verhindern würde) statt `_blank` — innerhalb derselben
Origin (also bei wiederholten Klicks direkt von Willkommen aus, browsergetestet) wird damit ein
bereits offener Tab wiederverwendet statt immer neu geöffnet.

Der WEB-Kanal-QR-/Demo-Link (Abschnitt "Web-Login per QR bestätigen" unten) verwendet denselben
Namen `dpop-demo-app-kanal`, **erreicht die Wiederverwendung aber in der Praxis nicht**: der
klickende Kontext liegt zu diesem Zeitpunkt bereits auf `https://localhost:8543` (echtes
Keycloak), also einer anderen Origin als der App-Kanal-Tab (`http://localhost:8080/app/`) — Chrome
behandelt eine solche Origin-übergreifende Namens-Suche browsergetestet als "nicht gefunden" und
öffnet einen neuen Tab, selbst wenn beide Tabs ursprünglich von derselben Willkommen-Seite aus
geöffnet wurden. Folge: der Demo-Link öffnet bei jedem Klick einen weiteren App-Kanal-Tab, statt
einen bereits offenen wiederzuverwenden — ein reines Komfort-Manko beim Desktop-Testen (mehrere
Tabs statt einem), keine Funktionseinschränkung: `intent=confirm_peer_login` landet in jedem dieser
Tabs korrekt auf dem passenden Schritt (Abschnitt "Web-Login per QR bestätigen"). In der Praxis
scannt ohnehin meist ein zweites Gerät den QR-Code, wo diese Frage gar nicht erst auftritt.

---

## 1) Technische Anforderungen

| ID | Anforderung | Kriterium |
|----|-------------|-----------|
| FE-1 | Frontend auf Basis von React (aktuelle Version) und TypeScript. | siehe Versionstabelle in [08-projektrahmen.md](08-projektrahmen.md) |
| FE-2 | Das Frontend kann autark betrieben werden. | `npm run dev` startet den Vite-Dev-Server, alle drei Entry-Points direkt erreichbar (`/`, `/app/`, `/web/`) |
| FE-3 | Das Frontend kann über Spring Boot gehostet werden. | Ein Vite-Build mit drei HTML-Entry-Points, Output landet gemeinsam in `src/main/resources/static`; `./gradlew bootRun` liefert es aus. `/app/`/`/web/` werden über explizite `WebMvcConfigurer`-Forwards ([WebConfig.kt](../src/main/kotlin/com/example/dpop/orchestrator/api/v1/WebConfig.kt)) auf ihre jeweilige `index.html` aufgelöst — Spring Boots Default-Static-Resource-Handling löst das nur für den Root-Fall automatisch |
| FE-4 | Im Entwicklungsmodus werden API-Requests weitergeleitet. | Vite-Dev-Server proxyt `/orchestrator` nach `http://localhost:8080`, für alle drei Apps gleichermaßen |
| FE-5 | Das Frontend kommuniziert ausschließlich über den `orchestrator`. | Keine direkten Aufrufe an fachliche Module |

---

## 2) UI-Anforderungen

| ID | Anforderung | Kriterium |
|----|-------------|-----------|
| FE-6 | Übersichtliches Layout mit Karten, konsistentem Farbschema und Darkmode. | visuelle Gestaltung als Karten |
| FE-7 | Formulare sind mit Testdaten vorbelegt. | Frei erfundene Erstangaben (`ident-fsc`, Telefonnummer) clientseitig fest vorbelegt; alles, wofür der Server einen Wert kennt (TAN/Code/Passwort/E-Mail), kommt über das `demo`-Objekt ([API](05-api.md)) — gilt einheitlich für Enrollment, geräte-gebundenes Login und Lookup-Login |
| FE-8 | Der aktuelle Stand und der nächste Schritt werden dargestellt. | Anzeige aus `next` und `stepData` |
| FE-9 | Telefonnummern werden clientseitig vorvalidiert. | Formatprüfung vor dem Absenden; das Backend lehnt ungültige Nummern mit `400` ab |
| FE-10 | Geräte-Identität und Kanal lassen sich unabhängig voneinander zurücksetzen. | „Neu erzeugen" tauscht nur den DPoP-Key (neue Geräte-Identität), startet aber keinen Kanal. „Leeren" vergisst nur die lokal gemerkte `channelSessionId`, ohne Backend-Aufruf. Logout beendet den Kanal serverseitig ([API](05-api.md), Logout) und legt anders als früher **keinen** neuen Kanal mehr automatisch an — nur sichtbar, wenn der Kanal `AUTHENTICATED` ist |
| FE-11 | Nach erfolgreicher Anmeldung werden `accountId` und `personId` angezeigt. | Werte stammen aus dem `demo`-Objekt der Antwort |
| FE-12 | Das Frontend merkt sich `channelSessionId` dauerhaft, getrennt vom DPoP-Key — es passiert aber nichts automatisch. | Der Init-Effekt lädt/erzeugt **ausschließlich** den DPoP-Key; ohne aktiven Kanal wählt der Nutzer explizit zwischen Fortsetzen (`GET`, falls eine ID gemerkt ist), Verbinden, Login ohne DPoP oder Registrieren (je ein `POST` mit passendem `intent`) |
| FE-13 | Beim Anlegen eines Kanals lässt sich `requiredAcr` wählen. | Ohne diese Wahlmöglichkeit wäre `enroll-password` (bestätigte E-Mail vorausgesetzt) in der Demo praktisch unerreichbar: Die Registrierung schließt automatisch ab, sobald ein einzelnes `loa1`-Mittel die Standard-Untergrenze erfüllt — Passwort käme nie an die Reihe |
| FE-14 | Die Geräte-Identität (JWK-Thumbprint) ist sichtbar und lässt sich unabhängig vom Kanal neu erzeugen. | Eigene Karte, immer sichtbar, auch ohne aktiven Kanal |
| FE-15 | Ein authentifizierter Kanal lässt sich gezielt auf ein höheres Sicherheitsniveau anheben (Step-up). | Button „Auf loa2 anheben" ruft den bisher ungenutzten Step-up-Auslöser (`PATCH /channels`, [API](05-api.md)) auf; nur angeboten, wenn noch nicht erreicht — loa2 ist das einzige mit den vorhandenen Tools erreichbare Niveau. Die Antwort läuft über dieselbe Tool-Navigation wie jeder andere Login/Step-up |
| FE-16 | Solange ein Tool aktiv Eingaben erwartet — oder der Nutzer zwischen mehreren Tools wählt —, wird auf eine einzige naheliegende Aktion reduziert. | Nur „Abbrechen" bleibt sichtbar; Logout und Umstiegs-Links sind an der Session-Status-Karte gruppiert und nur außerhalb dieses Modus sichtbar, Logout zusätzlich nur wenn `AUTHENTICATED` |
| FE-17 | Die „Anmeldeverfahren verwalten"-Liste zeigt den vollständigen Methodenbestand des Kontos, nicht nur das, was diese Sitzung selbst nachgewiesen hat. | Sourced aus `activeMethods` ([API](05-api.md)), nicht aus `currentAmr` — sonst wäre eine Methode, die zwar aktiv ist, aber diese Sitzung nie geprüft hat (z. B. `email` bei einem Login, der nur `sms`+`password` brauchte), weder sichtbar noch verwaltbar |
| FE-18 | Der App-Kanal ist per URL mit einem `intent`-Query-Parameter (Wire-Vokabular wie `createChannel`s `intent`-Feld) direkt in einen bestimmten Ablauf einsteigbar, optional mit `pairingCode`. Der Back-Button verlässt einen laufenden Vorgang auf die Startauswahl zurück. | `AppChannelApp.tsx`: `intent`/`pairingCode` werden einmalig aus der URL gelesen und entfernt, `intent` startet automatisch den passenden `handleStart`-Aufruf. Ein `popstate`-Listener ruft bei aktivem Channel `handleClearChannel()` (lokal, kein Backend-Call) — kein Schritt-für-Schritt-Undo, der Ablauf ist serverseitig vorwärtsgetrieben (next-Modell) |

### Anmeldeverfahren verwalten & Step-up (AuthIntent.MANAGE_AUTH_METHODS)

Nach erfolgreicher Anmeldung zeigt die Ansicht zwei getrennte, aber verwandte Abschnitte: „Sicherheitsniveau erhöhen" (FE-15, vom Nutzer selbst ausgelöster Step-up) und „Anmeldeverfahren verwalten" (FE-17, Hinzufügen/Deaktivieren über `AuthIntent.MANAGE_AUTH_METHODS`, [Orchestrierung](04-orchestrierung.md) Abschnitt 3). Beide Wege enden in derselben `STEP_UP_IN_PROGRESS`-Mechanik und derselben Tool-Navigation — nur der Auslöser unterscheidet sich (`PATCH /channels` vs. ein `409` bei `POST .../methods`).

### Login ohne DPoP

`EntryChoiceLinks` bietet, solange der Kanal weder `AUTHENTICATED` noch `LOGGED_OUT` ist, den Wechsel auf `intent="lookup_login"`/`"register"` an — bewusst nicht nur auf den allerersten Bildschirm beschränkt, ein Nutzer darf auch mitten in einem mehrstufigen Ablauf anders neu starten. Für den Lookup-Login selbst existiert je Methode ein eigenes Formular (SMS/Passwort/E-Mail); die jeweilige Bestätigungseingabe (TAN/Code) teilt sich das Formular mit dem geräte-gebundenen Pendant, da beide denselben `next.step` nutzen.

### Web-Login per QR bestätigen (AuthIntent.CONFIRM_PEER_LOGIN)

Zwei gleichwertige Einstiege, passend zu `CONFIRM_PEER_LOGIN`s doppelter Erreichbarkeit
([Orchestrierung](04-orchestrierung.md) Abschnitt 2/3, `CONFIRM_PEER_LOGIN`):

- **Startbildschirm** („Wie möchten Sie starten?"): ein Eintrag „Web-Login per QR bestätigen" neben
  Automatisch/Registrieren/Neu anmelden, mit Diagramm-Hover wie die anderen. Setzt ein auf diesem
  Gerät bereits bekanntes Konto voraus — ohne `DeviceAccountLink` bricht die Journey sofort ab.
- **Authentifizierte Ansicht** (`AuthenticationCompletedView`): ein eigener Abschnitt „Web-Login per
  QR bestätigen" mit Button, der `POST /channels/{id}/peer-logins` auslöst — für den Fall, dass die
  App schon offen und angemeldet ist, wenn der QR-Code gescannt wird.

Beide Wege laufen auf denselben `next` hinaus (loa2 nicht erreicht → normaler loa2-Login/Step-up;
loa2 bereits erreicht, aber unabhängig von diesem Durchlauf → ein zusätzlicher Re-Proof mit einem
beliebigen aktiven Faktor, wie bei „Konto löschen" — kein Auto-Confirm allein auf Basis
vorhandener Evidenz; [Orchestrierung](04-orchestrierung.md) `CONFIRM_PEER_LOGIN`). Der Demo-Link, den die Web-Seite anzeigt,
zeigt auf `/app/?intent=confirm_peer_login&pairingCode=...` — dieselbe Origin, aber direkt der
App-Kanal statt Willkommen, und `intent` im selben Wire-Vokabular, das `createChannel`s
`intent`-Body-Feld ohnehin schon akzeptiert (`AuthIntent.fromRequest`, case-insensitiv). Öffnen
landet direkt im App-Kanal statt in einem fiktiven nativen Deep-Link-Schema oder erst auf der
Startauswahl: `intent`/`pairingCode` werden beim Laden aus der URL gelesen (und sofort wieder
entfernt), `intent=confirm_peer_login` startet automatisch denselben Ablauf, den sonst der Button
„Web-Login per QR bestätigen" auslöst — ist auf diesem Gerät bereits ein Channel bekannt, wird der
zuerst geladen; ist er schon `AUTHENTICATED`, läuft die Bestätigung darüber (kein neuer, verworfener
Channel), sonst wie beim kalten Einstieg. `pairingCode` wird zusätzlich lokal gemerkt
(`pendingPairingCode`), damit `confirm-qr-login`s eigener `input`-Schritt ihn vorbefüllt, sobald das
Tool aktiviert ist.

---

## 3) Navigation ausschließlich über `next`

Das Frontend nutzt eine **feste lokale Routing-Tabelle** und trifft UI-Entscheidungen
ausschließlich anhand von `next` — nie anhand von URLs, Action-Namen oder eigener
Ableitung aus dem Sessionzustand.

- **Backend liefert**: `next.type` (`tool` oder `orchestrator` — beide benennen, wem der nächste Screen gehört und welchen Endpunkt der Client als nächstes ruft), dazu `next.toolId` bzw. `next.context`, sowie `next.step`. Auswahloptionen stehen in `stepData.options`, fehlende Felder in `stepData.missingFields`.
- **Frontend entscheidet**: Eine lokale Routing-Tabelle (`routing.ts`), Schlüssel `(type, toolId|context, step)`, bildet das auf eine UI-Komponente ab — nie auf ein URL-Muster.
- Der Client konstruiert **niemals** eine `toolId` selbst; sie kommt entweder aus `next.toolId` oder als gewählter Eintrag aus `stepData.options`.

Beispiel (Ausschnitt):

```
ident-fsc  / input     -> FscForm
enroll-sms / enroll    -> SmsEnrollForm
enroll-sms / tanInput  -> TanInputForm
auth-sms   / auth      -> TanInputForm
...
enrollment / selectMethod   -> EnrollmentMethodSelection
authentication / authenticated -> AuthenticationCompleted
```

Bei einer Auswahlseite (`selectMethod`) füllt das Frontend die Auswahl aus `stepData.options`; die Einträge sind vollständige `toolId`-Werte. `SelectMethodView` übersetzt sie zusätzlich über eine rein darstellungsbezogene Tabelle (Icon, Kurzlabel, Erklärung) in Auswahlkarten — reine Anzeigefrage, keine Routing-Entscheidung: Welche `toolId` gewählt wurde, geht unverändert weiter.

Konsequenzen: Alle Backend-URLs bleiben Implementierungsdetail; ein neues Tool braucht im Frontend nur einen weiteren Eintrag in der Routing-Tabelle; auch tool-eigene Endpunkte ([API](05-api.md), Tool-Namespace) findet der Client über `(toolId, step)`, nie über URL-Interpretation.

Zwei Ausnahmen sind bewusst **kein** Bruch dieser Regel, weil sie nie entscheiden, welche
Komponente gerendert wird, sondern nur eine Aktion auslösen (derselbe Charakter wie der
`intent`-Body-Parameter bei `createChannel`): App-zu-App-Wechsel (Abschnitt 0) ist echte
Browser-Navigation, keine `next`-Entscheidung; der `intent`-Query-Parameter beim App-Kanal-Einstieg
(FE-18 unten) übersetzt sich einmalig in einen `handleStart`-Aufruf, das anschließende Rendering
läuft danach wieder ausschließlich über `next`.
