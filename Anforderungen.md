# Anforderungen

## 1. Einführung und Ziele

### 1.1 Aufgabenstellung

Aufbau einer kompilier- und startfähigen **Spring Boot Modulith**-Applikation zur Demonstration eines DPoP-gesicherten Registrierungs- und Anmeldeablaufs. Das System umfasst:

- Ein React/TypeScript-Frontend, das einen DPoP-Proof erzeugt und mit dem Backend kommuniziert.
- Einen `orchestrator`, der Session-Zustände verwaltet und Identifikation (FSC) sowie Authentifizierung orchestriert.
- Mehrere fachliche Module (`id_fsc`, `auth_sms`, `account`, `ext_stammdaten`), die über definierte Schnittstellen vom Orchestrator genutzt werden.
- Persistenz der Session-Daten in einer H2-Datenbank mit Flyway-Migrationen.

### 1.2 Qualitätsziele

| Priorität | Ziel | Beschreibung |
|-----------|------|--------------|
| 1 | Modularität | Klare fachliche Module mit definierten Abhängigkeiten |
| 2 | Verifizierbarkeit | Architektur- und Modulstruktur automatisiert prüfbar |
| 3 | Aktualität | Verwendung aktueller Versionen des Spring-Ökosystems |
| 4 | Entwicklerfreundlichkeit | Sofort ausführbar über Gradle Wrapper |

## 2. Kontextabgrenzung (C4 System Context)

```
┌─────────────────────────────────────────────┐
│              Externe Nutzer /               │
│              Klienten-Systeme               │
└───────────────────┬─────────────────────────┘
                    │ HTTP / REST
                    ▼
┌─────────────────────────────────────────────┐
│           DPoP-Demo Applikation             │
│  (Spring Boot Modulith, Port 8080)          │
└─────────────────────────────────────────────┘
```

### 2.1 Systems under Consideration

- **Name**: `dpop-demo`
- **Typ**: Spring Boot Webanwendung
- **Schnittstelle nach außen**: HTTP/REST (Tomcat auf Port 8080)

## 3. Funktionale Anforderungen

### 3.1 Frontend

- Das System besitzt ein Frontend auf Basis von **React** (neueste Version) und **TypeScript**.
- Das Frontend kann **autark** mittels Vite-Dev-Server betrieben werden (`npm run dev`).
- Das Frontend kann **über Spring Boot gehostet** werden, indem der Build-Output nach `src/main/resources/static` kopiert wird.
- Das Frontend kommuniziert über die REST-API des **Orchestrators** mit dem Backend.
- Im Entwicklungsmodus leitet der Vite-Dev-Server Requests an `/orchestrator` an `http://localhost:8080` weiter.
- Das UI bietet ein übersichtliches Layout mit Karten, konsistentem Farbschema und Darkmode-Unterstützung.
- Formulare zur Identifikation und FSC-Eingabe sind mit Testdaten vorbelegt, um den Registrierungsflow direkt durchspielen zu können.
- Der aktuelle Session-Status und der nächste Schritt werden übersichtlich dargestellt.
- Nach erfolgreicher Anmeldung (`next.step = authenticated`) zeigt das Frontend zusätzlich `accountId` und `personId` aus der Backend-Response an.
- Das Frontend bietet eine Reset-Aktion, die den gespeicherten DPoP-Key löscht, einen neuen Key generiert und den Session-Flow neu startet.

### 3.2 Übersicht der Module

Die Applikation gliedert sich in fünf fachliche Module:

| Nr. | Modul | Verantwortung |
|-----|-------|---------------|
| M1 | `orchestrator` | Koordiniert Abläufe über die anderen Module; stellt REST-API für das Frontend bereit |
| M2 | `id_fsc` | Bereitstellung von Identifizierungsfunktionalität |
| M3 | `auth_sms` | Bereitstellung von SMS-Authentifizierungsfunktionalität |
| M4 | `account` | Verwaltung von Konten |
| M5 | `ext_stammdaten` | Zugriff auf externe Stammdaten; verwaltet `Person`-Entitäten mit Adressdaten |

### 3.3 Modulabhängigkeiten (C4 Component View)

```
         ┌─────────────┐
         │   Browser   │
         └──────┬──────┘
                │ HTTP / REST
                ▼
┌─────────────────────────────────────────────────────────────┐
│                        orchestrator                         │
│                (REST-API: /orchestrator/process)            │
└──────────┬─────────────┬──────────────┬───────────────────────┘
           │             │              │
           ▼             ▼              ▼                       ▼
     ┌─────────┐   ┌──────────┐   ┌──────────┐   ┌──────────────────┐
     │ id_fsc  │   │ auth_sms │   │ account  │   │ ext_stammdaten   │
     └─────────┘   └──────────┘   └──────────┘   └──────────────────┘
```

- Der `orchestrator` ist der einzige Modul, der die anderen Module referenzieren darf.
- Die Module `id_fsc`, `auth_sms`, `account` und `ext_stammdaten` sind voneinander entkoppelt.
- Die Package-Grenzen werden durch `@ApplicationModule` (Spring Modulith) abgesichert.
- Das Frontend kommuniziert ausschließlich über den `orchestrator` mit dem Backend.

