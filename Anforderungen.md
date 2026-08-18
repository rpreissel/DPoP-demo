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
- Fuer das diskutierte Zielbild der App-/Keycloak-Kopplung gilt zusaetzlich folgende API-Regel: Das oeffentliche API wird unter `/orchestrator/api/v1` versioniert. Die Prozess-API bleibt kanalabhaengig getrennt (App vs. Keycloak); im oeffentlichen App-API gibt es jedoch keinen separaten Prozessstart mehr. Stattdessen erzeugt oder liest der Einstiegscall die `channelSessionId`, und das Backend leitet unmittelbar anhand von Kanalzustand, Accountstatus und Policy den intern noetigen Prozess (`REGISTRATION`, `LOGIN` oder `STEP_UP`) ab und liefert direkt den ersten fachlichen `next`-Schritt. Die interne `processSessionId` bleibt fuer Persistenz, Korrelation und Audit bestehen, ist aber kein Bestandteil der oeffentlichen Ziel-URLs. Unterschiedliche fachliche Varianten duerfen nicht durch denselben Endpunkt nur anhand verschiedener Request-Daten unterschieden werden; stattdessen werden methoden- und modusspezifische Attempt-Ressourcen mit jeweils eigenem URL-Muster und eigenem Request-Schema modelliert, insbesondere fuer `fsc`, `sms/setup` und `sms/use`. Fuer vorbereitende Methoden gilt ein ressourcenorientiertes Muster: `POST` legt eine Attempt-Ressource an (auch mit teilweisen Daten), `PATCH` ergaenzt fehlende Daten auf derselben Ressource, `GET` liest den aktuellen Stand. Solange Daten fehlen, antwortet der Server erfolgreich mit `status=INPUT_REQUIRED` und `missingFields`; sobald alle Pflichtdaten vorliegen und validiert sind, liefert dieselbe Ressource das Ergebnis mit `status=VERIFIED` plus `result`. Das Rueckgabeobjekt `next` verwendet im Zielbild nur zwei Routing-Attribute: `context` und `step`. `context` benennt den fuer die UI relevanten Kontext, also entweder einen generischen fachlichen Bereich wie `registration` oder den konkreten Methodenkontext wie `fsc` bzw. `sms`. `step` beschreibt nur die Phase innerhalb dieses Kontexts, z. B. `selectIdentificationMethod`, `setup`, `use`, `input`, `tanInput` oder `authenticated`. Die UI-Fortsetzung wird ausschliesslich aus `(context, step)` abgeleitet; URLs sind fest vorgegeben und keine Entscheidungsquelle. HATEOAS-Links sind im Zielbild nicht vorgesehen.
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
  - Eine `AuthenticationMethod` enthaelt `method` (z. B. `sms`), `active`, `createdAt` und `details` (JSON, z. B. `smsSetupId`).
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
| F16 | Der Session-Einstieg erfolgt ueber einen versionierten POST-Endpunkt. | POST `/orchestrator/api/v1/app/channels` mit DPoP-Proof liefert `channelSessionId` und `next` |
| F17 | Die Abfrage verwendet die `binding_key_ref` als Schluessel. | Es gibt genau eine Binding-Session pro `binding_key_ref` |
| F18 | Bei fehlender Session wird direkt der erste Registrierungs-Schritt zurueckgegeben. | Antwort enthaelt `next: { context: "registration", step: "selectIdentificationMethod" }` |
| F19 | Eine Channel-Session wird beim Einstieg erzeugt oder wiederverwendet; der interne Prozess wird serverseitig abgeleitet. | POST `/orchestrator/api/v1/app/channels` liefert `channelSessionId` und den ersten fachlichen `next`-Schritt |
| F20 | Der Einstieg verwendet die `binding_key_ref` als Schluessel. | Die `channelSessionId` wird anhand der `binding_key_ref` wiederverwendet oder neu angelegt |
| F21 | Methoden- und modusspezifische Aufrufe enthalten die `channelSessionId` und die konkrete Variante im Pfad. | z. B. `/orchestrator/api/v1/app/channels/{channelSessionId}/identification-methods/fsc/attempts` oder `/orchestrator/api/v1/app/channels/{channelSessionId}/authentication-methods/sms/setup/attempts` |
| F22 | Die Identifikationsmethode FSC wird als Attempt-Ressource modelliert und kann vollstaendig oder schrittweise befuellt werden. | `POST /orchestrator/api/v1/app/channels/{channelSessionId}/identification-methods/fsc/attempts` akzeptiert `kvnr`, `name`, `vorname` und optional `fsc`; `PATCH /orchestrator/api/v1/identification-methods/fsc/attempts/{attemptId}` ergaenzt fehlende Felder |
| F23 | Der Orchestrator prüft die KVNR gegen die Stammdaten und fordert bei Erfolg die FSC-Eingabe an. | Antwort enthält `next: { context: "fsc", step: "input" }` |
| F24 | Der Freischaltcode wird auf derselben FSC-Attempt-Ressource uebermittelt und vom FSC-Service validiert. | `PATCH /orchestrator/api/v1/identification-methods/fsc/attempts/{attemptId}` mit Feld `fsc`; Pruefung auf Existenz und Ablauf |
| F25 | Nach erfolgreicher FSC-Validierung liefert dieselbe Attempt-Ressource das fachliche Ergebnis und den naechsten Schritt zur Authentifizierungs-Auswahl. | Antwort enthaelt `status: "VERIFIED"`, `result` und `next: { context: "authentication", step: "selectMethod", authenticationMethods: [...] }` |
| F25a | Nach erfolgreicher FSC-Validierung wird ein Account erstellt. | Account referenziert die `personId` und speichert mindestens Identifikationsmittel, -qualität, Zeitpunkt und Session-Id |
| F25b | Der erstellte Account wird in der Registration Session gemerkt. | `ClientSession.data` enthält die `accountId` |
| F25c | Ein Account kann mehrere Identifikationen speichern. | `identifications` ist ein JSON-Array in der Account-Tabelle |
| F32 | Nach erfolgreicher Identifikation werden die moeglichen Authentifizierungsverfahren im vorhergehenden Schritt aufgelistet. | Antwort enthaelt `next: { context: "authentication", step: "selectMethod", authenticationMethods: [...] }` |
| F33 | Die SMS-Authentifizierung wird fuer Setup und Use als getrennte Attempt-Ressourcen mit `POST`/`PATCH`/`GET` modelliert. | `POST /orchestrator/api/v1/app/channels/{channelSessionId}/authentication-methods/sms/setup/attempts` legt die Setup-Ressource an; `PATCH /orchestrator/api/v1/authentication-methods/sms/setup/attempts/{attemptId}` verarbeitet `phoneNumber` oder `tan`; fuer `sms/use` existieren analoge, aber eigene Endpunkte |
| F34 | Die Telefonnummer wird client- und serverseitig validiert. | Frontend prüft Format; Backend lehnt ungültige Nummern mit HTTP 400 ab |
| F35 | Der SMS-Versand und die TAN-Validierung werden im `auth_sms`-Modul ausgeführt. | `AuthSmsService` generiert TAN, mockt Versand und validiert TAN gegen die `auth_sms`-Tabelle |
| F36 | Bei erfolgreicher TAN-Validierung wird das `validated`-Flag in `auth_sms` gesetzt. | `validated` wechselt von `false` auf `true` |
| F37 | Bei erfolgreicher TAN-Validierung wird die Authentifizierungsmethode im Account gespeichert. | Account enthält `AuthenticationMethod` mit Verweis (`smsSetupId`) auf den Eintrag in `auth_sms` |
| F38 | Ein Account kann mehrere verschiedene Authentifizierungsmethoden speichern. | `authenticationMethods` ist ein JSON-Array in der Account-Tabelle |
| F39 | Nach erfolgreicher FSC-Identifikation wird ein bestehender Account zur Person wiederverwendet. | Der Flow erstellt keinen zweiten Account für dieselbe `personId` |
| F40 | Auch bei wiederverwendetem Account wird die neue Identifikation gespeichert. | `identifications` enthält für jede erfolgreiche FSC-Identifikation einen weiteren Eintrag |
| F41 | Der Orchestrator speichert die Zuordnung `binding_key_ref -> accountId`. | Persistente Mapping-Tabelle erlaubt mehrere Binding-Keys pro Account |
| F42 | Das SMS-Setup wird nur angeboten, wenn der Account keine aktive Methode besitzt. | Bei aktiver Methode erfolgt direkt Übergang zur Authentifizierungs-Auswahl |
| F43 | Beim Wechsel von Registrierung zur Anmeldung bleibt die Channel-Session erhalten; nur der interne Prozess wechselt. | `channelSessionId` bleibt stabil; `next.context` und `next.step` leiten den Client weiter |
| F44 | In der Authentifizierungsphase werden vorhandene Methoden ueber konkrete Use-Attempt-Ressourcen verwendet. | Die SMS-Challenge unter `/orchestrator/api/v1/authentication-methods/sms/use/attempts/{attemptId}` verwendet die im Setup (`auth_sms`) gespeicherte Nummer ueber `smsSetupId`; der Client uebergibt keine Telefonnummer mehr |
| F45 | Responses enthalten nur technische IDs/Zustaende und kein HATEOAS. | Einstieg liefert `channelSessionId`; Attempt-Responses liefern `attemptId`, `status`, `missingFields` (falls unvollstaendig), optional `result` (falls verifiziert) und `next` |
| F47 | HTTP-Statuscodes fuer vorbereitende Attempt-Ressourcen sind einheitlich definiert. | `POST` -> `201 Created`; `PATCH`/`GET` -> `200 OK`; unvollstaendige Daten sind kein Fehler (`INPUT_REQUIRED` im Body); fachlich ungueltige Daten -> `422`; ungueltiger Zustandswechsel -> `409` |
| F46 | Bei erneutem Frontend-Start wird ein bekannter Kanal ueber denselben Einstiegscall fortgesetzt und der noetige Login-Schritt serverseitig bestimmt. | `POST /orchestrator/api/v1/app/channels` verwendet bei bekanntem Account die bestehende `channelSessionId` und liefert fuer die Anmeldung zunaechst `next: { context: "authentication", step: "selectMethod" }` |
| F26 | DPoP-Proofs werden gegen Replay-Angriffe abgesichert. | Wiederverwendung derselben Kombination aus JWK-Thumbprint und `jti` wird mit HTTP 401 abgewiesen |
| F27 | DPoP-Proofs haben eine begrenzte Gültigkeit über `iat`. | Proofs mit zu altem `iat` werden mit HTTP 401 abgewiesen |
| F28 | Der private DPoP-Schlüssel ist im Browser nicht exportierbar. | Erzeugung des Keypairs mit `extractable=false`, öffentliche JWK bleibt für Proof-Header exportierbar |
| F29 | Das DPoP-`iat`-Zeitfenster ist konfigurierbar. | `max-age-seconds` und `max-clock-skew-seconds` werden über `application.yml` gesetzt und im Validator verwendet |
| F30 | Das Frontend bietet ein benutzerfreundliches Layout mit Vorbelegung. | Formulare sind mit Testdaten vorbelegt und visuell als Karten gestaltet |
| F31 | FSC-Testdaten stehen beim Start zur Verfügung. | Flyway-Migration legt gültige FSC-Codes für die Testpersonen an |

