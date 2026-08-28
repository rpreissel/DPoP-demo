# API-Spezifikation

Das öffentliche API unter `/orchestrator/api/v1`, getrennt nach App-Kanal (Orchestrator-first)
und Web-Kanal (Keycloak-first).

Die fachliche Bedeutung der Antworten — insbesondere `next` — ergibt sich aus
[04-orchestrierung.md](04-orchestrierung.md).

---

## 1) API-Grundsätze

Festgelegte API-Entscheidung:

- Das öffentliche API wird unter `/orchestrator/api/v1` versioniert.
- Unterschiedliche Methoden und Modi werden über getrennte konkrete Endpunkte modelliert.
- Die URL bestimmt die Operation; derselbe Endpunkt darf nicht allein anhand unterschiedlicher Request-Bodies verschiedene fachliche Abläufe ausführen.
- Vorbereitende Methoden nutzen ressourcenorientierte Tool-Objekte: `POST` erzeugt das Tool über den Channel, danach gestaltet das Tool seinen eigenen URL-Namespace (Abschnitt 2). `PATCH` zum Nachliefern und `GET` zum Lesen sind der empfohlene Regelfall, aber nicht für jedes Tool verpflichtend.
- Bei `PATCH` wird grundsätzlich nur der aktuell nachzuliefernde oder zu ändernde Teil übergeben; bereits vorhandene Felder dürfen dabei gezielt überschrieben werden.
- HTTP-Fehlercodes sind gestörten Abläufen vorbehalten, nicht erwartbaren Nutzereingaben: Fehlende Pflichtdaten und fehlgeschlagene Versuche mit verbleibenden Retries werden mit `200` plus `next` beantwortet (Retry-Regel in [Orchestrierung](04-orchestrierung.md)), nicht mit `4xx`.
- HATEOAS wird im Zielbild nicht verwendet.
- Der Client leitet den nächsten technischen Call aus `next.type` (`tool` vs. `orchestrator`) und dem dazu passenden Attribut (`next.toolId` bzw. `next.context` + gewähltem Eintrag aus `stepData.options`) über eine feste Routing-Tabelle ab.
- Lesbarkeit hat Vorrang vor maximal generischem API-Wiring: methoden- und artspezifische Endpunkte sowie klar benannte DTOs/Handler (`ident-fsc`, `enroll-sms`, `auth-sms`) sind gewollt, auch wenn dafür etwas mehr expliziter Code entsteht.

---

## 2) App-Fassade (Orchestrator-first)

Alle Requests enthalten den Header `DPoP: <proof>`.

Designentscheidung:

- `processSessionId` bleibt als interne Prozessinstanz für Persistenz, Korrelation und Audit erhalten.
- Die fachliche Prozesswahl (`REGISTRATION`, `LOGIN`, `STEP_UP`) trifft das Backend auf Basis von Kanalzustand, Accountstatus und Policy.
- Öffentliche App-APIs verwenden nur `channelSessionId`; weder `purpose` noch die interne `processSessionId` werden vom Client vorgegeben.
- Das `next`-Objekt ist reine Adresse und hat immer dieselbe schlanke Form: bei einem konkreten Tool-Schritt `{ "type": "tool", "toolId": "...", "step": "...", "toolSessionId": "..." }`, bei einer orchestrator-eigenen Seite (Auswahl, Bestätigung, Abschluss) `{ "type": "orchestrator", "context": "...", "step": "..." }` — niemals mit Inhalt vermischt. `toolSessionId` ist die vollständige Adresse der Tool-Ressource (`/tools/{toolSessionId}/{toolId}`) und ist gesetzt, sobald eine `ToolSession` für diesen Schritt existiert — insbesondere beim Resume (`GET /channels/{channelSessionId}`) mitten in einem laufenden Tool, damit der Client die laufende Session weiterbenutzt statt sie erneut zu aktivieren.
- **Eine Antworthülle für alle Endpunkte aller Schichten** (`ChannelResponse`): `{ "channel": {channelSessionId, state, currentAcr, currentAmr, activeMethods}, "next": {...}, "stepData": {...}, "demo": {...} }`. `channel` ist ein benannter Block statt flacher Felder — als Block ist sofort erkennbar, was Kanalzustand und was Schrittzustand ist. `channelSessionId`/`state` stehen in jeder Antwort (werden durchgängig gebraucht, z. B. Statusanzeige, Cancel/Logout-Verfügbarkeit). `currentAcr`/`currentAmr`/`activeMethods` dagegen NIE in Tool-Antworten (`POST .../tools/{toolId}`, `PATCH`/`GET`/`DELETE` auf `/tools/...`) — sie sind keine Kerndaten des Ablaufs, sondern werden ausschließlich von der Sicherheits-Detailansicht gelesen, die der Client bei Bedarf gezielt nachlädt (`GET /channels/{channelSessionId}`), so wie jede echte Bildschirmansicht ihre eigenen Daten holt, statt dass jede Antwort sie prophylaktisch mitschleppt. Nur die echten Kanal-Endpunkte (`GET`/`POST /channels`, `step-ups`, `enrollments`, `DELETE .../methods/{methodInstanceId}`) liefern sie, weil genau das ihr Zweck ist.
- **`channel.state`-Werte** — alles, was der Client aus `state` selbst ableiten kann, ohne einen Endpunkt aufzurufen:

  | Wert | Bedeutung für den Client |
  |---|---|
  | `ANONYMOUS` | Kanal offen, noch kein Account bekannt — `next` zeigt auf `ident-fsc` oder Login |
  | `REGISTERING` | Registrierung läuft; `DELETE .../journey` (Cancel) bricht zurück auf `ANONYMOUS` |
  | `AUTHENTICATED` | Account bekannt und aktuelles Niveau ausreichend; `logout`, `methods`, `enrollments` verfügbar |
  | `STEP_UP_REQUIRED` / `STEP_UP_IN_PROGRESS` | Ein höheres Niveau ist nötig bzw. der Nachweis läuft bereits; `next` zeigt den fälligen Schritt, Cancel liefert direkt `AUTHENTICATED` zurück |
  | `LOGGED_OUT` | Terminal — dieser Kanal ist tot, `next` fehlt, ein neuer Kanal braucht einen neuen `POST /channels` |
  | `EXPIRED` | Terminal (TTL erreicht) — wie `LOGGED_OUT` aus Client-Sicht: neuer `POST /channels` nötig |

  Vollständiges Zustandsdiagramm inkl. Übergängen: [Domänenmodell](02-domaenenmodell.md) Abschnitt 3 — für die API selbst reicht die Tabelle oben.
- Auswahloptionen stehen nicht in `next`, sondern in `stepData.options` als vollständige `toolId`-Werte (z. B. `enroll-sms`), sodass der Client direkt den zugehörigen Endpunkt aufrufen kann.
- Der Client darf `toolId` nie selbst konstruieren oder erraten; sie kommt entweder direkt in `next.toolId` oder als Eintrag in `stepData.options`.
- Wenn genau eine Methode erlaubt ist, überspringt das Backend die Auswahlseite und liefert direkt den Tool-Schritt.
- `stepData` trägt alles, was der aktuelle Schritt zum Anzeigen braucht: bei laufendem Tool den tool-internen Zustand (z. B. `missingFields`), bei einer Auswahlseite die erlaubten Folge-Tools (`options`), nach einem fehlgeschlagenen Versuch den Grund (`error`). Ist nichts davon nötig, entfällt das Feld.

Pfadkonvention:

- Einstieg: `POST /orchestrator/api/v1/app/channels` — `201` mit `Location: .../channels/{channelSessionId}` (immer eine neue Ressource, nie ein Resume; die Location zeigt bereits auf die fassadenneutrale Kanal-Ressource, siehe Abschnitt 3).
- Kanalzustand lesen: `GET /orchestrator/api/v1/channels/{channelSessionId}`
- Niveau anheben (Step-up-Auslöser): `POST .../{channelSessionId}/step-ups` mit `{"requiredAcr": "..."}`
- Journey abbrechen: `DELETE .../{channelSessionId}/journey` (kein Body)
- Logout: `DELETE .../{channelSessionId}` (kein Body)
- Methodenbestand lesen: `GET .../{channelSessionId}/methods`
- Methode hinzufügen (startet Enrollment): `POST .../{channelSessionId}/enrollments` (kein Body)
- Methode deaktivieren: `DELETE .../{channelSessionId}/methods/{methodInstanceId}` (kein Body) — adressiert per Instanz-ID, nicht per Methodenname (siehe unten)
- Tool-Anlage über Channel: `POST .../{channelSessionId}/tools/{toolId}` — `201` mit `Location: .../tools/{toolSessionId}/{toolId}` (kein Body — `toolId` trägt Kind und Methode zusammen)
- Tool-Fortschreibung/-Lesen: `PATCH/GET /orchestrator/api/v1/tools/{toolSessionId}/{toolId}` als Regelfall
- Tool-Attempt verwerfen: `DELETE /orchestrator/api/v1/tools/{toolSessionId}/{toolId}`

Tool-Namespace:

- Die Aktivierung bleibt Orchestrator-Hoheit und ist für alle Tools gleich — nur dort entsteht die `toolSessionId`.
- Alles unterhalb von `/tools/{toolSessionId}/{toolId}` gestaltet das Tool selbst: eigene Sub-Ressourcen und frei gewählte HTTP-Methoden. `PATCH`/`GET` sind der empfohlene Regelfall, aber keine Pflicht — nicht jedes Verfahren passt in "Felder nachliefern" (WebAuthn reicht eine Assertion ein, eID braucht Redirect/Callback).
- Der Client findet diese Endpunkte über dieselbe Routing-Tabelle wie überall: `(toolId, step)` bildet auf den konkreten Endpunkt ab, ohne URL-Interpretation.
- Der garantierte Resume-Einstieg ist **nicht** die Tool-Ressource (deren `GET` ein Tool optional weglassen darf), sondern `GET /channels/{channelSessionId}`: existiert immer und liefert den aktuell fälligen `next`.
- **Implementierungsnote:** `POST`/`PATCH`/`GET` liegen je Tool in einem eigenen Controller mit typisiertem Request-DTO ([Tool-Architektur](03-tool-architektur.md) Abschnitt 2), nicht in einem generischen, `toolId`-dispatchenden Handler. `DELETE` ist die einzige Ausnahme (kein tool-spezifisches Verhalten).

### Cancel

`DELETE .../journey` bricht die laufende `AuthJourney` ab und rollt `ChannelSession.state` zurück ([Domänenmodell](02-domaenenmodell.md) Abschnitt 3). Danach startet der Kanal **denselben Intent** erneut, mit dem er eröffnet wurde — deshalb wird ein abgebrochener Lookup-Login wieder ein Lookup-Login und nicht stillschweigend eine Registrierung. `STEP_UP`- und `MANAGE_AUTH_METHODS`-Abbruch liefern direkt `authenticated`. Account-Bindung und `AuthContext` werden dabei aus der dauerhaften Wahrheit neu abgeleitet (`DeviceAccountLink`), nicht blind behalten oder blind verworfen; ein zuvor per `ident-fsc` angelegter Account bleibt unangetastet und wird bei erneuter Identifikation wiedergefunden.

### Logout

