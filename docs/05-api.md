# API-Spezifikation

Das öffentliche API unter `/orchestrator/api/v1`, getrennt nach App-Kanal (Orchestrator-first)
und Web-Kanal (Keycloak-first).

Die fachliche Bedeutung der Antworten — insbesondere `next` — ergibt sich aus
[04-orchestrierung.md](04-orchestrierung.md).

---

## 1) API-Grundsätze

- Das öffentliche API wird unter `/orchestrator/api/v1` versioniert.
- Unterschiedliche Methoden und Modi werden über getrennte konkrete Endpunkte modelliert; die URL bestimmt die Operation, nicht der Request-Body.
- Vorbereitende Methoden nutzen ressourcenorientierte Tool-Objekte: `POST` erzeugt das Tool über den Channel, danach gestaltet das Tool seinen eigenen URL-Namespace (Abschnitt 2).
- Bei `PATCH` wird nur der nachzuliefernde oder zu ändernde Teil übergeben; vorhandene Felder dürfen gezielt überschrieben werden.
- HTTP-Fehlercodes sind gestörten Abläufen vorbehalten: Fehlende Pflichtdaten und fehlgeschlagene Versuche mit verbleibenden Retries werden mit `200` plus `next` beantwortet (Retry-Regel in [Orchestrierung](04-orchestrierung.md)).
- HATEOAS wird im Zielbild nicht verwendet.
- Der Client leitet den nächsten technischen Call aus `next.type` (`tool` vs. `orchestrator`) und dem dazu passenden Attribut (`next.toolId` bzw. `next.context` + gewähltem Eintrag aus `stepData.options`) über eine feste Routing-Tabelle ab.
- Methoden- und artspezifische Endpunkte sowie klar benannte DTOs/Handler (`ident-fsc`, `enroll-sms`, `auth-sms`) gehen vor generischem API-Wiring.

### Der Vertrag: `api/`

Den Vertrag lesen drei Stellen: die Kotlin-DTOs selbst, das Frontend und `keycloak-extension`
(`OrchestratorClient`). Vorher war er dreimal von Hand geschrieben, und eine geänderte Antwort
erreichte jede Stelle zu einem anderen Zeitpunkt.

| Datei | Inhalt | Wofür |
|---|---|---|
| `api/openapi.yaml` | der App-Vertrag: alles unter `/orchestrator/api/v1` | Eingabe für beide Generatoren |
| `api/published/v1.yaml` | der veröffentlichte Stand von v1 | Vergleichsbasis für `checkPublishedApiCompatibility` |
| `api/modules/<modul>.yaml` | alle Endpunkte und eigenen DTOs dieses Moduls; gemeinsame Schemas per `$ref` auf `../openapi.yaml` | zum Lesen und Reviewen |
| `frontend/src/generated/` | die daraus erzeugten TypeScript-Typen | vom Frontend importiert, eingecheckt |
| `keycloak-extension/build/generated/` | die daraus erzeugten Java-Modelle | von `OrchestratorClient` benutzt, nicht eingecheckt |

**Was zum App-Vertrag gehört, entscheidet der Pfad.** `api/openapi.yaml` enthält genau die
Endpunkte unter `API_V1`, abgeleitet aus der Konstante, nicht aus einer Ausschlussliste. Zwei Arten
von Endpunkten liegen bewusst woanders: Betriebsendpunkte unter `/orchestrator/admin` (Tool-Sperre,
Registrierungsreihenfolge) und die Stellvertreter externer Systeme unter `/mock-*`. Auf beide darf
sich kein App-Client verlassen. Stünden sie im eingefrorenen Stand, meldete der
Kompatibilitätsvergleich ihr späteres Entfernen als Bruch des App-Vertrags. Dokumentiert sind sie
trotzdem: in der Datei ihres Moduls unter `api/modules/`.

**Versionierung.** Es gibt eine globale Version, weil die Antworthülle `ChannelResponse` in jeder
Antwort jedes Moduls steckt; eine brechende Änderung an ihr trifft alle Endpunkte zugleich.
Die meisten Änderungen sind additiv und brauchen keine neue Version. Dafür prüft
`checkPublishedApiCompatibility` in der CI jede Änderung gegen `api/published/v1.yaml`
(openapi-diff) und schlägt bei einem Bruch fehl. Ein bewusster neuer Stand wird mit
`./gradlew publishApiVersion` übernommen; der Diff dieser Datei im PR ist das Signal, dass eine
veröffentlichte Version angefasst wird.

Beide YAML-Dateien entstehen im selben Testlauf aus derselben laufenden Anwendung
(`OpenApiSnapshotTest`), können also nicht auseinanderlaufen. Die Modul-Dateien sind keine zweite
Quelle, sondern ein Ausschnitt: eine Änderung an einem SMS-Endpunkt steht in
`api/modules/auth_sms.yaml` (knapp 500 Zeilen) statt irgendwo in 4700 Zeilen. Die Gruppen leitet
`ModuleApiGroups` aus den vorhandenen `@RestController` ab, nicht aus einer gepflegten Liste.

Ein Schema, das mehr als ein Modul benutzt und das im App-Vertrag steht (die Hülle
`ChannelResponse` samt allem, was sie erreicht, `ErrorResponse`), steht nur in `api/openapi.yaml`;
die Modul-Dateien verweisen mit `../openapi.yaml#/components/schemas/…` darauf. Sonst trüge jede
Modul-Datei dieselben rund 360 Zeilen, und eine Änderung an der Hülle erschiene als zehn Diffs.
Auch das ist abgeleitet, keine Liste: was nur ein Modul benutzt, bleibt in dessen Datei. Preis:
`StepData` zeigt in der Modul-Datei die vollständige Union, nicht nur die Formen dieses Moduls.

`api/openapi.yaml` bleibt trotzdem eine einzige Datei. Ein modularer Vertrag, der die
Modul-Dateien per `$ref` einbindet, wurde ausprobiert: unter OpenAPI 3.1 löst swagger-parser
externe Referenzen inline auf, beide Generatoren verlieren dann alle Modellnamen und das
Diskriminator-Mapping, und openapi-diff vergleicht aufgeteilte Dateien nicht verlässlich.

Was sonst noch dazugehört:

- **Der Test vergleicht, er dokumentiert nicht.** Ändert sich eine Antwort, ohne dass die Snapshots
  nachgezogen sind, schlägt er fehl. Übernehmen mit `./gradlew updateOpenApiSnapshot`, danach
  `./gradlew generateFrontendApiTypes`.
- **`frontend/src/types.ts` leitet ab statt nachzubauen.** Von Hand steht dort nur noch, was das
  Backend als offene Map liefert: die benannten Werte im `demo`-Block und die Token-Formen. Dazu
  kommt eine Ergänzung zur generierten `stepData`-Union: `UnknownStepData` für eine Form, die
  dieser Build noch nicht kennt, und `stepDataOf(stepData, kind)` als einziger Lesezugriff.
- **Pflichtfelder stehen im Schema.** springdoc übernimmt Kotlins Non-Null nicht von selbst;
  `KotlinRequiredModelConverter` macht das: eine nicht-nullable Property ohne Default wird
  `required`.