### 3.6 DPoP- und Session-Ablauf (Beispiel)

Das Frontend erzeugt beim ersten Start ein ECDSA-P-256-Schluesselpaar und persistiert es im Browser (IndexedDB). Der oeffentliche Schluessel wird als JWK in den DPoP-Proofs uebertragen. Das Backend leitet daraus einen JWK-Thumbprint (RFC 7638) ab und verwendet ihn fachlich als `binding_key_ref`. Das oeffentliche API ist unter `/orchestrator/api/v1` versioniert. Antworten enthalten keine HATEOAS-Links; der Client leitet den naechsten Schritt ausschliesslich aus `next.context` und `next.step` ab.

Ressourcenmuster fuer vorbereitende Methoden (Identifikation/Authentifizierung):

- `POST` auf die Attempt-Collection legt eine neue Attempt-Ressource an (`201 Created`, mit `Location`).
- `PATCH` auf dieselbe Attempt-Ressource ergaenzt fehlende Felder.
- `GET` auf dieselbe Attempt-Ressource liefert den aktuellen Stand.
- Solange Pflichtdaten fehlen: `status = INPUT_REQUIRED`, `missingFields = [...]`, HTTP `200` (kein Fehlerfall).
- Wenn alle Pflichtdaten vorliegen und fachlich gueltig sind: `status = VERIFIED`, `result` ist gesetzt, HTTP `200`.
- Bei fachlich ungueltigen Eingaben: HTTP `422`; bei ungueltigem Zustandswechsel: HTTP `409`.

