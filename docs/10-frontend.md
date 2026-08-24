# Frontend

Anforderungen an die Demo-Oberfläche und die Regel, nach der sie navigiert.

Die zugrundeliegende API beschreibt [05-api.md](05-api.md), die Schlüsselerzeugung
[09-dpop.md](09-dpop.md).

---

## 1) Technische Anforderungen

| ID | Anforderung | Kriterium |
|----|-------------|-----------|
| FE-1 | Frontend auf Basis von React (aktuelle Version) und TypeScript. | siehe Versionstabelle in [08-projektrahmen.md](08-projektrahmen.md) |
| FE-2 | Das Frontend kann autark betrieben werden. | `npm run dev` startet den Vite-Dev-Server |
| FE-3 | Das Frontend kann über Spring Boot gehostet werden. | Build-Output landet in `src/main/resources/static`; `./gradlew bootRun` liefert es aus |
| FE-4 | Im Entwicklungsmodus werden API-Requests weitergeleitet. | Vite-Dev-Server proxyt `/orchestrator` nach `http://localhost:8080` |
| FE-5 | Das Frontend kommuniziert ausschließlich über den `orchestrator`. | Keine direkten Aufrufe an fachliche Module |

---

## 2) UI-Anforderungen

| ID | Anforderung | Kriterium |
|----|-------------|-----------|
| FE-6 | Übersichtliches Layout mit Karten, konsistentem Farbschema und Darkmode. | visuelle Gestaltung als Karten |
| FE-7 | Formulare sind mit Testdaten vorbelegt. | `ident-fsc`, Telefonnummer: clientseitig fest vorbelegt (freie Eingabe, kein Server-Wert zum Echo). TAN/Code/Passwort/E-Mail dagegen serverseitig über das generische `demo`-Objekt (`demo.tan`/`demo.password`/`demo.email`, [05-api.md](05-api.md) Abschnitt 2 „Implementierungshinweis") — jedes betroffene Formular übernimmt den Wert per `useEffect`, sobald die Prop gesetzt ist, dieselbe Vorbelegung gilt für Enrollment, geräte-gebundenes Login und Lookup-Login gleichermaßen |
| FE-8 | Der aktuelle Stand und der nächste Schritt werden dargestellt. | Anzeige aus `next` und `stepData` |
| FE-9 | Telefonnummern werden clientseitig vorvalidiert. | Formatprüfung vor dem Absenden; das Backend lehnt ungültige Nummern mit `400` ab |
| FE-10 | Geräte-Identität und Kanal lassen sich unabhängig voneinander zurücksetzen. | **„Neu erzeugen"** (Geräte-Identität-Karte): löscht den gespeicherten DPoP-Key, erzeugt einen neuen (neue Geräte-Identität) und setzt den lokalen Kanal-State zurück — startet aber **keinen** neuen Kanal (`handleRecreateKey`, gleiches „nichts automatisch"-Prinzip wie beim Start, FE-12). **„Leeren"** (Session-Status-Karte, neben der gekürzten `channelSessionId`): vergisst nur die lokal gemerkte `channelSessionId` und den lokalen State, ruft **kein** Backend auf (`handleClearChannel`). **Logout**: behält den DPoP-Key (gleiches Gerät), ruft `DELETE .../channels/{channelSessionId}` ([API](05-api.md), Abschnitt „Logout") auf, das die `ChannelSession` endgültig beendet, und vergisst danach die lokal gemerkte ID — legt anders als früher **keinen** neuen Kanal mehr automatisch an; nur sichtbar, wenn der Kanal `AUTHENTICATED` ist |
| FE-11 | Nach erfolgreicher Anmeldung werden `accountId` und `personId` angezeigt. | Werte stammen aus dem `demo`-Objekt der Antwort (siehe unten) |
| FE-12 | Das Frontend merkt sich `channelSessionId` dauerhaft, getrennt vom DPoP-Key — es passiert aber nichts automatisch. | `localStorage` über `frontend/src/session.ts` (`loadChannelSessionId`/`storeChannelSessionId`/`forgetChannelSessionId`), separat von der `IndexedDB`-Schlüsselablage ([DPoP-Bindung](09-dpop.md) Abschnitt 3: der Key beweist nur das Gerät, nie welche Session fortzusetzen ist). Der Init-Effekt in `App.tsx` erzeugt/lädt **ausschließlich** den DPoP-Key; ist kein Kanal aktiv, zeigt eine eigene Karte „Kein Kanal aktiv" die möglichen Startaktionen (`handleStart(mode)`) als explizite Buttons: „Sitzung fortsetzen (…)" (nur sichtbar, wenn eine `channelSessionId` gemerkt ist; `GET`), „Verbinden (automatisch)", „Login ohne DPoP", „Neuen Account registrieren" (letzte drei: `POST` mit passendem `intent`) — der Nutzer entscheidet, nichts läuft beim Laden der Seite von selbst |
| FE-13 | Beim Anlegen eines Kanals lässt sich `requiredAcr` wählen. | `<select>` (`loa1 (Standard)` / `loa2 (MFA)`) im „Kein Kanal aktiv"-Formular in `App.tsx`; `api.ts` reicht den Wert über `createChannel(dpop, requiredAcr?)` an `POST /channels` durch ([API](05-api.md) Beispiel 1). Ohne diese Wahlmöglichkeit wäre `enroll-password` (bestätigte E-Mail vorausgesetzt) in der Demo praktisch unerreichbar: Die Registrierung schließt automatisch ab, sobald irgendein einzelnes `loa1`-Mittel (SMS oder E-Mail) die Standard-Untergrenze erfüllt — Passwort käme nie an die Reihe, ohne von vornherein ein höheres Niveau zu fordern |
| FE-14 | Die Geräte-Identität (JWK-Thumbprint) ist sichtbar und lässt sich unabhängig vom Kanal neu erzeugen. | Eigene Karte „Geräte-Identität" in `App.tsx`, immer sichtbar (auch ohne aktiven Kanal): gekürzter RFC-7638-Thumbprint (`dpop.ts:computeJwkThumbprint`, volle Länge im Debug-Panel/`title`-Attribut) plus Button „Neu erzeugen" (`handleRecreateKey`, siehe FE-10) |
| FE-15 | Ein authentifizierter Kanal lässt sich gezielt auf ein höheres Sicherheitsniveau anheben (Step-up). | `AuthenticationCompletedView.tsx` zeigt einen Abschnitt „Sicherheitsniveau erhöhen" mit einem Button „Auf loa2 anheben", sobald `currentAcr !== "loa2"` (loa2/MFA ist das einzige Niveau, das mit den in dieser Demo verfügbaren Tools erreichbar ist — kein `loa3`-Tool implementiert). `onStepUp` ruft `raiseRequiredAcr(dpop, channelSessionId, "loa2")` -> `PATCH /channels/{channelSessionId}` ([API](05-api.md) Beispiel 9); die Antwort (`next` zeigt auf ein Auth-Tool oder eine Auswahlseite) läuft über dasselbe Routing/dieselben Tool-Formulare wie jeder andere Login/Step-up, keine eigene Step-up-UI nötig. Ein `410` (Ziel-Niveau mit den enrollten Mitteln nicht erreichbar) landet im normalen Fehlerpfad (`describeError`) |
| FE-16 | Solange ein Tool aktiv Eingaben erwartet — oder der Nutzer gerade zwischen mehreren Tools wählt —, wird auf eine einzige naheliegende Aktion reduziert. | `App.tsx`s `inToolMode` (`!!activeTool \|\| uiComponent === 'select-method'`) blendet Logout und die Login/Registrieren-Umstiegs-Links (`EntryChoiceLinks`) aus und zeigt nur noch „Abbrechen" — alle anderen, seltener gebrauchten Aktionen sind an der Session-Status-Karte gruppiert und nur sichtbar, wenn weder ein Tool aktiv ist noch eine Verfahrensauswahl aussteht; Logout zusätzlich nur, wenn der Kanal `AUTHENTICATED` ist. Die `select-method`-Bedingung schließt eine frühere Lücke: die Auswahlseite selbst zählte nicht als „Tool-Modus" (noch kein `activeTool`), obwohl dort dieselbe Reduktion ebenso gilt |
| FE-17 | Die „Anmeldeverfahren verwalten"-Liste zeigt den vollständigen Methodenbestand des Kontos, nicht nur das, was diese Sitzung selbst nachgewiesen hat. | `AuthenticationCompletedView.tsx` iteriert für die Deaktivieren-Liste über `activeMethods` (neues Feld auf `ChannelResponse`, [API](05-api.md) Beispiel 8), nicht mehr über `currentAmr`. Vorher konnte eine Methode, die zwar aktiv war, aber diese konkrete Sitzung nie geprüft hat (z. B. `email` bei einem Login, der nur `sms`+`password` brauchte), in der UI weder gesehen noch verwaltet werden — `currentAmr` ist bewusst Sitzungsevidenz, kein Methodenbestand. `activeMethods` enthält nie `fsc` (kommt aus `identifications`, nicht `authenticationMethods`), daher entfällt hier auch der frühere `.filter(method !== 'fsc')` |