- **YAML, nicht JSON**, weil die Dateien in Diffs gelesen werden: keine Anführungszeichen, keine
  Klammern, und lange Beschreibungen stehen als umbrochener Text statt als eine endlose Zeile.

---

## 2) App-Fassade (Orchestrator-first)

Alle Requests enthalten den Header `DPoP: <proof>`.

- `processSessionId` bleibt als interne Prozessinstanz für Persistenz, Korrelation und Audit erhalten; öffentliche App-APIs verwenden nur `channelSessionId`, weder `purpose` noch `processSessionId` gibt der Client vor.
- Die fachliche Prozesswahl trifft das Backend anhand von Kanalzustand, Accountstatus und Policy; sie wird durch `AuthIntent` und dessen Strategie beschrieben, nicht durch einen separaten `REGISTRATION`-/`LOGIN`-Typ.
- Das `next`-Objekt ist reine Adresse, nie mit Inhalt vermischt: Tool-Schritt `{ "type": "tool", "toolId": "...", "step": "...", "toolSessionId": "..." }`, orchestrator-eigene Seite (Auswahl, Bestätigung, Abschluss) `{ "type": "orchestrator", "context": "...", "step": "..." }`. `toolSessionId` adressiert die Tool-Ressource vollständig (`/tools/{toolSessionId}/{toolId}`) und ist gesetzt, sobald eine `ToolSession` für diesen Schritt existiert — auch beim Resume (`GET /channels/{channelSessionId}`) mitten in einem laufenden Tool.
- **Eine Antworthülle für alle Endpunkte aller Schichten** (`ChannelResponse`): `{ "channel": {channelSessionId, state, currentAcr, currentAmr, activeMethods}, "next": {...}, "stepData": {...}, "demo": {...} }`. `channelSessionId`/`state` stehen in jeder Antwort. `currentAcr`/`currentAmr`/`activeMethods` dagegen NIE in Tool-Antworten (`POST .../tools/{toolId}`, `PATCH`/`GET`/`DELETE` auf `/tools/...`), sondern nur bei den Kanal-Endpunkten (`GET`/`POST /channels`, `step-ups`, `enrollments`, `DELETE .../methods/{methodInstanceId}`).
- **`channel.state`-Werte** — alles, was der Client aus `state` selbst ableiten kann, ohne einen Endpunkt aufzurufen:

  | Wert | Bedeutung für den Client |
  |---|---|
  | `ANONYMOUS` | Kanal offen, noch kein Account bekannt — `next` zeigt auf `ident-fsc` oder Login |
  | `REGISTERING` | Registrierung läuft; `DELETE .../journey` (Cancel) bricht zurück auf `ANONYMOUS` |
  | `AUTHENTICATED` | Account bekannt und aktuelles Niveau ausreichend; `logout`, `methods`, `enrollments` verfügbar |
  | `STEP_UP_REQUIRED` / `STEP_UP_IN_PROGRESS` | Ein höheres Niveau ist nötig bzw. der Nachweis läuft bereits; `next` zeigt den fälligen Schritt, Cancel liefert direkt `AUTHENTICATED` zurück |
  | `LOGGED_OUT` | Terminal — dieser Kanal ist tot, `next` fehlt, ein neuer Kanal braucht einen neuen `POST /channels` |
  | `EXPIRED` | Terminal (TTL erreicht) — wie `LOGGED_OUT` aus Client-Sicht |

  Zustandsdiagramm: [Domänenmodell](02-domaenenmodell.md) Abschnitt 3.
- Auswahloptionen stehen nicht in `next`, sondern in `stepData.options` als vollständige `toolId`-Werte (z. B. `enroll-sms`). Der Client darf `toolId` nie selbst konstruieren; sie kommt aus `next.toolId` oder `stepData.options`.
- Wenn genau eine Methode erlaubt ist, überspringt das Backend die Auswahlseite und liefert direkt den Tool-Schritt.
- `stepData` trägt, was der aktuelle Schritt zum Anzeigen braucht: tool-internen Zustand (z. B. `missingFields`), erlaubte Folge-Tools (`options`), nach einem fehlgeschlagenen Versuch den Grund (`error`). Sonst entfällt das Feld.
- Jede `stepData` nennt ihre Form im Feld `kind` (`select-method`, `missing-fields`, `failed-attempt`, `confirm`, `message`, dazu die Formen der Module wie `qr-pairing` oder `kobil-otp`). Welche Formen es gibt, steht im `discriminator.mapping` von `StepData` in der Spec. Jede Form führt `kind` selbst als Pflichtfeld mit genau ihrem Wert (Ein-Wert-`enum`); `StepData` ist nur noch `oneOf` plus Diskriminator, ohne eigene Properties. Jedes Modul deklariert seine eigenen Formen selbst; eine zentrale Liste gibt es nicht. Ein Client muss mit einem unbekannten `kind` rechnen und ihn übergehen, statt abzubrechen.
- Der Diskriminator heißt auf dem Draht überall `kind`, auch bei `Prompt` und `KobilUnlockCredential`. `@t` gibt es nur noch intern für die gespeicherten Journey-Zustände: Der TypeScript-Generator kann den Namen nicht abbilden und hatte daraus `t` gemacht.

Pfadkonvention:

- Einstieg: `POST /orchestrator/api/v1/app/channels` — `201` mit `Location: .../channels/{channelSessionId}` (immer eine neue Ressource, nie ein Resume; die Location zeigt auf die fassadenneutrale Kanal-Ressource, Abschnitt 3).
- Kanalzustand lesen: `GET /orchestrator/api/v1/channels/{channelSessionId}`
- Niveau anheben (Step-up-Auslöser): `POST .../{channelSessionId}/step-ups` mit `{"requiredAcr": "..."}`
- Journey abbrechen: `DELETE .../{channelSessionId}/journey` (kein Body)
- Bestätigter Logout (startet Journey): `POST .../{channelSessionId}/logouts` (kein Body)
- Sofort-Logout: `DELETE .../{channelSessionId}` (kein Body)
- Methodenbestand lesen: `GET .../{channelSessionId}/methods`
- Methode hinzufügen (startet Enrollment): `POST .../{channelSessionId}/enrollments` (kein Body)
- Methode deaktivieren: `DELETE .../{channelSessionId}/methods/{methodInstanceId}` (kein Body) — adressiert per Instanz-ID, nicht per Methodenname (siehe unten)
- Account löschen (startet Journey): `POST .../{channelSessionId}/account-deletions` (kein Body)
- Rückfrage beantworten: `POST .../{channelSessionId}/answer` mit `{"answer": "accept"|"decline"}` — der generische Endpunkt für jeden `Prompt` (siehe unten)
- Tool-Anlage über Channel: `POST .../{channelSessionId}/tools/{toolId}` — `201` mit `Location: .../tools/{toolSessionId}/{toolId}` (kein Body; `toolId` trägt Kind und Methode zusammen)
- Tool-Fortschreibung/-Lesen: `PATCH/GET /orchestrator/api/v1/tools/{toolSessionId}/{toolId}` als Regelfall
- Tool-Attempt verwerfen: `DELETE /orchestrator/api/v1/tools/{toolSessionId}/{toolId}`