#### Schritt 1: Channel-Einstieg

```http
POST /orchestrator/api/v1/app/channels HTTP/1.1
Host: localhost:8080
Content-Type: application/json
DPoP: eyJ0eXAiOiJkcG9wK2p3dCIsImFsZyI6IkVTMjU2IiwiandrIjp7Imt0eSI6IkVDIiwiY3J2IjoiUC0yNTYi..."

{
  "channelSessionId": null
}
```

Antwort bei noch unbekanntem Kanal:

```json
{
  "channelSessionId": "c1111111-1111-1111-1111-111111111111",
  "state": "ANONYMOUS",
  "next": {
    "context": "registration",
    "step": "selectIdentificationMethod",
    "identificationMethods": ["fsc"]
  }
}
```

Der Einstieg erzeugt oder liest die `channelSessionId` und startet intern bei Bedarf einen `REGISTRATION`-, `LOGIN`- oder `STEP_UP`-Prozess.

#### Schritt 2: FSC-Attempt anlegen (optional direkt mit Daten)

```http
POST /orchestrator/api/v1/app/channels/c1111111-1111-1111-1111-111111111111/identification-methods/fsc/attempts HTTP/1.1
Host: localhost:8080
Content-Type: application/json
DPoP: eyJ0eXAiOiJkcG9wK2p3dCIsImFsZyI6IkVTMjU2IiwiandrIjp7Imt0eSI6IkVDIiwiY3J2IjoiUC0yNTYi..."

{
  "kvnr": "A123456789",
  "name": "Muster",
  "vorname": "Max"
}
```

Antwort bei unvollstaendigen Daten (`fsc` fehlt):

