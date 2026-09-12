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
- Bestätigter Logout (startet Journey): `POST .../{channelSessionId}/logouts` (kein Body)
- Sofort-Logout: `DELETE .../{channelSessionId}` (kein Body)
- Methodenbestand lesen: `GET .../{channelSessionId}/methods`
- Methode hinzufügen (startet Enrollment): `POST .../{channelSessionId}/enrollments` (kein Body)
- Methode deaktivieren: `DELETE .../{channelSessionId}/methods/{methodInstanceId}` (kein Body) — adressiert per Instanz-ID, nicht per Methodenname (siehe unten)
- Account löschen (startet Journey): `POST .../{channelSessionId}/account-deletions` (kein Body)
- Rückfrage beantworten: `POST .../{channelSessionId}/answer` mit `{"answer": "accept"|"decline"}` — der eine generische Endpunkt für jeden `Prompt` (siehe unten), unabhängig davon, welcher Intent gerade darauf wartet
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

Zwei Varianten:

- **Bestätigter Logout** (bevorzugt): `POST .../{channelSessionId}/logouts` startet eine `LOGOUT`-Journey mit einem Bestätigungs-Prompt ([Orchestrierung](04-orchestrierung.md) Abschnitt 3). Nach Zustimmung über `POST .../answer` wird der Kanal `LOGGED_OUT`.
- **Sofort-Logout** (nicht-interaktive Clients): `DELETE /channels/{channelSessionId}` beendet den Kanal direkt (`AUTHENTICATED → LOGGED_OUT`, terminal, `204`), bricht einen aktiven Prozess ab und verwirft den `AuthContext`.

In beiden Fällen lebt die `channelSessionId` danach nicht weiter — ein neuer Kanal braucht einen neuen `POST`. Die Gerätebindung bleibt nutzbar: `DeviceAccountLink` ([DPoP-Bindung](09-dpop.md) Abschnitt 3) sorgt dafür, dass der nächste `POST` das Gerät wiedererkennt.

### AccessToken (`GET .../{channelSessionId}/token`)

**`APP`-Kanal-only** (DPoP-demo-xso, ADR-9): ein `KEYCLOAK`-Kanal hat nie einen `AuthContext`, aus
dem sich ein Token minten ließe, und braucht auch keinen — dessen Client hält bereits echte
Keycloak-Tokens aus dem normalen Browser-Login und erneuert sie direkt gegen Keycloak, nie über den
Orchestrator. `ChannelService.getToken` weist einen `KEYCLOAK`-Kanal deshalb mit `409
INVALID_STATE_TRANSITION` ab, bevor `TokenProvider` überhaupt aufgerufen wird. Für `APP` selbst
entscheidet `TokenProvider` profilabhängig:

- **Default-Profil** (`MockTokenProvider`): liefert unverändert `TokenService`s Mock-JWT
  (`alg=none`, `iss=mock-keycloak`) — kein echtes Keycloak beteiligt, wie bisher.
- **`keycloak`-Profil** (`KcTokenProvider`): liefert einen echten, von Keycloak signierten
  AccessToken, der auch echte `acr`/`amr`-Claims trägt (über den bestehenden
  `OrchestratorAcrAmrMapper`, wie beim WEB-Kanal). Drei Fälle, dieselbe Struktur wie
  `TokenService.tokenFor`: (1) noch lange genug gültig → unverändert zurück; (2) läuft ab, aber
  ACR/AMR unverändert → billige Erneuerung über Keycloaks eigenen `refresh_token`-Grant, ohne
  Account-Private-Key; (3) kein gültiges RefreshToken mehr (Erstausstellung oder ein Step-up hat
  den Cache gerade invalidiert) → frische, signierte Assertion (Private Key AUS
  `account_keycloak_keypair`, trägt `acr`/`amr` selbst als Claims) über den custom OAuth2-Grant
  (`urn:dpop-demo:account-token`, `keycloak-extension`s `AccountTokenGrantType`; Details: ADR-9).
  Alle Aufrufe für denselben Account teilen sich dabei dieselbe Keycloak-Session
  (`AccountTokenGrantType` sucht sie über eine eigene Session-Note wieder, statt bei jedem Aufruf
  eine neue anzulegen) — ein späteres Step-up mintet ein neues Token in derselben Session, nicht in
  einer parallelen.

`minValiditySeconds` verhält sich in beiden Profilen identisch (Rückgabe unverändert, solange das
aktuelle AccessToken noch lange genug gilt) — mit einer Ausnahme: ein Step-up, der die zugrunde
liegende `AuthEvidence` verändert (`AuthEvidenceService.applyEvidence`/`applyEvidenceUpdate`),
verwirft das gecachte Token aktiv, egal wie lange es zeitlich noch gültig wäre. Ohne das würde ein
Client, der kurz nach einem Step-up erneut `.../token` aufruft, bis zu die volle TTL lang noch das
alte, Vor-Step-up-Token/-Claims zurückbekommen.

### ID-Token-Claims (`GET .../{channelSessionId}/idclaims`)