Tool-Namespace:

- Die Aktivierung bleibt Orchestrator-Hoheit und ist für alle Tools gleich — nur dort entsteht die `toolSessionId`.
- Alles unterhalb von `/tools/{toolSessionId}/{toolId}` gestaltet das Tool selbst: eigene Sub-Ressourcen und frei gewählte HTTP-Methoden. `PATCH`/`GET` sind Regelfall, keine Pflicht — nicht jedes Verfahren passt in "Felder nachliefern" (WebAuthn, eID).
- Bislang genutzt von genau einem Tool: `POST .../tools/{toolSessionId}/auth-kobil/pin-releases` gibt den backend-verwahrten KOBIL-PIN frei ([Abläufe](06-ablaeufe.md) Abschnitt 7). Warum keine Erweiterung des `PATCH`: Der PIN darf **nicht wieder abrufbar** sein, und `stepData` wird bei jedem `GET` einer lebenden Tool-Session neu aufgebaut — was dort steht, kommt wieder heraus. Außerdem ist eine Freigabe eine Erzeugung (einmalig, befristet, nicht idempotent), und zwei verschiedene Akte trennt eine URL sauberer als die Frage, welche nullable Felder gerade gesetzt sind. Der Body ist ein typisiertes Entweder-Oder (Gerätegeheimnis **oder** Kontopasswort); die Antwort ist `201` mit dem PIN im `stepData` oder — bei fehlgeschlagenem Entsperren — `200` mit dem gewöhnlichen `stepData.error`, weil ein falsches Geheimnis ein Retry-Fall ist und kein Fehlerstatus.
- Der garantierte Resume-Einstieg ist **nicht** die Tool-Ressource (deren `GET` ein Tool weglassen darf), sondern `GET /channels/{channelSessionId}`.
- **Implementierungsnote:** `POST`/`PATCH`/`GET` liegen je Tool in einem eigenen Controller mit typisiertem Request-DTO ([Tool-Architektur](03-tool-architektur.md) Abschnitt 2). `DELETE` ist die einzige Ausnahme.

### Cancel

`DELETE .../journey` bricht die laufende `AuthJourney` ab und rollt `ChannelSession.state` zurück ([Domänenmodell](02-domaenenmodell.md) Abschnitt 3). Danach startet der Kanal **denselben Intent** erneut, mit dem er eröffnet wurde. `STEP_UP`- und `MANAGE_AUTH_METHODS`-Abbruch liefern direkt `authenticated`. Account-Bindung und `AuthContext` werden aus `DeviceAccountLink` neu abgeleitet; ein zuvor per `ident-fsc` angelegter Account bleibt bestehen.

### Logout

Zwei Varianten:

- **Bestätigter Logout** (bevorzugt): `POST .../{channelSessionId}/logouts` startet eine `LOGOUT`-Journey mit einem Bestätigungs-Prompt ([Orchestrierung](04-orchestrierung.md) Abschnitt 3). Nach Zustimmung über `POST .../answer` wird der Kanal `LOGGED_OUT`.
- **Sofort-Logout** (nicht-interaktive Clients): `DELETE /channels/{channelSessionId}` beendet den Kanal direkt (`AUTHENTICATED → LOGGED_OUT`, terminal, `204`), bricht einen aktiven Prozess ab und verwirft den `AuthContext`.

Die Gerätebindung bleibt nutzbar (`DeviceAccountLink`, [DPoP-Bindung](09-dpop.md) Abschnitt 3).

### AccessToken (`GET .../{channelSessionId}/token`)

**`APP`-Kanal-only** (DPoP-demo-xso, ADR-9): Ein `KEYCLOAK`-Kanal hat nie einen `AuthContext` und
braucht auch keinen; `ChannelService.getToken` weist ihn mit `409 INVALID_STATE_TRANSITION` ab.
Für `APP` entscheidet `TokenProvider` profilabhängig:

- **Default-Profil** (`MockTokenProvider`): liefert `TokenService`s Mock-JWT (`alg=none`,
  `iss=mock-keycloak`).
- **`keycloak`-Profil** (`KcTokenProvider`): liefert einen echten, von Keycloak signierten
  AccessToken mit echten `acr`/`amr`-Claims (über `OrchestratorAcrAmrMapper`, wie beim WEB-Kanal).
  Drei Fälle, dieselbe Struktur wie `TokenService.tokenFor`: (1) noch lange genug gültig →
  unverändert zurück; (2) läuft ab, ACR/AMR unverändert → Erneuerung über Keycloaks
  `refresh_token`-Grant, ohne Account-Private-Key; (3) kein gültiges RefreshToken mehr
  (Erstausstellung oder ein Step-up hat den Cache invalidiert) → frische, signierte Assertion
  (Private Key aus `orchestrator.keycloak_keypair`, trägt `acr`/`amr` selbst als Claims) über den
  custom OAuth2-Grant (`urn:dpop-demo:account-token`, `keycloak-extension`s `AccountTokenGrantType`,
  ADR-9). Alle Aufrufe für denselben Account teilen dieselbe Keycloak-Session.

`minValiditySeconds` wirkt in beiden Profilen identisch. Ausnahme: Ein Step-up, der die `AuthEvidence` verändert
(`AuthEvidenceService.applyEvidence`/`applyEvidenceUpdate`), verwirft das gecachte Token aktiv.

### ID-Token-Claims (`GET .../{channelSessionId}/idclaims`)

**`APP`-Kanal-only**, wie das AccessToken oben — dieselbe `requireAuthenticated`-Vorbedingung.
Fachliche (nicht in die AccessToken-Signatur codierte) Claims: `sub`/`accountId`/`personId`,
`acr`/`amr`, `auth_time`, `email`/`email_verified`, `name`. `name` ist die einzige Stelle, an der
das Frontend erfährt, WER angemeldet ist: der Registerperson-Anker entscheidet — `personId`
vorhanden heißt "Vorname Name" der Person (`PersonDirectory.displayName`), fehlt er (Interessent,
ADR-10/18), fällt `name` auf die eigenen bestätigten Claims des Kontos zurück (stärkster überlebender
NAME/VORNAME-Claim, nur `null`, wenn auch davon keiner existiert). Dasselbe `personId`-Vorhandensein
ist es, woraus das Frontend den Kontostatus ableitet (Versicherter vs. Interessent) — ein eigener
Status-Claim wäre nur eine Redundanz desselben Ankers; es gilt derselbe `channelAccessGuard` wie
überall sonst. Die Authenticated-Seite zeigt beides kompakt im Begrüßungstext — „Angemeldet als
*Name* (Versicherter/Interessent)" — und die vollständigen Claims aufklappbar wie die
AccessToken-Details.

### Methoden verwalten (AuthIntent.MANAGE_AUTH_METHODS)

Freiwillige Kontoverwaltung auf einem bereits `AUTHENTICATED`-Kanal, losgelöst von `REGISTER` und `STEP_UP` ([Orchestrierung](04-orchestrierung.md) Abschnitt 3).