```json
{
  "attemptId": "i7777777-7777-7777-7777-777777777777",
  "status": "INPUT_REQUIRED",
  "input": {
    "kvnr": "A123456789",
    "name": "Muster",
    "vorname": "Max"
  },
  "missingFields": ["fsc"],
  "result": null,
  "next": {
    "context": "fsc",
    "step": "input"
  }
}
```

Falls alle Pflichtdaten bereits im `POST` enthalten sind (`kvnr`, `name`, `vorname`, `fsc`), wird die Identifikation in demselben Request validiert und direkt ein verifiziertes Ergebnis geliefert (`201 Created`).

#### Schritt 3: FSC nachliefern (PATCH auf dieselbe Ressource)

```http
PATCH /orchestrator/api/v1/identification-methods/fsc/attempts/i7777777-7777-7777-7777-777777777777 HTTP/1.1
Host: localhost:8080
Content-Type: application/json
DPoP: eyJ0eXAiOiJkcG9wK2p3dCIsImFsZyI6IkVTMjU2IiwiandrIjp7Imt0eSI6IkVDIiwiY3J2IjoiUC0yNTYi..."

{
  "fsc": "VALIDCODE"
}
```

Antwort bei gueltiger, vollstaendiger Eingabe:

```json
{
  "attemptId": "i7777777-7777-7777-7777-777777777777",
  "status": "VERIFIED",
  "input": {
    "kvnr": "A123456789",
    "name": "Muster",
    "vorname": "Max",
    "fsc": "******"
  },
  "missingFields": [],
  "result": {
    "identified": true,
    "personId": 456
  },
  "next": {
    "context": "authentication",
    "step": "selectMethod",
    "authenticationMethods": ["sms"]
  }
}
```

Der Orchestrator speichert die zugeordnete `personId` in der Registration Session.

#### Schritt 4: Alternative - alles in einem POST

```http
POST /orchestrator/api/v1/app/channels/c1111111-1111-1111-1111-111111111111/identification-methods/fsc/attempts HTTP/1.1
Host: localhost:8080
Content-Type: application/json
DPoP: eyJ0eXAiOiJkcG9wK2p3dCIsImFsZyI6IkVTMjU2IiwiandrIjp7Imt0eSI6IkVDIiwiY3J2IjoiUC0yNTYi..."

{
  "kvnr": "A123456789",
  "name": "Muster",
  "vorname": "Max",
  "fsc": "VALIDCODE"
}
```

Antwort bei gueltigem, nicht abgelaufenem FSC und noch fehlender aktiver Methode:

```json
{
  "attemptId": "i7777777-7777-7777-7777-777777777777",
  "status": "VERIFIED",
  "result": {
    "identified": true,
    "personId": 456
  },
  "next": {
    "context": "authentication",
    "step": "selectMethod",
    "authenticationMethods": ["sms"]
  }
}
```

Falls fuer die identifizierte Person bereits ein Account mit aktiver SMS-Methode existiert, wird ebenfalls `selectMethod` geliefert; die vorhandenen Methoden werden dort gelistet, danach erfolgt der direkte Aufruf der passenden Attempt-Route.

```json
{
  "attemptId": "i7777777-7777-7777-7777-777777777777",
  "status": "VERIFIED",
  "result": {
    "identified": true,
    "personId": 456
  },
  "next": {
    "context": "authentication",
    "step": "selectMethod",
    "authenticationMethods": ["sms"]
  }
}
```

#### Schritt 5: SMS-Setup-Attempt anlegen und aktualisieren

##### 5a: Setup-Attempt reservieren

```http
POST /orchestrator/api/v1/app/channels/c1111111-1111-1111-1111-111111111111/authentication-methods/sms/setup/attempts HTTP/1.1
Host: localhost:8080
Content-Type: application/json
DPoP: eyJ0eXAiOiJkcG9wK2p3dCIsImFsZyI6IkVTMjU2IiwiandrIjp7Imt0eSI6IkVDIiwiY3J2IjoiUC0yNTYi..."

{}
```

Antwort:

```json
{
  "attemptId": "a3333333-3333-3333-3333-333333333333",
  "status": "INPUT_REQUIRED",
  "missingFields": ["phoneNumber"],
  "result": null,
  "next": {
    "context": "sms",
    "step": "setup"
  }
}
```

##### 5b: Telefonnummer uebergeben

```http
PATCH /orchestrator/api/v1/authentication-methods/sms/setup/attempts/a3333333-3333-3333-3333-333333333333 HTTP/1.1
Host: localhost:8080
Content-Type: application/json
DPoP: eyJ0eXAiOiJkcG9wK2p3dCIsImFsZyI6IkVTMjU2IiwiandrIjp7Imt0eSI6IkVDIiwiY3J2IjoiUC0yNTYi..."

{
  "phoneNumber": "+49 170 1234567"
}
```

Antwort:

```json
{
  "attemptId": "a3333333-3333-3333-3333-333333333333",
  "status": "INPUT_REQUIRED",
  "missingFields": ["tan"],
  "next": {
    "context": "sms",
    "step": "tanInput"
  }
}
```

Das Backend validiert die Telefonnummer, speichert sie in der `auth_sms`-Tabelle (`validated: false`) zusammen mit einer generierten TAN und sendet eine gemockte Test-SMS.

##### 5c: TAN bestaetigen

```http
PATCH /orchestrator/api/v1/authentication-methods/sms/setup/attempts/a3333333-3333-3333-3333-333333333333 HTTP/1.1
Host: localhost:8080
Content-Type: application/json
DPoP: eyJ0eXAiOiJkcG9wK2p3dCIsImFsZyI6IkVTMjU2IiwiandrIjp7Imt0eSI6IkVDIiwiY3J2IjoiUC0yNTYi..."

{
  "tan": "123456"
}
```

Antwort bei korrekter TAN:

```json
{
  "attemptId": "a3333333-3333-3333-3333-333333333333",
  "status": "VERIFIED",
  "result": {
    "authenticated": true,
    "method": "sms",
    "mode": "setup"
  },
  "next": {
    "context": "authentication",
    "step": "authenticated",
    "accountId": 123,
    "personId": 456
  }
}
```

Nach erfolgreicher Validierung wird das `validated`-Flag in `auth_sms` auf `true` gesetzt, der Account um die `sms`-Authentication-Methode ergaenzt und der Channel auf `AUTHENTICATED` aktualisiert.

#### Schritt 6: Erneuter Einstieg und SMS-Use

Beim erneuten Frontend-Start mit bekanntem Kanal liefert derselbe Einstiegscall den noetigen Login-Schritt:

```http
POST /orchestrator/api/v1/app/channels HTTP/1.1
Host: localhost:8080
Content-Type: application/json
DPoP: eyJ0eXAiOiJkcG9wK2p3dCIsImFsZyI6IkVTMjU2IiwiandrIjp7Imt0eSI6IkVDIiwiY3J2IjoiUC0yNTYi..."

{
  "channelSessionId": "c1111111-1111-1111-1111-111111111111"
}
```

```json
{
  "channelSessionId": "c1111111-1111-1111-1111-111111111111",
  "state": "AUTHENTICATED",
  "next": {
    "context": "authentication",
    "step": "selectMethod",
    "authenticationMethods": ["sms"]
  }
}
```

Danach folgen die festen, vom Setup getrennten Use-Endpunkte:

- `POST /orchestrator/api/v1/app/channels/{channelSessionId}/authentication-methods/sms/use/attempts`
- `PATCH /orchestrator/api/v1/authentication-methods/sms/use/attempts/{attemptId}` (z. B. TAN)
- `GET /orchestrator/api/v1/authentication-methods/sms/use/attempts/{attemptId}`

Bei `sms/use` wird keine Telefonnummer vom Client uebergeben. Die zu verwendende Nummer kommt aus dem zuvor validierten SMS-Setup im Modul `auth_sms` (Referenz ueber `smsSetupId`) und nicht aus einem allgemeinen Kontaktfeld am Account. Pro `binding_key_ref` existiert weiterhin genau ein aktiver Kanalbezug; welche fachliche Phase gerade aktiv ist, ergibt sich aus dem intern gefuehrten Prozess und wird vom `FlowNextStepResolver` in den `next`-Schritt uebersetzt.

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
| A11 | Lesbarkeit hat Vorrang vor maximal generischem API-Wiring. | Endpunkte, DTOs und Handler bleiben methoden-/modusspezifisch explizit (`fsc`, `sms/setup`, `sms/use`), auch wenn dadurch mehr, aber klarerer Code entsteht |

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

## 7. App-Only Orchestrator Persistierungsmodell (v1)

### 7.1 Zielstruktur (Konzept-konform nach orchestrator-keycloak-session-konzept.md)

Das Persistierungsmodell wurde vollständig gemäß dem orchestrator-keycloak-session-konzept.md refaktoriert. Die Struktur unterstützt:
- Stabile, langlebige Kanal-Sessions mit DPoP-Bindung (APP/WEB)
- Kurzlebige Prozess-Sessions (REGISTRATION, LOGIN, STEP_UP)
- Zentrale Attempt-Metadaten für Identification/Authentication
- Keycloak/AuthContext-Integration für Web-Kanal
- State-Management für Channel-Lifecycle

#### 7.1.1 ChannelSession