### 3.4 Persistenz und Datenmodell

- Als Datenbank wird **H2** verwendet.
- Im Betrieb (Dev/Prod-Profil) wird eine dateibasierte Datenbank unter `./data/dpopdb` verwendet.
- Im Testprofil wird eine **In-Memory**-Datenbank verwendet.
- Das Datenbankschema wird mit **Flyway**-Migrationen aufgebaut.
- Der Datenbankzugriff erfolgt über **Spring Data JPA**.
- Im Modul `ext_stammdaten` existiert eine `Person`-Entität mit folgenden Attributen:
  - `id` (Primärschlüssel, auto-generiert)
  - `kvnr` (Krankenversicherungsnummer, eindeutig)
  - `name`
  - `vorname`
  - `strasse`
  - `hausnummer`
  - `plz`
  - `ort`
- Bei Applikationsstart werden Testdaten in die `person`-Tabelle eingespielt.
- Für die vorhandenen Testpersonen werden gültige FSC-Codes per Flyway-Migration eingespielt, damit der Registrierungsflow im UI direkt durchgespielt werden kann.
- Die verfügbaren Identifikations- und Authentifizierungsmethoden werden über Provider-Abstraktionen (`IdentificationMethodProvider`, `AuthenticationMethodProvider`) ermittelt, sodass der Flow unabhängig von konkreten Methoden bleibt.
- Binding-Sessions werden in einer gemeinsamen Tabelle `binding_session` persistiert (aktuelle technische Bezeichnung im Code/Schema derzeit noch `client_session`):
  - `binding_key_ref` (Primärschlüssel; aktuelle technische Spalte im Code/Schema derzeit noch `jwk_thumbprint`)
  - `expire_at`
  - `last_accessed`
  - `format` (`V1`)
  - `data` (JSON mit session-spezifischen Daten: `id` der Session, `phase`, `personId`, `accountId`, `selectedIdentificationMethod`, `selectedAuthenticationMethod`, `pendingChallenge`)
  - `version` (Optimistic Locking)
- Terminologieentscheidung (interaktiv mit Nutzer festgelegt):
  - Finales Namensset:
    - `client_session` -> `binding_session`
    - `jwk_thumbprint` -> `binding_key_ref`
  - Begruendung:
    - klare fachliche Begriffe ohne `jwk`/`dpop`/`proof` im Namen
    - strikte Abgrenzung zu OIDC-`client_id` (kein "client" im Schluesselbegriff)
    - zukunftsfaehig fuer weitere Client-Arten und moeglicherweise geteilte Sessions
    - konsistente Paarbildung zwischen Session- und Schluesselbegriff (`binding_*`)
  - Verworfene Alternativen:
    - Set A: `proof_key_session` / `proof_key_fingerprint`
    - Set B: `dpop_binding_session` / `dpop_jwk_fingerprint`
    - Set C: `auth_context_session` / `key_binding_id`
    - Set D: unveraendert `client_session` / `jwk_thumbprint`
    - Set F: `actor_session` / `actor_key_ref`
    - Set G: `context_session` / `context_key_ref`
    - Set H: `channel_session` / `channel_key_ref`
- Verbindliche Naming-Matrix Alt -> Neu (gueltig fuer kommende Umbenennungen in Schema, Code und Doku):