- `GET .../methods` liest den aktiven Methodenbestand als eigenständige Collection (`{"methods": [{"id","method","label"}]}`) — dieselben Daten wie `ChannelResponse.activeMethods`, nie `fsc`. Leere Liste statt Fehler, solange kein Account bekannt ist.
- `POST .../enrollments` bietet dieselben Kandidaten/Enroll-Tools wie `REGISTER` an und startet ein Enrollment. Nichts mehr zu enrollen ist kein Fehler: `200` mit `{"message": "Keine weiteren Mittel verfuegbar"}`.
- `DELETE .../methods/{methodInstanceId}` widerruft eine aktive Methoden-*Instanz*: Die Credential-Zeile des besitzenden Moduls wird gelöscht (`EnrollmentCleanup`), die Instanzzeile bleibt deaktiviert stehen. Adressiert per `id` aus `GET .../methods` — nie per Methodenname, da eine Methode mehrere aktive Instanzen haben kann (`docs/03-tool-architektur.md`, `allowsMultipleInstances`). `409`, falls der Account danach das kanaleigene `requiredAcr` nicht mehr erreichen könnte — sonst könnte sich jemand selbst aussperren. Nicht auf Instanzen des aufrufenden Geräts beschränkt.
- `DELETE .../attributes/{attribute}` nimmt ein **kontoeigenes Attribut** zurück statt eines Credentials — heute nur die bestätigte Adresse (`email`). Geschwister-Endpunkt zu `DELETE .../methods/{id}`, mit demselben Gate. Der Unterschied ist die Folgenkette: Jedes Credential, das dieses Attribut per `requires` verlangte, wird mitentzogen — transitiv. Eine zurückgenommene Adresse nimmt damit ein darauf eingerichtetes Passwort mit (`enroll-password` verlangt `ClaimRequirement(EMAIL, PROVEN)`) — und alles, was seinerseits daran hinge (ADR-24). `409`, wenn genau diese Folge den Account unter das kanaleigene `requiredAcr` drücken würde; die Meldung nennt, was dabei mitfallen würde. Nur Attribute, die dem Konto selbst gehören, sind zurücknehmbar: ein Stammdatenfeld gehört uns nicht, ein methodeneigenes geht mit seiner Methode.
- `POST .../enrollments`, `DELETE .../methods/{methodInstanceId}` und `DELETE .../attributes/{attribute}` verlangen zusätzlich, dass die aktuelle Session bereits `loa2` erreicht hat; reicht es nicht, liefert die Antwort statt der Aktion einen Step-up-Schritt; danach ruft der Client den Endpunkt erneut auf.

### Das `Prompt`-Objekt

Jeder `JourneyState`, der auf eine explizite Ja/Nein-Antwort statt auf einen Tool-Lauf wartet (`AnswerableState`), trägt einen `prompt` in `stepData` (Form `confirm`): `{"kind": "Confirm", "title": "...", "description": "...", "confirmLabel": "...", "cancelLabel": "...", "destructive": true|false}`. `next` ist für **jeden** `AnswerableState`, unabhängig vom Intent, derselbe feste Wert: `{"type":"orchestrator","context":"prompt","step":"confirm"}`. Beantwortet wird jeder `Prompt` über denselben generischen `POST .../{channelSessionId}/answer` mit `{"answer": "accept"|"decline"}`.

Jeder Prompt-Text wird **komplett vom Backend geliefert**, nie clientseitig vorformuliert; eine geänderte Rückfrage braucht so keinen App-Release.

`Prompt` ist als `sealed interface` mit `kind`-Diskriminator modelliert; `Confirm` ist die einzige Variante, eine `Choice`-Variante ist vorbereitet, aber nicht implementiert. Verwendungen: Gerätebindungs-Screen des Lookup-Logins, Account-Löschbestätigung, Logout-Bestätigung, `ReIdentifyState.OfferReIdent` vor der `RE_IDENTIFY`-SubJourney ([Orchestrierung](04-orchestrierung.md)).

### Account löschen (AuthIntent.DELETE_ACCOUNT)

Self-Service-Löschung des eigenen Accounts auf einem bereits `AUTHENTICATED`-Kanal. Die Ja/Nein-Bestätigung kommt **immer zuerst, unbedingt**; erst nach Zustimmung greift dasselbe `selfServiceAcrFloor`-Gate wie vor `MANAGE_AUTH_METHODS` (loa2, für ein nie identifiziertes Konto nur loa1, [Orchestrierung](04-orchestrierung.md) Abschnitt 3):

1. `POST .../{channelSessionId}/account-deletions` (kein Body) startet die Journey und liefert sofort die Bestätigungsrückfrage: `next={"type":"orchestrator","context":"prompt","step":"confirm"}`, `stepData.prompt` mit `destructive: true`.
2. `POST .../answer` mit `{"answer":"accept"}` (oder `"decline"`, bricht wie ein normales Cancel zurück auf `AUTHENTICATED`). Erst jetzt greift das Gate: reicht das aktuelle Niveau nicht, liefert die Antwort einen Step-up-Schritt; danach ruft der Client `account-deletions` erneut auf.
3. Reichte das Niveau schon vorher (ein Nachweis unbekannten Alters), folgt ein frischer Nachweis über ein **beliebiges** aktives `auth-*`-Verfahren des Accounts, unabhängig vom damit erreichbaren Niveau (auch ein soeben bewiesener Faktor zählt erneut — anders als bei `STEP_UP`). Genau ein Verfahren reicht; mehrere ergeben dieselbe `next={"context":"auth","step":"selectMethod"}`-Auswahlseite. **Ausnahme**: musste Schritt 2 erst einen Step-up auslösen, zählt dieser Nachweis bereits als der hier geforderte.
4. Nach erfolgreichem Nachweis wird der Account mit allem, was er exklusiv besitzt, unwiderruflich gelöscht: alle über `authenticationMethods` referenzierten Credential-Datensätze der Methodenmodule (aktive **und** abgelöste), der `DeviceAccountLink`, jeder `AuthContext` sowie die `account`-Zeile selbst. `person` (ext_stammdaten) bleibt unangetastet ([Tool-Architektur](03-tool-architektur.md), `EnrollmentCleanup`).
5. Jede `ChannelSession`, die je an diesen Account gebunden war, wird serverseitig auf `LOGGED_OUT` gezwungen; ein anderes eingeloggtes Gerät braucht einen neuen `POST /channels`.
5. Die Antwort auf den erfolgreichen Abschluss ist `channel.state="LOGGED_OUT"` ohne `next` — dieselbe Form wie ein normaler Logout.

### Back/Switch

`DELETE /tools/{toolSessionId}/{toolId}` verwirft einen aktivierten, aber noch nicht abgeschlossenen Tool-Versuch. Die verworfene `toolSessionId` wird sofort ungültig; der Prozess bekommt dieselbe Kandidatenermittlung erneut wie beim letzten `Completed`.