- **Rolle**: Stabile, langlebige Kanal-Bindung (APP oder WEB) mit DPoP-Bindung
- **Identifikat**: `channelSessionId` (UUID)
- **Schlüsselattribute**:
  - `channel`: APP oder WEB
  - `bindingKeyRef`: Eindeutige DPoP-Binding-Referenz (JWK-Thumbprint)
  - `state`: `ChannelState` Enum (ANONYMOUS, REGISTERING, AUTHENTICATED, STEP_UP_REQUIRED, STEP_UP_IN_PROGRESS, LOGGED_OUT, EXPIRED)
  - `accountId`: Referenz zum Account (nullable für anonyme Sessions)
  - `authContextId`: Referenz zu AuthContext für Keycloak-Integration (nullable)
  - `createdAt`, `lastAccessedAt`, `expiresAt`: Lifecycle-Timestamps
  - `version`: Optimistic Locking
- **Persistierung**: Tabelle `channel_session` (Flyway V13+V14)
- **Repository**: `ChannelSessionRepository`

#### 7.1.2 ProcessSession (abstrakt mit Spezialisierungen)

- **Rolle**: Kurzlebige Prozess-Kontexte für REGISTRATION, LOGIN oder STEP_UP
- **Basisklasse**: `ProcessSession` (abstrakt)
- **Spezialisierungen**:

| Typ | Klasse | Spezialattribute | Einsatz |
|-----|--------|------------------|--------|
| REGISTRATION | `RegistrationProcessSession` | `personId`, `selectedIdentificationMethod`, `selectedAuthenticationMethod`, `pendingChallenge` | Neuer Account im App-Kanal |
| LOGIN | `LoginProcessSession` | `selectedAuthenticationMethod`, `pendingChallenge` | Anmeldung bestehender Account |
| STEP_UP | `StepUpProcessSession` | `requiredAcr`, `startingAcr`, `achievedAcr`, `selectedAuthenticationMethod`, `pendingChallenge` | Erhöhung der Authentifizierungsstufe |

- **Gemeinsame Attribute** (alle Typen):
  - `processSessionId` (UUID)
  - `channelSessionId` (Foreign Key zu `ChannelSession`)
  - `purpose` (Discriminator: REGISTRATION, LOGIN, STEP_UP)
  - `status`: STARTED, CHALLENGE_SENT, VERIFY_PENDING, SUCCEEDED, FAILED, CANCELLED, EXPIRED, CONSUMED
  - `accountId` (nullable)
  - `selectedIdentificationMethod` (String, nullable)
  - `selectedAuthenticationMethod` (String, nullable)
  - `personId` (Long, nullable)
  - `pendingChallenge` (JSON, nullable)
  - `createdAt`, `expiresAt`, `consumedAt` (nullable)
  - `version`: Optimistic Locking
- **Persistierung**: Tabelle `process_session` mit Single-Table-Inheritance (Flyway V13+V14)
- **Repository**: `ProcessSessionRepository`

#### 7.1.3 OrchestratorAttempt (abstrakt mit Spezialisierungen)

- **Rolle**: Zentrale Lifecycle- und Routing-Metadaten für Identifikations- und Authentifizierungsversuche
- **Basisklasse**: `OrchestratorAttempt` (abstrakt)
- **Spezialisierungen**:

| Typ | Klasse | Einsatz |
|-----|--------|--------|
| IDENTIFICATION | `IdentificationAttempt` | FSC/KVNR-Identifikation |
| AUTHENTICATION | `AuthenticationAttempt` | SMS-Setup/Use, TAN-Verifizierung, etc. |

- **Gemeinsame Attribute** (beide Typen):
  - `attemptId` (UUID)
  - `processSessionId` (Foreign Key zu `ProcessSession`)
  - `attemptType` (Discriminator: `identification`, `authentication`)
  - `status`: INPUT_REQUIRED, VERIFIED, FAILED, EXPIRED, CANCELLED
  - `missingFields`: String-Array (JSON) mit fehlenden Feldern
  - `result`: JSON-Objekt mit Verifizierungsergebnis (nullable)
  - `createdAt`, `expiresAt`: Lifecycle
  - `retryCount`: Zähler für Wiederholung
  - `version`: Optimistic Locking
- **Persistierung**: Tabelle `orchestrator_attempt` mit Single-Table-Inheritance (Flyway V13+V14)
- **Repository**: `OrchestratorAttemptRepository` + Subtyp-Repositories

### 7.1.4 AuthContext (Keycloak-Integration)

- **Rolle**: Serverseitige Speicherung von IAM-Kontext und Keycloak-Tokenverwaltung
- **Identifikat**: `authContextId` (UUID, Primary Key)
- **Schlüsselattribute**:
  - `accountId`: Referenz zum Account
  - `keycloakSessionId`: Session-ID aus Keycloak (für Web-Kanal)
  - `keycloakSubject`: Nutzer-ID aus Keycloak
  - `tokenHandle`: Referenz zum Keycloak-Token
  - `currentAcr`: Gegenwärtige Authentication Context Reference (z. B. "loa1", "loa2")
  - `currentAmr`: JSON-Array mit Authentication Methods (z. B. ["fsc", "sms"])
  - `authTime`: Zeitstempel der Authentifizierung
  - `tokenExpiresAt`: Ablaufdatum des Tokens
  - `refreshExpiresAt`: Ablaufdatum des Refresh-Tokens
  - `updatedAt`: Letzte Aktualisierung