**`APP`-Kanal-only**, wie das AccessToken oben — dieselbe `requireAuthenticated`-Vorbedingung.
Fachliche (nicht in die AccessToken-Signatur codierte) Claims: `sub`/`accountId`/`personId`,
`acr`/`amr`, `auth_time`, `email`/`email_verified`, `name` ("Vorname Name" der Person,
`PersonDirectory.displayName`). `name` ist die einzige Stelle, an der das Frontend erfährt, WER
angemeldet ist — bewusst hier statt im `demo`-Objekt (das würde bei jeder Tool-Antwort mitberechnet,
nicht nur beim ohnehin schon gezielt abgerufenen Claims-Aufruf) oder einem eigenen
`/accounts/{id}`-Endpunkt (der bräuchte eine neue, von der Kanal-Autorisierung unabhängige
Zugriffsprüfung) — derselbe `channelAccessGuard` wie überall sonst, derselbe Zweck wie ein echtes
OIDC-ID-Token, nur eben als reine Business-Claims-Ressource statt eines signierten JWT.

### Methoden verwalten (AuthIntent.MANAGE_AUTH_METHODS)

Freiwillige Kontoverwaltung auf einem bereits `AUTHENTICATED`-Kanal, losgelöst vom policy-getriebenen REGISTRATION/STEP_UP-Ablauf ([Orchestrierung](04-orchestrierung.md) Abschnitt 3).

- `GET .../methods` liest den aktiven Methodenbestand als echte, eigenständig lesbare Collection (`{"methods": [{"id","method","label"}]}`) — dieselben Daten wie `ChannelResponse.activeMethods`, nie `fsc`. Leere Liste statt Fehler, solange kein Account bekannt ist. `id` ist die einzige gültige Adressierung für `DELETE` (siehe unten); `label` ist nur bei mehrfach-möglichen Methoden (`device`) vom Nutzer gesetzt, sonst `null` — der Client zeigt dafür einen festen Default-Namen aus `method`.
- `POST .../enrollments` bietet dieselben Kandidaten/Enroll-Tools wie REGISTRATION an (legt selbst keine Methode an, sondern startet ein Enrollment — daher der Name). Nichts mehr zu enrollen ist kein Fehler: `200` mit `{"message": "Keine weiteren Mittel verfuegbar"}`.
- `DELETE .../methods/{methodInstanceId}` deaktiviert eine aktive Methoden-*Instanz*, adressiert per `id` aus `GET .../methods` — nie per Methodenname, da eine Methode mehrere aktive Instanzen haben kann (z. B. mehrere Geräte, `docs/03-tool-architektur.md`, `allowsMultipleInstances`). `409`, falls der Account danach das kanaleigene `requiredAcr` nicht mehr erreichen könnte (Selbstsperrschutz). Bewusst **nicht** darauf beschränkt, nur Instanzen des aufrufenden Geräts zu deaktivieren — ein verlorenes/gestohlenes Gerät muss von jeder authentifizierten Session aus entfernbar sein.
- `POST .../enrollments` und `DELETE .../methods/{methodInstanceId}` verlangen zusätzlich, dass die aktuelle Session bereits `loa2` erreicht hat; reicht es nicht, liefert die Antwort statt der Aktion einen Step-up-Schritt — der Client folgt ihm wie jedem anderen Step-up und ruft den Endpunkt danach erneut auf.
- Response-Form von `POST .../enrollments`/`DELETE .../methods/{methodInstanceId}` ist dieselbe `ChannelResponse` wie bei `GET`/`PATCH` auf `/channels/{channelSessionId}`.

### Das `Prompt`-Objekt

Jeder `JourneyState`, der auf eine explizite Ja/Nein-Antwort statt auf einen Tool-Lauf wartet (`AnswerableState`), trägt einen `prompt` in `stepData`: `{"@t": "Confirm", "title": "...", "description": "...", "confirmLabel": "...", "cancelLabel": "...", "destructive": true|false}`. `next` ist dabei für **jeden** `AnswerableState`, unabhängig vom Intent, exakt derselbe feste Wert — `{"type":"orchestrator","context":"prompt","step":"confirm"}` —, denn der gerenderte Screen ist ebenfalls immer derselbe, rein aus `stepData.prompt` gespeist; ein eigener `context`/`step` pro Intent (wie ursprünglich `authentication/offerDeviceBinding` und `accountDeletion/confirmDelete`) wäre dieselbe Adresse nur mehrfach anders geschrieben. Beantwortet wird jeder `Prompt` über denselben generischen `POST .../{channelSessionId}/answer` mit `{"answer": "accept"|"decline"}`.

Der App-Kanal ist eine mobile App mit App-Store-Release-Zyklen von Wochen; jeder Text, den ein Prompt anzeigt, wird deshalb **komplett vom Backend geliefert**, nie clientseitig vorformuliert — eine neue oder geänderte Rückfrage braucht dadurch keinen App-Release, nur neuen Backend-Code.

