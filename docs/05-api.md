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
- Vorbereitende Methoden nutzen ressourcenorientierte Tool-Objekte: `POST` erzeugt das Tool über den Channel, danach gestaltet das Tool seinen eigenen URL-Namespace (siehe „Tool-Namespace" in Abschnitt 2). `PATCH` zum Nachliefern und `GET` zum Lesen sind der empfohlene Regelfall, aber nicht für jedes Tool verpflichtend.
- Bei `PATCH` wird grundsätzlich nur der aktuell nachzuliefernde oder zu ändernde Teil übergeben; bereits vorhandene Felder dürfen dabei gezielt überschrieben werden.
- HTTP-Fehlercodes sind gestörten Abläufen vorbehalten, nicht erwartbaren Nutzereingaben: Fehlende Pflichtdaten und fehlgeschlagene Versuche mit verbleibenden Retries werden mit `200` plus `next` beantwortet (Retry-Regel in [Orchestrierung](04-orchestrierung.md)), nicht mit `4xx`.
- HATEOAS wird im Zielbild nicht verwendet.
- Der Client leitet den nächsten technischen Call aus `next.type` (`tool` vs. `flow`) und dem dazu passenden Attribut (`next.toolId` bzw. `next.context` + gewähltem Eintrag aus `stepData.options`) über eine feste Routing-Tabelle ab.
- Lesbarkeit hat Vorrang vor maximal generischem API-Wiring: methoden- und artspezifische Endpunkte sowie klar benannte DTOs/Handler (`ident-fsc`, `enroll-sms`, `auth-sms`) sind gewollt, auch wenn dafür etwas mehr expliziter Code entsteht.

---

## 2) App-Fassade (Orchestrator-first)

Die folgenden Endpunkte sind als Zielbild für einen durchgängigen App-Flow gedacht. Alle Requests enthalten den Header `DPoP: <proof>`.

Designentscheidung:

- `processSessionId` bleibt als interne Prozessinstanz für Persistenz, Korrelation und Audit erhalten.
- Die fachliche Prozesswahl (`REGISTRATION`, `LOGIN`, `STEP_UP`) trifft das Backend auf Basis von Kanalzustand, Accountstatus und Policy.
- Öffentliche App-APIs verwenden nur `channelSessionId`; weder `purpose` noch die interne `processSessionId` werden vom Client vorgegeben.
- Das `next`-Objekt ist reine Adresse und hat immer dieselbe schlanke Form: bei einem konkreten Tool-Schritt `{ "type": "tool", "toolId": "...", "step": "..." }`, bei einer Auswahl- oder Abschlussseite `{ "type": "flow", "context": "...", "step": "..." }` — niemals mit Inhalt vermischt.
- Auswahloptionen stehen nicht in `next`, sondern in `stepData.options` als vollständige `toolId`-Werte (z. B. `enroll-sms`), sodass der Client direkt den zugehörigen Endpunkt aufrufen kann, ohne Kind und Methode selbst zu kombinieren.
- Die UI leitet den nächsten Call aus `next.type` (`tool` vs. `flow`) und dem passenden Attribut (`toolId` bzw. `context` + gewähltem Eintrag aus `stepData.options`) über eine feste Routing-Tabelle ab; URLs sind feste technische Endpunkte und keine Entscheidungsquelle.
- Der Client darf `toolId` nie selbst konstruieren oder erraten; er wird serverseitig festgelegt und entweder direkt in `next.toolId` oder als Eintrag in `stepData.options` geliefert.
- Wenn genau eine Methode erlaubt ist, darf das Backend die Auswahlseite überspringen und direkt den Tool-Schritt liefern, z. B. `{ "type": "tool", "toolId": "enroll-sms", "step": "enroll" }` oder `{ "type": "tool", "toolId": "auth-sms", "step": "auth" }`.
- `stepData` trägt alles, was der aktuelle Schritt zum Anzeigen braucht: bei laufendem Tool den tool-internen Zustand (z. B. `missingFields`), bei einer Auswahlseite die erlaubten Folge-Tools (`options`), nach einem fehlgeschlagenen Versuch den Grund (`error`). Ist nichts davon nötig, entfällt das Feld — bei erfolgreichem Abschluss ist der Erfolg bereits durch `200` plus weiterzeigendes `next` ausgedrückt.

Pfadkonvention:

- Einstieg: `POST /orchestrator/api/v1/app/channels`
- Kanalzustand lesen/Niveau anheben: `GET/PATCH /orchestrator/api/v1/app/channels/{channelSessionId}`
- Laufende Prozessschritte: `/orchestrator/api/v1/app/channels/{channelSessionId}/...`
- Tool-Anlage über Channel: `POST /orchestrator/api/v1/app/channels/{channelSessionId}/tool-activate/{toolId}` (kein Body nötig, `toolId` trägt Kind und Methode zusammen, z. B. `tool-activate/enroll-sms`)
- Tool-Fortschreibung/-Lesen: `PATCH/GET /orchestrator/api/v1/tools/{toolSessionId}/{toolId}` als Regelfall

Tool-Namespace:

- Die Aktivierung bleibt Orchestrator-Hoheit und ist für alle Tools gleich — nur dort entsteht die `toolSessionId`.
- Alles unterhalb von `/orchestrator/api/v1/tools/{toolSessionId}/{toolId}` gestaltet das Tool selbst: eigene Sub-Ressourcen und frei gewählte HTTP-Methoden. `PATCH` (nachliefern) und `GET` (Stand lesen) sind der empfohlene Regelfall und decken die hier gezeigten Tools ab, sind aber keine Pflicht.
- Grund: Nicht jedes Verfahren passt in „Felder nachliefern". WebAuthn reicht eine Assertion ein (kein partielles Update), eID braucht Redirect und Callback, QR-Verfahren einen Bildabruf, Push-Verfahren einen Polling- oder Callback-Endpunkt.
- Der Client findet diese Endpunkte über denselben Mechanismus wie bisher: `next.step` ist ohnehin tool-spezifisch, die Routing-Tabelle bildet `(toolId, step)` auf den konkreten Endpunkt ab. Es braucht dafür keine URL-Interpretation.
- Weil damit auch `GET` auf der Tool-Ressource entfallen darf, ist der garantierte Resume-Einstieg **nicht** die Tool-Ressource, sondern `GET /orchestrator/api/v1/app/channels/{channelSessionId}`: Dieser Endpunkt existiert immer und liefert den aktuell fälligen `next` (Abschnitt 2, Beispiel 8).

Konsistenzregel:

- Pro `channelSessionId` darf es höchstens einen aktiven öffentlichen Prozesskontext geben. Welcher interne `purpose` dazu gehört, entscheidet und verwaltet das Backend.

Konsistentes Beispiel über alle Calls:

- `channelSessionId`: `c1111111-1111-1111-1111-111111111111`
- Identifikation: `ident-fsc`
- Authentifizierung: `auth-sms`

### 1) `POST /orchestrator/api/v1/app/channels`

Zweck:

- Erstkontakt der App mit dem Backend.
- Liefert eine neue oder bestehende `ChannelSession`.
- Das Backend leitet dabei sofort den aktuell nötigen fachlichen Prozess ab und liefert direkt den ersten fachlichen `next`-Schritt.

Request (beide Felder optional — `channelSessionId` zum Fortsetzen, `requiredAcr` als geforderte Untergrenze):

```json
{
  "channelSessionId": null,
  "requiredAcr": "loa2"
}
```

`requiredAcr` erspart der App den Umweg über ein niedriges Einstiegsniveau mit anschließendem Step-up, wenn schon beim Start feststeht, dass eine sensible Funktion genutzt werden soll. Der Wert wirkt nur nach oben: Das Backend rechnet mit `max(Policy-Anforderung, Client-Wunsch)`.

Response `200`:

```json
{
  "channelSessionId": "c1111111-1111-1111-1111-111111111111",
  "state": "ANONYMOUS",
  "stepData": {
    "options": ["ident-fsc"]
  },
  "next": {
    "type": "flow",
    "context": "registration",
    "step": "selectIdentificationMethod"
  }
}
```

### 2) `POST /orchestrator/api/v1/app/channels/{channelSessionId}/tool-activate/ident-fsc`

Zweck:

- Erzeugt eine neue Tool-Ressource für `ident-fsc`; kein Body nötig, da `toolId` bereits Kind und Methode festlegt.

Response `201`:

```json
{
  "toolSessionId": "i7777777-7777-7777-7777-777777777777",
  "stepData": {
    "missingFields": ["kvnr", "name", "vorname"]
  },
  "next": {
    "type": "tool",
    "toolId": "ident-fsc",
    "step": "input"
  }
}
```

### 3) `PATCH /orchestrator/api/v1/tools/{toolSessionId}/ident-fsc`

Zweck:

- Ergänzt fehlende Felder auf der FSC-Tool-Ressource; bei Bedarf dürfen bereits gesetzte Felder gezielt überschrieben werden.

Request (erste Datenlieferung):

```json
{
  "kvnr": "A123456789",
  "name": "Muster",
  "vorname": "Max"
}
```

Response `200` bei unvollständiger Eingabe:

```json
{
  "toolSessionId": "i7777777-7777-7777-7777-777777777777",
  "stepData": {
    "missingFields": ["fsc"]
  },
  "next": {
    "type": "tool",
    "toolId": "ident-fsc",
    "step": "input"
  }
}
```

Request (zweite Datenlieferung):

```json
{
  "fsc": "TESTCODE123"
}
```

Response `200` bei erfolgreicher Verifikation:

```json
{
  "toolSessionId": "i7777777-7777-7777-7777-777777777777",
  "stepData": {
    "options": ["enroll-sms"]
  },
  "next": {
    "type": "flow",
    "context": "enrollment",
    "step": "selectMethod"
  }
}
```

### 4) `GET /orchestrator/api/v1/tools/{toolSessionId}/ident-fsc`

Zweck:

- Liest den aktuellen Zustand der FSC-Tool-Ressource.
- Dient für Resume/Polling und UI-Synchronisation. `next` ist immer der aktuelle Prozessschritt — ist das Tool schon abgeschlossen, zeigt es bereits auf das Folge-Tool.

Response `200` Beispiel:

```json
{
  "toolSessionId": "i7777777-7777-7777-7777-777777777777",
  "stepData": {
    "missingFields": ["fsc"]
  },
  "next": {
    "type": "tool",
    "toolId": "ident-fsc",
    "step": "input"
  }
}
```

### 5) `POST /orchestrator/api/v1/app/channels/{channelSessionId}/tool-activate/enroll-sms`

Zweck:

- Erzeugt eine neue Tool-Ressource für `enroll-sms`; kein Body nötig.
- Für Login/Step-up existiert der separate, explizite Endpoint `POST /orchestrator/api/v1/app/channels/{channelSessionId}/tool-activate/auth-sms`.
- Das Frontend leitet den Ziel-Endpunkt direkt aus `next.toolId` (bzw. bei Auswahlseiten aus `stepData.options`) ab.

Response `201`:

```json
{
  "toolSessionId": "a3333333-3333-3333-3333-333333333333",
  "stepData": {
    "missingFields": ["phoneNumber"]
  },
  "next": {
    "type": "tool",
    "toolId": "enroll-sms",
    "step": "enroll"
  }
}
```

### 6) `PATCH /orchestrator/api/v1/tools/{toolSessionId}/enroll-sms`

Zweck:

- Ergänzt oder aktualisiert partiell Daten eines konkreten SMS-Enrollment-Tools.
- Diese Route ist nur für den Enrollment-Fall definiert.
- Der Request enthält nur die Felder, die in diesem Schritt fehlen oder bewusst geändert werden sollen.

Request (Telefonnummer):

```json
{
  "phoneNumber": "+49 170 1234567"
}
```

Response `200`:

```json
{
  "toolSessionId": "a3333333-3333-3333-3333-333333333333",
  "stepData": {
    "missingFields": ["tan"]
  },
  "next": {
    "type": "tool",
    "toolId": "enroll-sms",
    "step": "tanInput"
  }
}
```

Request (TAN):

```json
{
  "tan": "123456"
}
```

Response `200` bei erfolgreicher Verifikation:

```json
{
  "toolSessionId": "a3333333-3333-3333-3333-333333333333",
  "next": {
    "type": "flow",
    "context": "authentication",
    "step": "authenticated"
  }
}
```

### 7) `GET /orchestrator/api/v1/tools/{toolSessionId}/enroll-sms`

Zweck:

- Liest den stabilen Tool-Zustand für Resume/Polling; `next` ist auch hier immer der aktuelle Prozessschritt, nicht der historische Stand beim Tool-Abschluss.

Response `200`:

```json
{
  "toolSessionId": "a3333333-3333-3333-3333-333333333333",
  "stepData": {
    "missingFields": ["tan"]
  },
  "next": {
    "type": "tool",
    "toolId": "enroll-sms",
    "step": "tanInput"
  }
}
```

### 8) `GET /orchestrator/api/v1/app/channels/{channelSessionId}`

Zweck:

- Liest den stabilen Kanalzustand für Folgerequests.
- Dient als einfache Session- und Policy-Sicht für die App und als Resume-Einstieg: `next` zeigt immer auf den aktuell fälligen Schritt (bzw. fehlt, wenn kein Prozess läuft).

Response `200` (laufender Prozess, Resume nach App-Neustart):

```json
{
  "channelSessionId": "c1111111-1111-1111-1111-111111111111",
  "state": "REGISTERING",
  "next": {
    "type": "tool",
    "toolId": "enroll-sms",
    "step": "tanInput"
  }
}
```

Response `200` (abgeschlossen, kein Prozess offen):

```json
{
  "channelSessionId": "c1111111-1111-1111-1111-111111111111",
  "state": "AUTHENTICATED",
  "currentAcr": "loa2",
  "currentAmr": ["fsc", "sms"],
  "next": {
    "type": "flow",
    "context": "authentication",
    "step": "authenticated"
  }
}
```

`next` ist auch hier immer gesetzt — ein separates `stepUpRequired`-Flag gibt es bewusst nicht: Ob ein Step-up ansteht, sagt bereits `next` (Verweis auf ein Auth-Tool statt auf `authenticated`). Ein zusätzliches Bool wäre dieselbe Information in zweiter Form und könnte davon abweichen.

### 9) `PATCH /orchestrator/api/v1/app/channels/{channelSessionId}`

Zweck:

- Hebt die geforderte Untergrenze des Kanals an — der **Step-up-Auslöser des App-Kanals**, Gegenstück zu `processes/step-up` auf der kc-Seite (API).
- Die App ruft das auf, bevor sie eine Funktion nutzt, die ein höheres Niveau verlangt.

Request:

```json
{
  "requiredAcr": "loa3"
}
```

Response `200`, wenn das aktuelle Niveau nicht ausreicht — das Backend startet eine `ProcessSession(STEP_UP)` und liefert den fälligen Schritt in derselben Form wie überall:

```json
{
  "channelSessionId": "c1111111-1111-1111-1111-111111111111",
  "state": "STEP_UP_IN_PROGRESS",
  "next": {
    "type": "tool",
    "toolId": "auth-passkey",
    "step": "assert"
  }
}
```

Response `200`, wenn das Niveau bereits erreicht ist — kein Prozess wird gestartet, `requiredAcr` bleibt aber als neue Untergrenze gesetzt. Die Antwort zeigt sofort auf `authenticated`:

```json
{
  "channelSessionId": "c1111111-1111-1111-1111-111111111111",
  "state": "AUTHENTICATED",
  "currentAcr": "loa3",
  "currentAmr": ["fsc", "passkey"],
  "next": {
    "type": "flow",
    "context": "authentication",
    "step": "authenticated"
  }
}
```

- Beide Fälle liefern ein `next` — der Client muss nie aus dessen Abwesenheit schließen, dass nichts zu tun ist. Er folgt einfach dem Verweis: entweder auf ein Step-up-Tool oder direkt auf `authenticated`.
- Nur Anheben ist möglich. Ein `requiredAcr` unterhalb des bereits geforderten Niveaus wird ignoriert (das Maximum bleibt bestehen), nicht als Herabstufung interpretiert.
- Ist das geforderte Niveau mit den vorhandenen Methoden des Accounts nicht erreichbar, greift die Regel aus [Orchestrierung](04-orchestrierung.md): Prozessabbruch mit `410`, statt eine Auswahl ohne gültige Kandidaten anzubieten.

### Erreichbare APIs je Prozess (Beispielregel)

- Welches `next` nach Abschluss eines Tools folgt, legt die Orchestrator-Tabelle in [Orchestrierung](04-orchestrierung.md) fest (`ProcessPurpose` + abgeschlossenes `toolId` -> `next`); Beispiel 3 zeigt die REGISTRATION-Auswahlseite konkret.
- Nicht erlaubte Aktion für den Prozess: `409 invalid_state` mit `allowedActions`.
- Der Client muss dafür weder URLs auswerten noch `toolId`-Werte selbst konstruieren.

### Durchgängiger Sequenzblick (kurz)

1. Channel anlegen/holen (`POST /orchestrator/api/v1/app/channels`)
2. FSC-Tool anlegen (`POST /orchestrator/api/v1/app/channels/{channelSessionId}/tool-activate/ident-fsc`)
3. FSC-Tool mit Identifikationsdaten befüllen (`PATCH /orchestrator/api/v1/tools/{toolSessionId}/ident-fsc`)
4. FSC-Tool mit FSC verifizieren (`PATCH /orchestrator/api/v1/tools/{toolSessionId}/ident-fsc`)
5. FSC-Tool-Zustand lesen (`GET /orchestrator/api/v1/tools/{toolSessionId}/ident-fsc`)
6. SMS-Enrollment-Tool anlegen (`POST /orchestrator/api/v1/app/channels/{channelSessionId}/tool-activate/enroll-sms`)
7. SMS-Enrollment-Tool mit `phoneNumber` befüllen (`PATCH /orchestrator/api/v1/tools/{toolSessionId}/enroll-sms`)
8. SMS-Enrollment-Tool mit `tan` abschließen (`PATCH /orchestrator/api/v1/tools/{toolSessionId}/enroll-sms`)
9. SMS-Enrollment-Tool-Zustand lesen (`GET /orchestrator/api/v1/tools/{toolSessionId}/enroll-sms`)
10. Finalen Kanalstatus lesen (`GET /orchestrator/api/v1/app/channels/{channelSessionId}`)

---

## 3) Web/Keycloak-Fassade (Keycloak-first)

- `POST /orchestrator/api/v1/kc/sessions/{kcSessionId}/processes/step-up`
- `POST /orchestrator/api/v1/kc/sessions/{kcSessionId}/processes/login`
- `POST /orchestrator/api/v1/kc/sessions/{kcSessionId}/tool-activate/{toolId}` (Gegenstück zur App-Fassade; danach laufen beide Kanäle über dieselben `/tools/{toolSessionId}/{toolId}`-URLs)
- optional: `POST /orchestrator/api/v1/kc/sessions/{kcSessionId}/processes/{purpose}/cancel`

Beispiel `POST /orchestrator/api/v1/kc/sessions/{kcSessionId}/processes/step-up`:

```json
{
  "channelSessionId": "uuid",
  "keycloakSessionId": "kc-session-id",
  "keycloakSubject": "user-sub",
  "startingAcr": "loa1",
  "requiredAcr": "loa2",
  "currentAmr": ["pwd"]
}
```

Antwort `201`:

```json
{
  "next": {
    "type": "tool",
    "toolId": "auth-sms",
    "step": "auth"
  }
}
```

Keycloak folgt diesem `next` über dieselbe Routing-Tabelle wie die App: `POST /orchestrator/api/v1/kc/sessions/{kcSessionId}/tool-activate/auth-sms` liefert die `toolSessionId`, danach laufen `PATCH`/`GET` kanalneutral über `/tools/{toolSessionId}/auth-sms`.

---

## 4) Hybrid-Modell: Prozess-API + Tool-Ressourcen

Ziel:

- Prozesssicht und Fachführung bleiben in den Prozess-Endpoints sichtbar.
- App-Frontend und Keycloak nutzen für Eingabe- und Verifikationsschritte dieselben kanalneutralen Tool-URLs.

Kernidee:

1. Channel-Endpunkt wählt über `toolId` das Tool aus und erzeugt eine technische Tool-Ressource.
2. Backend erstellt die `ToolSession` und ordnet sie über den `toolId` der passenden Moduldaten-Klasse zu (z. B. `EnrollSmsToolData` für `enroll-sms`, siehe [Tool-Architektur](03-tool-architektur.md)).
3. Die Channel-API nimmt dabei keine fachlichen Eingabedaten entgegen; `toolId` steht bereits in der URL, ein Body ist nicht nötig.
4. Das Backend liefert einen fachlich eindeutigen `next`-Zustand, z. B. `{"type":"flow","context":"enrollment","step":"selectMethod"}` (plus `stepData={"options":["enroll-sms"]}`) oder `{"type":"tool","toolId":"auth-sms","step":"auth"}`.
5. App oder Keycloak nutzen eine feste Routing-Tabelle von `next.type` (`tool`/`flow`) und dem passenden Attribut (`toolId` bzw. `context`+`stepData.options`) auf die passenden Endpunkte und leiten nichts aus URLs ab.

### Ressourcenmodell

- `ProcessSession` bleibt der fachliche Owner und enthält den aktuellen Routing-Zustand:
  - `processSessionId`
  - `channelSessionId`
  - `purpose`
  - `state`
  - `accountId`
  - `nextType` (`tool` oder `flow`)
  - `nextToolId` (nur bei `nextType=tool` gesetzt)
  - `nextContext` (nur bei `nextType=flow` gesetzt)
  - `nextStep`
  - `expiresAt`, `consumedAt`
- Neue technische Ressource: `ToolSession` (keine Kind-Subtypen mehr; `toolId` wählt direkt Handler und Moduldaten-Klasse, siehe [Tool-Architektur](03-tool-architektur.md)):
  - `toolSessionId`
  - `processSessionId` (nur intern)
  - `expiresAt`, `retryCount`

Klarstellung zum Persistenzmodell:

- Der Orchestrator persistiert für Tools nur Lifecycle-Metadaten (`toolSessionId`, `processSessionId`, `retryCount`, Zeitstempel).
- Weder `toolId` noch `stepData` sind persistierte Datenfelder: `toolId` ergibt sich aus der anlegenden/lesenden Route, `stepData` wird bei jeder Antwort aus den Moduldaten neu aufgebaut.
- Fachliche Ergebnisdaten bleiben in den jeweiligen Methodenmodulen; `GET`-Responses baut der Orchestrator aus Lifecycle-Zustand, Moduldaten und dem Routing-Zustand der `ProcessSession` zusammen.
- `stepData` hat je nach Situation zwei Quellen: bei laufendem Tool reicht der Orchestrator `ToolOutcome.InProgress.data` unverändert durch; bei Auswahl- und Abschlussantworten baut er es selbst (z. B. `options`, `error`). Der Inhalt von `Completed` geht nie direkt zum Client — er ist für die Verarbeitung in [Orchestrierung](04-orchestrierung.md) bestimmt.
- `accountId`/`personId` erscheinen in keinem Client-Response: Der Client braucht sie für keinen der über `next` erreichbaren Folgeaufrufe, deshalb bleiben sie ausschließlich serverseitig in der `ProcessSession`. `personId` liefert das Ident-Tool selbst (`Completed.Identified` — Identifikation *ist* die Auflösung zur Person); `accountId` kennt kein Methoden-Handler, es entsteht erst bei der Verarbeitung über `findOrCreateAccount(personId)` ([Orchestrierung](04-orchestrierung.md)), und spätere Tools lesen es nur noch aus der `ProcessSession`.

### API-Schnitt

Start erfolgt über den Channel-Endpunkt (App) bzw. die Prozess-Endpunkte (Web/Keycloak, Abschnitt 3):

- `POST /orchestrator/api/v1/app/channels/{channelSessionId}/tool-activate/{toolId}` (kein Body nötig, z. B. `ident-fsc`, `enroll-sms`, `auth-sms`)

Jede unterstützte Kombination aus Kind und Methode hat eine eigene, konkrete `toolId` (kein generischer `{kind}`/`{method}`-Platzhalter); der Tool-Katalog in [Tool-Architektur](03-tool-architektur.md) listet die aktuell unterstützten Werte.

Die eigentliche Parametereingabe und Verifikation laufen danach über eine kanalneutrale Tool-API, deren Struktur das jeweilige Tool bestimmt (Tool-Namespace, Abschnitt 2). Für den `PATCH`-Regelfall gilt durchgängig: Der Client sendet nur den aktuell fehlenden oder zu korrigierenden Teil der Tool-Daten; bereits vorhandene Werte können gezielt überschrieben werden, müssen aber nicht erneut vollständig mitgesendet werden.

- `PATCH/GET /orchestrator/api/v1/tools/{toolSessionId}/{toolId}` (Regelfall; von `ident-fsc`, `enroll-sms` und `auth-sms` genutzt)
- `<beliebig> /orchestrator/api/v1/tools/{toolSessionId}/{toolId}/{aktion}` (tool-eigene Endpunkte, z. B. WebAuthn-Assertion oder eID-Callback)

Die Request-/Response-Beispiele für `ident-fsc` und `enroll-sms` entsprechen exakt den in Abschnitt 2 gezeigten Payloads (gleiches `toolSessionId`-Schema) und werden hier nicht erneut dupliziert. Einzig `auth-sms` (Login/Step-up) ist dort nicht abgedeckt:

Prozess-Start: `POST /orchestrator/api/v1/app/channels/{channelSessionId}/tool-activate/auth-sms` (kein Body nötig)

Antwort `201`:

```json
{
  "toolSessionId": "a4444444-4444-4444-4444-444444444444",
  "stepData": {
    "missingFields": ["tan"]
  },
  "next": {
    "type": "tool",
    "toolId": "auth-sms",
    "step": "auth"
  }
}
```

`PATCH /orchestrator/api/v1/tools/{toolSessionId}/auth-sms` (TAN):

```json
{
  "tan": "123456"
}
```

Antwort `200`:

```json
{
  "toolSessionId": "a4444444-4444-4444-4444-444444444444",
  "next": {
    "type": "flow",
    "context": "authentication",
    "step": "authenticated"
  }
}
```

### Gibt es doppelte APIs?

Nein, wenn die Verantwortung klar getrennt ist:

- Prozess-API: kanal- und fachkontextspezifischer Start (`app` oder `kc`), Policy, Ableitung des intern nötigen `purpose`, Reservierung einer Tool-Instanz
- Tool-API: kanalneutrale technische Parametereingabe und Verifikation über dieselbe Ressource (`PATCH`/`GET`); unterschiedliche fachliche Varianten wie `enroll-sms` und `auth-sms` werden als getrennte konkrete Endpunkte modelliert

Damit ist nur die fachliche Freigabe prozess- und kanalabhängig; Startparameter und Verifikation laufen danach kanalneutral über ein einheitliches Tool-Muster.