### Anmeldeverfahren verwalten (MANAGE_METHODS)

Nach erfolgreicher Anmeldung zeigt `AuthenticationCompletedView.tsx` einen Abschnitt „Anmeldeverfahren verwalten": die aktiven Methoden (aus `currentAmr`, ohne `fsc` — das ist Identifikation, kein dauerhaftes Auth-Mittel) mit je einem „Deaktivieren"-Button sowie einen Button „Weiteres Verfahren hinzufügen". `api.ts` stellt dafür `startManageMethods`/`deactivateMethod` bereit ([API](05-api.md), Abschnitt „Methoden verwalten (MANAGE_METHODS)"), `App.tsx` verdrahtet sie über `handleAddMethod`/`handleDeactivateMethod`. Navigation danach läuft über dasselbe `next`/Routing-Prinzip wie überall — ein dabei intern ausgelöster Step-up (loa2-Gate, [Orchestrierung](04-orchestrierung.md) Abschnitt 3) sieht für die UI aus wie jeder andere Step-up auch.

Direkt darüber steht der Abschnitt „Sicherheitsniveau erhöhen" (FE-15): ein vom Nutzer selbst ausgelöster Step-up, unabhängig von MANAGE_METHODS — beide Wege enden aber in derselben `STEP_UP_IN_PROGRESS`-Mechanik und derselben Tool-Formular-Navigation, nur der Auslöser unterscheidet sich (`PATCH /channels` vs. ein `409` bei `POST .../methods`).