`Prompt` ist als `sealed interface` mit `@t`-Diskriminator modelliert; `Confirm` ist die einzige Variante, eine künftige `Choice`-Variante (Auswahl aus mehreren Antworten) ist vorbereitet, aber nicht implementiert. Verwendungen, alle über dieselbe `prompt/confirm`-Adresse: der optionale Gerätebindungs-Screen des Lookup-Logins, die Account-Löschbestätigung (siehe unten), die Logout-Bestätigung und `ReIdentifyState.OfferReIdent` — „Mit den vorhandenen Verfahren nicht erreichbar, stattdessen erneut identifizieren?", bevor die geteilte `RE_IDENTIFY`-SubJourney (angefordert von `FAST_ACCESS`/`LOOKUP_LOGIN`/`STEP_UP`) eine Re-Identifizierung anbietet ([Orchestrierung](04-orchestrierung.md)).

### Account löschen (AuthIntent.DELETE_ACCOUNT)

Self-Service-Löschung des eigenen Accounts auf einem bereits `AUTHENTICATED`-Kanal. Die Ja/Nein-Bestätigung kommt bewusst **immer zuerst, unbedingt** — das kostet nichts und darf nie hinter einem Step-up versteckt sein, den der Aufrufer vielleicht gar nicht durchlaufen will. Erst nach Zustimmung greift dasselbe loa2-Gate wie vor `MANAGE_AUTH_METHODS`:

1. `POST .../{channelSessionId}/account-deletions` (kein Body) startet die Journey und liefert sofort die Bestätigungsrückfrage: `next={"type":"orchestrator","context":"prompt","step":"confirm"}`, `stepData.prompt` mit `destructive: true`.
2. `POST .../answer` mit `{"answer":"accept"}` (oder `"decline"`, bricht wie ein normales Cancel zurück auf `AUTHENTICATED`). Erst jetzt greift das loa2-Gate: reicht das aktuelle Niveau nicht, liefert die Antwort statt des nächsten Schritts einen Step-up-Schritt; der Client folgt ihm wie jedem anderen und ruft `account-deletions` danach erneut auf.
3. Reichte das Niveau schon vorher (Evidenz unbekannten Alters), folgt jetzt ein frischer Nachweis über ein **beliebiges** aktives `auth-*`-Verfahren des Accounts, unabhängig vom damit erreichbaren Niveau (auch ein soeben erst in dieser Session bewiesener Faktor zählt erneut — anders als bei `STEP_UP`, das genau solche bereits bewiesenen Faktoren ausschließt). Genau ein Verfahren reicht; mehrere aktive Verfahren ergeben dieselbe `next={"context":"auth","step":"selectMethod"}`-Auswahlseite wie jeder andere auf IDENTIFIED_AUTH-Kandidaten wartende Zustand (`selectionContext` benennt die Art des Angebots, nicht den Intent — kein eigener `accountDeletion`-Auswahlkontext). **Ausnahme**: musste Schritt 2 selbst erst einen Step-up auslösen, zählt dieser frisch erbrachte Nachweis bereits als der hier geforderte — die Löschung erfolgt dann direkt im Anschluss an den Step-up, ohne einen zweiten, redundanten Nachweis zu verlangen.
4. Nach erfolgreichem Nachweis wird der Account und alles, was er exklusiv besitzt, sofort und unwiderruflich gelöscht: alle über `authenticationMethods` referenzierten Credential-Datensätze der Methodenmodule (aktive **und** bereits abgelöste), der `DeviceAccountLink`, jeder `AuthContext` sowie die `account`-Zeile selbst. `person` (ext_stammdaten) bleibt unangetastet — der Account referenziert es nur, besitzt es aber nicht ([Tool-Architektur](03-tool-architektur.md), `EnrollmentCleanup`).
5. Jede `ChannelSession`, die je an diesen Account gebunden war — nicht nur die aufrufende — wird dabei serverseitig auf `LOGGED_OUT` gezwungen: ein anderes eingeloggtes Gerät fällt beim nächsten Request sofort auf `ANONYMOUS` zurück, ein neuer Kanal braucht dort einen neuen `POST /channels`.
5. Die Antwort auf den erfolgreichen Abschluss ist `channel.state="LOGGED_OUT"` ohne `next` — exakt dieselbe Form wie ein normaler Logout, nicht ein eigener Erfolgs-Zustand.

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
- `register`: erzwingt eine frische REGISTRATION — auch auf einem bereits verlinkten Gerät (Zweitaccount). Löst die neue Identifikation ein anderes Konto auf als das bisher verlinkte, fragt der Kanal direkt danach (noch vor jeder Methodenauswahl) über eine Bestätigung (`RegisterState.ConfirmDeviceRebind`) nach, ob die bestehende `DeviceAccountLink`-Bindung ersetzt werden soll — Zustimmung bindet das Gerät um und deaktiviert/löscht das bisherige Konto-eigene Geräte-Credential (`enroll-device`) für genau diesen Schlüssel; Ablehnung bricht die Journey regulär ab (kein Fehler, `DELETE .../journey`-Semantik), die alte Bindung bleibt unverändert bestehen.
- `confirm_peer_login`: startet `AuthIntent.CONFIRM_PEER_LOGIN` — einen wartenden Web-Login (`auth-qr`/`auth-qr-lookup`) bestätigen/ablehnen (siehe unten, "Peer-Login bestätigen"). Auch von einem kalten, noch nicht authentifizierten Kanal aus erreichbar, aber nie mit Identifikation/Registrierung als Fallback.