`DELETE /channels/{channelSessionId}` beendet den Kanal endgültig (`AUTHENTICATED -> LOGGED_OUT`, terminal, `204`): bricht wie Cancel einen aktiven Prozess ab und verwirft zusätzlich den `AuthContext`. Anders als Cancel lebt diese `channelSessionId` danach nicht weiter — ein neuer Kanal braucht einen neuen `POST`. Die Geräte-Bindung bleibt trotzdem nutzbar: `DeviceAccountLink` ([DPoP-Bindung](09-dpop.md) Abschnitt 3) sorgt dafür, dass der nächste `POST` auf einen neuen Kanal das Gerät wiedererkennt und direkt LOGIN statt `ident-fsc` anbietet.

### Methoden verwalten (AuthIntent.MANAGE_AUTH_METHODS)

Freiwillige Kontoverwaltung auf einem bereits `AUTHENTICATED`-Kanal, losgelöst vom policy-getriebenen REGISTRATION/STEP_UP-Ablauf ([Orchestrierung](04-orchestrierung.md) Abschnitt 3).

- `GET .../methods` liest den aktiven Methodenbestand als echte, eigenständig lesbare Collection (`{"methods": [{"id","method","label"}]}`) — dieselben Daten wie `ChannelResponse.activeMethods`, nie `fsc`. Leere Liste statt Fehler, solange kein Account bekannt ist. `id` ist die einzige gültige Adressierung für `DELETE` (siehe unten); `label` ist nur bei mehrfach-möglichen Methoden (`device`) vom Nutzer gesetzt, sonst `null` — der Client zeigt dafür einen festen Default-Namen aus `method`.
- `POST .../enrollments` bietet dieselben Kandidaten/Enroll-Tools wie REGISTRATION an (legt selbst keine Methode an, sondern startet ein Enrollment — daher der Name). Nichts mehr zu enrollen ist kein Fehler: `200` mit `{"message": "Keine weiteren Mittel verfuegbar"}`.
- `DELETE .../methods/{methodInstanceId}` deaktiviert eine aktive Methoden-*Instanz*, adressiert per `id` aus `GET .../methods` — nie per Methodenname, da eine Methode mehrere aktive Instanzen haben kann (z. B. mehrere Geräte, `docs/03-tool-architektur.md`, `allowsMultipleInstances`). `409`, falls der Account danach das kanaleigene `requiredAcr` nicht mehr erreichen könnte (Selbstsperrschutz). Bewusst **nicht** darauf beschränkt, nur Instanzen des aufrufenden Geräts zu deaktivieren — ein verlorenes/gestohlenes Gerät muss von jeder authentifizierten Session aus entfernbar sein.
- `POST .../enrollments` und `DELETE .../methods/{methodInstanceId}` verlangen zusätzlich, dass die aktuelle Session bereits `loa2` erreicht hat; reicht es nicht, liefert die Antwort statt der Aktion einen Step-up-Schritt — der Client folgt ihm wie jedem anderen Step-up und ruft den Endpunkt danach erneut auf.
- Response-Form von `POST .../enrollments`/`DELETE .../methods/{methodInstanceId}` ist dieselbe `ChannelResponse` wie bei `GET`/`PATCH` auf `/channels/{channelSessionId}`.

### Back/Switch

`DELETE /tools/{toolSessionId}/{toolId}` verwirft einen aktivierten, aber noch nicht abgeschlossenen Tool-Versuch (z. B. um doch eine andere Methode zu wählen). Die verworfene `toolSessionId` wird sofort ungültig; der Prozess bekommt dieselbe Kandidatenermittlung erneut vorgesetzt, die schon beim letzten `Completed` benutzt wurde — ohne dass etwas neu nachgewiesen wurde.

Konsistenzregel: Pro `channelSessionId` darf es höchstens einen aktiven öffentlichen Prozesskontext geben; welcher interne `purpose` dazu gehört, entscheidet das Backend.

### Ein Tool-Zyklus im Beispiel

Registrierung mit `ident-fsc` -> `enroll-sms`:

1. `POST /app/channels` (`{"requiredAcr": "loa2", "availableTools": ["ident-fsc", "enroll-sms", ...]}` - `intent` weggelassen (Default `fast_access`), `availableTools` ist Pflicht, siehe unten) liefert eine neue `channelSessionId` (im `channel`-Block) und direkt den ersten Schritt: `next={"type":"tool","toolId":"ident-fsc","step":"input"}` (genau eine `IDENT`-Methode in `availableTools` enthalten, daher kein Auswahlschritt; noch keine `ToolSession`, also kein `toolSessionId` in `next`). Erklärt dieser Client stattdessen auch `ident-eid` als verfügbar, liefert derselbe `POST` einen Auswahlschritt: `next={"type":"orchestrator","context":"registration","step":"selectIdentificationMethod"}`, `stepData={"options":["ident-fsc","ident-eid"]}` — der Client aktiviert dann direkt das gewählte Tool, wie in Schritt 2.
2. `POST .../tools/ident-fsc` (kein Body) legt die Tool-Ressource an: `201` mit `stepData={"missingFields":["kvnr","name","vorname"]}` und `next.toolSessionId` gesetzt.
3. `PATCH /tools/{toolSessionId}/ident-fsc` mit den Feldern, zuletzt dem FSC. Solange Felder fehlen: `200` mit aktualisiertem `stepData.missingFields`, `next` unverändert auf `ident-fsc`. Nach erfolgreicher Verifikation: `stepData={"options":["enroll-sms"]}`, `next={"type":"orchestrator","context":"enrollment","step":"selectMethod"}` (bzw. direkt `{"type":"tool","toolId":"enroll-sms",...}` bei nur einer erlaubten Methode).
4. `POST .../tools/enroll-sms` liefert `stepData={"missingFields":["phoneNumber"]}`.
5. `PATCH .../enroll-sms` mit `{"phoneNumber": "..."}` löst den TAN-Versand aus: `stepData={"missingFields":["tan"]}` plus (siehe unten) `demo={"tan":"123456"}`.
6. `PATCH .../enroll-sms` mit `{"tan": "123456"}` schließt ab: `next={"type":"orchestrator","context":"authentication","step":"authenticated"}`, `channel.state` bereits `"AUTHENTICATED"` in derselben Antwort — kein `stepData` und kein separater `GET` mehr nötig, um das zu erfahren.
7. `GET /channels/{channelSessionId}` liefert jederzeit den stabilen Kanalzustand — der garantierte Resume-Einstieg. Mitten in einem laufenden Tool (z. B. App-Neustart nach Schritt 5) liefert er denselben `next` inklusive `toolSessionId` zurück, sodass der Client die laufende Session weiterbenutzt statt die Tool-Anlage erneut aufzurufen.

Jedes weitere Tool (`enroll-password`/`auth-password`, `enroll-email`/`auth-email`, die `-lookup`-Varianten) folgt demselben `POST`(anlegen)/`PATCH`(nachliefern)/`GET`(lesen)-Muster; die tool-spezifischen Abweichungen stehen unten.

### Das `demo`-Objekt

Jede Antwort kann ein zusätzliches, klar gekennzeichnetes `demo`-Objekt tragen — **kein Teil des produktiven Vertrags**, nur damit die Demo-Oberfläche ohne Server-Log-Zugriff durchgeklickt werden kann (in einer echten Umgebung abgeschaltet). Es trägt `accountId`/`personId` (interne Korrelations-IDs, kein Folgeaufruf braucht sie), sowie je nach Tool `tan` (gerade ausgestellte TAN/Code), `password`/`email` (feste Demo-Werte zum Vorbelegen von Formularen). Ein Tool hängt seine demo-Werte generisch über einen reservierten Schlüssel an (`tool_spi.demoData(...)`), ohne dass der Orchestrator die einzelnen Feldnamen kennen muss.

### `POST /app/channels`: `intent`-Parameter