### Login ohne DPoP (`intent`-Wechsel + Lookup-Formulare)

`EntryChoiceLinks.tsx` zeigt, solange der Kanal weder `AUTHENTICATED` noch `LOGGED_OUT` ist, zwei Buttons „Ich habe schon einen Account (Login ohne DPoP)" und „Neuen Account registrieren". Beide legen über `createChannel(dpop, requiredAcr?, intent)` ([API](05-api.md), Abschnitt „`intent`-Parameter") einen komplett neuen Kanal an (`intent="login"` bzw. `"register"`) und ersetzen den lokalen State vollständig — bewusst nicht nur auf den allerersten Bildschirm von REGISTRATION/LOGIN beschränkt, ein Nutzer darf auch mitten in einem mehrstufigen Ablauf abbrechen und anders neu starten.

Für den Lookup-Login selbst gibt es eigene Formulare, verdrahtet über `routing.ts`s Einträge `auth-sms-lookup`/`auth-password-lookup`/`auth-email-lookup` ([Orchestrierung](04-orchestrierung.md) Abschnitt 4):
- `EmailLookupForm.tsx` (Schritt `auth` von `auth-sms-lookup`): fragt nur nach der E-Mail-Adresse; die anschließende TAN-Eingabe läuft über das bereits bestehende `TanInputForm.tsx` (Schritt `tanInput`), kein neues Formular nötig.
- `EmailPasswordLookupForm.tsx` (Schritt `auth` von `auth-password-lookup`): fragt E-Mail und Passwort in einem einzigen Formular ab (selbstverifizierend, wie `PasswordLoginForm.tsx`).
- `EmailCodeLookupForm.tsx` (Schritt `auth` von `auth-email-lookup`): fragt nur nach der E-Mail-Adresse (die zugleich Identifikator und Zustellziel des Codes ist); die anschließende Code-Eingabe läuft über das bereits bestehende `EmailCodeInputForm.tsx` (Schritt `codeInput`, geteilt mit `enroll-email`/`auth-email`) — dritte und letzte fehlende Parität zu den geräte-gebundenen Verfahren: bis dahin gab es für „Login ohne DPoP" nur SMS und Passwort, nie E-Mail selbst, obwohl E-Mail als Konto-Methode gleichwertig zu den anderen beiden ist.

### Zum `demo`-Objekt

`accountId` und `personId` sind interne Korrelations-IDs und gehören fachlich nicht in eine
Client-Antwort — der Client braucht sie für keinen Folgeaufruf. Für die Demo-Oberfläche sind
sie dennoch nützlich, um den Ablauf nachvollziehbar zu machen. Sie werden deshalb in einem
eigens gekennzeichneten `demo`-Objekt geliefert, das kein Teil des produktiven Vertrags ist
und in einer echten Umgebung abgeschaltet wird ([05-api.md](05-api.md)).

Dasselbe Objekt trägt inzwischen auch `tan`, `password` und `email` — alle drei über denselben
generischen Mechanismus geliefert (`demoData(...)`, [05-api.md](05-api.md) Abschnitt 2). Jedes
Formular, das einen dieser Werte übernehmen kann, bekommt ihn als eigene `demo*`-Prop
durchgereicht (z. B. `PasswordLoginForm`s `demoPassword`, `EmailLookupForm`s `demoEmail`) und
übernimmt ihn per `useEffect`, sobald sich der Wert ändert — dasselbe Muster wie das schon
länger bestehende `TanInputForm`s `demoTan`.

### Debug-Sidebar

`DebugSidebar.tsx` ist eine dauerhaft sichtbare, rechts angedockte Spalte über die volle
Bildschirmhöhe (`.debug-sidebar` in `App.css`, kein Umschalt-Button mehr) — nicht optional
einblendbar, weil der ganze Zweck dieser Demo ist, unter der Haube zu zeigen, was passiert.
Sie zeigt zwei Dinge:

- **Kanal**: der aktuelle Client-State (`channelSessionId`, `channelState`, `currentAcr`/`currentAmr`, `next`, `stepData`, `demo`, `activeTool`) als JSON-Dump. Die Geräte-Identität (JWK-Thumbprint) erscheint hier bewusst **nicht** noch einmal — die hat bereits ihre eigene Karte im Hauptbereich (FE-14).
- **Verlauf**: ein chronologisches Ereignis-Log (neueste zuerst, auf 200 Einträge gedeckelt). Jeder API-Aufruf erscheint automatisch darin, inklusive Methode, URL, Request-Body und Response-Body (oder Fehlermeldung) — Header (insbesondere der `DPoP`-Proof) werden bewusst **nicht** geloggt, weil sie für das Nachvollziehen des Demo-Ablaufs nicht relevant sind.

Technisch zentralisiert `api.ts`s `onApiCall`-Mechanismus dieses Logging: Jeder `call()`-Aufruf
meldet sich selbst dort (`ApiCallLogEntry` mit `method`/`path`/`requestBody`/`status`/
`responseBody`/`error`), `App.tsx` abonniert das einmal (`useEffect` mit `onApiCall`, leeres
Deps-Array) und reicht jeden Eintrag an `logEvent` durch. Einzelne Handler (`handlePatch`,
`handleStart`, ...) schreiben deshalb **keine** eigenen Log-Einträge mehr für API-Calls — nur
noch für rein lokale, nicht-API-Ereignisse (`DPoP-Key geladen/erzeugt`, `Kanal lokal geleert`).
Das vermeidet die vorherige Duplikation (jeder Call-Handler pflegte sein eigenes Label/Payload
von Hand, leicht divergent vom tatsächlich Gesendeten) zugunsten einer einzigen Quelle der
Wahrheit direkt an der Stelle, die den Request tatsächlich baut.

---

## 3) Navigation ausschließlich über `next`

Das Frontend nutzt eine **feste lokale Routing-Tabelle** und trifft UI-Entscheidungen
ausschließlich anhand von `next` — nie anhand von URLs, Action-Namen oder eigener
Ableitung aus dem Sessionzustand.

- **Backend liefert**: `next.type` (`tool` oder `flow`), dazu `next.toolId` bzw. `next.context`, sowie `next.step`. Auswahloptionen stehen in `stepData.options`, fehlende Felder in `stepData.missingFields`.
- **Frontend entscheidet**: Aus diesen Angaben ermittelt eine lokale Routing-Tabelle (`routing.ts`), welche UI-Komponente anzuzeigen ist.
- **UI-Komponenten** sind an `(type, toolId|context, step)` gekoppelt, nicht an URL-Muster.
- Der Client konstruiert **niemals** eine `toolId` selbst; sie kommt entweder aus `next.toolId` oder als gewählter Eintrag aus `stepData.options`.

### Beispiel Routing-Tabelle

```
type = tool
  ident-fsc      / input      -> FscForm
  enroll-sms     / enroll     -> SmsEnrollForm
  enroll-sms     / tanInput   -> TanInputForm
  auth-sms       / auth       -> TanInputForm
  enroll-password / enroll    -> PasswordEnrollForm
  auth-password  / auth       -> PasswordLoginForm
  enroll-email   / enroll     -> EmailEnrollForm
  enroll-email   / codeInput  -> EmailCodeInputForm
  auth-email     / auth       -> EmailCodeInputForm
  auth-sms-lookup      / auth      -> EmailLookupForm
  auth-sms-lookup      / tanInput  -> TanInputForm
  auth-password-lookup / auth      -> EmailPasswordLookupForm
  auth-email-lookup    / auth      -> EmailCodeLookupForm
  auth-email-lookup    / codeInput -> EmailCodeInputForm

type = flow
  registration   / selectIdentificationMethod -> IdentificationMethodSelection
  enrollment     / selectMethod               -> EnrollmentMethodSelection
  auth           / selectMethod               -> AuthenticationMethodSelection
  authentication / authenticated              -> AuthenticationCompleted
```

Bei `type = flow` und einem `selectMethod`-Schritt füllt das Frontend die Auswahl aus
`stepData.options`; die Einträge sind vollständige `toolId`-Werte und lassen sich direkt
auf den zugehörigen Endpunkt abbilden. `SelectMethodView.tsx` übersetzt jede rohe `toolId`
zusätzlich über eine lokale, rein darstellungsbezogene `TOOL_META`-Tabelle (Icon, Kurzlabel,
Ein-Satz-Erklärung) in eine Auswahlkarte statt eines Buttons mit dem rohen `toolId`-Text als
Beschriftung — reine Anzeigefrage, keine Routing-Entscheidung: Welche `toolId` tatsächlich
gewählt wurde, geht unverändert an `onSelect`. Eine unbekannte `toolId` fällt auf ein generisches
Icon und den `toolId`-Text selbst zurück, statt zu crashen.

### Konsequenzen

- Alle Backend-URLs sind Implementierungsdetails und nicht Gegenstand der UI-Logik.
- Die UI-Navigation ist deterministisch und unabhängig von der Form der Backend-Endpunkte.
- Ein neues Tool erfordert im Frontend nur einen weiteren Eintrag in der Routing-Tabelle.
- Tools dürfen eigene Endpunkte mitbringen ([05-api.md](05-api.md), Tool-Namespace); auch die findet der Client über `(toolId, step)`, nicht über URL-Interpretation.