| Bereich | Alt (Ist) | Neu (Soll) | Konkreter Scope/Beispiel |
|---|---|---|---|
| DB Tabelle | `client_session` | `binding_session` | Migration `V7__create_client_session.sql`, JPA `@Table` in `ClientSession` |
| DB Spalte (Session-PK) | `jwk_thumbprint` | `binding_key_ref` | Session-Primärschlüssel in Tabelle `client_session`/`binding_session` |
| DB Tabelle (Mapping) | `account_jwk_mapping` | `account_binding_key_mapping` | Migration `V11__create_account_jwk_mapping.sql` |
| DB Spalte (Mapping-PK) | `jwk_thumbprint` | `binding_key_ref` | Primärschlüssel in `account_jwk_mapping`/`account_binding_key_mapping` |
| Java Entitaet | `ClientSession` | `BindingSession` | Datei `orchestrator/session/ClientSession.java` |
| Java Service | `ClientFlowSessionService` | `BindingFlowSessionService` | Datei `orchestrator/session/ClientFlowSessionService.java` |
| Java Service | `AccountJwkMappingService` | `AccountBindingKeyMappingService` | Datei `orchestrator/account/AccountJwkMappingService.java` |
| Java Feldname | `jwkThumbprint` | `bindingKeyRef` | Entitaeten/DTO-intern, z. B. in `ClientSession` |
| Java Methodenname | `findByJwkThumbprint(...)` | `findByBindingKeyRef(...)` | Repository/Service-Methoden fuer Session- und Mapping-Lookups |
| Java Methodenname | `getOrCreateByJwkThumbprint(...)` | `getOrCreateByBindingKeyRef(...)` | Session-Lifecycle in `ClientFlowSessionService` |
| API/Controller-intern | Variable `thumbprint` | Variable `bindingKeyRef` | `FlowController`/`FlowActionService` (externe REST-Pfade bleiben unveraendert) |
| API/DTO Fachbegriff | "JWK-Thumbprint als Session-Schluessel" | "Binding Key Reference als Session-Schluessel" | API-Dokumentation und Response-Beschreibungen |
| Doku Begriff | `client_session` | `binding_session` | Anforderungen, Architekturtexte, Sequenzbeschreibungen |
| Doku Begriff | `jwk_thumbprint` | `binding_key_ref` | Anforderungen, Architekturtexte, Sequenzbeschreibungen |
| Kryptografie-Begriff (bewusst unveraendert) | `JwkThumbprintService`, "JWK thumbprint" | **unveraendert** | Bleibt fuer RFC-7638-Berechnung bestehen; liefert Wert, der fachlich als `binding_key_ref` verwendet wird |
- Public-vs-Internal Naming-Regel (verbindlich):
  1. **Externe Vertraege (Public API)**: In REST-Endpunkten, JSON-Requests/-Responses, DTO-Feldnamen, OpenAPI-/README-Texten und Fehlermeldungen sind ausschliesslich fachliche Begriffe aus dem finalen Set zulaessig (`binding_session`, `binding_key_ref`, "Binding Session", "Binding Key Reference").
  2. **Interne Fachlogik (Domain/Application)**: In `orchestrator`, `account`, `id_fsc`, `auth_sms`, `ext_stammdaten` gelten ebenfalls die fachlichen Zielnamen; Altbegriffe (`client_*`, `jwk_thumbprint`) sind dort unzulaessig.
  3. **Low-Level-DPoP-Kryptografie (technische Ausnahme)**: Nur im Paket `src/main/java/com/example/dpop/orchestrator/dpop` duerfen technische RFC-Begriffe wie `thumbprint`/`JWK thumbprint` weiter verwendet werden, sofern sie ausschliesslich die kryptografische Berechnung bezeichnen.
  4. **Uebersetzungsregel zwischen Ebenen**: Der im DPoP-Modul berechnete `thumbprint` wird ausserhalb dieses Pakets als `binding_key_ref` weitergereicht; dieselbe Zeichenfolge, anderer fachlicher Name.
  5. **OIDC-Begriffe**: `client_id`/`clientId` und andere OIDC-behaftete Namen sind nur erlaubt, wenn sie explizit als OIDC-Kontext markiert sind (z. B. Kommentar "OIDC terminology"). Ohne diese Markierung sind sie verboten, um Verwechslungen mit Binding-Terminologie auszuschliessen.
  6. **Default bei Unklarheit**: Wenn ein neuer Name nicht eindeutig Public/Internal zuordenbar ist, gilt standardmaessig die fachliche Binding-Terminologie; technische Begriffe sind nur mit dokumentierter Ausnahme nach Regel 3 erlaubt.
- Umbenennungsstrategie (sequenziert, ohne Implementierung):

| Schritt | Reihenfolge | Ziel | Konkrete Arbeitsanweisung | Exit-Kriterium |
|---|---|---|---|---|
| 1 | Doku | Einheitliche Soll-Terminologie festschreiben | Anforderungen/README/API-Doku auf Naming-Matrix angleichen; Altbegriffe nur als `Legacy:` markieren | Alle fachlichen Beschreibungen nutzen `binding_session`/`binding_key_ref`; keine offenen TBD |
| 2 | Java Domain/Services | Fachliche Typen und Service-Namen angleichen | Klassen/Felder/Methoden in Domain+Service-Layer von `Client*`/`Jwk*` auf `Binding*` bzw. `bindingKeyRef` umbenennen; DPoP-Paket ausnehmen | Code kompiliert; keine Altbegriffe ausser DPoP-Ausnahme |
| 3 | Repositories | Persistenz-APIs konsistent machen | Repository-Namen und Finder (`findByJwkThumbprint`) auf `...BindingKeyRef` umstellen | Alle Repository-Methoden verwenden neue Namen |
| 4 | Controller/DTO | Public Contract sprachlich bereinigen | Interne Variablen/DTO-Felder und Fehlermeldungen auf Binding-Terminologie umstellen; URL-Pfade nur aendern, wenn explizit beschlossen | Kein Public Text mit Altbegriffen; OIDC-Ausnahmen explizit markiert |
| 5 | DB Migration | Physisches Schema nachziehen | Neue Flyway-Migration fuer Tabellen-/Spalten-Renames (oder Copy+Backfill+Drop) erstellen; bestehende Daten migrieren; keine Datenverluste | Schema enthaelt Zielnamen; Datenintegritaet und Tests grün |

Optionaler Kompatibilitaetszeitraum (wenn API/Schema-Risiko hoch):
- Dauer: **max. 1 Minor-Release** oder **30 Kalendertage** (was frueher eintritt).
- Alias-Regeln:
  - DB: Lesepfad darf Alt+Neu akzeptieren, Schreibpfad nur Neu.
  - Java: Deprecated Wrapper-Methoden fuer Alt-Namen erlaubt, muessen auf Neu delegieren.
  - API: Falls unvermeidbar, Alt-Feldnamen nur als input-kompatibler Alias; Responses nur Neu.