`intent` (optional, Default `fast_access`) ist der Name des `AuthIntent`-Werts, case-insensitiv (`AuthIntent.fromRequest`) - keine separate Wire-Vokabel; unbekannte Werte werden abgelehnt, nie still gemappt. Steuert, welcher Prozess auf DIESEM Kanal startet, unabhängig vom durch `DeviceAccountLink` erkannten Gerät:

- `fast_access` (Default, auch bei weggelassenem `intent`): heutiges Verhalten — `DeviceAccountLink` gefunden -> LOGIN mit vorbefülltem Account, sonst REGISTRATION.
- `lookup_login`: erzwingt lookup-basierten Login (E-Mail + Credential, siehe unten) — auch auf einem bereits verlinkten Gerät. Der Link-Lookup wird für diesen Kanal komplett übersprungen.
- `register`: erzwingt eine frische REGISTRATION — auch auf einem bereits verlinkten Gerät (Zweitaccount). Übersteht der Kanal die Registrierung, überschreibt sie den bestehenden `DeviceAccountLink`.

`requiredAcr` (optional) erspart der App den Umweg über ein niedriges Einstiegsniveau mit anschließendem Step-up. Der Wert wirkt nur nach oben: Das Backend rechnet mit `max(Policy-Anforderung, Client-Wunsch)`.

`availableTools` (Pflicht) erklärt, welche toolIds dieser Client aktivieren kann — was er rendern kann, minus was der Nutzer lokal deaktiviert hat. Fest für die Lebensdauer des Kanals, kein Update danach. Ein Tool außerhalb dieser Menge wird nie angeboten und auch bei direkter Aktivierung abgelehnt (`docs/03-tool-architektur.md`, Verfügbarkeit) — zusätzlich zu dieser Client-Achse kann das Backend jedes Tool global und zur Laufzeit sperren (`GET`/`PUT /admin/tools/.../availability`, kein Redeploy nötig).

### `GET /channels/{channelSessionId}`

Liest den stabilen Kanalzustand — Resume-Einstieg und einfache Session-/Policy-Sicht. Zwei zusätzliche Felder im `channel`-Block neben `state`:

- `currentAmr`: was **diese Sitzung** bereits nachgewiesen hat (Sitzungsevidenz aus dem `AuthContext`).
- `activeMethods`: der volle, kontostabile Methodenbestand als `{id, method, label}`-Objekte — unabhängig davon, was diese Sitzung geprüft hat. Enthält nie `fsc` (Identifikation liegt in `identifications`, nicht in `authenticationMethods`). `id` adressiert die Instanz für `DELETE`; `label` ist nur bei mehrfach-möglichen Methoden gesetzt (aktuell nur `device` — mehrere Geräte können je ein eigenes, benanntes Credential halten). `auth-device` erscheint als AUTH-Kandidat nur auf dem physischen Gerät, das den passenden Schlüssel hält (`docs/04-orchestrierung.md`); Deaktivieren selbst bleibt bewusst ungefiltert.

Beide Felder werden nur bei bekanntem `accountId` befüllt. `next` ist immer gesetzt — auch bei abgeschlossener Journey (`{"type":"orchestrator","context":"authentication","step":"authenticated"}`); ein separates `stepUpRequired`-Flag gibt es bewusst nicht, da schon `next` selbst zeigt, ob ein Step-up ansteht. Nur bei `LOGGED_OUT` (terminal) fehlt `next` ganz.

### `POST /channels/{channelSessionId}/step-ups`: Step-up-Auslöser

Hebt die geforderte Untergrenze des Kanals an (auf der kc-Seite derselbe fassadenneutrale Endpunkt, kein eigener kc-Trigger — siehe Abschnitt 3). Request: `{"requiredAcr": "loa3"}`. Reicht das aktuelle Niveau nicht, startet das Backend eine `AuthJourney(STEP_UP)` und liefert den fälligen Schritt in derselben `ChannelResponse`-Form wie überall; reicht es bereits, bleibt keine Journey offen und `next` zeigt sofort auf `authenticated`. Nur Anheben ist möglich — ein niedrigeres `requiredAcr` wird ignoriert. Ist das geforderte Niveau mit den vorhandenen Methoden des Accounts nicht erreichbar, bricht die Journey mit `410` ab, statt eine Auswahl ohne gültige Kandidaten anzubieten ([Orchestrierung](04-orchestrierung.md)).