Konsistenzregel: Pro `channelSessionId` darf es höchstens einen aktiven öffentlichen Prozesskontext geben; welcher interne `purpose` dazu gehört, entscheidet das Backend.

### Ein Tool-Zyklus im Beispiel

Registrierung mit `ident-fsc` -> `enroll-sms`:

1. `POST /app/channels` (`{"requiredAcr": "loa2", "availableTools": ["ident-fsc", "enroll-sms", ...]}`, `intent` weggelassen → Default `fast_access`; `availableTools` ist Pflicht, siehe unten) liefert eine neue `channelSessionId` und direkt den ersten Schritt: `next={"type":"tool","toolId":"ident-fsc","step":"input"}` (genau eine `IDENT`-Methode, daher kein Auswahlschritt; noch keine `ToolSession`, also kein `toolSessionId`). Enthält `availableTools` auch `ident-eid`, liefert derselbe `POST` einen Auswahlschritt: `next={"type":"orchestrator","context":"registration","step":"selectIdentificationMethod"}`, `stepData={"kind":"select-method","options":["ident-fsc","ident-eid"]}`.
2. `POST .../tools/ident-fsc` (kein Body) legt die Tool-Ressource an: `201` mit `stepData={"kind":"missing-fields","missingFields":["kvnr","name","vorname"]}` und `next.toolSessionId` gesetzt.
3. `PATCH /tools/{toolSessionId}/ident-fsc` mit den Feldern, zuletzt dem FSC. Solange Felder fehlen: `200` mit aktualisiertem `stepData.missingFields`, `next` unverändert auf `ident-fsc`. Nach erfolgreicher Verifikation: `stepData={"kind":"select-method","options":["enroll-sms"]}`, `next={"type":"orchestrator","context":"enrollment","step":"selectMethod"}`.
4. `POST .../tools/enroll-sms` liefert `stepData={"kind":"missing-fields","missingFields":["phoneNumber"]}`.
5. `PATCH .../enroll-sms` mit `{"phoneNumber": "..."}` löst den TAN-Versand aus: `stepData={"kind":"missing-fields","missingFields":["tan"]}` plus (siehe unten) `demo={"tan":"123456"}`.
6. `PATCH .../enroll-sms` mit `{"tan": "123456"}` schließt ab: `next={"type":"orchestrator","context":"authentication","step":"authenticated"}`, `channel.state` bereits `"AUTHENTICATED"` in derselben Antwort — kein separater `GET` nötig.
7. `GET /channels/{channelSessionId}` liefert jederzeit den stabilen Kanalzustand — der garantierte Resume-Einstieg, mitten in einem laufenden Tool inklusive dessen `toolSessionId`.

Jedes weitere Tool (`enroll-password`/`auth-password`, `enroll-email`/`auth-email`, die `-lookup`-Varianten) folgt demselben `POST`/`PATCH`/`GET`-Muster; die tool-spezifischen Abweichungen stehen unten.

### Das `demo`-Objekt

Jede Antwort kann ein zusätzliches, klar gekennzeichnetes `demo`-Objekt tragen — **kein Teil des produktiven Vertrags** (in einer echten Umgebung abgeschaltet). Es trägt `accountId`/`personId` sowie je nach Tool `tan` (gerade ausgestellte TAN/Code), `password`/`email` (feste Demo-Werte zum Vorbelegen von Formularen). Tools liefern ihre Demo-Werte im eigenen Feld `ToolOutcome.InProgress.demo`, getrennt von `stepData`.

### `POST /app/channels`: `intent`-Parameter

`intent` (optional, Default `fast_access`) ist der Name des `AuthIntent`-Werts, case-insensitiv (`AuthIntent.fromRequest`); unbekannte Werte werden abgelehnt. Steuert, welcher Prozess auf DIESEM Kanal startet, unabhängig vom durch `DeviceAccountLink` erkannten Gerät:

- `fast_access` (Default, auch bei weggelassenem `intent`): `DeviceAccountLink` gefunden -> Anmeldepfad mit vorbefülltem Account, sonst die `REGISTER`-Sub-Journey.
- `lookup_login`: erzwingt lookup-basierten Login (E-Mail + Credential, siehe unten) — auch auf einem bereits verlinkten Gerät; der Link-Lookup wird für diesen Kanal übersprungen.
- `register`: erzwingt eine frische `REGISTER`-Journey — auch auf einem bereits verlinkten Gerät (Zweitaccount). Löst die neue Identifikation ein anderes Konto auf als das bisher verlinkte, fragt der Kanal noch vor jeder Methodenauswahl per Bestätigung (`RegisterState.ConfirmDeviceRebind`), ob die bestehende `DeviceAccountLink`-Bindung ersetzt werden soll: Zustimmung bindet das Gerät um und deaktiviert/löscht das bisherige konto-eigene Geräte-Credential (`enroll-device`) für genau diesen Schlüssel; Ablehnung bricht die Journey regulär ab (kein Fehler, `DELETE .../journey`-Semantik), die alte Bindung bleibt bestehen.
- `confirm_peer_login`: startet `AuthIntent.CONFIRM_PEER_LOGIN` — einen wartenden Web-Login (`auth-qr`/`auth-qr-lookup`) bestätigen/ablehnen (siehe unten, "Peer-Login bestätigen"). Auch von einem noch nicht authentifizierten Kanal aus erreichbar, aber nie mit Identifikation oder Registrierung als Fallback.

`requiredAcr` (optional) erspart den Umweg über ein niedriges Einstiegsniveau mit anschließendem Step-up. Das Backend rechnet mit `max(Policy-Anforderung, Client-Wunsch)`.

`availableTools` (Pflicht) erklärt, welche toolIds dieser Client aktivieren kann. Fest für die Lebensdauer des Kanals. Ein Tool außerhalb dieser Menge wird nie angeboten und auch bei direkter Aktivierung abgelehnt (`docs/03-tool-architektur.md`, Verfügbarkeit); zusätzlich kann das Backend jedes Tool global und zur Laufzeit sperren (`GET`/`PUT /orchestrator/admin/tools/.../availability` — ein Betriebsendpunkt, nicht Teil des App-Vertrags).

### `GET /channels/{channelSessionId}`

Liest den stabilen Kanalzustand. Zwei zusätzliche Felder im `channel`-Block neben `state`:

- `currentAmr`: was **diese Sitzung** bereits nachgewiesen hat (Sitzungsevidenz aus dem `AuthContext`).
- `activeMethods`: der volle, kontostabile Methodenbestand als `{id, method, label}`-Objekte — unabhängig davon, was diese Sitzung geprüft hat. Enthält nie `fsc` (Identifikation liegt im Audit-Log `account.identification`, nicht in `account.auth_method`). `id` adressiert die Instanz für `DELETE`; `label` ist nur bei mehrfach-möglichen Methoden gesetzt (aktuell nur `device`). `auth-device` erscheint als AUTH-Kandidat nur auf dem Gerät mit dem passenden Schlüssel (`docs/04-orchestrierung.md`); Deaktivieren bleibt ungefiltert.