- Entferndatum: **spaetestens am Ende des Kompatibilitaetszeitraums**; anschliessend komplette Entfernung aller Alt-Aliase in einem dedizierten Cleanup-PR.

Abnahmekriterium fuer die Gesamtumbenennung:
- Keine Restverwendung von Altbegriffen (`client_session`, `jwk_thumbprint`, `ClientSession` als Fachbegriff etc.) ausser explizit markierten Legacy-Kommentaren oder der dokumentierten DPoP-Kryptografie-Ausnahme.
- Verifikation ueber Volltextsuche (`rg`) und gruene Test-Suite (`./gradlew test`).

Abhaengigkeiten und Risiken:
- Abhaengigkeit: Public-vs-Internal-Regel muss vor Codeumbenennung final sein.
- Risiko 1: Breaking Changes in API/DB bei ungeplanter simultaner Umstellung -> Mit Schrittfolge + optionalem Alias-Fenster minimieren.
- Risiko 2: OIDC-Verwechslung bei `client*`-Restbegriffen -> Durch harte Verbotsregel ausser explizitem OIDC-Kontext minimieren.
- Risiko 3: Inkonsistente Begriffe zwischen Modulen -> Durch Reihenfolge (Doku -> Domain -> Repo -> API -> DB) und abschliessende Suchprüfung minimieren.
- Die Session enthält keine separate Registrierungs- oder Authentifizierungssession; stattdessen entscheidet der `FlowNextStepResolver` anhand der Session-Daten und des Accounts, ob Identifikation, Setup einer Authentifizierungsmethode oder eine Authentifizierungs-Challenge erforderlich ist.
- Im Modul `id_fsc` existiert eine `FscCode`-Entität mit den Attributen `personId`, `code` und `expiresAt`.
- Im Modul `account` existiert eine `Account`-Entität mit folgenden Attributen:
  - `id` (Primärschlüssel, auto-generiert)
  - `personId` (Referenz auf die identifizierte Person)
  - `createdAt` (Zeitpunkt der Erstellung)
  - `identifications` (JSON-Array mit den durchgeführten Identifikationen)
- Eine `AccountIdentification` enthält:
  - `identificationMethod` (z. B. `fsc`)
  - `identificationQuality` (z. B. `HIGH`)
  - `identifiedAt` (Zeitpunkt der Identifikation)
  - `registrationSessionId` (Session, in der die Identifikation stattfand)
  - `details` (JSON mit weiteren wichtigen Daten, z. B. KVNR)
- Die `Account`-Entitaet speichert zusaetzlich `authenticationMethods` als JSON-Array.
  - Eine `AuthenticationMethod` enthaelt `method` (z. B. `sms`), `active`, `createdAt` und `details` (JSON, z. B. `smsSetupId`, `phoneNumber`).
  - Der Account kann mehrere verschiedene Authentifizierungsmethoden halten.
- Im Modul `auth_sms` existiert eine `AuthSmsSetup`-Entitaet in eigener Tabelle `auth_sms`:
  - `id` (Primaerschluessel, auto-generiert)
  - `phoneNumber`
  - `tan` (waehrend des Setups generierter Verifikationscode)
  - `validated` (Flag, ob die TAN erfolgreich bestaetigt wurde)
  - `createdAt`, `updatedAt`
  - Die Tabelle enthaelt keinen Fremdschluessel auf `account`, `client_session` oder `person`.
  - Der Verweis auf das SMS-Setup wird ausschliesslich vom Account in den `details` der Authentication-Methode gehalten.

### 3.5 Pflichtenheft