### `enroll-email` / `auth-email` / `enroll-password` / `auth-password`

Folgen demselben Muster wie `ident-fsc`/`enroll-sms`/`auth-sms` oben, mit diesen Abweichungen:

- `enroll-email` folgt dem Zwei-`PATCH`-Muster (`email`, dann `code`); `demo.email` steht schon in der `start`/`read`-Antwort (feste, überall gleiche Demo-Adresse), `demo.tan` erst nach dem ersten `PATCH`.
- `auth-email` folgt dem Ein-`PATCH`-Muster (`code`), deckt aber nur den geräte-gebundenen Fall ab: authentifiziert gegen die bereits bekannte, bestätigte E-Mail-Adresse des Accounts, nicht gegen eine im Request übergebene.
- `enroll-password`/`auth-password` erwarten nur `{"password": "..."}` — **kein** `username` ([Tool-Architektur](03-tool-architektur.md) Abschnitt 1). `enroll-password` schließt in einem einzigen `PATCH` ab und setzt eine bereits bestätigte Account-E-Mail voraus (`requiresConfirmedEmail`); ohne sie lehnt schon die Aktivierung mit `409` ab. `demo.password` (feste Demo-Konstante) steht in jeder `InProgress`-Antwort aller drei Passwort-Tools.

### Lookup-basierter Login (`auth-sms-lookup` / `auth-password-lookup` / `auth-email-lookup`, "Login ohne DPoP")

Erreichbar nur über `POST /channels` mit `intent: "lookup_login"` — nie über die normale Kandidatenermittlung einer bereits Account-gebundenen Session (`MethodRole.LOOKUP_AUTH`; `AuthPolicy.candidateTools` wählt ausschließlich `DEVICE_AUTH`). Löst den Account selbst über die eingegebene E-Mail auf, statt ihn schon über den Kanal zu kennen:

- `auth-sms-lookup`/`auth-email-lookup` folgen dem Zwei-`PATCH`-Muster: erst `{"email": "..."}` (löst den Account auf, verschickt bei Erfolg TAN/Code), dann `{"tan"/"code": "..."}`.
- `auth-password-lookup` erwartet `{"email": "...", "password": "..."}` in einem einzigen `PATCH`.
- Enumeration-Schutz: Eine unbekannte oder unbestätigte E-Mail verhält sich in Form und Timing identisch zu einem korrekt aufgelösten Account mit falschem Credential — nie eine eigene Fehlerform. Das gilt auch für die demo-Werte: `demo.email`/`demo.password` sind feste Konstanten, unabhängig vom tatsächlich aufgelösten Account, verraten also nichts.
- Bei Erfolg schreibt der Orchestrator `DeviceAccountLink` für dieses Gerät neu — ein danach ohne `intent` (Default `fast_access`) angelegter Kanal erkennt das Gerät und bietet direkt den gewöhnlichen geräte-gebundenen LOGIN an.

---

## 3) Web/Keycloak-Fassade (Keycloak-first)

**Geplant, noch nicht gebaut** (bd-Epic `DPoP-demo-bqi`, 0/7 – ersetzt das früher hier dokumentierte
`processes/login`/`processes/step-up`-Muster).

Designentscheidung (bd `DPoP-demo-bqi.5`): **ein einziger** fassaden-spezifischer Endpunkt —

- `POST /orchestrator/api/v1/kc/channels` — etabliert eine `ChannelSession(WEB)`, verankert an der
  kc-Auth-Session (initialer Login) bzw. User-Session (Step-up). Antwort in derselben `ChannelResponse`-
  Form wie `POST /app/channels`.