Beide Felder werden nur bei bekanntem `accountId` befüllt. `next` ist immer gesetzt — auch bei abgeschlossener Journey (`{"type":"orchestrator","context":"authentication","step":"authenticated"}`); ein separates `stepUpRequired`-Flag gibt es nicht. Nur bei `LOGGED_OUT` (terminal) fehlt `next` ganz.

### Journey-Log (`GET /journey-log`, `GET /channels/{channelSessionId}/journey-log`)

Debug/Demo-Ansicht, kein Audit-Trail (`SessionEvent`, [Betrieb](07-betrieb.md) Abschnitt 2). Zwei Varianten, beide `dpop`-authentifiziert:

- `GET /orchestrator/api/v1/journey-log`: jeder Journey-Schritt unter dem `bindingKeyRef` des Aufrufers (aus dem validierten DPoP-Proof), neuestes zuerst, über alle `channelSessionId`s dieses Geräts hinweg (`JourneyLogController`).
- `GET /channels/{channelSessionId}/journey-log`: jeder Journey-Schritt unter dem **Account**, an den dieser Kanal gebunden ist, kanalübergreifend (APP wie KEYCLOAK). Leer statt Fehler, solange kein Account gebunden ist.

### `GET /app/channels/device-link`

Reines Lesen: ob dieses Gerät (DPoP-Proof, keine `channelSessionId` nötig) bereits an einen Account
gebunden ist (`DeviceAccountLink`, [Domänenmodell](02-domaenenmodell.md) Abschnitt 1) — legt **kein**
Channel/Journey an. `{"linked": true, "accountId": 42, "personName": "Max Muster"}` bzw.
`{"linked": false}`.

Dazu ein Demo-Feld `boundCredentials`: je ein Eintrag `{method, reference}` für jedes
schlüsselgebundene Credential des verknüpften Kontos, das auf **diesem** Schlüssel lebt — die
`device`-Methode nennt ihren Credential-Schlüssel, `kobil` die Kennung, die der Anbieter diesem
Telefon gegeben hat. Was gezeigt wird, entscheidet jedes Modul selbst
(`ToolDescriptor.instanceDisclosure`, [03-tool-architektur.md](03-tool-architektur.md)); der
Orchestrator kennt dafür keinen einzigen Methodennamen.

Ein fehlender Eintrag sagt dabei genauso viel wie ein vorhandener: Hält ein Client lokale Daten zu
einer Methode, die hier nicht mehr aufgeführt ist, sind diese Daten veraltet. Genau
daran erkennt das KOBIL-Frontend, dass es sein Gerätegeheimnis löschen muss
([09-dpop.md](09-dpop.md) Abschnitt 3).

### `POST /channels/{channelSessionId}/step-ups`: Step-up-Auslöser

Hebt die geforderte Untergrenze des Kanals an (auf der kc-Seite derselbe Endpunkt, Abschnitt 3). Request: `{"requiredAcr": "loa3"}`. Reicht das aktuelle Niveau nicht, startet das Backend eine `AuthJourney(STEP_UP)` und liefert den fälligen Schritt als `ChannelResponse`; reicht es bereits, zeigt `next` sofort auf `authenticated`. Nur Anheben ist möglich — ein niedrigeres `requiredAcr` wird ignoriert. Reicht keine vorhandene Methode für das geforderte Niveau, aber eine Re-Identifizierung (`ident-fsc`/`ident-eid`) könnte es allein erreichen, fragt die Journey das erst per `stepData.prompt` (`context: "prompt", step: "confirm"`) — bei Zustimmung folgt die Auswahl, bei Ablehnung endet der Step-up ohne Fehler. Nur wenn selbst das nicht möglich ist, bricht die Journey mit `410` ab ([Orchestrierung](04-orchestrierung.md)).

### `enroll-email` / `auth-email` / `enroll-password` / `auth-password`

Folgen demselben Muster wie `ident-fsc`/`enroll-sms`/`auth-sms` oben, mit diesen Abweichungen:

- `enroll-email` folgt dem Zwei-`PATCH`-Muster (`email`, dann `code`); `demo.email` steht schon in der `start`/`read`-Antwort, `demo.tan` erst nach dem ersten `PATCH`.
- `auth-email` folgt dem Ein-`PATCH`-Muster (`code`) und authentifiziert gegen die bekannte, bestätigte E-Mail-Adresse des Accounts, nicht gegen eine im Request übergebene.
- `enroll-password`/`auth-password` erwarten nur `{"password": "..."}` — **kein** `username` ([Tool-Architektur](03-tool-architektur.md) Abschnitt 1). `enroll-password` schließt in einem einzigen `PATCH` ab und setzt eine bereits bestätigte Account-E-Mail voraus (`requires = { ClaimRequirement(EMAIL, PROVEN) }`); ohne sie lehnt schon die Aktivierung mit `409` ab. `demo.password` (feste Demo-Konstante) steht in jeder `InProgress`-Antwort aller drei Passwort-Tools.

### Lookup-basierter Login (`auth-sms-lookup` / `auth-password-lookup` / `auth-email-lookup`, "Login ohne DPoP")

Erreichbar nur über `POST /channels` mit `intent: "lookup_login"` — nie über die normale Kandidatenermittlung (`MethodRole.LOOKUP_AUTH`; `AuthPolicy.candidateTools` wählt ausschließlich `IDENTIFIED_AUTH`). Löst den Account selbst über die eingegebene E-Mail auf:

- `auth-sms-lookup`/`auth-email-lookup` folgen dem Zwei-`PATCH`-Muster: erst `{"email": "..."}` (löst den Account auf, verschickt bei Erfolg TAN/Code), dann `{"tan"/"code": "..."}`.
- `auth-password-lookup` erwartet `{"email": "...", "password": "..."}` in einem einzigen `PATCH`.
- Enumeration-Schutz: Eine unbekannte oder unbestätigte E-Mail verhält sich in Form und Timing identisch zu einem korrekt aufgelösten Account mit falschem Credential. `demo.email`/`demo.password` sind feste Konstanten, unabhängig vom aufgelösten Account.
- Bei Erfolg endet der Flow nicht immer sofort: Ist dieses Gerät noch nicht oder bereits demselben Konto zugeordnet, bietet der Orchestrator die Gerätebindung optional an. Ist es bereits an ein anderes Konto gebunden, fragt er vor dem Überschreiben dieser `DeviceAccountLink`-Bindung ausdrücklich nach. Ablehnung lässt den Login trotzdem erfolgreich ohne neue Bindung enden; Zustimmung bindet das Gerät um.

### Peer-Login bestätigen (AuthIntent.CONFIRM_PEER_LOGIN)

Ein App-Kanal bestätigt/lehnt einen wartenden Web-Login ab, den eine `auth-qr`/`auth-qr-lookup`-
Aktivierung des Web-Kanals angestoßen hat ([Orchestrierung](04-orchestrierung.md) `CONFIRM_PEER_LOGIN`).
Zwei gleichwertige Einstiege:

- `POST /app/channels` mit `{"intent":"confirm_peer_login"}` — Einstieg ohne bestehende Session, siehe `intent`-Parameter oben.
- `POST /channels/{channelSessionId}/peer-logins` (kein Body) — auf einem bereits `AUTHENTICATED`-Kanal.

