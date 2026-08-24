# Umsetzungsplan (Backend + Frontend)

Konkreter Bauplan, um den in [01](01-ueberblick.md)–[10](10-frontend.md) beschriebenen
Zielzustand aus dem aktuellen Codestand zu erreichen. Dieses Dokument ist bewusst
*Reihenfolge und Aufgabe*, nicht nochmal *Fachkonzept* — die fachliche Begründung steht
in den referenzierten Kapiteln.

**Explizit ausgeklammert:** die Keycloak-Anbindung (kc-Fassade,
`AuthContext`↔Keycloak-Tokenfluss, `/orchestrator/api/v1/kc/...`). Der Plan endet dort, wo
Keycloak anfängt; `AuthContext` wird nur soweit gebaut, wie er ohne Keycloak sinnvoll ist
(Felder vorhanden, `keycloakSessionId`/`keycloakSubject`/`tokenHandle` bleiben ungenutzt/null).

---

## 0) Bestandsaufnahme: was mit dem bestehenden Code passiert

Der aktuelle Code steht auf einem klar älteren Stand (siehe bereits [README.md](README.md#bezug-zum-bestehenden-code)):
`Attempt`-Terminologie statt `ToolSession`/`ToolOutcome`, methodenspezifische URL-Pfade statt
generischem Tool-Namespace, kein `ToolDescriptor`/`ToolHandlerRegistry`, keine `AuthPolicy`,
Klartext-TAN statt Hash, kein `enrolledUnderAcr`, `next`-Form `{context, step, methods}` statt
`{type, toolId|context, step}`. Eine Migration Schritt für Schritt wäre aufwändiger und
fehleranfälliger als ein sauberer Neubau der betroffenen Teile. Entscheidung:

| Bereich | Entscheidung | Begründung |
|---|---|---|
| `orchestrator/session/*Attempt*.kt`, `AttemptStatus.kt`, `AttemptPendingStore.kt` | **löschen** | Ersetzt durch `ToolSession` + Modul-`*ToolData`; kein Migrationswert |
| `orchestrator/api/v1/identification/fsc/*`, `orchestrator/api/v1/authentication/sms/*` | **löschen** | Ersetzt durch generischen Tool-Namespace (`/tools/{toolSessionId}/{toolId}`) |
| `orchestrator/api/v1/ApiTypes.kt` (alte `OrchestratorResponse`/`NextRouting`) | **löschen**, neu bauen | Antwortform passt nicht zum Zielvertrag (`next.type`) |
| `orchestrator/Orchestrator.kt`, `ProcessController.kt` | **löschen** | Faktisch leere Platzhalter (22/13 Zeilen), keine funktionale Substanz |
| `orchestrator/session/ChannelSession.kt`, `ChannelSessionRepository.kt` | **anpassen** | Grundidee passt, Felder/Zustände auf Zielmodell (Abschnitt 1) bringen |
| `orchestrator/session/ProcessSession.kt` + Subklassen | **anpassen** | Single-Table-Vererbung beibehalten, Routing-Felder (`nextType/nextToolId/nextContext/nextStep`) ergänzen, Attempt-spezifische Felder entfernen |
| `orchestrator/session/AuthContext.kt` | **anpassen** | Felder gemäß [02-domaenenmodell.md](02-domaenenmodell.md) Abschnitt 1 ergänzen (`currentFactorTypes` etc.) |
| `orchestrator/dpop/*` (DPoP-Validierung, Thumbprint, Replay-Schutz) | **behalten** | Deckt [09-dpop.md](09-dpop.md) bereits ab, unabhängig vom Session-Umbau |
| `orchestrator/account/AccountBindingKeyMapping*` | **prüfen, vermutlich löschen** | Bindung läuft über `ChannelSession.bindingKeyRef` direkt; separate Mapping-Tabelle ist vermutlich Altlast aus früherem Modell — vor dem Löschen kurz verifizieren, dass nichts anderes darauf verweist |
| `account/*` (Account, AccountService, JSON-Spalten) | **anpassen, Struktur bleibt** | JSON-Spalten-Ansatz ist bereits richtig; Feldnamen/Inhalt an [06-ablaeufe.md](06-ablaeufe.md) Abschnitt 1 angleichen (`enrolledUnderAcr`, `enrollmentRef` statt `enrollmentId`, `loa`→`loa`-Feld konsistent benennen) |
| `id_fsc/*` | **anpassen** | `IdFscService` zu `IdentFscToolHandler` + `ToolDescriptor` umbauen, Moduldaten in eigene `id_fsc_tool_data`-Tabelle statt Attempt-Payload |
| `auth_sms/*`, `AuthSmsSetup.kt` | **anpassen** | TAN muss gehasht + versuchsbezogen werden (`enroll_sms_tool_data`/`auth_sms_use_tool_data`), `AuthSmsSetup` wird zu schlankem `AuthSmsEnrollment` (nur `id`, `phoneNumber`, `createdAt`, kein `tan`/`validated`) |
| `ext_stammdaten/*` | **behalten** | `Person`-Entität und Testdaten passen unverändert |
| Flyway `V1`…`V16` | **löschen und neu baselinen** | Demo-App mit lokaler H2-Datei, keine produktiven Daten zu migrieren; ein sauberes `V1__schema.sql` gegen das Zielmodell ist einfacher und weniger fehleranfällig als 16+ Migrationen fortzuschreiben. `data/dpopdb.mv.db` dabei löschen (lokale Datei, kein Datenverlust relevant) |
| Frontend `routing.ts`, `types.ts`, `App.tsx` | **anpassen** | Struktur (feste Routing-Tabelle) ist richtig, Form/Felder auf `next.type` umstellen |
| Frontend Components (`FscForm`, `TanInputForm`, …) | **größtenteils behalten** | Reine Eingabeformulare, Anpassung nur an neue Prop-Typen |
| `frontend/src/dpop.ts` | **behalten** | Reine Krypto-Logik, unabhängig vom API-Umbau |

Falls beim Umsetzen eine hier als „behalten"/„anpassen" markierte Datei doch nicht mehr
trägt: löschen statt Kompatibilitäts-Shims bauen (Vorgabe des Nutzers).

---

## 1) Backend — Phasenplan

### Phase B1: Domänenmodell

Ziel: Entitäten aus [02-domaenenmodell.md](02-domaenenmodell.md) Abschnitt 1 vollständig anlegen.

- `ChannelSession`: Felder `channelSessionId`, `channel`, `bindingKeyRef`, `accountId`, `authContextId`, `state` (neues `ChannelState` inkl. `STEP_UP_IN_PROGRESS`), `requiredAcr`, `createdAt`, `lastAccessedAt`, `expiresAt`.
- `ProcessSession` (abstract, Single-Table) mit `RegistrationProcessSession(personId)`, `LoginProcessSession` (Marker), `StepUpProcessSession(requiredAcr, startingAcr, achievedAcr)`; gemeinsame Routing-Felder `nextType`, `nextToolId`, `nextContext`, `nextStep`; `ProcessState` gemäß Enum-Liste in [02](02-domaenenmodell.md#4-enumerationen).
- `AuthContext`: `authContextId`, `accountId`, `keycloakSessionId`/`keycloakSubject`/`tokenHandle` (Felder vorhanden, bleiben ungenutzt ohne Keycloak), `currentAcr`, `currentAmr` (JSON-Liste), `currentFactorTypes` (JSON-Set), `authTime`, `tokenExpiresAt`, `refreshExpiresAt`, `updatedAt`.
- `SessionEvent`: `eventId`, `channelSessionId`, `processSessionId`, `eventType`, `source`, `payloadHash`, `createdAt` — neue Entität, existiert im Code noch nicht.
- `ToolSession` (Orchestrator-Modul): nur Lifecycle (`toolSessionId`, `processSessionId`, `createdAt`, `expiresAt`, `retryCount`), keine Subtypen.
- Enums: `Channel`, `ChannelState`, `ProcessPurpose`, `ProcessState`, `ToolState`, `ToolCategory`, `FactorType` gemäß [02](02-domaenenmodell.md#4-enumerationen).
- Flyway-Baseline (`V1__schema.sql`, ersetzt alle bisherigen Migrationen): `channel_session`, `process_session` (Single-Table + Discriminator `purpose`), `auth_context`, `session_event`, `tool_session`, `account`, `person`, `fsc_code`, `id_fsc_tool_data`, `enroll_sms_tool_data`, `auth_sms_use_tool_data`, `auth_sms` (Enrollment-Tabelle) — plus Testdaten-Insert (Personen, FSC-Codes) wie bisher unter `V2`.

### Phase B2: Tool-Architektur

Ziel: Contracts aus [03-tool-architektur.md](03-tool-architektur.md) bauen, je Modul konkret befüllen.

- Orchestrator-seitig: `ToolDescriptor`-Interface, `ToolHandlerRegistry` (sammelt alle `ToolDescriptor`-Beans beim Start, löst `toolId` auf Handler auf), `ToolOutcome`/`Completed`-Sealed-Interfaces exakt wie in [03](03-tool-architektur.md#2-modulklassen-und-verträge) spezifiziert.
- Modul `id_fsc`: `IdentFscToolHandler` (ersetzt `IdFscService`) mit `ToolDescriptor(toolId="ident-fsc", category=IDENT, method="fsc", factorTypes={POSSESSION}, maxAcr="loa2")`, `IdentFscToolData`-Entität + Repository, internes `FlowOutcome`-State-Machine-Muster (Abschnitt 3 in [03](03-tool-architektur.md#3-modulinterne-flow-architektur-state--effects)) optional, aber `validateAndBuildResult` muss `ToolOutcome` liefern.
- Modul `auth_sms`: `EnrollSmsToolHandler` (`toolId="enroll-sms"`, category=ENROLL) und `AuthSmsUseToolHandler` (`toolId="auth-sms"`, category=AUTH), beide `method="sms"`, `factorTypes={POSSESSION}`, `maxAcr="loa2"`; je eigene `*ToolData`-Entität/Repository; TAN wird ab hier **gehasht** mit `tanExpiresAt` abgelegt, nie im Klartext.
- Jedes Modul liefert seinen `ToolDescriptor` über eine Spring-Bean; kein zentrales Verzeichnis pflegen.

### Phase B3: Orchestrierung und Policy

Ziel: `Completed`-Verarbeitung, `next`-Ermittlung und `AuthPolicy` aus [04-orchestrierung.md](04-orchestrierung.md).

- Zentraler, erschöpfender `when`-Zweig über `Completed` (Abschnitt 1 in [04](04-orchestrierung.md#1-vom-tooloutcome-zum-nächsten-prozessschritt)): `Identified` → Account finden/anlegen + `account.addIdentification`; `Enrolled` → `account.addAuthenticationMethod` inkl. `enrolledUnderAcr`/`enrolledUnderAmr`; `Authenticated` → Deckelung über `enrolledUnderAcr` der verwendeten Methode.
- Gemeinsam für alle drei: `authContext.addAmr`, `addFactorTypes`, `currentAcr`-Update, `SessionEvent`-Eintrag.
- `next`-Ermittlungstabelle als Funktion von `ProcessPurpose` + `toolId`-Kategorie + `AuthPolicy`-Ergebnis (Tabelle in [04](04-orchestrierung.md#1-vom-tooloutcome-zum-nächsten-prozessschritt)), inklusive Retry-Regel (`200` + `next` auf gleiches Tool bei verbleibenden Retries, `410` bei erschöpftem Limit).
- `AuthPolicy`-Interface (`isSatisfied`, `candidateTools`, `canAccountReach`, `enrollmentCandidates`, `resolveAcr`) gemäß [04](04-orchestrierung.md#2-authpolicy-mehr-faktor-entscheidung). Für `resolveAcr` (amr→acr-Abbildung) reicht für diesen Plan eine **einfache, klar als vorläufig gekennzeichnete Default-Implementierung** (z. B. statische Zuordnung `{"fsc"}→loa2`, `{"sms"}→loa2`, `{"fsc","sms"}→loa2`, `{"passkey"}→loa3`), da die reguläre Abbildung laut Doku bewusst offen ist. Nicht Teil dieses Plans: die fachlich/regulatorisch korrekte Abbildung.
- MFA-Zählung: Vereinigung der `factorTypes` über alle abgeschlossenen Tools der Session, nicht Tool-Anzahl.

### Phase B4: API-Schicht

Ziel: Endpunkte aus [05-api.md](05-api.md).

- `ChannelController`/`ChannelService` neu: `POST /app/channels` (mit `channelSessionId`/`requiredAcr` im Body), `GET /app/channels/{channelSessionId}`, `PATCH /app/channels/{channelSessionId}` (Step-up-Auslöser).
- Neuer `ToolActivationController`: `POST /app/channels/{channelSessionId}/tool-activate/{toolId}` — legt `ToolSession` an, delegiert an `ToolHandlerRegistry`.
- Neuer `ToolController` (Tool-Namespace): `PATCH/GET /tools/{toolSessionId}/{toolId}` als Regelfall für `ident-fsc`, `enroll-sms`, `auth-sms`.
- DTOs: `next` strikt als `{type, toolId|context, step}`, `stepData` als generische Map, kein Vermischen mit Inhalt (siehe [05](05-api.md#1-api-grundsätze)). Altes `ApiTypes.kt` komplett ersetzen.
- Fehlervertrag konsequent aus [07-betrieb.md](07-betrieb.md) Abschnitt 1 (`400/401/403/404/409/410/422`), fehlende Pflichtfelder und Retry-Fehlversuche **nicht** als HTTP-Fehler.
- `demo`-Objekt (Abschnitt 2, Beispiel 6 in [05](05-api.md)) nur in der `enroll-sms`-Abschlussantwort, klar als Demo-only markiert.

### Phase B5: Betrieb (ohne Keycloak-Anteil)

- Transaktionale Klammer um die `Completed`-Verarbeitung (ein Commit für Moduldaten + Account + `ProcessSession` + `AuthContext`), SMS-Versand bewusst außerhalb der Transaktion.
- Aufräum-Jobs je Aufbewahrungsfrist aus [07-betrieb.md](07-betrieb.md) Abschnitt 3 (`*ToolData` 24h, `ToolSession` 24h, `ProcessSession` 7 Tage, `AuthContext` bei Logout, `ChannelSession` 24h, `SessionEvent` 90 Tage) — je Modul eigenständig, kein zentraler Löschbefehl.
- `AuthSmsEnrollment`/`account.*`/`DeviceAccountLink`/`LoginAttemptThrottle` explizit vom Cleanup ausnehmen.

### Phase B6: Tests

- `ApplicationModules.verify()` weiterhin grün halten (Modulgrenzen `orchestrator`→`id_fsc`/`auth_sms`/`account`/`ext_stammdaten`).
- Integrationstests je vollständigem Ablauf: `ident-fsc` (Ident-Only-Registrierung bis `enroll-sms`-Angebot), `enroll-sms` (bis `authenticated`), `auth-sms` (Login-Flow), Step-up-Fall (`PATCH /app/channels/{id}` mit höherem `requiredAcr`).
- Unit-Tests `AuthPolicy` (isSatisfied/candidateTools/Deckelungslogik) unabhängig von HTTP.
- Retry- und Fehlerfälle (Retry-Limit → `410`, ungültiger Zustand → `409`, unbekannte `enrollmentRef` → `422`).

---

## 2) Frontend — Phasenplan

### Phase F1: Typen

- `types.ts` neu: `Next = {type: 'tool'|'flow', toolId?: string, context?: string, step: string}`, `StepData` generisch (`missingFields?`, `options?`, `error?`), `ChannelSessionResponse` mit `state`, `currentAcr?`, `currentAmr?`, `next?`. Altes `NextRouting`/`ProcessState`/`AttemptState` entfällt.

### Phase F2: Routing-Tabelle

- `routing.ts` gemäß Tabelle in [10-frontend.md](10-frontend.md#3-navigation-ausschließlich-über-next): Schlüssel `(type, toolId|context, step)` statt bisher `(context, step)`. Komponentenzuordnung: `ident-fsc/input`→`FscForm`, `enroll-sms/enroll`→`SmsEnrollForm` (neu, Telefonnummer-Eingabe getrennt von TAN), `enroll-sms/tanInput`→`TanInputForm`, `auth-sms/auth`→`TanInputForm`; Flow-Seiten `registration/selectIdentificationMethod`, `enrollment/selectMethod`, `auth/selectMethod`, `authentication/authenticated`.

### Phase F3: API-Client

- Dünne Fetch-Wrapper, je einer pro Endpunkt: `createChannel`, `getChannel`, `patchChannelRequiredAcr`, `activateTool(channelSessionId, toolId)`, `patchTool(toolSessionId, toolId, body)`, `getTool(toolSessionId, toolId)`. Jeder Call hängt den DPoP-Proof-Header an (bestehende Logik aus `dpop.ts` wiederverwenden).
- Kein Client-seitiges Konstruieren von `toolId`; immer aus `next.toolId` oder gewähltem `stepData.options`-Eintrag übernehmen.

### Phase F4: Components

- `FscForm`, `TanInputForm`: Props auf `toolSessionId` + generisches `stepData` umstellen, sonst weitgehend unverändert übernehmbar.
- Neue generische `SelectMethodView`: rendert `stepData.options` als Auswahl, ruft bei Klick `tool-activate/{gewählte toolId}` auf (ersetzt `IdentificationMethodSelection`/`EnrollmentMethodSelection`/`AuthenticationMethodSelection` durch eine Komponente).
- `AuthenticationCompleted`-View: zeigt `currentAcr`/`currentAmr` aus dem Kanalstatus sowie `accountId`/`personId` aus dem `demo`-Objekt (FE-11).
- `App.tsx`: Zustandsführung auf „aktueller `next` + `stepData`" reduzieren, Verzweigung ausschließlich über `routing.ts`.

### Phase F5: DPoP-Client

- `dpop.ts` unverändert übernehmen (ECDSA-P-256-Keypair, IndexedDB-Persistenz, `extractable=false`) — deckt D-1…D-4 bereits ab, kein Backend-API-Bezug.

### Phase F6: Demo-Komfort

- FE-7 (Testdaten-Vorbelegung), FE-9 (Telefonnummer-Vorvalidierung), FE-10 (Reset-Aktion: DPoP-Key löschen + neu erzeugen + Flow neu starten), FE-6 (Card-Layout/Darkmode) — bestehende Ansätze in `App.css`/Components sichten und auf neue Datenform ummünzen statt neu erfinden.

---

## 3) Empfohlene Reihenfolge

1. Flyway-Baseline + Domänenentitäten (B1) — Grundlage für alles Weitere.
2. Tool-Architektur je Modul (B2), zuerst `id_fsc` (einfachster Fall: eine Completed-Variante, keine Kandidatenauswahl), danach `auth_sms` (zwei Handler, TAN-Hashing).
3. Orchestrierung/`AuthPolicy` (B3) — erst wenn beide Module echte `ToolOutcome`s liefern, sinnvoll testbar.
4. API-Schicht (B4) — Controller/DTOs über die jetzt stabilen internen Contracts legen.
5. Frontend Typen/Routing/API-Client (F1–F3) parallel zu B4 möglich, sobald die DTO-Form feststeht.
6. Components/Demo-Komfort (F4–F6).
7. Betrieb/Cleanup (B5) und Tests (B6) zuletzt, wenn die Flows stehen.

---

## 4) Nicht Teil dieses Plans

- Keycloak-Anbindung (kc-Fassade, echter Tokenfluss) — expliziter Ausschluss laut Auftrag.
- Fachlich/regulatorisch verbindliche `amr`→`acr`-Abbildung (`AuthPolicy.resolveAcr`) — bleibt Platzhalter, siehe Phase B3.
- Weitere Tools über `ident-fsc`/`enroll-sms`/`auth-sms` hinaus (z. B. `auth-passkey`) — Architektur trägt sie, aber sie sind hier nicht eingeplant.

---

## 5) Nachträge nach Phase B4 (A11-Korrektur, Cancel/Back)

Nach Abschluss der ursprünglichen Phasen B0–B6/F1–F6 wurden zwei Lücken behoben, die erst beim genaueren Hinsehen auf A11 (08-projektrahmen.md: „Lesbarkeit hat Vorrang vor maximal generischem API-Wiring") auffielen:

- **Tool-Endpunkte waren zu generisch.** Ein einziger `ToolController` löste `toolId` zur Laufzeit über eine Registry auf einen Handler auf (`Map<String, Any?>`-Bodies statt typisierter DTOs). Korrigiert auf je einen Controller pro Tool (`IdentFscToolController`/`EnrollSmsToolController`/`AuthSmsToolController`) mit eigenem typisiertem Request-DTO, der seinen Handler direkt aufruft — auch die Aktivierung (vorher `ToolActivationController`/`-Service`). `ToolHandlerRegistry` aggregiert seither nur noch den Katalog, dispatcht nicht mehr. `ToolHandler` (tool_spi) ist auf `descriptor` verschlankt; `start()` ist auf den Handler-Klassen typisiert (z. B. `EnrollmentRef` statt `Map` bei `auth-sms`). Einzige bewusst generisch gebliebene Ausnahme: `DELETE /tools/{toolSessionId}/{toolId}` (Back/Switch, siehe unten) — hat keinerlei tool-spezifisches Verhalten, eine Aufteilung auf drei Controller wäre dieselbe Implementierung dreifach kopiert.
- **Cancel/Back fehlten komplett**, obwohl `ProcessState.CANCELLED` und die entsprechenden `ChannelState`-Übergänge (REGISTERING→ANONYMOUS, STEP_UP_IN_PROGRESS→AUTHENTICATED) schon im Domänenmodell standen. Ergänzt: `POST /app/channels/{id}/cancel` (ganzer Prozess) und `DELETE /tools/{toolSessionId}/{toolId}` (einzelnes Tool verwerfen, Kandidaten-Ermittlung erneut anbieten). Details in [05-api.md](05-api.md) Abschnitte „Cancel"/„Back/Switch".

Design-Regel, die dabei durchgehend angewendet wurde: Referenzen, die der Orchestrator für ein Modul auflöst (z. B. `EnrollmentRef` für `auth-sms`), werden am Aufrufort geprüft und nur non-null weitergereicht — nie eine nullable Referenz in eine Methode reichen, die dort erst bei `null` wirft.