| ID | Anforderung | Kriterium |
|----|-------------|-----------|
| F1 | Jedes Modul besitzt ein eigenes Java-Package. | Package-Struktur unter `com.example.dpop.<modul>` |
| F2 | Jedes Modul enthält mindestens eine Service-Klasse. | `@Service` in jedem Modul vorhanden |
| F3 | Der `orchestrator` orchestriert alle anderen Module. | Konstruktor-Injection aller Modul-Services |
| F4 | Der `orchestrator` stellt eine REST-API für das Frontend bereit. | Endpunkt `/orchestrator/process` verfügbar |
| F5 | Die Modulstruktur ist verifizierbar. | `ApplicationModules.verify()` in Tests |
| F6 | Das Frontend kann autark und via Spring Boot betrieben werden. | `npm run dev` sowie `./gradlew bootRun` funktionieren |
| F7 | Das System verwendet H2 mit dateibasierter DB und In-Memory-Tests. | `application.yml` und `application-test.yml` korrekt konfiguriert |
| F8 | Schema-Aufbau erfolgt mit Flyway. | Migrationen unter `src/main/resources/db/migration/` |
| F9 | Zugriff auf Personen erfolgt über Spring Data JPA. | `PersonRepository extends JpaRepository` |
| F10 | Die Adresse einer Person ist in einzelne Attribute aufgeteilt. | Entität enthält `strasse`, `hausnummer`, `plz`, `ort` |
| F11 | Testdaten werden beim Start eingespielt. | Flyway-Migration oder Initialisierungsroutine vorhanden |
| F12 | Im Frontend wird ein DPoP-fähiges Schlüsselpaar erzeugt. | Asymmetrisches Keypair (ECDSA P-256) mit Web Crypto API |
| F13 | Das DPoP-Keypair wird im Browser persistiert. | Wiederverwendung über Seitenneuladungen hinweg |
| F14 | Der öffentliche DPoP-Schlüssel ist als JWK im Frontend einsehbar. | Anzeige des `jwk`-Teils im UI |
| F15 | Alle Registration-Aufrufe werden mit DPoP abgesichert. | Header `DPoP` enthält valides DPoP-Proof-JWT |
| F16 | Der Session-Einstieg erfolgt über einen POST-Endpunkt. | POST `/orchestrator/sessions` mit DPoP-Proof liefert `sessionId` und `next` |
| F17 | Die Abfrage verwendet die `binding_key_ref` als Schluessel. | Es gibt genau eine Binding-Session pro `binding_key_ref` |
| F18 | Bei fehlender Session wird der nächste Schritt "registration" zurückgegeben. | Ohne Identifikationsmethoden; diese folgen beim Setup |
| F19 | Eine neue Flow-Session wird über den Setup-Prozess erzeugt oder wiederverwendet. | POST `/orchestrator/sessions` liefert `sessionId` |
| F20 | Der Setup-Prozess verwendet die `binding_key_ref` als Schluessel. | Session wird anhand der `binding_key_ref` wiederverwendet |
| F21 | Folgende Aufrufe enthalten die `sessionId` im Pfad. | z.B. `/orchestrator/sessions/{id}/identification-methods/fsc` |
| F22 | Die Identifikationsmethode FSC wird über einen dedizierten Endpunkt gestartet. | POST `/orchestrator/sessions/{id}/identification-methods/fsc` mit KVNR, Name und Vorname |
| F23 | Der Orchestrator prüft die KVNR gegen die Stammdaten und fordert bei Erfolg die FSC-Eingabe an. | Antwort enthält `next: { context: "fsc", step: "input" }` |
| F24 | Der Freischaltcode wird per PATCH übermittelt und vom FSC-Service validiert. | PATCH `/orchestrator/sessions/{id}/identification-methods/fsc`; Prüfung auf Existenz und Ablauf |
| F25 | Nach erfolgreicher FSC-Validierung wird der Authentication-Setup-Schritt zurückgegeben. | Antwort enthält `next: { context: "authentication", step: "setup", authenticationMethods: ["sms"] }` |
| F25a | Nach erfolgreicher FSC-Validierung wird ein Account erstellt. | Account referenziert die `personId` und speichert mindestens Identifikationsmittel, -qualität, Zeitpunkt und Session-Id |
| F25b | Der erstellte Account wird in der Registration Session gemerkt. | `ClientSession.data` enthält die `accountId` |
| F25c | Ein Account kann mehrere Identifikationen speichern. | `identifications` ist ein JSON-Array in der Account-Tabelle |
| F32 | Nach erfolgreicher Identifikation wird der SMS-Setup-Schritt angeboten. | Antwort enthält `next: { context: "authentication", step: "setup", authenticationMethods: ["sms"] }` |
| F33 | Die SMS-Authentifizierung wird in zwei Schritten eingerichtet. | `POST .../authentication-methods/sms` speichert Telefonnummer, generiert TAN und sendet gemockte SMS; `POST .../authentication-methods/sms/verify-tan` validiert die TAN |
| F34 | Die Telefonnummer wird client- und serverseitig validiert. | Frontend prüft Format; Backend lehnt ungültige Nummern mit HTTP 400 ab |
| F35 | Der SMS-Versand und die TAN-Validierung werden im `auth_sms`-Modul ausgeführt. | `AuthSmsService` generiert TAN, mockt Versand und validiert TAN gegen die `auth_sms`-Tabelle |
| F36 | Bei erfolgreicher TAN-Validierung wird das `validated`-Flag in `auth_sms` gesetzt. | `validated` wechselt von `false` auf `true` |
| F37 | Bei erfolgreicher TAN-Validierung wird die Authentifizierungsmethode im Account gespeichert. | Account enthält `AuthenticationMethod` mit Verweis (`smsSetupId`) auf den Eintrag in `auth_sms` |
| F38 | Ein Account kann mehrere verschiedene Authentifizierungsmethoden speichern. | `authenticationMethods` ist ein JSON-Array in der Account-Tabelle |
| F39 | Nach erfolgreicher FSC-Identifikation wird ein bestehender Account zur Person wiederverwendet. | Der Flow erstellt keinen zweiten Account für dieselbe `personId` |
| F40 | Auch bei wiederverwendetem Account wird die neue Identifikation gespeichert. | `identifications` enthält für jede erfolgreiche FSC-Identifikation einen weiteren Eintrag |
| F41 | Der Orchestrator speichert die Zuordnung `binding_key_ref -> accountId`. | Persistente Mapping-Tabelle erlaubt mehrere Binding-Keys pro Account |
| F42 | Das SMS-Setup wird nur angeboten, wenn der Account keine aktive Methode besitzt. | Bei aktiver Methode erfolgt direkt Übergang zur Authentifizierungs-Auswahl |
| F43 | Beim Wechsel von Registrierung zur Anmeldung bleibt die Session erhalten; die Phase wechselt. | `binding_session.type` bleibt `FLOW`; `data.phase` und `next.context` leiten den Client weiter |
| F44 | In der Authentifizierungsphase werden vorhandene Methoden angeboten und die TAN dort bestätigt. | Die SMS-Challenge unter `/orchestrator/sessions/{id}/authentication-methods/sms/challenge` verwendet die im Account hinterlegte aktive Telefonnummer; der Client uebergibt keine Telefonnummer mehr |
| F45 | Jede Antwort enthält die aktuelle `sessionId`. | `sessionId` und `next` sind die einzigen Session-Felder in Responses |
| F46 | Bei erneutem Frontend-Start wird eine vorhandene Authentifizierungs-Session rotiert. | `POST /orchestrator/sessions` erzeugt bei bekanntem Account mit aktiver Methode eine neue `sessionId` und bietet `selectMethod` an |
| F26 | DPoP-Proofs werden gegen Replay-Angriffe abgesichert. | Wiederverwendung derselben Kombination aus JWK-Thumbprint und `jti` wird mit HTTP 401 abgewiesen |
| F27 | DPoP-Proofs haben eine begrenzte Gültigkeit über `iat`. | Proofs mit zu altem `iat` werden mit HTTP 401 abgewiesen |
| F28 | Der private DPoP-Schlüssel ist im Browser nicht exportierbar. | Erzeugung des Keypairs mit `extractable=false`, öffentliche JWK bleibt für Proof-Header exportierbar |
| F29 | Das DPoP-`iat`-Zeitfenster ist konfigurierbar. | `max-age-seconds` und `max-clock-skew-seconds` werden über `application.yml` gesetzt und im Validator verwendet |
| F30 | Das Frontend bietet ein benutzerfreundliches Layout mit Vorbelegung. | Formulare sind mit Testdaten vorbelegt und visuell als Karten gestaltet |
| F31 | FSC-Testdaten stehen beim Start zur Verfügung. | Flyway-Migration legt gültige FSC-Codes für die Testpersonen an |

