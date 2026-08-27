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

### Anmeldeverfahren verwalten & Step-up (AuthIntent.MANAGE_AUTH_METHODS)

Nach erfolgreicher Anmeldung zeigt die Ansicht zwei getrennte, aber verwandte Abschnitte: „Sicherheitsniveau erhöhen" (FE-15, vom Nutzer selbst ausgelöster Step-up) und „Anmeldeverfahren verwalten" (FE-17, Hinzufügen/Deaktivieren über `AuthIntent.MANAGE_AUTH_METHODS`, [Orchestrierung](04-orchestrierung.md) Abschnitt 3). Beide Wege enden in derselben `STEP_UP_IN_PROGRESS`-Mechanik und derselben Tool-Navigation — nur der Auslöser unterscheidet sich (`PATCH /channels` vs. ein `409` bei `POST .../methods`).

### Login ohne DPoP

`EntryChoiceLinks` bietet, solange der Kanal weder `AUTHENTICATED` noch `LOGGED_OUT` ist, den Wechsel auf `intent="login"`/`"register"` an — bewusst nicht nur auf den allerersten Bildschirm beschränkt, ein Nutzer darf auch mitten in einem mehrstufigen Ablauf anders neu starten. Für den Lookup-Login selbst existiert je Methode ein eigenes Formular (SMS/Passwort/E-Mail); die jeweilige Bestätigungseingabe (TAN/Code) teilt sich das Formular mit dem geräte-gebundenen Pendant, da beide denselben `next.step` nutzen.

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