- **Persistierung**: Tabelle `auth_context` (Flyway V14)
- **Repository**: `AuthContextRepository`

### 7.2 SessionManagementService und AuthContextService

- **SessionManagementService**:
  - `createChannelSession(channel, bindingKeyRef, ttl): ChannelSession`
  - `getChannelSessionByBindingKeyRef(bindingKeyRef): Optional<ChannelSession>`
  - `getChannelSessionById(channelSessionId): Optional<ChannelSession>`
  - `updateChannelState(channelSessionId, newState): void`
  - `createRegistrationProcessSession(...): RegistrationProcessSession`
  - `createLoginProcessSession(...): LoginProcessSession`
  - `createStepUpProcessSession(...): StepUpProcessSession`
  - `createIdentificationAttempt(...): IdentificationAttempt`
  - `createAuthenticationAttempt(...): AuthenticationAttempt`
  - `getAttemptById(attemptId): Optional<OrchestratorAttempt>`
  - `completeAttempt(attemptId, result): void`
  - `consumeProcessSession(processSessionId): void`

- **AuthContextService** (neu):
  - `createAuthContext(accountId, keycloakSessionId, keycloakSubject): AuthContext`
  - `updateAcr(authContextId, acr, amr): void`
  - `getAuthContext(authContextId): Optional<AuthContext>`
  - `refreshToken(authContextId, newTokenHandle, tokenExpiresAt): void`

### 7.3 API v1 Endpoints (Konzept-konform)

Die neuen API-Endpunkte folgen konsistent dem orchestrator-keycloak-session-konzept:

#### App-Kanal-Fassade (Orchestrator-first)

```
POST   /orchestrator/api/v1/app/channels
  → Kanal-Initialisierung / Fortführung
  → Response: channelSessionId, state, next(context, step, methods)

GET    /orchestrator/api/v1/app/channels/{channelSessionId}
  → Channel-Status lesen
  → Response: channelSessionId, state, currentAcr, currentAmr, stepUpRequired

POST   /orchestrator/api/v1/app/channels/{channelSessionId}/identification-methods/fsc/attempts
  → FSC-Attempt anlegen
  → Response: attemptId, status, missingFields, next

PATCH  /orchestrator/api/v1/identification-methods/fsc/attempts/{attemptId}
  → FSC-Daten ergänzen
  → Response: attemptId, status, missingFields, result, next

GET    /orchestrator/api/v1/identification-methods/fsc/attempts/{attemptId}
  → FSC-Attempt-Status lesen

POST   /orchestrator/api/v1/app/channels/{channelSessionId}/authentication-methods/sms/enroll/attempts
  → SMS-Enroll-Attempt anlegen (für REGISTRATION)
  → Response: attemptId, status, missingFields, next

PATCH  /orchestrator/api/v1/authentication-methods/sms/enroll/attempts/{attemptId}
  → SMS-Enroll-Daten ergänzen / TAN verifizieren
  → Response: attemptId, status, missingFields, result, next

GET    /orchestrator/api/v1/authentication-methods/sms/enroll/attempts/{attemptId}
  → SMS-Enroll-Attempt-Status lesen

POST   /orchestrator/api/v1/app/channels/{channelSessionId}/authentication-methods/sms/use/attempts
  → SMS-Use-Attempt anlegen (für LOGIN/STEP_UP)
  → Response: attemptId, status, missingFields, next

PATCH  /orchestrator/api/v1/authentication-methods/sms/use/attempts/{attemptId}
  → SMS-Use-Daten ergänzen / TAN verifizieren
  → Response: attemptId, status, missingFields, result, next

GET    /orchestrator/api/v1/authentication-methods/sms/use/attempts/{attemptId}
  → SMS-Use-Attempt-Status lesen
```

**Response-Struktur:**
```json
{
  "channelSessionId": "UUID",
  "processState": {
    "purpose": "REGISTRATION",
    "status": "STARTED",
    "personId": 5001,
    "accountId": 1001
  },
  "attemptState": {
    "attemptId": "UUID",
    "attemptType": "identification",
    "status": "VERIFIED",
    "missingFields": [],
    "result": {
      "identified": true,
      "personId": 5001
    }
  },
  "next": {
    "context": "enrollment",
    "step": "selectMethod",
    "methods": ["sms"],
    "accountId": 1001,
    "personId": 5001
  }
}
```

### 7.3 Rückwärtskompatibilität

Die alte `BindingSession`-Tabelle bleibt bestehen (Tabelle `binding_session`), um Rückwärtskompatibilität mit existierenden Tests und Services zu gewährleisten. Eine vollständige Migration von alten zu neuer Struktur ist als Folgearbeit vorgesehen.