### 3.6 DPoP- und Session-Ablauf (Beispiel)

Das Frontend erzeugt beim ersten Start ein ECDSA P-256 Schlüsselpaar und persistiert es im Browser (IndexedDB). Der öffentliche Schlüssel wird als JWK in den DPoP-Proofs übertragen. Das Backend leitet daraus einen JWK-Thumbprint (RFC 7638) ab und verwendet ihn als Schlüssel für Sessions.

#### Schritt 1: Session-Einstieg

```http
POST /orchestrator/sessions HTTP/1.1
Host: localhost:8080
DPoP: eyJ0eXAiOiJkcG9wK2p3dCIsImFsZyI6IkVTMjU2IiwiandrIjp7Imt0eSI6IkVDIiwiY3J2IjoiUC0yNTYi..."
```

Antwort bei noch unbekanntem Client (neue Session wird angelegt):

```json
{
  "sessionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "next": {
    "context": "registration",
    "step": "useIdentificationMethod",
    "identificationMethods": ["fsc"]
  }
}
```

Derselbe Endpunkt dient auch der Wiederverwendung und Rotation einer bestehenden Session.

#### Schritt 2: Identifikationsmethode auswählen

Das Frontend zeigt dem Nutzer die vom `IdentificationMethodProvider` ermittelten Methoden an (z. B. `fsc`). Die eigentliche Auswahl erfolgt durch den Aufruf des entsprechenden Endpunkts in Schritt 3.

#### Schritt 3: FSC-Identifikation starten

```http
POST /orchestrator/sessions/a1b2c3d4-e5f6-7890-abcd-ef1234567890/identification-methods/fsc HTTP/1.1
Host: localhost:8080
Content-Type: application/json
DPoP: eyJ0eXAiOiJkcG9wK2p3dCIsImFsZyI6IkVTMjU2IiwiandrIjp7Imt0eSI6IkVDIiwiY3J2IjoiUC0yNTYi..."

{
  "kvnr": "A123456789",
  "name": "Muster",
  "vorname": "Max"
}
```

Antwort bei bekannter KVNR:

```json
{
  "next": {
    "context": "fsc",
    "step": "input"
  }
}
```

Der Orchestrator speichert die zugeordnete `personId` in der Registration Session.

#### Schritt 4: Freischaltcode übermitteln

```http
PATCH /orchestrator/sessions/a1b2c3d4-e5f6-7890-abcd-ef1234567890/identification-methods/fsc HTTP/1.1
Host: localhost:8080
Content-Type: application/json
DPoP: eyJ0eXAiOiJkcG9wK2p3dCIsImFsZyI6IkVTMjU2IiwiandrIjp7Imt0eSI6IkVDIiwiY3J2IjoiUC0yNTYi..."

{
  "fsc": "VALIDCODE"
}
```

