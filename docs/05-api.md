# API-Spezifikation

Die öffentliche API unter `/orchestrator/api/v1`, getrennt nach App-Kanal (der Orchestrator führt)
und Web-Kanal (Keycloak führt).

Was die Antworten fachlich bedeuten – insbesondere `next` –, ergibt sich aus
[04-orchestrierung.md](04-orchestrierung.md).

---

## 1) API-Grundsätze

- Die öffentliche API ist unter `/orchestrator/api/v1` versioniert.
- Verschiedene Methoden und Modi haben jeweils eigene, konkrete Endpunkte. Die URL bestimmt die
  Operation, nicht der Inhalt der Anfrage.
- Vorbereitende Schritte arbeiten mit Tool-Ressourcen: Ein `POST` auf den Kanal legt das Tool an;
  danach gestaltet das Tool seinen eigenen URL-Bereich selbst (Abschnitt 2).
- Ein `PATCH` enthält nur das, was nachgeliefert oder geändert wird; vorhandene Felder dürfen
  gezielt überschrieben werden.
- HTTP-Fehlercodes sind gestörten Abläufen vorbehalten. Fehlende Pflichtdaten und fehlgeschlagene
  Versuche, bei denen noch weitere Versuche übrig sind, werden mit `200` plus `next` beantwortet
  (die Regel dazu steht in [Orchestrierung](04-orchestrierung.md)).
- HATEOAS wird im Zielbild nicht verwendet.
- Welchen Aufruf der Client als Nächstes macht, leitet er über eine feste Routing-Tabelle aus
  `next.type` (`tool` oder `orchestrator`) und dem passenden Attribut ab: `next.toolId` bzw.
  `next.context` zusammen mit dem gewählten Eintrag aus `stepData.options`.
- Endpunkte für ein bestimmtes Verfahren und klar benannte DTOs und Handler (`ident-fsc`,
  `enroll-sms`, `auth-sms`) haben Vorrang vor allgemeinen Sammel-Endpunkten.

### Der Vertrag: `api/`

Den Vertrag nutzen drei Stellen: die Kotlin-DTOs selbst, das Frontend und `keycloak-extension`
(`OrchestratorClient`). Früher war er dreimal von Hand geschrieben, und eine geänderte Antwort kam
bei jeder Stelle zu einem anderen Zeitpunkt an.

| Datei | Inhalt | Wofür |
|---|---|---|
| `api/openapi.yaml` | der App-Vertrag: alles unter `/orchestrator/api/v1` | Eingabe für beide Generatoren |
| `api/published/v1.yaml` | der veröffentlichte Stand von v1 | Vergleichsbasis für `checkPublishedApiCompatibility` |
| `api/modules/<modul>.yaml` | alle Endpunkte und eigenen DTOs dieses Moduls; gemeinsame Schemas per `$ref` auf `../openapi.yaml` | zum Lesen und für Reviews |
| `frontend/src/generated/` | die daraus erzeugten TypeScript-Typen | vom Frontend importiert, eingecheckt |
| `keycloak-extension/build/generated/` | die daraus erzeugten Java-Modelle | von `OrchestratorClient` benutzt, nicht eingecheckt |

**Was zum App-Vertrag gehört, entscheidet der Pfad.** `api/openapi.yaml` enthält genau die
Endpunkte unter `API_V1`; das ergibt sich aus der Konstante, nicht aus einer Ausschlussliste. Drei
Arten von Endpunkten liegen bewusst woanders:

- Betriebsendpunkte unter `/orchestrator/admin` (Tool-Sperre, Reihenfolge der Registrierung,
  Abgleich mit Keycloak, Journey-Log aller Konten, Konten löschen, Demo zurücksetzen). Nur sie
  liegen hinter einer Anmeldung (HTTP Basic mit `demo.admin.*`).
- Der öffentliche, nur lesende Server-Status unter `/orchestrator/demo/server-info`.
- Die Stellvertreter externer Systeme unter `/mock-*` (`/mock-kobil`,
  `/mock-personenverzeichnis`, `/mock-nect`, ADR-31).

Auf keinen dieser Endpunkte darf sich ein App-Client verlassen. Stünden sie im eingefrorenen Stand,
würde der Kompatibilitätsvergleich ihr späteres Entfernen als Bruch des App-Vertrags melden.
Beschrieben sind sie trotzdem, in der Datei ihres Moduls unter `api/modules/`.

**Versionierung.** Es gibt eine gemeinsame Version für alles, weil das Antwortformat
`ChannelResponse` in jeder Antwort jedes Moduls steckt; eine inkompatible Änderung daran trifft alle
Endpunkte zugleich. Die meisten Änderungen fügen nur etwas hinzu und brauchen keine neue Version.
Dafür prüft `checkPublishedApiCompatibility` in der CI jede Änderung gegen `api/published/v1.yaml`
(openapi-diff) und schlägt bei einem Bruch fehl. Einen bewusst neuen Stand übernimmt man mit
`./gradlew publishApiVersion`; der Diff dieser Datei im PR zeigt, dass eine veröffentlichte Version
geändert wird.

Beide YAML-Dateien entstehen im selben Testlauf aus derselben laufenden Anwendung
(`OpenApiSnapshotTest`) und können deshalb nicht auseinanderlaufen. Die Moduldateien sind keine
zweite Quelle, sondern ein Ausschnitt: Eine Änderung an einem SMS-Endpunkt steht in
`api/modules/auth_sms.yaml` (knapp 500 Zeilen) statt irgendwo in 4700 Zeilen. Die Gruppen leitet
`ModuleApiGroups` aus den vorhandenen `@RestController` ab, nicht aus einer gepflegten Liste.

Ein Schema, das mehr als ein Modul nutzt und das im App-Vertrag steht (das Antwortformat
`ChannelResponse` mit allem, was dazugehört, und `ErrorResponse`), steht nur in `api/openapi.yaml`.
Die Moduldateien verweisen mit `../openapi.yaml#/components/schemas/…` darauf. Sonst enthielte jede
Moduldatei dieselben rund 360 Zeilen, und eine Änderung am Antwortformat erschiene als zehn Diffs.
Auch das ergibt sich von selbst, nicht aus einer Liste: Was nur ein Modul nutzt, bleibt in dessen
Datei. Der Preis: `StepData` zeigt in der Moduldatei alle möglichen Formen, nicht nur die dieses
Moduls.

`api/openapi.yaml` bleibt trotzdem eine einzige Datei. Ein aufgeteilter Vertrag, der die
Moduldateien per `$ref` einbindet, wurde ausprobiert. Unter OpenAPI 3.1 setzt swagger-parser
externe Verweise aber direkt ein; beide Generatoren verlieren dann alle Modellnamen und die
Zuordnung der Unterscheidungsmerkmale (Diskriminator-Mapping), und openapi-diff vergleicht
aufgeteilte Dateien nicht verlässlich.

Was sonst noch dazugehört:

- **Der Test vergleicht, er beschreibt nicht.** Ändert sich eine Antwort, ohne dass die Snapshots
  nachgezogen sind, schlägt er fehl. Übernommen wird die Änderung mit
  `./gradlew updateOpenApiSnapshot`, danach `./gradlew generateFrontendApiTypes`.
- **`frontend/src/types.ts` leitet ab, statt nachzubauen.** Von Hand steht dort nur noch, was das
  Backend als offene Map liefert: die benannten Werte im `demo`-Block und die Formen der Tokens.
  Dazu kommt eine Ergänzung zur erzeugten Menge der `stepData`-Formen: `UnknownStepData` für eine
  Form, die dieser Build noch nicht kennt, und `stepDataOf(stepData, kind)` als einziger Weg, sie zu
  lesen.
- **Pflichtfelder stehen im Schema.** springdoc übernimmt Kotlins Nicht-null-Typen nicht von
  selbst. Das erledigt `KotlinRequiredModelConverter`: Eine Property, die nicht `null` sein kann
  und keinen Standardwert hat, wird `required`.
- **YAML statt JSON**, weil die Dateien in Diffs gelesen werden: keine Anführungszeichen, keine
  Klammern, und lange Beschreibungen stehen als umbrochener Text statt in einer endlosen Zeile.

---

### Texte (`GET /orchestrator/api/v1/texts/{lang}`)