### 7.4 Ausschlüsse (aktuell nicht implementiert)

- Web-Channel-spezifische Keycloak-Integration (nur App-Kanal)
- Datenmigration von alter zu neuer Struktur
- Echte Delegation an `id_fsc` und `auth_sms` Module (derzeit Mock)
- SessionEvent Audit-Trail (Infrastruktur vorhanden, Schreiben deaktiviert)

### 7.5 Frontend-Decoupling von Action-URLs

Das Frontend nutzt eine **feste lokale Routing-Tabelle** und trifft UI-Entscheidungen ausschließlich basierend auf `next(context, step, methods)`, nicht auf vom Backend gelieferten URLs oder Action-Namen.

#### 7.5.1 Routing-Architektur

- **Backend liefert**: `context` (String, z. B. "registration", "fsc", "authentication", "sms"), `step` (String, z. B. "selectMethod", "input", "setup", "tanInput"), `methods` (Array von Strings, wenn mehrere Methoden zur Auswahl stehen)
- **Frontend entscheidet**: Anhand dieser drei Attribute wird aus einer lokalen Routing-Tabelle (`routing.ts`) ermittelt, welche UI-Komponente anzuzeigen ist
- **UI-Komponenten** sind an `(context, step)`-Paare gekoppelt, nicht an URL-Muster
- **Verfügbare Methoden**: Falls `methods` vom Backend geliefert wird, nutzt das Frontend diese; ansonsten fallback auf Konfiguration in der Routing-Tabelle

#### 7.5.2 Beispiel Routing-Tabelle

```
registration:
  selectMethod -> IdentificationMethodSelection
fsc:
  input -> FscForm
authentication:
  selectMethod -> AuthenticationMethodSelection
  setup -> AuthenticationSetup
  tanInput -> TanInputForm
  authenticated -> AuthenticationCompleted
sms:
  setup -> SmsSetupForm
  tanInput -> TanInputForm
```

#### 7.5.3 Konsequenzen

- Alle Backend-URLs sind Implementierungsdetails und nicht Gegenstand der UI-Logik
- UI-Navigation ist deterministisch und nicht von der Form oder Semantik von Backend-Endpunkten abhängig
- Änderungen an Backend-URL-Strukturen beeinflussen die UI nicht
- Das Frontend ist wartbar und testbar unabhängig vom Backend-Routing-Konzept

## 8. Implementierungsstatus (Phase 1-4 abgeschlossen)

### Phase 1: Enums & Entities ✅
- ChannelState Enum (7 Werte)
- AttemptStatus Enum (5 Werte)
- AuthContext Entität
- Subtypen IdentificationAttempt, AuthenticationAttempt
- ProcessSession mit type-spezifischen Feldern
- Flyway V14 Migration

### Phase 2: API Response Types ✅
- NextRouting mit accountId/personId
- ChannelSessionResponse DTO
- Strukturierte result-Objekte

### Phase 3: API Endpoints ✅
- Neue Pfade nach orchestrator-keycloak-session-konzept
- DPoP-Validierung in allen Endpoints
- Backward Compatibility mit alten Pfaden

### Phase 4: Business Logic ✅
- AuthContextService für Keycloak-Lifecycle
- OrchestratorApiV1Service mit echter Prozesslogik
- Channel State-Übergänge
- FSC Identification Flow
- SMS Enroll/Use Flows

## 9. Verifikation

- `./gradlew build` baut Backend und Frontend und führt alle Tests aus.
- `./gradlew bootRun` startet die Applikation auf Port 8080 (blockierend; für Verifikation lieber Integrationstests verwenden).
- Integrationstests starten den eingebetteten Server auf einem zufälligen Port und prüfen den Endpunkt `/orchestrator/process` sowie den vollständigen DPoP-Session-Flow.

## 9. Abnahmekriterien

- [x] `./gradlew build` läuft erfolgreich durch.
- [x] `ApplicationModules.verify()` bestätigt die Einhaltung der Modulabhängigkeiten.
- [x] Der Integrationstest für `/orchestrator/process` liefert eine Antwort aus dem `orchestrator` und enthält Personen-Daten aus `ext_stammdaten`.
- [x] Der Integrationstest für den Session-Flow durchläuft Registrierung, FSC-Identifikation, SMS-Setup und TAN-Bestaetigung.
- [x] Das Frontend ist über Spring Boot (`./gradlew bootRun`) erreichbar.
- [x] Das Frontend kann autark über `npm run dev` im Verzeichnis `frontend/` betrieben werden.
- [x] Das Frontend zeigt den Session-Status übersichtlich an und erlaubt das Durchspielen des Registrierungsflows mit vorbelegten Testdaten.
- [x] Das Frontend trifft Routing-Entscheidungen ausschließlich basierend auf `next(context, step, methods)` und nutzt eine lokale Routing-Tabelle, nicht Backend-URLs.