Antwort bei gültigem, nicht abgelaufenem FSC:

```json
{
  "next": {
    "context": "authentication",
    "step": "setup",
    "authenticationMethods": ["sms"]
  }
}
```

Falls für die identifizierte Person bereits ein Account mit aktiver Authentifizierungsmethode existiert, wird kein neues Setup verlangt. Stattdessen wechselt die Flow-Session in die Authentifizierungsphase und bietet die vorhandene Methode an:

```json
{
  "sessionId": "b2c3d4e5-f6a7-8901-bcde-f23456789012",
  "next": {
    "context": "authentication",
    "step": "selectMethod",
    "authenticationMethods": ["sms"]
  }
}
```

#### Schritt 5: SMS-Authentifizierung einrichten

##### 5a: Telefonnummer senden

```http
POST /orchestrator/sessions/a1b2c3d4-e5f6-7890-abcd-ef1234567890/authentication-methods/sms HTTP/1.1
Host: localhost:8080
Content-Type: application/json
DPoP: eyJ0eXAiOiJkcG9wK2p3dCIsImFsZyI6IkVTMjU2IiwiandrIjp7Imt0eSI6IkVDIiwiY3J2IjoiUC0yNTYi…"

{
  "phoneNumber": "+49 170 1234567"
}
```

Antwort:

```json
{
  "next": {
    "context": "authentication",
    "step": "smsTanInput",
    "smsSetupId": 1
  }
}
```

Das Backend validiert die Telefonnummer, speichert sie in der `auth_sms`-Tabelle (`validated: false`) zusammen mit einer generierten TAN und sendet eine gemockte Test-SMS.

##### 5b: TAN bestätigen

```http
POST /orchestrator/sessions/a1b2c3d4-e5f6-7890-abcd-ef1234567890/authentication-methods/sms/verify HTTP/1.1
Host: localhost:8080
Content-Type: application/json
DPoP: eyJ0eXAiOiJkcG9wK2p3dCIsImFsZyI6IkVTMjU2IiwiandrIjp7Imt0eSI6IkVDIiwiY3J2IjoiUC0yNTYi…"

{
  "smsSetupId": 1,
  "tan": "123456"
}
```

Antwort bei korrekter TAN:

```json
{
  "sessionId": "b2c3d4e5-f6a7-8901-bcde-f23456789012",
  "next": {
    "context": "authentication",
    "step": "selectMethod",
    "authenticationMethods": ["sms"]
  }
}
```

Nach erfolgreicher Validierung wird das `validated`-Flag in `auth_sms` auf `true` gesetzt, der Account um die `sms`-Authentication-Methode ergaenzt und die Flow-Session in die Authentifizierungsphase ueberfuehrt.

#### Schritt 6: SMS-Challenge in der Authentifizierungsphase

Nach dem Wechsel in die Authentifizierungsphase (entweder direkt nach Schritt 4 bei bestehender Methode oder nach Schritt 5b) wird die TAN in derselben Flow-Session bestaetigt:

##### 6a: Challenge starten

```http
POST /orchestrator/sessions/b2c3d4e5-f6a7-8901-bcde-f23456789012/authentication-methods/sms/challenge HTTP/1.1
Host: localhost:8080
DPoP: eyJ0eXAiOiJkcG9wK2p3dCIsImFsZyI6IkVTMjU2IiwiandrIjp7Imt0eSI6IkVDIiwiY3J2IjoiUC0yNTYi…"
```

Antwort:

```json
{
  "next": {
    "context": "authentication",
    "step": "smsTanInput",
    "smsSetupId": 2
  }
}
```

Die Telefonnummer wird aus der aktiven `sms`-Authentication-Methode des Accounts gelesen (z. B. aus `details.phoneNumber`) und nicht vom Client mitgesendet.

##### 6b: TAN bestaetigen

```http
POST /orchestrator/sessions/b2c3d4e5-f6a7-8901-bcde-f23456789012/authentication-methods/sms/verify HTTP/1.1
Host: localhost:8080
Content-Type: application/json
DPoP: eyJ0eXAiOiJkcG9wK2p3dCIsImFsZyI6IkVTMjU2IiwiandrIjp7Imt0eSI6IkVDIiwiY3J2IjoiUC0yNTYi…"

{
  "smsSetupId": 2,
  "tan": "123456"
}
```

Antwort bei korrekter TAN:

```json
{
  "next": {
    "context": "authentication",
    "step": "authenticated"
  }
}
```

#### Schritt 7: Erneuter Session-Einstieg

Nach erfolgreicher Registration liefert `POST /orchestrator/sessions` je nach Zustand:

- während der Registrierungsphase (Session wird wiederverwendet):

```json
{
  "sessionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "next": {
    "context": "registration",
    "step": "useIdentificationMethod",
    "identificationMethods": ["fsc"]
  }
}
```

- nach Abschluss der Registrierung (Login-Phase); bei der naechsten Initialisierung wird die Session-ID rotiert und eine neue Authentifizierungs-Challenge erzeugt:

```json
{
  "sessionId": "c3d4e5f6-a7b8-9012-cdef-345678901234",
  "next": {
    "context": "authentication",
    "step": "selectMethod",
    "authenticationMethods": ["sms"]
  }
}
```

Es existiert zu einem Zeitpunkt immer nur ein `ClientSession`-Eintrag pro JWK-Thumbprint mit `type=FLOW`. Die Phase der Session ergibt sich aus den gespeicherten Daten (`accountId`, `selectedAuthenticationMethod`, `pendingChallenge`) und wird vom `FlowNextStepResolver` in den `next`-Schritt uebersetzt.

Bei jedem neuen Initialisieren des Frontends (`POST /orchestrator/sessions`) wird eine bereits vorhandene Authentifizierungs-Session rotiert. Ist fuer den JWK-Thumbprint weiterhin ein Account mit mindestens einer aktiven Authentifizierungsmethode bekannt, wird sofort eine neue `sessionId` erzeugt und der Client erhaelt `next.step=selectMethod`. Der Nutzer muss sich so bei jedem erneuten Aufruf erneut authentifizieren.

## 4. Architekturbeschränkungen

| ID | Beschränkung | Begründung |
|----|--------------|------------|
| A1 | Build-Tool: Gradle mit Kotlin-DSL | Einheitliche, typsichere Build-Konfiguration |
| A2 | Gradle Wrapper muss enthalten sein | Reproduzierbarkeit ohne lokale Gradle-Installation |
| A3 | Java-Version 21 | Voraussetzung für Spring Boot 4.x |
| A4 | Aktuelle Spring Boot-Version verwenden | Sicherheit und Aktualität |
| A5 | Versionen zentral in `gradle/libs.versions.toml` pflegen | Zentrale Versionsverwaltung, konsistente Abhängigkeiten |
| A6 | Frontend-Build ist in den Gradle-Build integriert | Einheitlicher Build-Prozess für Backend und Frontend |
| A7 | Frontend-Build-Output landet in `src/main/resources/static` | Spring Boot liefert das Frontend als statische Ressource aus |
| A8 | Datenbank: H2 (dateibasiert im Betrieb, In-Memory in Tests) | Einfache lokale Entwicklung und schnelle Tests |
| A9 | Schema-Management mit Flyway | Versionierter und reproduzierbarer Datenbankaufbau |
| A10 | Datenzugriff mit Spring Data JPA | Standardisierte Persistenzschicht |

## 5. Lösungsstrategie

- **Framework**: Spring Boot 4.x mit eingebettetem Tomcat
- **Modularisierung**: Spring Modulith 2.x zur Architekturverifikation
- **Build**: Gradle 9.x mit Kotlin-DSL (`build.gradle.kts`, `settings.gradle.kts`)
- **Versionsverwaltung**: Gradle Version Catalog in `gradle/libs.versions.toml`
- **Persistenz**: H2 + Spring Data JPA + Flyway
- **Frontend**: React 19.x + TypeScript 6.x mit Vite 8.x
- **Frontend-Integration**: Vite-Build schreibt in `src/main/resources/static`; Gradle führt `npm install` und `npm run build` aus
- **Test**: JUnit 5 mit Spring Boot Test und Spring Modulith Test-Starter

## 6. Verwendete Versionen

| Komponente | Version |
|------------|---------|
| Spring Boot | `4.1.0` |
| Spring Modulith | `2.1.0` |
| Dependency Management Plugin | `1.1.7` |
| Gradle (Wrapper) | `9.7.0` |
| Java | `21` |
| React | `19.2.8` |
| React DOM | `19.2.8` |
| TypeScript | `6.0.2` |
| Vite | `8.2.0` |
| H2 | (von Spring Boot verwaltet) |
| Flyway | (von Spring Boot verwaltet) |

## 7. Verifikation

- `./gradlew build` baut Backend und Frontend und führt alle Tests aus.
- `./gradlew bootRun` startet die Applikation auf Port 8080 (blockierend; für Verifikation lieber Integrationstests verwenden).
- Integrationstests starten den eingebetteten Server auf einem zufälligen Port und prüfen den Endpunkt `/orchestrator/process` sowie den vollständigen DPoP-Session-Flow.

## 8. Abnahmekriterien

- [x] `./gradlew build` läuft erfolgreich durch.
- [x] `ApplicationModules.verify()` bestätigt die Einhaltung der Modulabhängigkeiten.
- [x] Der Integrationstest für `/orchestrator/process` liefert eine Antwort aus dem `orchestrator` und enthält Personen-Daten aus `ext_stammdaten`.
- [x] Der Integrationstest für den Session-Flow durchläuft Registrierung, FSC-Identifikation, SMS-Setup und TAN-Bestaetigung.
- [x] Das Frontend ist über Spring Boot (`./gradlew bootRun`) erreichbar.
- [x] Das Frontend kann autark über `npm run dev` im Verzeichnis `frontend/` betrieben werden.
- [x] Das Frontend zeigt den Session-Status übersichtlich an und erlaubt das Durchspielen des Registrierungsflows mit vorbelegten Testdaten.