Keine Antwort enthält fertigen Wortlaut. Wo Menschen etwas lesen (`ErrorResponse.text`,
`FailedAttemptStep.error`, `MessageStep.message`, `SelectMethodStep.title/description`, `Prompt.*`,
`JourneyDebugStep.note`), steht eine Text-Referenz:
`{ "key": "3f9a1c0b2e7d", "args": {"n": "3"}, "texts": {"grund": [{ "key": … }]} }`. Der Client
schlägt `key` im Bundle nach und setzt die Platzhalter `{name}` ein: aus `args` unverändert, aus
`texts` nachdem er sie selbst aufgelöst hat (mehrere mit „, " verbunden). Eine unbekannte ID zeigt
er so, wie sie ist.

Das Bundle holt der Client beim Start: `GET /orchestrator/api/v1/texts/{lang}` (ohne DPoP; `de` oder
`en`, sonst `de`; `Content-Language` nennt die gelieferte Sprache) liefert `{ id: Wortlaut }` mit
ETag. Beim nächsten Start schickt der Client `If-None-Match`; 304 heißt „unverändert“. Hintergrund
und Pflege der Texte: ADR-33.

Jeder Stellvertreter eines Fremdsystems liefert seine eigenen Texte auf dieselbe Weise:
`GET /mock-*/texts/{lang}` (`/mock-kobil`, `/mock-personenverzeichnis`, `/mock-nect`), ebenfalls mit
ETag und 304.

## 2) App-Zugang (der Orchestrator führt)

Alle Anfragen enthalten den Header `DPoP: <proof>`.

- Die öffentliche App-API kennt nur die `channelSessionId`. Intern steht hinter dem Kanal die
  laufende `AuthJourney` mit ihrer `journeyId`; sie dient dem Speichern, dem Zusammenführen von
  Protokolleinträgen und der Revision. Der Client gibt sie nie vor.
- Welcher Ablauf fachlich läuft, entscheidet das Backend anhand von Kanalzustand, Kontostatus und
  Regeln. Beschrieben wird das durch den `AuthIntent` und seine Strategie, nicht durch einen
  eigenen Typ wie `REGISTRATION` oder `LOGIN`.
- Das `next`-Objekt ist eine reine Adresse und enthält nie Inhalte:
  - Tool-Schritt: `{ "type": "tool", "toolId": "...", "step": "...", "toolSessionId": "..." }`
  - Seite des Orchestrators (Auswahl, Bestätigung, Abschluss):
    `{ "type": "orchestrator", "context": "...", "step": "..." }`

  `toolSessionId` adressiert die Tool-Ressource vollständig (`/tools/{toolSessionId}/{toolId}`). Sie
  ist gesetzt, sobald es für diesen Schritt eine `ToolSession` gibt – auch beim Fortsetzen
  (`GET /channels/{channelSessionId}`) mitten in einem laufenden Tool.
- **Ein Antwortformat für alle Endpunkte** (`ChannelResponse`):
  `{ "channel": {channelSessionId, channelType, state, currentAcr, currentAmr, activeMethods}, "next": {...}, "stepData": {...}, "demo": {...} }`.
  `channelSessionId`, `channelType` und `state` stehen in jeder Antwort. `currentAcr`, `currentAmr`
  und `activeMethods` stehen dagegen NIE in Antworten eines Tools (`POST .../tools/{toolId}` sowie
  `PATCH`/`GET`/`DELETE` auf `/tools/...`), sondern nur bei den Endpunkten des Kanals
  (`GET`/`POST /channels`, `step-ups`, `enrollments`, `DELETE .../methods/{methodInstanceId}`).
- **Werte von `channel.state`** – alles, was der Client aus `state` ablesen kann, ohne einen
  Endpunkt aufzurufen:

  | Wert | Bedeutung für den Client |
  |---|---|
  | `ANONYMOUS` | Kanal offen, noch kein Konto bekannt – `next` zeigt auf `ident-fsc` oder den Login |
  | `REGISTERING` | Die Registrierung läuft; `DELETE .../journey` (Abbruch) führt zurück auf `ANONYMOUS` |
  | `AUTHENTICATED` | Konto bekannt und aktuelles Niveau ausreichend; `logout`, `methods` und `enrollments` sind verfügbar |
  | `STEP_UP_REQUIRED` / `STEP_UP_IN_PROGRESS` | Ein höheres Niveau ist nötig bzw. der Nachweis läuft schon; `next` zeigt den fälligen Schritt, ein Abbruch führt direkt zurück auf `AUTHENTICATED` |
  | `LOGGED_OUT` | Endzustand – dieser Kanal ist beendet, `next` fehlt; für einen neuen Kanal braucht es einen neuen `POST /channels` |
  | `EXPIRED` | Endzustand (Lebensdauer abgelaufen) – aus Sicht des Clients wie `LOGGED_OUT` |

  Zustandsdiagramm: [Domänenmodell](02-domaenenmodell.md) Abschnitt 3.
- Auswahlmöglichkeiten stehen nicht in `next`, sondern in `stepData.options`, und zwar als
  vollständige `toolId`-Werte (z. B. `enroll-sms`). Der Client darf eine `toolId` nie selbst
  zusammensetzen; sie kommt immer aus `next.toolId` oder `stepData.options`.
- Ist genau ein Verfahren erlaubt, überspringt das Backend die Auswahlseite und liefert direkt den
  Tool-Schritt.
- `stepData` enthält, was der aktuelle Schritt zum Anzeigen braucht: den Zustand innerhalb des
  Tools (z. B. `missingFields`), die erlaubten nächsten Tools (`options`) und nach einem
  fehlgeschlagenen Versuch den Grund (`error`). Gibt es nichts davon, fehlt das Feld.
- Jede `stepData` nennt ihre Form im Feld `kind` (`select-method`, `missing-fields`,
  `failed-attempt`, `confirm`, `message`, dazu die Formen der Module wie `qr-pairing` oder
  `kobil-otp`). Welche Formen es gibt, steht im `discriminator.mapping` von `StepData` in der Spec.
  Jede Form führt `kind` selbst als Pflichtfeld mit genau ihrem Wert (ein `enum` mit einem einzigen
  Wert); `StepData` selbst ist nur noch `oneOf` plus Unterscheidungsmerkmal, ohne eigene
  Properties. Jedes Modul deklariert seine Formen selbst; eine zentrale Liste gibt es nicht. Ein
  Client muss mit einem unbekannten `kind` rechnen und es überspringen, statt abzubrechen.
- Das Unterscheidungsmerkmal heißt in allen übertragenen Daten `kind`, auch bei `Prompt` und
  `KobilUnlockCredential`. `@t` gibt es nur noch intern für die gespeicherten Zustände der
  Journeys: Der TypeScript-Generator kann diesen Namen nicht abbilden und hatte daraus `t`
  gemacht.

Pfade:

- Einstieg: `POST /orchestrator/api/v1/app/channels` – `201` mit
  `Location: .../channels/{channelSessionId}`. Es entsteht immer eine neue Ressource, nie wird eine
  bestehende fortgesetzt; die Location zeigt auf die Kanal-Ressource, die für beide Zugänge gleich
  ist (Abschnitt 3).
- Kanalzustand lesen: `GET /orchestrator/api/v1/channels/{channelSessionId}`
- Niveau anheben (löst einen Step-up aus): `POST .../{channelSessionId}/step-ups` mit
  `{"requiredAcr": "..."}`
- Journey abbrechen: `DELETE .../{channelSessionId}/journey` (ohne Inhalt)
- Abmelden mit Bestätigung (startet eine Journey): `POST .../{channelSessionId}/logouts` (ohne
  Inhalt)
- Sofort abmelden: `DELETE .../{channelSessionId}` (ohne Inhalt)
- Eingerichtete Verfahren lesen: `GET .../{channelSessionId}/methods`
- Verfahren hinzufügen (startet das Einrichten): `POST .../{channelSessionId}/enrollments` (ohne
  Inhalt)
- Verfahren deaktivieren: `DELETE .../{channelSessionId}/methods/{methodInstanceId}` (ohne Inhalt)
  – adressiert über die ID des Eintrags, nicht über den Namen der Methode (siehe unten)
- Konto löschen (startet eine Journey): `POST .../{channelSessionId}/account-deletions` (ohne
  Inhalt)
- Rückfrage beantworten: `POST .../{channelSessionId}/answer` mit `{"answer": "accept"|"decline"}` –
  der gemeinsame Endpunkt für jeden `Prompt` (siehe unten)
- Tool über den Kanal anlegen: `POST .../{channelSessionId}/tools/{toolId}` – `201` mit
  `Location: .../tools/{toolSessionId}/{toolId}` (ohne Inhalt; die `toolId` trägt Art und Methode
  zusammen)
- Tool fortschreiben und lesen: im Regelfall `PATCH`/`GET /orchestrator/api/v1/tools/{toolSessionId}/{toolId}`
- Zurück zur Auswahl: `POST /orchestrator/api/v1/tools/{toolSessionId}/{toolId}/back`
- Tool-Versuch verwerfen (Verfahren ablehnen): `DELETE /orchestrator/api/v1/tools/{toolSessionId}/{toolId}`
- Tool-Katalog: `GET /orchestrator/api/v1/tools/catalog` (ohne DPoP, ohne Kanal) –
  `{toolId, method, role}` je Tool; daraus bildet der Client seine `availableTools`.

URL-Bereich der Tools:

- Das Anlegen eines Tools bleibt Sache des Orchestrators und ist für alle Tools gleich; nur dort
  entsteht die `toolSessionId`.
- Alles unterhalb von `/tools/{toolSessionId}/{toolId}` gestaltet das Tool selbst: eigene
  Unterressourcen und frei gewählte HTTP-Methoden. `PATCH`/`GET` sind der Regelfall, keine Pflicht
  – nicht jedes Verfahren passt zu „Felder nachliefern" (WebAuthn, eID).
- Bisher nutzt das genau ein Tool: `POST .../tools/{toolSessionId}/auth-kobil/pin-releases` gibt den
  vom Backend verwahrten KOBIL-PIN heraus ([Abläufe](06-ablaeufe.md) Abschnitt 7). Warum das kein
  zusätzliches Feld im `PATCH` ist:
  - Der PIN darf **nicht erneut abrufbar** sein, und `stepData` wird bei jedem `GET` auf eine noch
    laufende Tool-Sitzung neu aufgebaut; was dort steht, käme also wieder heraus.
  - Eine Herausgabe erzeugt etwas Neues: Sie ist einmalig, befristet und nicht mit gleichem
    Ergebnis wiederholbar.
  - Zwei verschiedene Vorgänge trennt eine URL sauberer als die Frage, welche optionalen Felder
    gerade gesetzt sind.

  Der Inhalt der Anfrage ist ein typisiertes Entweder-oder (Gerätegeheimnis **oder** Passwort des
  Kontos). Die Antwort ist `201` mit dem PIN in `stepData` oder – wenn das Entsperren fehlschlägt –
  `200` mit dem gewöhnlichen `stepData.error`, denn ein falsches Geheimnis ist ein gewöhnlicher
  Fehlversuch und kein Fehlerstatus.
- Der garantierte Einstieg zum Fortsetzen ist **nicht** die Tool-Ressource (deren `GET` ein Tool
  weglassen darf), sondern `GET /channels/{channelSessionId}`.
- **Hinweis zur Umsetzung:** `POST`, `PATCH` und `GET` liegen je Tool in einem eigenen Controller
  mit typisiertem DTO für die Anfrage ([Tool-Architektur](03-tool-architektur.md) Abschnitt 2).
  `DELETE` ist die einzige Ausnahme.

### Abbruch

`DELETE .../journey` bricht die laufende `AuthJourney` ab und setzt `ChannelSession.state` zurück
([Domänenmodell](02-domaenenmodell.md) Abschnitt 3). Danach startet der Kanal **denselben Intent**
erneut, mit dem er eröffnet wurde. Bricht man `STEP_UP` oder `MANAGE_AUTH_METHODS` ab, lautet die
Antwort direkt `authenticated`. Die Zuordnung zum Konto und der `AuthContext` werden über die
Geräteverknüpfung (`DeviceAccountLink`) neu abgeleitet; ein zuvor per `ident-fsc` angelegtes Konto bleibt bestehen.

Im Web-Kanal ruft „Abbrechen“ auf der Verfahrensauswahl der Keycloak-Anmeldeseite dieses `DELETE`
**nicht** auf, sondern beendet den ganzen Login bei Keycloak (`context.cancelLogin()` in
`OrchestratorAuthenticator`). Keycloak kehrt dann mit `error=access_denied` zur Website zurück, die
„Anmeldung abgebrochen“ anzeigt. Würde derselbe Intent neu starten, landete der Nutzer sonst
wieder auf derselben Seite, und eine Registrierung würde endlos neu beginnen. Der verlassene
KEYCLOAK-Kanal läuft nach seiner Lebensdauer von selbst ab.

### Logout

Zwei Varianten:

- **Abmelden mit Bestätigung** (bevorzugt): `POST .../{channelSessionId}/logouts` startet eine
  `LOGOUT`-Journey mit einer Rückfrage ([Orchestrierung](04-orchestrierung.md) Abschnitt 3). Nach
  Zustimmung über `POST .../answer` wird der Kanal `LOGGED_OUT`.
- **Sofort abmelden** (für Clients ohne Rückfrage): `DELETE /channels/{channelSessionId}` beendet
  den Kanal direkt (`AUTHENTICATED → LOGGED_OUT`, Endzustand, `204`), bricht einen laufenden Ablauf
  ab und verwirft den `AuthContext`.

Die Geräteverknüpfung bleibt nutzbar (`DeviceAccountLink`, [DPoP-Bindung](09-dpop.md) Abschnitt 3).

### AccessToken (`GET .../{channelSessionId}/token`)

**Nur im `APP`-Kanal** (DPoP-demo-xso, ADR-9): Ein `KEYCLOAK`-Kanal hat nie einen `AuthContext`
und braucht auch keinen. `ChannelService.getToken` prüft deshalb zuerst den Kanaltyp und weist einen
`KEYCLOAK`-Kanal mit `409 INVALID_STATE_TRANSITION` ab. Für `APP` entscheidet `TokenProvider` je
nach Profil:

- **Standardprofil** (`MockTokenProvider`): liefert das Mock-JWT aus `TokenService`
  (`alg=none`, `iss=mock-keycloak`).
- **Profil `keycloak`** (`KcTokenProvider`): liefert ein echtes, von Keycloak signiertes
  AccessToken mit echten Claims `acr` und `amr` (über `OrchestratorAcrAmrMapper`, wie im
  Web-Kanal). Es gibt drei Fälle, aufgebaut wie `TokenService.tokenFor`:
  1. Das Token ist noch lange genug gültig: Es wird unverändert zurückgegeben.
  2. Es läuft bald ab, ACR und AMR sind unverändert: Erneuerung über Keycloaks
     `refresh_token`-Grant, ohne den privaten Schlüssel des Kontos.
  3. Es gibt kein gültiges RefreshToken mehr (erste Ausstellung, oder ein Step-up hat den
     Zwischenspeicher verworfen): Eine frische, signierte Assertion geht an den eigenen
     OAuth2-Grant (`urn:dpop-demo:account-token`, `AccountTokenGrantType` in
     `keycloak-extension`, ADR-9). Signiert wird sie mit dem privaten Schlüssel aus
     `orchestrator.keycloak_keypair`; sie trägt `acr` und `amr` selbst als Claims.

  Alle Aufrufe für dasselbe Konto teilen sich dieselbe Keycloak-Sitzung.

`minValiditySeconds` wirkt in beiden Profilen gleich. Ausnahme: Ein Step-up, der die `AuthEvidence`
verändert (`AuthEvidenceService.applyEvidence`/`applyEvidenceUpdate`), verwirft das
zwischengespeicherte Token ausdrücklich.

### ID-Token-Claims (`GET .../{channelSessionId}/idclaims`)

**Nur im `APP`-Kanal**, wie das AccessToken oben; es gilt dieselbe Vorbedingung, und ein
`KEYCLOAK`-Kanal bekommt ebenso `409 INVALID_STATE_TRANSITION`.

Die fachlichen Claims (nicht in die Signatur des AccessTokens eingebaut) sind:
`sub`/`accountId`/`personId`/`versnr`, `acr`/`amr`, `auth_time`, `email`/`email_verified` und
`name`. `personId` ist die Partnernummer (`P` und neun Ziffern, ADR-34), `versnr` die
Versicherungsnummer; beide kommen live aus dem Personenverzeichnis.

`name` ist die einzige Stelle, an der das Frontend erfährt, WER angemeldet ist. Es entscheidet der
`PERSON_ID`-Anker:

- Ist `personId` vorhanden, ist `name` „Vorname Name" der Person (`PersonDirectory.displayName`).
- Fehlt `personId` (Interessent, ADR-10/18), nimmt `name` die eigenen bestätigten Claims des
  Kontos: den stärksten noch gültigen NAME/VORNAME-Claim. `null` ist `name` nur, wenn es auch davon
  keinen gibt.

Aus `personId` und `versnr` leitet das Frontend die Rolle ab (ADR-34): Mit `versnr` ist es ein
Versicherter, nur mit `personId` ein Partner, mit keinem von beiden ein Interessent. Ein eigener
Claim für die Rolle würde dieselben Werte nur doppelt ausdrücken. Es gilt derselbe
`channelAccessGuard` wie überall sonst. Die Seite nach erfolgreicher Anmeldung zeigt beides knapp
im Begrüßungstext – „Angemeldet als *Name* (Versicherter/Partner/Interessent)" – und die
vollständigen Claims zum Aufklappen, wie die Details des AccessTokens.

### Methoden verwalten (AuthIntent.MANAGE_AUTH_METHODS)

Freiwillige Verwaltung des eigenen Kontos auf einem Kanal, der bereits `AUTHENTICATED` ist,
unabhängig von `REGISTER` und `STEP_UP` ([Orchestrierung](04-orchestrierung.md) Abschnitt 3).

- `GET .../methods` liest die aktiven Verfahren als eigene Liste
  (`{"methods": [{"id","method","label",…}]}`). Es sind dieselben Daten wie in
  `ChannelResponse.activeMethods`, nie mit `fsc`. Solange auf diesem Kanal noch kein Faktor
  nachgewiesen ist (`hasProvenFactor`), kommt eine leere Liste statt eines Fehlers; ein nur
  wiedererkanntes Gerät bekommt also nichts.
- `POST .../enrollments` bietet dieselben Kandidaten zum Einrichten an wie `REGISTER` und startet
  das Einrichten. Gibt es nichts mehr einzurichten, ist das kein Fehler: `200`, und `next` zeigt
  direkt auf `authenticated`, ohne `stepData`.
- `DELETE .../methods/{methodInstanceId}` widerruft einen aktiven *Eintrag* eines Verfahrens: Die
  Credential-Zeile des Moduls, dem sie gehört, wird gelöscht (`EnrollmentCleanup`); der Eintrag
  selbst bleibt deaktiviert stehen. Adressiert wird er über die `id` aus `GET .../methods`, nie über
  den Namen der Methode, denn eine Methode kann mehrere aktive Einträge haben
  (`docs/03-tool-architektur.md`, `allowsMultipleInstances`). Die Antwort ist `409`, wenn das Konto
  danach das `requiredAcr` des Kanals nicht mehr erreichen könnte – sonst könnte sich jemand selbst
  aussperren. Das Widerrufen ist nicht auf Einträge des aufrufenden Geräts beschränkt.
- `DELETE .../attributes/{attribute}` nimmt ein **Attribut des Kontos** zurück statt eines
  Credentials; das ist nur die bestätigte E-Mail-Adresse (`email`). Welches Attribut der Inhaber
  selbst zurücknehmen darf, sagt `AnchorRule.retractableByHolder`; Identitätsanker (`person_id`,
  `versnr`, die Karten-Pseudonyme) sind es nicht und werden mit 409 abgelehnt. Es ist das Gegenstück zu
  `DELETE .../methods/{id}` und durchläuft dieselbe Prüfung. Der Unterschied liegt in den Folgen:
  Jedes Credential, das dieses Attribut per `requires` verlangt hat, wird mit entzogen, und zwar
  über alle Stufen hinweg. Eine zurückgenommene Adresse nimmt also ein darauf eingerichtetes
  Passwort mit (`enroll-password` verlangt `ClaimRequirement(EMAIL, PROVEN)`) und alles, was
  seinerseits daran hängt (ADR-24). Die Antwort ist `409`, wenn genau diese Folgen das Konto unter
  das `requiredAcr` des Kanals drücken würden; die Meldung nennt, was dabei mitfallen würde.
  Zurücknehmen lassen sich nur Attribute, die dem Konto selbst gehören: Ein Stammdatenfeld gehört
  nicht uns, und ein Attribut eines Verfahrens verschwindet mit diesem Verfahren.
- `POST .../enrollments`, `DELETE .../methods/{methodInstanceId}` und
  `DELETE .../attributes/{attribute}` verlangen zusätzlich, dass die aktuelle Sitzung die Schwelle
  `selfServiceAcrFloor` erreicht hat (loa2, für ein nie identifiziertes Konto loa1). Reicht das
  nicht, enthält die Antwort statt der Aktion einen Step-up-Schritt; danach ruft der Client den
  Endpunkt erneut auf.

### Das `Prompt`-Objekt

Jeder `JourneyState`, der auf eine ausdrückliche Ja/Nein-Antwort statt auf ein Tool wartet
(`AnswerableState`), trägt einen `prompt` in `stepData` (Form `confirm`):
`{"kind": "Confirm", "title": "...", "description": "...", "confirmLabel": "...", "cancelLabel": "...", "destructive": true|false}`.
`next` hat für **jeden** `AnswerableState`, egal zu welchem Intent, denselben festen Wert:
`{"type":"orchestrator","context":"prompt","step":"confirm"}`. Beantwortet wird jeder `Prompt` über
denselben gemeinsamen Endpunkt `POST .../{channelSessionId}/answer` mit
`{"answer": "accept"|"decline"}`.

Den ganzen Text jeder Rückfrage liefert **das Backend**; der Client formuliert nie selbst. Eine
geänderte Rückfrage braucht so keine neue App-Version.

`Prompt` ist ein `sealed interface` mit `kind` als Unterscheidungsmerkmal; `Confirm` ist die einzige
Variante. Eine weitere (z. B. eine Auswahl unter mehreren Antworten) ist denkbar, aber nicht
angelegt. Verwendet wird es für: die Frage nach der Geräteverknüpfung bei der Anmeldung über die
E-Mail-Adresse, die Bestätigung beim Löschen des Kontos, die Bestätigung beim Abmelden und
`ReIdentifyState.OfferReIdent` vor der Sub-Journey `RE_IDENTIFY`
([Orchestrierung](04-orchestrierung.md)).

### Konto löschen (AuthIntent.DELETE_ACCOUNT)

Das eigene Konto selbst löschen, auf einem Kanal, der bereits `AUTHENTICATED` ist. Die
Ja/Nein-Bestätigung kommt **immer zuerst und ohne Bedingung**. Erst nach der Zustimmung gilt
dieselbe Schwelle `selfServiceAcrFloor` wie vor `MANAGE_AUTH_METHODS` (loa2, für ein nie
identifiziertes Konto nur loa1, [Orchestrierung](04-orchestrierung.md) Abschnitt 3):

1. `POST .../{channelSessionId}/account-deletions` (ohne Inhalt) startet die Journey und liefert
   sofort die Rückfrage: `next={"type":"orchestrator","context":"prompt","step":"confirm"}`,
   `stepData.prompt` mit `destructive: true`.
2. `POST .../answer` mit `{"answer":"accept"}`. (`"decline"` bricht wie ein gewöhnlicher Abbruch
   zurück auf `AUTHENTICATED` ab.) Erst jetzt wird die Schwelle geprüft: Reicht das aktuelle
   Niveau nicht, liefert die Antwort einen Step-up-Schritt; danach ruft der Client
   `account-deletions` erneut auf.
3. Reichte das Niveau schon vorher (der Nachweis ist also unbekannt alt), folgt ein frischer
   Nachweis über ein **beliebiges** aktives `auth-*`-Verfahren des Kontos, unabhängig davon, welches
   Niveau es erreicht. Auch ein gerade erst nachgewiesener Faktor zählt dabei erneut – anders als
   bei `STEP_UP`. Genau ein Verfahren genügt; bei mehreren kommt dieselbe Auswahlseite
   `next={"context":"auth","step":"selectMethod"}`. **Ausnahme**: Musste in Schritt 2 erst ein
   Step-up stattfinden, zählt dessen Nachweis bereits als der hier geforderte.
4. Nach erfolgreichem Nachweis wird das Konto mit allem, was nur ihm gehört, unwiderruflich
   gelöscht: alle Credential-Datensätze der Methodenmodule, auf die seine Verfahren verweisen
   (aktive **und** abgelöste), der `DeviceAccountLink`, jeder `AuthContext` und die Zeile in
   `account` selbst. Die `person` im Personenverzeichnis (`ext_personenverzeichnis`) bleibt
   unberührt ([Tool-Architektur](03-tool-architektur.md), `EnrollmentCleanup`).
5. Jede `ChannelSession`, die je an dieses Konto gebunden war, wird auf dem Server auf
   `LOGGED_OUT` gesetzt; ein anderes angemeldetes Gerät braucht einen neuen `POST /channels`.
6. Nach dem erfolgreichen Abschluss lautet die Antwort `channel.state="LOGGED_OUT"` ohne `next` –
   dieselbe Form wie bei einem normalen Logout.

### Zurück und Verfahren wechseln

Einen gestarteten, aber noch nicht abgeschlossenen Versuch eines Tools verlässt der Client auf
einem von zwei Wegen. In beiden Fällen ist die `toolSessionId` sofort ungültig.

- **Zurück** (`POST /tools/{toolSessionId}/{toolId}/back`): Das Tool endet, ohne abgelehnt zu
  werden. Die Journey zeigt ihre Auswahlseite wieder, mit allen Verfahren, die sie gerade anbietet
  – das verlassene eingeschlossen, und auch dann, wenn nur eines übrig ist: Wer zurückgeht, will
  wählen, nicht sofort wieder im selben Tool landen. Die Strategie wird nicht gefragt, es ist nichts
  passiert, das sie bewerten müsste. Hat der Zustand keine Auswahlseite (ein einzelnes bevorzugtes
  Verfahren, eine überspringbare Zuordnung), ist Zurück dasselbe wie Ablehnen. Im Web-Kanal ist das
  jeder „Zurück“-Knopf einer Tool-Seite (`orchestrator_back`), in der App der „Zurück“-Knopf der
  Fußleiste.
- **Ablehnen** (`DELETE /tools/{toolSessionId}/{toolId}`): Das Verfahren gilt in diesem Zustand
  als abgelehnt. Die Journey ermittelt die Kandidaten erneut, genau wie nach dem letzten
  `Completed`: In einem Ausweichzustand geht es zum nächsten Weg, bleibt dabei nur ein Verfahren,
  startet es von selbst; in einem Pflichtzustand kommt die volle Auswahl zurück. Im Web-Kanal ist
  das „Abbrechen“ auf den QR-Seiten (`orchestrator_abandon`), in der App der Ausweg, den ein Tool
  selbst anbietet (etwa „Jetzt nicht“ bei der Versichertennummer).

Vor der Trennung hieß Ablehnen im Web „Zurück“ und in der App „Anderes Verfahren“: Wer bei der
Registrierung vom Freischaltcode zurückging, landete ohne Auswahl im Online-Ausweis, weil nur
dieser übrig blieb.

Regel für die Konsistenz: Je `channelSessionId` läuft höchstens eine Journey aktiv; welche das ist,
entscheidet das Backend.

### Ein Tool-Durchlauf als Beispiel

Registrierung mit `ident-fsc` -> `enroll-sms`:

1. `POST /app/channels` (`{"requiredAcr": "loa2", "availableTools": ["ident-fsc", "enroll-sms", ...]}`;
   ohne `intent` gilt `fast_access`; `availableTools` ist Pflicht, siehe unten) liefert eine neue
   `channelSessionId` und gleich den ersten Schritt:
   `next={"type":"tool","toolId":"ident-fsc","step":"input"}`. Es gibt nur ein `IDENT`-Verfahren,
   also keine Auswahl, und noch keine `ToolSession`, also keine `toolSessionId`. Enthält
   `availableTools` auch `ident-eid`, liefert derselbe `POST` eine Auswahl:
   `next={"type":"orchestrator","context":"registration","step":"selectIdentificationMethod"}`,
   `stepData={"kind":"select-method","options":["ident-fsc","ident-eid"]}`.
2. `POST .../tools/ident-fsc` (ohne Inhalt) legt die Tool-Ressource an: `201` mit
   `stepData={"kind":"missing-fields","missingFields":["kvnr","name","vorname","geburtsdatum"]}`
   und gesetzter `next.toolSessionId`. Nach `fsc` fragt das Tool erst, wenn diese Angaben zum
   Personenverzeichnis passen.
3. `PATCH /tools/{toolSessionId}/ident-fsc` mit den Feldern, zuletzt dem Freischaltcode. Solange
   Felder fehlen, kommt `200` mit aktualisiertem `stepData.missingFields`, und `next` zeigt weiter
   auf `ident-fsc`. Nach erfolgreicher Prüfung:
   `stepData={"kind":"select-method","options":["enroll-sms"]}`,
   `next={"type":"orchestrator","context":"enrollment","step":"selectMethod"}`.
4. `POST .../tools/enroll-sms` liefert
   `stepData={"kind":"missing-fields","missingFields":["phoneNumber"]}`.
5. `PATCH .../enroll-sms` mit `{"phoneNumber": "..."}` löst den Versand der TAN aus:
   `stepData={"kind":"missing-fields","missingFields":["tan"]}` plus (siehe unten)
   `demo={"tan":"123456"}`.
6. `PATCH .../enroll-sms` mit `{"tan": "123456"}` schließt ab:
   `next={"type":"orchestrator","context":"authentication","step":"authenticated"}`. `channel.state`
   ist in derselben Antwort schon `"AUTHENTICATED"`; ein eigener `GET` ist nicht nötig.
7. `GET /channels/{channelSessionId}` liefert jederzeit den stabilen Kanalzustand. Das ist der
   garantierte Einstieg zum Fortsetzen, auch mitten in einem laufenden Tool samt dessen
   `toolSessionId`.

Jedes weitere Tool (`enroll-password`/`auth-password`, `confirm-email`, `enroll-email`/`auth-email`,
die `-lookup`-Varianten) folgt demselben Muster aus `POST`, `PATCH` und `GET`; die Abweichungen der
einzelnen Tools stehen unten.

### Das `demo`-Objekt

Jede Antwort kann zusätzlich ein klar gekennzeichnetes `demo`-Objekt enthalten. Es ist **kein Teil
des produktiven Vertrags** und in einer echten Umgebung abgeschaltet. Es enthält `accountId` und
`personId` sowie je nach Tool `tan` (die gerade ausgestellte TAN bzw. den Code) oder
`password`/`email` (feste Demo-Werte zum Vorbelegen von Formularen). Tools liefern ihre Demo-Werte
im eigenen Feld `ToolOutcome.InProgress.demo`, getrennt von `stepData`.

### `POST /app/channels`: Parameter `intent`

`intent` (optional, standardmäßig `fast_access`) ist der Name eines `AuthIntent`-Werts; Groß- und
Kleinschreibung spielen keine Rolle (`AuthIntent.fromRequest`), unbekannte Werte werden abgelehnt.
Er bestimmt, welcher Ablauf auf DIESEM Kanal startet, unabhängig davon, ob `DeviceAccountLink` das
Gerät erkennt:

- `fast_access` (Standard, auch ohne `intent`): Gibt es einen `DeviceAccountLink`, geht es auf den
  Weg zur Anmeldung mit schon bekanntem Konto, sonst in die Sub-Journey `REGISTER`.
- `lookup_login`: erzwingt die Anmeldung über die E-Mail-Adresse (E-Mail-Adresse plus Credential, siehe
  unten), auch auf einem schon verknüpften Gerät; die Verknüpfung wird für diesen Kanal nicht
  nachgeschlagen.
- `register`: erzwingt eine frische `REGISTER`-Journey, auch auf einem schon verknüpften Gerät
  (für ein zweites Konto). Führt die neue Identifizierung zu einem anderen Konto als dem bisher
  verknüpften, fragt der Kanal noch vor jeder Auswahl eines Verfahrens nach
  (`RegisterState.ConfirmDeviceRebind`), ob die bestehende Geräteverknüpfung (`DeviceAccountLink`)
  ersetzt werden soll. Bei Zustimmung wird das Gerät neu verknüpft, und das bisherige Geräte-Credential des
  alten Kontos (`enroll-device`) für genau diesen Schlüssel wird deaktiviert bzw. gelöscht. Bei
  Ablehnung endet die Journey regulär (kein Fehler, wie bei `DELETE .../journey`), und die alte
  Verknüpfung bleibt bestehen.
- `confirm_peer_login`: startet `AuthIntent.CONFIRM_PEER_LOGIN`, also das Bestätigen oder Ablehnen
  eines wartenden Web-Logins (`auth-qr`/`auth-qr-lookup`; siehe unten „Peer-Login bestätigen").
  Das geht auch von einem noch nicht angemeldeten Kanal aus, weicht aber nie auf Identifizierung
  oder Registrierung aus.

`requiredAcr` (optional) erspart den Umweg über ein niedriges Einstiegsniveau mit anschließendem
Step-up. Das Backend rechnet mit `max(Policy-Anforderung, Client-Wunsch)`.

`availableTools` (Pflicht) gibt an, welche `toolId`s dieser Client starten kann. Die Menge ist für
die Lebensdauer des Kanals fest. Ein Tool außerhalb dieser Menge wird nie angeboten und auch bei
direktem Aufruf abgelehnt (`docs/03-tool-architektur.md`, Verfügbarkeit). Zusätzlich kann der
Betreiber jedes Tool je Kanaltyp zur Laufzeit sperren und die Reihenfolge der Angebote je Kanaltyp
festlegen (`GET /orchestrator/admin/tools/availability`,
`PUT .../tools/{toolId}/availability/{APP|KEYCLOAK}`, `PUT .../tools/order/{APP|KEYCLOAK}`). Das
sind Betriebsendpunkte, nicht Teil des App-Vertrags (ADR-32).

### `GET /channels/{channelSessionId}`

Liest den stabilen Kanalzustand. Neben `state` stehen im `channel`-Block zwei weitere Felder:

- `currentAmr`: was **diese Sitzung** schon nachgewiesen hat (aus der `AuthEvidence` der Sitzung).
- `activeMethods`: alle eingerichteten Verfahren des Kontos als Objekte
  `{id, method, label, factorTypes, maxAcr, enrolledUnderAcr, effectiveAcr}`, unabhängig davon, was
  diese Sitzung geprüft hat. `fsc` ist nie dabei, denn eine Identifizierung steht im Protokoll
  `account.identification`, nicht in `account.auth_method`. `id` adressiert den Eintrag für
  `DELETE`. `label` ist nur bei Methoden gesetzt, die mehrere Einträge haben können (derzeit
  `device` und `kobil`). `auth-device` wird zum Anmelden nur auf dem Gerät mit dem passenden
  Schlüssel angeboten (`docs/04-orchestrierung.md`); deaktivieren lässt es sich von überall.

Beide Felder werden erst gefüllt, sobald auf diesem Kanal ein Faktor nachgewiesen ist
(`hasProvenFactor`); ein nur wiedererkanntes Gerät bekommt sie nicht. `next` ist immer gesetzt,
auch bei abgeschlossener Journey
(`{"type":"orchestrator","context":"authentication","step":"authenticated"}`); ein eigenes Feld
`stepUpRequired` gibt es nicht. Nur bei `LOGGED_OUT` (Endzustand) fehlt `next` ganz.

### Journey-Log

Eine Ansicht zur Fehlersuche und für die Demo, kein Revisionsprotokoll (`SessionEvent`,
[Betrieb](07-betrieb.md) Abschnitt 2). Es gehört nicht zum App-Vertrag: Es gibt das Journey-Log nur
als Betriebsendpunkt `GET /orchestrator/admin/journey-log` (hinter der Admin-Anmeldung, über alle
Konten und Kanäle). Die früheren Varianten je Kanal, `GET /journey-log` (je Gerät) und
`GET /channels/{channelSessionId}/journey-log` (je Konto), sind entfernt. Das war ein bewusster
Bruch von v1; `api/published/v1.yaml` wurde dafür neu festgeschrieben.

### `GET /app/channels/device-link`

Liest nur: ob dieses Gerät (per DPoP-Proof, ohne `channelSessionId`) schon mit einem Konto verknüpft ist
(`DeviceAccountLink`, [Domänenmodell](02-domaenenmodell.md) Abschnitt 1). Es legt **weder** Kanal
noch Journey an. Antwort: `{"linked": true, "accountId": 42, "personName": "Max Muster"}` bzw.
`{"linked": false}`.

Dazu kommt ein Demo-Feld `boundCredentials`: je ein Eintrag `{method, reference}` für jedes an einen
Schlüssel gebundene Credential des verknüpften Kontos, das auf **diesem** Schlüssel liegt. Die
Methode `device` nennt ihren Credential-Schlüssel, `kobil` die Kennung, die der Anbieter diesem
Telefon gegeben hat. Was angezeigt wird, entscheidet jedes Modul selbst
(`ToolDescriptor.instanceDisclosure`, [03-tool-architektur.md](03-tool-architektur.md)); der
Orchestrator kennt dafür keinen einzigen Methodennamen.

Ein fehlender Eintrag sagt dabei genauso viel wie ein vorhandener: Hat ein Client lokale Daten zu
einer Methode, die hier nicht mehr steht, sind diese Daten veraltet. Genau daran erkennt das
KOBIL-Frontend, dass es sein Gerätegeheimnis löschen muss ([09-dpop.md](09-dpop.md) Abschnitt 3).

### `POST /channels/{channelSessionId}/step-ups`: Step-up-Auslöser

Hebt die geforderte Untergrenze des Kanals an (im Web-Zugang derselbe Endpunkt, Abschnitt 3).
Anfrage: `{"requiredAcr": "loa3"}`. Reicht das aktuelle Niveau nicht, startet das Backend eine
`AuthJourney(STEP_UP)` und liefert den fälligen Schritt als `ChannelResponse`; reicht es schon,
zeigt `next` sofort auf `authenticated`. Man kann das Niveau nur anheben; ein niedrigeres
`requiredAcr` wird ignoriert. Reicht kein vorhandenes Verfahren für das geforderte Niveau, könnte
eine erneute Identifizierung (`ident-fsc`/`ident-eid`) es aber allein erreichen, fragt die Journey
zuerst per `stepData.prompt` nach (`context: "prompt", step: "confirm"`). Bei Zustimmung folgt die
Auswahl, bei Ablehnung endet der Step-up ohne Fehler. Nur wenn auch das nicht möglich ist, bricht
die Journey mit `410` ab ([Orchestrierung](04-orchestrierung.md)).

### `confirm-email` / `enroll-email` / `auth-email` / `enroll-password` / `auth-password`

Sie folgen demselben Muster wie `ident-fsc`/`enroll-sms`/`auth-sms` oben, mit diesen Abweichungen:

- `confirm-email` arbeitet mit zwei `PATCH`-Aufrufen (erst `email`, dann `code`) und legt den
  EMAIL-Anker an. `demo.email` steht schon in der Antwort auf Start bzw. Lesen, `demo.tan` erst nach
  dem ersten `PATCH`.
- `enroll-email` hat nur einen Schritt: Schon das Anlegen (`201`) schließt es ab. Es richtet die
  bestätigte Adresse als Anmeldeverfahren ein und setzt sie voraus
  (`requires = { ClaimRequirement(EMAIL, PROVEN) }`).
- `auth-email` braucht nur einen `PATCH` (`code`) und prüft gegen die bekannte, bestätigte
  E-Mail-Adresse des Kontos, nicht gegen eine in der Anfrage übergebene.
- `enroll-password`/`auth-password` erwarten nur `{"password": "..."}` – **keinen** `username`
  ([Tool-Architektur](03-tool-architektur.md) Abschnitt 1). `enroll-password` schließt mit einem
  einzigen `PATCH` ab und setzt eine bereits bestätigte E-Mail-Adresse des Kontos voraus
  (`requires = { ClaimRequirement(EMAIL, PROVEN) }`); fehlt sie, lehnt schon das Anlegen mit `409`
  ab. `demo.password` (ein fester Demo-Wert) steht in jeder `InProgress`-Antwort aller drei
  Passwort-Tools.

### Anmeldung über die E-Mail-Adresse (`auth-sms-lookup` / `auth-password-lookup` / `auth-email-lookup`, „Login ohne DPoP")

Nur erreichbar über `POST /channels` mit `intent: "lookup_login"`, nie über die normale Auswahl der
Kandidaten (`MethodRole.LOOKUP_AUTH`; `AuthPolicy.candidateTools` wählt ausschließlich
`IDENTIFIED_AUTH`). Diese Tools finden das Konto selbst über die eingegebene E-Mail-Adresse:

- `auth-sms-lookup` und `auth-email-lookup` arbeiten mit zwei `PATCH`-Aufrufen: erst
  `{"email": "..."}` (findet das Konto und verschickt bei Erfolg TAN bzw. Code), dann
  `{"tan"/"code": "..."}`.
- `auth-password-lookup` erwartet `{"email": "...", "password": "..."}` in einem einzigen `PATCH`.
- Schutz vor dem Ausforschen von Adressen: Eine unbekannte oder unbestätigte E-Mail-Adresse verhält
  sich in Form und Antwortzeit genauso wie ein gefundenes Konto mit falschem Credential.
  `demo.email` und `demo.password` sind feste Werte, unabhängig vom gefundenen Konto.
- Bei Erfolg ist der Ablauf nicht immer sofort zu Ende. Ist dieses Gerät noch keinem oder schon
  demselben Konto zugeordnet, bietet der Orchestrator die Geräteverknüpfung optional an. Ist es mit
  einem anderen Konto verknüpft, fragt er ausdrücklich nach, bevor er diese Verknüpfung in
  `DeviceAccountLink` überschreibt. Bei Ablehnung endet der Login trotzdem erfolgreich, nur ohne
  neue Verknüpfung; bei Zustimmung wird das Gerät neu verknüpft.

### Peer-Login bestätigen (AuthIntent.CONFIRM_PEER_LOGIN)

Ein App-Kanal bestätigt oder lehnt einen wartenden Web-Login ab, den ein `auth-qr` bzw.
`auth-qr-lookup` im Web-Kanal angestoßen hat ([Orchestrierung](04-orchestrierung.md)
`CONFIRM_PEER_LOGIN`). Es gibt zwei gleichwertige Einstiege:

- `POST /app/channels` mit `{"intent":"confirm_peer_login"}` – Einstieg ohne bestehende Sitzung,
  siehe Parameter `intent` oben.
- `POST /channels/{channelSessionId}/peer-logins` (ohne Inhalt) – auf einem Kanal, der bereits
  `AUTHENTICATED` ist.

Beide durchlaufen dieselbe Prüfung:

1. Über `DeviceAccountLink` ist kein Konto bekannt (nur ohne bestehende Sitzung möglich): `410`. Es
   wird nie auf Identifizierung oder Registrierung ausgewichen.
2. Das aktuelle Niveau liegt unter `loa2`: Es folgt ein Step-up-Schritt; danach ruft der Client den
   Einstiegs-Endpunkt erneut auf.
3. Das Niveau ist schon `loa2`, wurde aber unabhängig von diesem Durchlauf erreicht (der Nachweis
   ist also unbekannt alt): Die Antwort verlangt einen frischen Nachweis über ein beliebiges aktives
   `auth-*`-Verfahren, genau wie beim Löschen des Kontos in Schritt 3 oben
   (`next={"context":"auth","step":"selectMethod"}` bei mehreren Kandidaten). **Ausnahme**: Musste
   in Schritt 2 erst ein Step-up stattfinden, zählt dessen Nachweis bereits als der geforderte.
4. `confirm-qr-login` starten:
   - `POST .../tools/confirm-qr-login` (ohne Inhalt) →
     `stepData={"kind":"missing-fields","missingFields":["pairingCode"]}`.
   - `PATCH .../confirm-qr-login` mit `{"pairingCode":"..."}` (aus dem QR-Code bzw. über den
     Demo-Link vorbelegt, [Frontend](10-frontend.md)) → bei einer gültigen, noch offenen Anfrage
     `next.step="confirm"`. Bei einem unbekannten, abgelaufenen oder schon entschiedenen Code kommt
     `stepData.error`; der Schritt bleibt auf `input`, und es gelten die üblichen Regeln für weitere
     Versuche.
   - `PATCH .../confirm-qr-login` mit `{"decision":"accept"}` bzw. `{"decision":"reject"}`. Hat das
     Konto kein aktives `enroll-qr`, liefert `accept` `stepData.error` („QR-Login ist für dieses
     Konto nicht aktiviert").
5. Nach erfolgreichem `accept`: `next.step="showCode"` mit
   `stepData={"kind":"qr-pairing","confirmationCode":"482913"}`. Diesen **Bestätigungscode** tippt
   der Nutzer in den wartenden Browser; erst damit ist der Browser angemeldet (Code in
   Gegenrichtung, [Betrieb](07-betrieb.md) Abschnitt 5). Der Code steht nur in dieser einen Antwort,
   gespeichert wird sein Hash; nach einem Neuladen zeigt `showCode` ihn nicht mehr.
   `PATCH .../confirm-qr-login` mit `{"decision":"done"}` beendet den Schritt:
   `next={"type":"orchestrator","context":"authentication","step":"authenticated"}`. War der Kanal
   vor diesem Aufruf noch nicht `AUTHENTICATED`, fragt die Antwort vorher per `Prompt` („Jetzt
   abmelden?"), ob der Kanal angemeldet bleiben soll. `reject` liefert stattdessen `stepData.error`
   („Vom Nutzer abgelehnt").

Die Web-Seite selbst (`auth-qr`/`auth-qr-lookup`) hat zwei Schritte. In `waitForApp` fragt sie
denselben allgemeinen `PATCH`-Endpunkt des Tools regelmäßig mit leerem Inhalt ab; das zählt nicht
gegen die Versuchs- oder Login-Sperre. Nach der Freigabe in der App liefert derselbe `PATCH`
`next.step="enterCode"` (`missingFields=["confirmationCode"]`). `PATCH` mit
`{"confirmationCode":"..."}` liefert beim richtigen Code `Completed.Authenticated`, bei einem
falschen `Failed`; nach drei falschen Codes ist die Anfrage verbrannt. Bei `DENIED` oder nach Ablauf
kommt ebenfalls `Failed`. Bei `auth-qr-lookup` steht die gefundene `accountId` im `demo`-Objekt.

---

## 3) Web-Zugang (Keycloak führt)

**Umgesetzt.** Der Browser spricht hier nie mit dem Orchestrator: Keycloak spricht mit dem
Orchestrator ausschließlich von Server zu Server, über Keycloaks Java-Plugin (SPI) in
`keycloak-extension/`. Es gibt einen einzigen Endpunkt nur für diesen Zugang; er legt an oder
aktualisiert:

- `PATCH /orchestrator/api/v1/kc/channels/{channelSessionId}` – legt den Kanal beim ersten Aufruf
  unter dieser von Keycloak gewählten ID an und setzt ihn bei jedem weiteren Aufruf fort. Die ID
  stammt aus Keycloaks laufendem Anmeldeablauf (`AuthenticationSessionModel`/`UserSessionModel`).

Inhalt der Anfrage (alle Felder optional, `KcChannelUpsertRequest`):

- **`accountId`** — Das Konto, das Keycloak schon kennt (`sub` ist vorhanden, Step-up). Es ordnet den Kanal sofort diesem Konto zu und wird später nie überschrieben.
- **`targetAcr`** — Das von Keycloak angefragte LoA, bereits in einen ACR-Wert des Orchestrators übersetzt. Es hebt die Untergrenze des Kanals nur an, nie ab, und filtert die Kandidaten von `KC_SELECT_METHOD` ([Orchestrierung](04-orchestrierung.md) Abschnitt 3).
- **`amr`** — Liste `{nativeToolId, amrSourceId}`: was ein eigenes Keycloak-Verfahren (nie ein Tool des Orchestrators) in DIESEM Anmeldedurchlauf nachgewiesen hat. Methode, LoA und Faktortypen ermittelt der Orchestrator auf dem Server über `nativeToolId` (`NativeAuthenticatorDescriptor`). Es ist immer die VOLLSTÄNDIGE, derzeit gültige Menge, keine Änderungsliste.
- **`restoreData` / `kcSessionId`** — Ein signiertes Token aus `GET .../restore-data` einer FRÜHEREN, unabhängigen `ChannelSession` derselben Keycloak-Nutzersitzung. Es gibt die dort erbrachten Nachweise an einen frisch angelegten Kanal weiter. `kcSessionId` bindet das Token an Keycloaks dauerhaftes `UserSessionModel`. Ein falsches, abgelaufenes oder manipuliertes Token wird als `null` behandelt, nie als Fehler.
- **`availableTools`** — Welche `toolId`s das Keycloak-Theme darstellen kann (ein `WebToolRenderer` je Tool). Nur beim ersten Aufruf gelesen; das Gegenstück zu `availableTools` bei `POST /app/channels`.
- **`intent`** — Nur beim ersten Aufruf gelesen. Fehlt er, gilt `kc_select_method`; sonst ist nur `register` erlaubt. Ein unbekannter oder unzulässiger Wert wird abgelehnt (`409`).

`GET .../{channelSessionId}/restore-data?kcSessionId=...` gibt es nur für den Aufruf, den Keycloak am
Ende des Anmeldeablaufs macht: Es liefert die gesammelten Nachweise dieses Kanals als Token, gebunden
an diese `kcSessionId` (`RestoreDataCodec`). Keycloak legt das Token als Notiz im `UserSessionModel`
ab und gibt es bei einem SPÄTEREN Step-up unverändert als `restoreData` im ersten `PATCH` zurück.

Danach läuft **alles** über dieselben Endpunkte wie im App-Zugang, ohne das Präfix `/kc/`:

- `GET .../channels/{channelSessionId}`, `.../step-ups`, `.../journey`, `.../methods`,
  `.../enrollments`, `.../token`, `.../idclaims` (Abschnitt 2)
- `POST .../channels/{channelSessionId}/tools/{toolId}` (Anlegen), danach `PATCH`/`GET
  /tools/{toolSessionId}/{toolId}` – genau wie im App-Zugang

Statt mit einem DPoP-Proof weist sich Keycloak mit einer signierten Peer-Auth-Assertion im Header
`Authorization` aus (kein mTLS, ADR-7). Das ist ein JWT je Anfrage mit:

- `iss`/`aud` aus dem Parametersatz (`peerAuthIssuer`/`peerAuthAudience`, standardmäßig
  `dpop-demo-keycloak`/`dpop-demo-orchestrator`),
- `htm`/`htu` dieser Anfrage,
- `jti` und `iat`: Der Schutz gegen Wiederholung nutzt denselben Zwischenspeicher wie bei DPoP
  ([09-dpop.md](09-dpop.md)), aber unter einem eigenen Namensraum `kc:` und mit einem eigenen
  Zeitfenster (`kc.peer-auth.max-clock-skew-seconds`/`max-age-seconds`, im Profil `keycloak` je
  300 Sekunden),
- dem Kanal-Anker dieses Anmeldedurchlaufs (Claim `channel_anchor`), den `KcChannelAccessGuard`
  gegen den Kanal prüft.

Geprüft wird die Signatur gegen Keycloaks JWKS; es gibt ein Schlüsselpaar je Client, nicht je
Nutzer.

Jede Antwort an einen `KEYCLOAK`-Kanal enthält zusätzlich `authData` (`accountId`/`acr`/`amr`, nie
bei `APP`). Keycloaks `OrchestratorAuthenticator` schreibt es sofort in seine Session-Notes. `amr`
ordnet jeder Methode ihre Quelle zu (`"kc"` für eine eigene Angabe Keycloaks, `"orchestrator"` für
ein abgeschlossenes Tool des Orchestrators). Das ist nur eine Information; den kombinierten `acr`
bestimmt ausschließlich der Orchestrator.

Der Web-Kanal kennt kein Gerät; `DeviceAccountLink` gibt es nur im App-Kanal
([02-domaenenmodell.md](02-domaenenmodell.md)). Angemeldet wird über den Login per E-Mail-Adresse
bzw. über den eigenen Einstiegs-Intent `KC_SELECT_METHOD` ([04-orchestrierung.md](04-orchestrierung.md)
Abschnitt 3), registriert über `REGISTER` (`intent=register`, siehe oben). `ident-fsc`, `ident-eid`
und die `enroll-*`-Tools werden über dieselben `WebToolRenderer` angezeigt.

Eine Lücke, die hier ausdrücklich benannt wird: **`enroll-kobil`/`auth-kobil`,
`enroll-device`/`auth-device` und `ident-nect` haben keinen `WebToolRenderer`** und fehlen damit im
Web-Kanal. KOBIL und der Geräteschlüssel brauchen ein Telefon bzw. dessen Schlüsselspeicher, Nect
seine eigene Sprungseite; von einer Anmeldeseite, die der Server erzeugt, ist keines davon
erreichbar. Das Theme führt diese `toolId`s deshalb nicht in `availableTools`, und so werden sie dort
nie angeboten. Derselbe Mechanismus, der alte App-Versionen lauffähig hält, deckt auch diesen Fall
ab.

**Offen:** Wie Logout im Web-Kanal funktionieren soll, ist noch nicht entschieden: ob
`DELETE /channels/{id}` für `KEYCLOAK`-Kanäle vom Client aus aufrufbar sein soll oder nur von
Keycloak ausgelöst wird.

### Anmeldeverfahren verwalten im Web-Kanal (Keycloak Required Action)

`AuthIntent.MANAGE_AUTH_METHODS` funktioniert bereits für beide Zugänge gleich (Abschnitt 2,
„Methoden verwalten"; `POST .../enrollments` nutzt denselben `DpopBindingKeyResolver`). Der
Web-Kanal braucht deshalb **keinen neuen Endpunkt im Orchestrator**, nur einen eigenen Einstieg:
eine Keycloak-`RequiredAction` (`getId()="orchestrator-manage-methods"`, `defaultAction=false`,
also nie erzwungen, nur über `kc_action` auslösbar). Sie ist im bestehenden Ablauf
`orchestrator-browser` registriert und über dieselbe `/auth`-URL erreichbar wie ein normaler
Login, ergänzt um `kc_action=orchestrator-manage-methods`.

Ein zweiter Login wird nicht erzwungen. Der vorangehende Durchlauf von `orchestrator-browser`
nutzt das bestehende SSO-Cookie von Keycloak, und `OrchestratorResumeAuthenticator` bringt den
neuen Kanal im Orchestrator über `restoreData` auf `AUTHENTICATED`, sofern die Nachweise reichen.
Sonst greift der normale Weg über Login und Step-up. Endet der Ablauf erfolgreich, ruft die Required
Action `startEnrollments(...)` auf dem frischen Kanal auf und zeigt `next` über dieselbe Zuordnung
zu den `WebToolRenderer`n an. Frontend: `redirectToManageMethods()` (`webOidc.ts`) baut dieselbe
`/auth`-URL wie `redirectToLogin`; zurück geht es über den bestehenden Weg
`completeLoginIfRedirected()`.

Wie `next` gedeutet wird (Auswahlseite, Formular eines Tools oder automatischer Start eines Tools),
ist zwischen `OrchestratorAuthenticator` und dieser Required Action **gemeinsamer Code**
(`OrchestratorNextDispatch.classify`/`dispatchToolAction`, `keycloak-extension`). Nur die Reaktion
darauf (`context.success()`/`failure()` bzw. die Entsprechungen von `RequiredActionContext`) ist
je Aufrufer eigen.

---

### Von Server zu Server: Keycloaks eigenes Passwort-Credential (`MgmtPasswordController`)

Ohne Zustand, ohne Kanal und ohne ToolSession: Keycloaks eigene UserStorage-SPI
(`OrchestratorStorageProvider`) prüft und setzt Passwörter für das Konto, das Keycloak über das
Nutzerattribut `orchestratorAccountId` kennt. Keycloak weist sich dabei mit derselben
`kc-peer-auth`-Signatur aus wie bei den anderen Aufrufen von Server zu Server
([DPoP-Bindung](09-dpop.md)/[12-entscheidungen.md](12-entscheidungen.md) ADR-7). Allerdings wird
`channel_anchor` hier für einen anderen Zweck genutzt: Der Claim trägt die `accountId` und wird
gegen den Pfadparameter geprüft.

- `POST /orchestrator/api/v1/tools/auth-password/mgmt/{accountId}` – prüft `{"password": "..."}`
  gegen das gespeicherte Credential; Antwort `{"valid": true|false}`.
- `POST /orchestrator/api/v1/tools/enroll-password/mgmt/{accountId}` – `{"newPassword": "..."}`
  setzt ein neues Credential und deaktiviert das bisherige `password`-Verfahren des Kontos, `204`.

## 4) Zusammenspiel von Prozess-API und Tool-Ressourcen

Ziel: Die fachliche Führung bleibt bei den Endpunkten für Kanal und Ablauf. App-Frontend und
Keycloak nutzen für Eingabe- und Prüfschritte dieselben kanalneutralen Tool-URLs.

- Der Endpunkt des Kanals wählt über die `toolId` das Tool aus und legt eine technische
  `ToolSession` an, ohne selbst fachliche Eingaben entgegenzunehmen.
- Fachlich zuständig bleibt die `AuthJourney` (Intent, Zustand, Versuchsbudget). Die `ToolSession`
  hält nur Daten zum Lebenszyklus (`toolSessionId`, `journeyId`, Zeitstempel): Die `toolId` ergibt
  sich aus der Route, `stepData` aus den Daten des Moduls, und das Versuchsbudget gilt für die ganze
  Journey (siehe [Domänenmodell](02-domaenenmodell.md)).
- `accountId` und `personId` gehören nicht zum fachlichen Antwortvertrag; einzige Ausnahme ist das
  `demo`-Objekt.

Für Keycloak sind `auth-sms`, `auth-password` und `auth-email` (Login und Step-up) der einzige
Fall, den der App-Zugang nicht schon abdeckt. Anlegen, `PATCH` und `GET` laufen aber genau wie in
der App: `POST .../channels/{channelSessionId}/tools/auth-sms`, danach
`PATCH`/`GET /tools/{toolSessionId}/auth-sms` (Abschnitt 3).