`requiredAcr` (optional) erspart der App den Umweg über ein niedriges Einstiegsniveau mit anschließendem Step-up. Der Wert wirkt nur nach oben: Das Backend rechnet mit `max(Policy-Anforderung, Client-Wunsch)`.

`availableTools` (Pflicht) erklärt, welche toolIds dieser Client aktivieren kann — was er rendern kann, minus was der Nutzer lokal deaktiviert hat. Fest für die Lebensdauer des Kanals, kein Update danach. Ein Tool außerhalb dieser Menge wird nie angeboten und auch bei direkter Aktivierung abgelehnt (`docs/03-tool-architektur.md`, Verfügbarkeit) — zusätzlich zu dieser Client-Achse kann das Backend jedes Tool global und zur Laufzeit sperren (`GET`/`PUT /admin/tools/.../availability`, kein Redeploy nötig).

### `GET /channels/{channelSessionId}`

Liest den stabilen Kanalzustand — Resume-Einstieg und einfache Session-/Policy-Sicht. Zwei zusätzliche Felder im `channel`-Block neben `state`:

- `currentAmr`: was **diese Sitzung** bereits nachgewiesen hat (Sitzungsevidenz aus dem `AuthContext`).
- `activeMethods`: der volle, kontostabile Methodenbestand als `{id, method, label}`-Objekte — unabhängig davon, was diese Sitzung geprüft hat. Enthält nie `fsc` (Identifikation liegt in `identifications`, nicht in `authenticationMethods`). `id` adressiert die Instanz für `DELETE`; `label` ist nur bei mehrfach-möglichen Methoden gesetzt (aktuell nur `device` — mehrere Geräte können je ein eigenes, benanntes Credential halten). `auth-device` erscheint als AUTH-Kandidat nur auf dem physischen Gerät, das den passenden Schlüssel hält (`docs/04-orchestrierung.md`); Deaktivieren selbst bleibt bewusst ungefiltert.

Beide Felder werden nur bei bekanntem `accountId` befüllt. `next` ist immer gesetzt — auch bei abgeschlossener Journey (`{"type":"orchestrator","context":"authentication","step":"authenticated"}`); ein separates `stepUpRequired`-Flag gibt es bewusst nicht, da schon `next` selbst zeigt, ob ein Step-up ansteht. Nur bei `LOGGED_OUT` (terminal) fehlt `next` ganz.

Wer angemeldet ist, gehört dagegen zu den **ID-Token-Claims** (`GET .../idclaims`, s. u.), nicht in
diesen Block — dafür ist diese Ressource da, kein zweiter Träger derselben Aussage.

### `GET /app/channels/device-link`

Reiner Read: ob dieses Gerät (DPoP-Proof, keine `channelSessionId` nötig) bereits an einen Account
gebunden ist (`DeviceAccountLink`, [Domänenmodell](02-domaenenmodell.md) Abschnitt 1) — legt **kein**
Channel/Journey an. `{"linked": true, "accountId": 42, "personName": "Max Muster"}` bzw.
`{"linked": false}`. Erlaubt der Startauswahl, "dieses Gerät gehört zu X" zu zeigen, bevor der Nutzer
überhaupt wählt, wie er starten will.

### `POST /channels/{channelSessionId}/step-ups`: Step-up-Auslöser

Hebt die geforderte Untergrenze des Kanals an (auf der kc-Seite derselbe fassadenneutrale Endpunkt, kein eigener kc-Trigger — siehe Abschnitt 3). Request: `{"requiredAcr": "loa3"}`. Reicht das aktuelle Niveau nicht, startet das Backend eine `AuthJourney(STEP_UP)` und liefert den fälligen Schritt in derselben `ChannelResponse`-Form wie überall; reicht es bereits, bleibt keine Journey offen und `next` zeigt sofort auf `authenticated`. Nur Anheben ist möglich — ein niedrigeres `requiredAcr` wird ignoriert. Reicht keine vorhandene Methode für das geforderte Niveau, aber eine Re-Identifizierung (`ident-fsc`/`ident-eid`) könnte es allein erreichen, fragt die Journey das erst per `stepData.prompt` (`context: "prompt", step: "confirm"`) — bei Zustimmung folgt die Auswahl, bei Ablehnung endet der Step-up ohne Fehler. Nur wenn selbst das nicht möglich ist, bricht die Journey mit `410` ab, statt eine Auswahl ohne gültige Kandidaten anzubieten ([Orchestrierung](04-orchestrierung.md)).

### `enroll-email` / `auth-email` / `enroll-password` / `auth-password`

Folgen demselben Muster wie `ident-fsc`/`enroll-sms`/`auth-sms` oben, mit diesen Abweichungen:

- `enroll-email` folgt dem Zwei-`PATCH`-Muster (`email`, dann `code`); `demo.email` steht schon in der `start`/`read`-Antwort (feste, überall gleiche Demo-Adresse), `demo.tan` erst nach dem ersten `PATCH`.
- `auth-email` folgt dem Ein-`PATCH`-Muster (`code`), deckt aber nur den geräte-gebundenen Fall ab: authentifiziert gegen die bereits bekannte, bestätigte E-Mail-Adresse des Accounts, nicht gegen eine im Request übergebene.
- `enroll-password`/`auth-password` erwarten nur `{"password": "..."}` — **kein** `username` ([Tool-Architektur](03-tool-architektur.md) Abschnitt 1). `enroll-password` schließt in einem einzigen `PATCH` ab und setzt eine bereits bestätigte Account-E-Mail voraus (`requiresConfirmedEmail`); ohne sie lehnt schon die Aktivierung mit `409` ab. `demo.password` (feste Demo-Konstante) steht in jeder `InProgress`-Antwort aller drei Passwort-Tools.

### Lookup-basierter Login (`auth-sms-lookup` / `auth-password-lookup` / `auth-email-lookup`, "Login ohne DPoP")

Erreichbar nur über `POST /channels` mit `intent: "lookup_login"` — nie über die normale Kandidatenermittlung einer bereits Account-gebundenen Session (`MethodRole.LOOKUP_AUTH`; `AuthPolicy.candidateTools` wählt ausschließlich `IDENTIFIED_AUTH`). Löst den Account selbst über die eingegebene E-Mail auf, statt ihn schon über den Kanal zu kennen:

- `auth-sms-lookup`/`auth-email-lookup` folgen dem Zwei-`PATCH`-Muster: erst `{"email": "..."}` (löst den Account auf, verschickt bei Erfolg TAN/Code), dann `{"tan"/"code": "..."}`.
- `auth-password-lookup` erwartet `{"email": "...", "password": "..."}` in einem einzigen `PATCH`.
- Enumeration-Schutz: Eine unbekannte oder unbestätigte E-Mail verhält sich in Form und Timing identisch zu einem korrekt aufgelösten Account mit falschem Credential — nie eine eigene Fehlerform. Das gilt auch für die demo-Werte: `demo.email`/`demo.password` sind feste Konstanten, unabhängig vom tatsächlich aufgelösten Account, verraten also nichts.
- Bei Erfolg schreibt der Orchestrator `DeviceAccountLink` für dieses Gerät neu — ein danach ohne `intent` (Default `fast_access`) angelegter Kanal erkennt das Gerät und bietet direkt den gewöhnlichen geräte-gebundenen LOGIN an.

### Peer-Login bestätigen (AuthIntent.CONFIRM_PEER_LOGIN)

Ein App-Kanal bestätigt/lehnt einen wartenden Web-Login ab, den eine `auth-qr`/`auth-qr-lookup`-
Aktivierung des Web-Kanals angestoßen hat ([Orchestrierung](04-orchestrierung.md) `CONFIRM_PEER_LOGIN`).
Zwei gleichwertige Einstiege, je nachdem ob die App gerade erst gescannt hat oder schon offen ist:

- `POST /app/channels` mit `{"intent":"confirm_peer_login"}` — kalter Einstieg, siehe `intent`-Parameter oben.
- `POST /channels/{channelSessionId}/peer-logins` (kein Body) — auf einem bereits `AUTHENTICATED`-Kanal.

Beide liefern dieselbe `ChannelResponse`-Form und laufen auf demselben Gate zusammen:

1. Kein Konto über `DeviceAccountLink` bekannt (nur beim kalten Einstieg möglich) → `410`, nie ein
   Fallback auf Identifikation/Registrierung.
2. Aktuelles Niveau unter `loa2` → ein Step-up-Schritt wie bei jedem anderen Step-up; der Client
   folgt ihm und ruft den Einstiegs-Endpunkt danach erneut auf.
3. Niveau bereits `loa2` — aber unabhängig von diesem Durchlauf erreicht (Evidenz unbekannten
   Alters) → statt direkt weiter verlangt die Antwort einen frischen Nachweis über ein beliebiges
   aktives `auth-*`-Verfahren, exakt wie bei „Account löschen" Schritt 3 oben (`next={"context":"auth","step":"selectMethod"}`
   bei mehreren Kandidaten). **Ausnahme**: Musste Schritt 2 selbst erst einen Step-up auslösen,
   zählt dieser frisch erbrachte Nachweis bereits als der hier geforderte.