Beide laufen auf demselben Gate zusammen:

1. Kein Konto über `DeviceAccountLink` bekannt (nur ohne bestehende Session möglich) → `410`, nie ein
   Fallback auf Identifikation/Registrierung.
2. Aktuelles Niveau unter `loa2` → ein Step-up-Schritt; der Client folgt ihm und ruft den
   Einstiegs-Endpunkt danach erneut auf.
3. Niveau bereits `loa2`, aber unabhängig von diesem Durchlauf erreicht (ein Nachweis unbekannten
   Alters) → die Antwort verlangt einen frischen Nachweis über ein beliebiges aktives
   `auth-*`-Verfahren, exakt wie bei „Account löschen" Schritt 3 oben
   (`next={"context":"auth","step":"selectMethod"}` bei mehreren Kandidaten). **Ausnahme**: Musste
   Schritt 2 erst einen Step-up auslösen, zählt dieser Nachweis bereits als der geforderte.
4. `confirm-qr-login` aktivieren:
   - `POST .../tools/confirm-qr-login` (kein Body) → `stepData={"kind":"missing-fields","missingFields":["pairingCode"]}`.
   - `PATCH .../confirm-qr-login` mit `{"pairingCode":"..."}` (aus dem QR-Code bzw. dem Demo-Link
     vorbefüllt, [Frontend](10-frontend.md)) → bei gültiger, noch offener Anfrage
     `stepData={"kind":"qr-pairing","verificationCode":"..."}`, `next.step="confirm"`. Der Nutzer vergleicht ihn mit dem
     auf der Web-Seite gezeigten (QR-Jacking-Schutz).
     Unbekannter/abgelaufener/bereits entschiedener Code: `stepData.error`, bleibt auf `input`,
     normale Retry-Logik.
   - `PATCH .../confirm-qr-login` mit `{"decision":"accept"}` bzw. `{"decision":"reject"}`. `accept`
     ohne aktives `enroll-qr` auf dem Konto: `stepData.error` ("QR-Login ist für dieses Konto nicht
     aktiviert").
5. Erfolgreiches `accept`: `next={"type":"orchestrator","context":"authentication","step":"authenticated"}`
   — war der Kanal vor diesem Aufruf noch nicht `AUTHENTICATED`, fragt die Antwort davor per
   `Prompt` ("Jetzt abmelden?"), ob der Kanal angemeldet bleiben soll; `reject` liefert stattdessen
   `stepData.error` ("Vom Nutzer abgelehnt").

Die WEB-Seite selbst (`auth-qr`/`auth-qr-lookup`) pollt denselben generischen Tool-Patch-Endpunkt
mit leerem Body, solange die Anfrage offen ist (kein Attempt-/Login-Throttle). Bei `APPROVED` liefert
derselbe `PATCH` `Completed.Authenticated`, bei `DENIED`/abgelaufen entsprechend `Failed` — für
`auth-qr-lookup` inklusive des aufgelösten `accountId` im `demo`-Objekt.

---

## 3) Web/Keycloak-Fassade (Keycloak-first)

**Umgesetzt.** Der Browser spricht hier nie mit dem Orchestrator: Keycloak → Orchestrator ist reine
Server-zu-Server-Kommunikation über Keycloaks Java-SPI-Plugin (`keycloak-extension/`). Ein
fassaden-spezifischer Endpunkt, Upsert-Semantik:

- `PATCH /orchestrator/api/v1/kc/channels/{channelSessionId}` — legt den Kanal beim ersten Aufruf
  unter dieser Keycloak-gewählten ID an, setzt ihn bei jedem weiteren Aufruf fort. Die ID stammt aus
  Keycloaks laufendem Auth-Flow (`AuthenticationSessionModel`/`UserSessionModel`).

Body (alle Felder optional, `KcChannelUpsertRequest`):

| Feld | Bedeutung |
|---|---|
| `accountId` | Der Account, den Keycloak schon kennt (`sub` vorhanden, Step-up) — bindet den Kanal sofort, nie später überschrieben. |
| `targetAcr` | Keycloaks angefragtes LoA-Level, bereits in einen Orchestrator-ACR-String übersetzt — hebt nur die Kanal-Untergrenze an, nie herab. Filtert die Kandidaten von `KC_SELECT_METHOD` ([Orchestrierung](04-orchestrierung.md) Abschnitt 3). |
| `amr` | Liste `{nativeToolId, amrSourceId}` — was ein natives Keycloak-Verfahren (nie ein Orchestrator-Tool) DIESEN Flow-Durchlauf bewiesen hat. Methode/Loa/Faktortypen löst der Orchestrator serverseitig über `nativeToolId` auf (`NativeAuthenticatorDescriptor`). Immer die VOLLSTÄNDIGE, aktuell gültige Menge, kein Delta. |
| `restoreData` / `kcSessionId` | Ein signiertes Token aus `GET .../restore-data` einer FRÜHEREN, unabhängigen `ChannelSession` derselben Keycloak-User-Session — reicht die dort erbrachten Nachweise an einen frisch angelegten Kanal weiter. `kcSessionId` bindet das Token an Keycloaks durables `UserSessionModel`. Ein falsches/abgelaufenes/manipuliertes Token kommt als `null` zurück, nie als Fehler. |
| `availableTools` | Welche `toolId`s das Keycloak-Theme rendern kann (ein `WebToolRenderer` pro Tool) — nur beim ersten Aufruf gelesen, das Web-Pendant zu `availableTools` bei `POST /app/channels`. |
| `intent` | Nur beim ersten Aufruf gelesen. Weggelassen bedeutet `kc_select_method`; akzeptiert wird sonst ausschließlich `register`. Ein unbekannter oder unzulässiger Wert wird abgelehnt (`409`). |

`GET .../{channelSessionId}/restore-data?kcSessionId=...` — nur für Keycloaks Flow-Ende-Hook: liefert die
gesammelten Nachweise dieses Kanals als Token, gebunden an diese `kcSessionId`
(`RestoreDataCodec`). Keycloak legt es in einer `UserSessionModel`-Note ab und reicht
es bei einem SPÄTEREN Step-up unverändert als `restoreData` im ersten `PATCH` zurück.

Danach läuft **alles** über dieselben fassadenneutralen Endpunkte wie die App-Fassade, ohne
`/kc/`-Präfix:

- `GET .../channels/{channelSessionId}`, `.../step-ups`, `.../journey`, `.../methods`,
  `.../enrollments`, `.../token`, `.../idclaims` (Abschnitt 2)
- `POST .../channels/{channelSessionId}/tools/{toolId}` (Aktivierung), danach `PATCH`/`GET
  /tools/{toolSessionId}/{toolId}` — identisch zur App-Fassade

Statt DPoP-Proof authentifiziert sich Keycloak über eine signierte Peer-Auth-Assertion im
`Authorization`-Header (kein mTLS, ADR-7): ein JWT pro Request mit `iss=keycloak`,
`aud=orchestrator`, `htm`/`htu` dieses Requests, `jti`+`iat` (derselbe Replay-Cache und dasselbe
`max-clock-skew-seconds`-Fenster wie bei DPoP, [09-dpop.md](09-dpop.md)), plus der Kanal-Anker
dieses Flow-Durchlaufs (`channelAnchor`), den `KcChannelAccessGuard` gegen diesen Kanal prüft.
Verifikation gegen Keycloaks JWKS, ein Schlüsselpaar pro Client, nicht
pro Nutzer.

Jede Antwort an einen `KEYCLOAK`-Kanal trägt zusätzlich `authData` (`accountId`/`acr`/`amr`,
niemals bei `APP`) — Keycloaks `OrchestratorAuthenticator` schreibt es sofort in seine
Session-Notes. `amr` bildet Methode auf Quelle ab (`"kc"` für eine native Selbstauskunft
Keycloaks, `"orchestrator"` für ein abgeschlossenes Orchestrator-Tool) — rein informativ, den
kombinierten `acr` bestimmt ausschließlich der Orchestrator.

Der Web-Kanal kennt kein Gerät — `DeviceAccountLink` bleibt APP-only
([02-domaenenmodell.md](02-domaenenmodell.md)). Login läuft über den Lookup-Login bzw. über den
eigenen Entry-Intent `KC_SELECT_METHOD` ([04-orchestrierung.md](04-orchestrierung.md) Abschnitt 3),
Registrierung über `REGISTER` (`intent=register`, s. o.); `ident-fsc`/`ident-eid`/`enroll-*` laufen über dieselben
`WebToolRenderer`.

Eine Lücke, die hier ausdrücklich benannt wird: **`enroll-kobil`/`auth-kobil` haben keinen
`WebToolRenderer`** und fehlen damit im Web-Kanal. Das Verfahren braucht ein
Telefon-SDK; aus einer servergerenderten Loginmaske ist es nicht ansprechbar. Das Theme deklariert
die beiden `toolId`s folglich nicht in `availableTools`, und damit werden sie dort nie angeboten —
derselbe Mechanismus, der alte App-Versionen funktionsfähig hält, trägt auch diesen Fall.

**Offen:** Die Logout-Semantik im Web-Kanal ist noch nicht entschieden — ob `DELETE
/channels/{id}` für `KEYCLOAK`-Kanäle clientseitig aufrufbar sein soll, oder ausschließlich
kc-getrieben.

### Anmeldeverfahren verwalten im Web-Kanal (Keycloak Required Action)

`AuthIntent.MANAGE_AUTH_METHODS` ist bereits fassadenneutral (Abschnitt 2, "Methoden verwalten";
`POST .../enrollments` nutzt denselben `DpopBindingKeyResolver`). Der Web-Kanal braucht
**keinen neuen Orchestrator-Endpunkt**, nur einen eigenen Einstieg: eine Keycloak-`RequiredAction`
(`getId()="orchestrator-manage-methods"`, `defaultAction=false` — nie erzwungen, nur über
`kc_action` auslösbar), registriert im bestehenden `orchestrator-browser`-Flow und erreichbar über
dieselbe `/auth`-URL wie ein normaler Login, ergänzt um `kc_action=orchestrator-manage-methods`.

Kein erzwungener zweiter Login nötig: Der vorangehende `orchestrator-browser`-Durchlauf nutzt das
bestehende Keycloak-SSO-Cookie, `OrchestratorResumeAuthenticator` bringt den neuen
Orchestrator-Kanal über `restoreData` auf `AUTHENTICATED`, sofern die Nachweise reichen — sonst greift
die normale Login-/Step-up-Kaskade. Schließt der Flow erfolgreich ab, ruft die Required Action
`startEnrollments(...)` auf dem frischen Kanal auf und rendert `next` über denselben
`WebToolRenderer`-Dispatch. Frontend: `redirectToManageMethods()` (`webOidc.ts`) baut dieselbe
`/auth`-URL wie `redirectToLogin`, Rückkehr über den bestehenden
`completeLoginIfRedirected()`-Pfad.

Das Interpretieren von `next` (Auswahlbildschirm vs. Tool-Formular vs. Tool automatisch aktivieren)
ist zwischen `OrchestratorAuthenticator` und dieser Required Action **gemeinsamer Code**
(`OrchestratorNextDispatch.classify`/`dispatchToolAction`, `keycloak-extension`); nur die Reaktion
darauf (`context.success()`/`failure()` vs. `RequiredActionContext`-Äquivalente) bleibt je Caller
eigenständig.

---

### Server-zu-Server: Keycloaks natives Passwort-Credential (`MgmtPasswordController`)

Stateless, ohne Channel/ToolSession: Keycloaks native UserStorage-SPI (`OrchestratorStorageProvider`)
verifiziert/setzt Passwörter auf dem über das `orchestratorAccountId`-User-Attribut bekannten
Account. Authentifiziert über dieselbe
`kc-peer-auth`-Signatur wie andere Peer-Aufrufe
([DPoP-Bindung](09-dpop.md)/[12-entscheidungen.md](12-entscheidungen.md) ADR-7), aber mit
`channel_anchor` zweckentfremdet: das Claim trägt hier die `accountId`, gegen den Pfad-Parameter
geprüft.

- `POST /orchestrator/api/v1/tools/auth-password/mgmt/{accountId}` — `{"password": "..."}` gegen das gespeicherte Credential prüfen, Antwort `{"valid": true|false}`.
- `POST /orchestrator/api/v1/tools/enroll-password/mgmt/{accountId}` — `{"newPassword": "..."}` setzt ein neues Credential und deaktiviert das bisherige `password`-Mittel des Accounts, `204`.

## 4) Hybrid-Modell: Prozess-API + Tool-Ressourcen

Ziel: Prozesssicht/Fachführung bleibt in den Prozess-Endpoints; App-Frontend und Keycloak nutzen für Eingabe- und Verifikationsschritte dieselben kanalneutralen Tool-URLs.

- Der Channel-/Prozess-Endpunkt wählt über `toolId` das Tool aus und erzeugt eine technische `ToolSession`, ohne selbst fachliche Eingabedaten entgegenzunehmen.
- `AuthJourney` bleibt fachlich zuständig (Intent, Zustand, Versuchsbudget); `ToolSession` trägt nur Lifecycle-Metadaten (`toolSessionId`, `journeyId`, Zeitstempel): `toolId` ergibt sich aus der Route, `stepData` aus den Moduldaten, das Versuchsbudget gilt für die ganze Journey (siehe [Domänenmodell](02-domaenenmodell.md)).
- `accountId`/`personId` sind kein Teil des fachlichen Antwortvertrags; einzige Ausnahme ist das demo-Objekt.

Für Keycloak sind `auth-sms`/`auth-password`/`auth-email` (Login/Step-up) der einzige nicht über die App-Fassade abgedeckte Fall; Aktivierung, `PATCH` und `GET` laufen identisch zur App-Seite: `POST .../channels/{channelSessionId}/tools/auth-sms`, danach `PATCH`/`GET /tools/{toolSessionId}/auth-sms` (Abschnitt 3).