Danach läuft **alles** über dieselben fassadenneutralen Endpunkte wie die App-Fassade — kein
`/kc/`-Präfix, keine Duplikate von Tool-Aktivierung & Co.:

- `GET`/`DELETE .../channels/{channelSessionId}`, `.../step-ups`, `.../journey`, `.../methods`,
  `.../enrollments`, `.../token`, `.../idclaims` (Abschnitt 2)
- `POST .../channels/{channelSessionId}/tools/{toolId}` (Aktivierung), danach `PATCH`/`GET
  /tools/{toolSessionId}/{toolId}` — identisch zur App-Fassade

Statt DPoP-Proof authentifiziert sich Keycloak selbst über eine signierte Request-Assertion (kein
mTLS, bd `DPoP-demo-bqi.4`): ein JWT pro Request mit `iss=keycloak`, `aud=orchestrator`, `htm`/`htu`
dieses Requests, `jti`+`iat` (derselbe Replay-Cache und dasselbe `max-clock-skew-seconds`-Fenster
wie bei DPoP), plus Kontext — `kcAuthSessionId` beim initialen Login (noch kein `sub`, der
Orchestrator ermittelt den Account erst im Ablauf) bzw. `kcSessionId`/`sub` beim Step-up (Nutzer
bereits bekannt). Verifikation gegen Keycloaks JWKS, ein Schlüsselpaar pro Client, nicht pro Nutzer.

Web-Login ist der bereits gebaute Lookup-Login (kein Gerät im Web-Kanal, `DeviceAccountLink` bleibt
APP-only, bd `DPoP-demo-bqi.1`): ohne Gerätebindung bleiben nur die `-lookup`-Tools
(`deviceBound=false`). `intent=fast_access` ist im Web bedeutungslos, da es auf
`DeviceAccountLink` aufsetzt — bleiben `register`/`lookup_login` als explizite Wahl.

---

## 4) Hybrid-Modell: Prozess-API + Tool-Ressourcen

Ziel: Prozesssicht/Fachführung bleibt in den Prozess-Endpoints; App-Frontend und Keycloak nutzen für Eingabe- und Verifikationsschritte dieselben kanalneutralen Tool-URLs.

- Der Channel-/Prozess-Endpunkt wählt über `toolId` das Tool aus und erzeugt eine technische `ToolSession`, ohne selbst fachliche Eingabedaten entgegenzunehmen.
- Das Backend liefert einen fachlich eindeutigen `next`-Zustand; App/Keycloak leiten daraus über dieselbe feste Routing-Tabelle den nächsten Endpunkt ab.
- `AuthJourney` bleibt der fachliche Owner (Intent, Zustand, Versuchsbudget); `ToolSession` trägt nur Lifecycle-Metadaten (`toolSessionId`, `journeyId`, Zeitstempel) — weder `toolId` noch `stepData` noch ein Retry-Zähler sind eigene Spalten: `toolId` ergibt sich aus der Route, `stepData` aus den Moduldaten, und das Versuchsbudget gilt für die ganze Journey (siehe [Domänenmodell](02-domaenenmodell.md)).
- `accountId`/`personId` sind kein Teil des fachlichen Antwortvertrags — der Client braucht sie für keinen der über `next` erreichbaren Folgeaufrufe. Einzige Ausnahme ist das demo-Objekt.

Für Keycloak ist `auth-sms`/`auth-password`/`auth-email` (Login/Step-up) der einzige nicht bereits über die App-Fassade abgedeckte Fall — Aktivierung, `PATCH` und `GET` laufen identisch zur App-Seite über den (kc-eigenen) Channel: `POST .../channels/{channelSessionId}/tools/auth-sms`, danach `PATCH`/`GET /tools/{toolSessionId}/auth-sms` (Abschnitt 3).

Damit ist nur die fachliche Freigabe prozess- und kanalabhängig; Startparameter und Verifikation laufen danach kanalneutral über ein einheitliches Tool-Muster.