4. `confirm-qr-login` aktivieren:
   - `POST .../tools/confirm-qr-login` (kein Body) → `stepData={"missingFields":["pairingCode"]}`.
   - `PATCH .../confirm-qr-login` mit `{"pairingCode":"..."}` (aus dem QR-Code bzw. dem Demo-Link
     vorbefüllt, [Frontend](10-frontend.md)) → bei gültiger, noch offener Anfrage
     `stepData={"verificationCode":"..."}`, `next.step="confirm"`. Der Nutzer vergleicht diesen Code
     mit dem auf der Web-Seite gezeigten (QR-Jacking-Schutz — ein reiner Blick-Abgleich, nie ein
     übertragenes Feld). Unbekannter/abgelaufener/bereits entschiedener Code: `stepData.error`,
     bleibt auf `input`, normale Retry-Logik.
   - `PATCH .../confirm-qr-login` mit `{"decision":"accept"}` bzw. `{"decision":"reject"}`. `accept`
     ohne aktives `enroll-qr` auf dem Konto: `stepData.error` ("QR-Login ist für dieses Konto nicht
     aktiviert"), kein stillschweigendes Durchlassen.
5. Erfolgreiches `accept`: `next={"type":"orchestrator","context":"authentication","step":"authenticated"}`
   — war der Kanal vor diesem Aufruf noch nicht `AUTHENTICATED`, fragt die Antwort davor per
   `Prompt` ("Jetzt abmelden?"), ob der Kanal angemeldet bleiben soll; `reject` liefert stattdessen
   `stepData.error` ("Vom Nutzer abgelehnt"), der Web-Kanal erfährt das über seinen eigenen Poll (unten).

Die WEB-Seite selbst (`auth-qr`/`auth-qr-lookup`) pollt denselben generischen Tool-Patch-Endpunkt
mit leerem Body, solange die Anfrage noch offen ist — kein eigener Statusproxy nötig, ein leeres
`PATCH` auf einen `InProgress`-Zustand kostet nirgends etwas (kein Attempt-/Login-Throttle). Bei
`APPROVED` liefert derselbe `PATCH` `Completed.Authenticated`, bei `DENIED`/abgelaufen entsprechend
`Failed` — für `auth-qr-lookup` inklusive des aufgelösten `accountId` im `demo`-Objekt.

---

## 3) Web/Keycloak-Fassade (Keycloak-first)

**Umgesetzt.** Anders als die App-Fassade spricht der Browser hier nie mit dem Orchestrator — die
Strecke Keycloak → Orchestrator ist reine Server-zu-Server-Kommunikation über Keycloaks eigenes
Java-SPI-Plugin (`keycloak-extension/`). Ein einziger fassaden-spezifischer Endpunkt, Upsert-
Semantik statt Erzeugen-dann-Fortsetzen:

- `PATCH /orchestrator/api/v1/kc/channels/{channelSessionId}` — legt den Kanal beim ersten Aufruf
  unter dieser Keycloak-gewählten ID an, setzt ihn bei jedem weiteren Aufruf fort. Die ID stammt aus
  Keycloaks eigenem, laufendem Auth-Flow (`AuthenticationSessionModel`/`UserSessionModel`), nie vom
  Orchestrator vergeben — das macht den Aufruf idempotent (ein wiederholter `PATCH` auf dieselbe ID
  liefert dasselbe Ergebnis) und erspart einen separaten "ID merken und zurückschicken"-Umlauf, den
  das `POST`-Muster der App-Fassade dafür braucht.

Body (alle Felder optional, `KcChannelUpsertRequest`):

| Feld | Bedeutung |
|---|---|
| `accountId` | Der Account, den Keycloak schon kennt (`sub` vorhanden, Step-up) — bindet den Kanal sofort, nie später überschrieben. |
| `targetAcr` | Keycloaks angefragtes LoA-Level, bereits in einen Orchestrator-ACR-String übersetzt — hebt nur die Kanal-Untergrenze an, nie herab. Ohne dieses Ziel könnte `KC_SELECT_METHOD` (Abschnitt 3 in [Orchestrierung](04-orchestrierung.md)) seine Kandidaten nicht sinnvoll filtern. |
| `amr` | Liste `{nativeToolId, amrSourceId}` — was ein natives Keycloak-Verfahren (nie ein Orchestrator-Tool) DIESEN Flow-Durchlauf gerade bewiesen hat. Methode/Loa/Faktortypen löst der Orchestrator serverseitig über `nativeToolId` auf (`NativeAuthenticatorDescriptor`), nicht mitgeschickt — genau wie ein Orchestrator-Tool seine Evidenz aus dem eigenen `ToolDescriptor` bezieht. Immer die VOLLSTÄNDIGE, aktuell gültige Menge, kein Delta. |
| `restoreData` / `kcSessionId` | Ein signiertes Token aus `GET .../restore-data` einer FRÜHEREN, unabhängigen `ChannelSession` (derselben Keycloak-User-Session) — reicht die dort erreichte Evidenz an einen frisch angelegten Kanal weiter, bevor dessen erste Journey-Entscheidung überhaupt läuft. `kcSessionId` bindet das Token an Keycloaks durables `UserSessionModel`, unabhängig vom flow-lokalen Kanal-Anker. Ein falsches/abgelaufenes/manipuliertes Token kommt als `null` zurück, nie als Fehler — bedeutet nur "ohne Vorlauf starten". |
| `availableTools` | Welche `toolId`s das Keycloak-Theme rendern kann (ein `WebToolRenderer` pro Tool) — nur beim ersten Aufruf gelesen, das Web-Pendant zu `availableTools` bei `POST /app/channels`. |
| `intent` | Nur beim ersten Aufruf gelesen, wie `availableTools`. Deutlich enger als das App-Pendant (`POST /app/channels`, s. o.): weggelassen bedeutet `kc_select_method` (unverändertes Login/Step-up-Verhalten), akzeptiert wird sonst ausschließlich `register` — nicht jeder `AuthIntent.isEntryIntent`-Wert, da `fast_access`/`lookup_login` einen App-förmigen Kanal (Gerätebindung, DPoP) voraussetzen, den diese Fassade nie hat. Ein unbekannter oder unzulässiger Wert wird abgelehnt (`409`), nie still auf `kc_select_method` zurückgemappt. |

`GET .../{channelSessionId}/restore-data?kcSessionId=...` — nur für Keycloaks eigenen
Flow-Ende-Hook: liest zurück, was dieser Kanal akkumuliert hat, signiert als Token, das an genau
diese `kcSessionId` gebunden ist (`RestoreDataCodec`). Keycloak legt es in einer
`UserSessionModel`-Note ab und reicht es bei einem SPÄTEREN Step-up unverändert als `restoreData`
im ersten `PATCH` des neuen Kanals zurück.

Danach läuft **alles** über dieselben fassadenneutralen Endpunkte wie die App-Fassade — kein
`/kc/`-Präfix, keine Duplikate von Tool-Aktivierung & Co.:

- `GET .../channels/{channelSessionId}`, `.../step-ups`, `.../journey`, `.../methods`,
  `.../enrollments`, `.../token`, `.../idclaims` (Abschnitt 2)
- `POST .../channels/{channelSessionId}/tools/{toolId}` (Aktivierung), danach `PATCH`/`GET
  /tools/{toolSessionId}/{toolId}` — identisch zur App-Fassade

Statt DPoP-Proof authentifiziert sich Keycloak selbst über eine signierte Peer-Auth-Assertion im
`Authorization`-Header (kein mTLS, ADR-7): ein JWT pro Request mit `iss=keycloak`,
`aud=orchestrator`, `htm`/`htu` dieses Requests, `jti`+`iat` (derselbe Replay-Cache und dasselbe
`max-clock-skew-seconds`-Fenster wie bei DPoP, [09-dpop.md](09-dpop.md)), plus der Kanal-Anker
dieses Flow-Durchlaufs (`channelAnchor`) — der Guard prüft ihn gegen exakt diesen Kanal
(`KcChannelAccessGuard`), sonst würde eine geleakte `channelSessionId` plus irgendeine gültig
signierte Keycloak-Assertion zum Kanal-Hijack reichen. Verifikation gegen Keycloaks JWKS, ein
Schlüsselpaar pro Client, nicht pro Nutzer.

Jede Antwort an einen `KEYCLOAK`-Kanal trägt zusätzlich `authData` (`accountId`/`acr`/`amr`,
niemals bei `APP`) — Keycloaks eigener `OrchestratorAuthenticator` schreibt es sofort in seine
Session-Notes, damit ein späterer nativer Schritt und Keycloaks eigene Conditional-LoA-Maschinerie
immer den aktuellen Stand sehen. `amr` bildet dabei Methode auf Quelle ab (`"kc"` für eine native
Selbstauskunft Keycloaks, `"orchestrator"` für ein abgeschlossenes Orchestrator-Tool) — rein
informativ, den kombinierten `acr` bestimmt weiterhin ausschließlich der Orchestrator.

Der Web-Kanal kennt kein Gerät — `DeviceAccountLink` bleibt APP-only
([02-domaenenmodell.md](02-domaenenmodell.md)). Login läuft über den bereits gebauten
Lookup-Login bzw. über den eigenen Entry-Intent `KC_SELECT_METHOD`
([04-orchestrierung.md](04-orchestrierung.md) Abschnitt 3), der Keycloak die Auswahl unter allen
kc-nutzbaren Tools überlässt, statt selbst eine Fallback-Kette zu fahren. Registrierung läuft
stattdessen über `REGISTER` (`intent=register`, s. o.) — komplett Keycloak-delegiert, kein
natives Registrierungsformular; `ident-fsc`/`ident-eid`/`enroll-*` laufen über dieselben
`WebToolRenderer` wie jeder andere Schritt.

**Offen:** Die Logout-Semantik im Web-Kanal ist noch nicht entschieden — ob `DELETE
/channels/{id}` für `KEYCLOAK`-Kanäle clientseitig überhaupt aufrufbar sein soll, oder ausschließlich
kc-getrieben (Keycloaks eigener Logout-Flow folgt dem Orchestrator-Kanal nur nach, nicht umgekehrt).

### Anmeldeverfahren verwalten im Web-Kanal (Keycloak Required Action)

`AuthIntent.MANAGE_AUTH_METHODS` ist wie oben (Abschnitt 2, "Methoden verwalten") beschrieben
bereits vollständig fassadenneutral — `POST .../enrollments` authentifiziert über denselben
`DpopBindingKeyResolver`, den die kc-Fassade längst für alle anderen Tool-Endpunkte nutzt. Der
Web-Kanal braucht dafür **keinen neuen Orchestrator-Endpunkt**, nur einen eigenen Einstieg: eine
Keycloak-`RequiredAction` (`getId()="orchestrator-manage-methods"`, `defaultAction=false` — nie
erzwungen, nur über `kc_action` auslösbar), registriert im bestehenden `orchestrator-browser`-Flow
und erreichbar über dieselbe `/auth`-URL wie ein normaler Login, ergänzt um
`kc_action=orchestrator-manage-methods` (Keycloaks eigener Mechanismus für „bereits angemeldeter
Nutzer löst selbst eine Zusatzaktion aus", analog zu Keycloaks eigenen „Passwort ändern"/„OTP
einrichten"-Selbstbedienungslinks).

Kein erzwungener zweiter Login nötig: Der vorangehende `orchestrator-browser`-Durchlauf nutzt das
bestehende Keycloak-SSO-Cookie, `OrchestratorResumeAuthenticator` bringt den dabei zwangsläufig
neuen Orchestrator-Kanal über `restoreData` (Abschnitt 3 oben) auf `AUTHENTICATED`, sofern die
Evidenz noch reicht — reicht sie nicht, greift stattdessen die normale Login-/Step-up-Kaskade, kein
Sonderfall. Schließt der Flow erfolgreich ab, ruft die Required Action `startEnrollments(...)` auf
dem frischen, `AUTHENTICATED`-Kanal auf und rendert `next` über denselben `WebToolRenderer`-
Dispatch wie jeder andere Schritt — dieselbe Kandidatenliste (Passwort, Gerät, E-Mail, QR, …), die
der App-Kanal über `.../enrollments` auch bekommt. Frontend: `redirectToManageMethods()`
(`webOidc.ts`) baut dieselbe `/auth`-URL wie `redirectToLogin`, Rückkehr über den bestehenden
`completeLoginIfRedirected()`-Pfad — Button „Anmeldeverfahren verwalten" im Web-Kanal-Demo-Tab.

Das Interpretieren von `next` (Auswahlbildschirm vs. Tool-Formular vs. Tool automatisch aktivieren)
ist zwischen dem normalen Login/Step-up-Flow (`OrchestratorAuthenticator`) und dieser Required
Action **gemeinsamer Code** (`OrchestratorNextDispatch.classify`/`dispatchToolAction`,
`keycloak-extension`) — reine, Keycloak-typ-freie Klassifikation, nur die Reaktion darauf
(`context.success()`/`failure()` vs. `RequiredActionContext`-Äquivalente) bleibt je Caller
eigenständig, da beide Kontexttypen keinen gemeinsamen Übertyp haben. Vorher war diese
Klassifikation wortgleich dupliziert — genau dort hatte die ACR/AMR-Übernahme nach einem Step-up in
„Anmeldeverfahren verwalten" gefehlt (das Access-Token blieb fälschlich bei `loa1`).

---

## 4) Hybrid-Modell: Prozess-API + Tool-Ressourcen

Ziel: Prozesssicht/Fachführung bleibt in den Prozess-Endpoints; App-Frontend und Keycloak nutzen für Eingabe- und Verifikationsschritte dieselben kanalneutralen Tool-URLs.

- Der Channel-/Prozess-Endpunkt wählt über `toolId` das Tool aus und erzeugt eine technische `ToolSession`, ohne selbst fachliche Eingabedaten entgegenzunehmen.
- Das Backend liefert einen fachlich eindeutigen `next`-Zustand; App/Keycloak leiten daraus über dieselbe feste Routing-Tabelle den nächsten Endpunkt ab.
- `AuthJourney` bleibt der fachliche Owner (Intent, Zustand, Versuchsbudget); `ToolSession` trägt nur Lifecycle-Metadaten (`toolSessionId`, `journeyId`, Zeitstempel) — weder `toolId` noch `stepData` noch ein Retry-Zähler sind eigene Spalten: `toolId` ergibt sich aus der Route, `stepData` aus den Moduldaten, und das Versuchsbudget gilt für die ganze Journey (siehe [Domänenmodell](02-domaenenmodell.md)).
- `accountId`/`personId` sind kein Teil des fachlichen Antwortvertrags — der Client braucht sie für keinen der über `next` erreichbaren Folgeaufrufe. Einzige Ausnahme ist das demo-Objekt.

Für Keycloak ist `auth-sms`/`auth-password`/`auth-email` (Login/Step-up) der einzige nicht bereits über die App-Fassade abgedeckte Fall — Aktivierung, `PATCH` und `GET` laufen identisch zur App-Seite über den (kc-eigenen) Channel: `POST .../channels/{channelSessionId}/tools/auth-sms`, danach `PATCH`/`GET /tools/{toolSessionId}/auth-sms` (Abschnitt 3).

Damit ist nur die fachliche Freigabe prozess- und kanalabhängig; Startparameter und Verifikation laufen danach kanalneutral über ein einheitliches Tool-Muster.
