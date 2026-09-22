# Projektrahmen

Aufgabenstellung, Modulstruktur und technische Rahmenbedingungen der Anwendung.
Die fachlichen Abläufe beschreibt [01-ueberblick.md](01-ueberblick.md).

---

## 1) Aufgabenstellung

Aufbau einer startfähigen **Spring Boot Modulith**-Applikation zur Demonstration eines
DPoP-gesicherten Registrierungs- und Anmeldeablaufs. Das System umfasst:

- Ein React/TypeScript-Frontend, das einen DPoP-Proof erzeugt und mit dem Backend kommuniziert.
- Einen `orchestrator`, der Session- und Journey-Zustände verwaltet und die fachliche Richtigkeit
  (Policy, Retry, DPoP-Bindung) durchsetzt, ohne die Methodenmodule zu kennen.
- Mehrere fachliche Module (`id_fsc`, `id_eid`, `id_kvnr`, `auth_sms`, `auth_password`, `auth_email`, `auth_device`, `auth_kobil`),
  die ihre eigenen Tool-Endpunkte mitbringen und den Orchestrator ausschließlich über
  `tool_api` erreichen ([Tool-Architektur](03-tool-architektur.md) Abschnitt 4).
- Zwei Datenmodule (`account`, `ext_stammdaten`), die Konto- bzw. Personendaten halten und
  ebenfalls Teile von `tool_api` implementieren.
- Persistenz in einer H2-Datenbank mit Flyway-Migrationen.

### Qualitätsziele

| Priorität | Ziel | Beschreibung |
|-----------|------|--------------|
| 1 | Modularität | Klare fachliche Module mit definierten Abhängigkeiten |
| 2 | Verifizierbarkeit | Architektur- und Modulstruktur automatisiert prüfbar |
| 3 | Aktualität | Verwendung aktueller Versionen des Spring-Ökosystems |
| 4 | Entwicklerfreundlichkeit | Sofort ausführbar über Gradle Wrapper |

---

## 2) Kontextabgrenzung (C4 System Context)

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

- **Name**: `dpop-demo`
- **Typ**: Spring Boot Webanwendung
- **Schnittstelle nach außen**: HTTP/REST (Tomcat auf Port 8080)

---

## 3) Module

| Nr. | Modul | Verantwortung |
|-----|-------|---------------|
| M1 | `orchestrator` | Verwaltet Session-/Journey-Zustand, Policy und Retry; stellt die Channel-REST-API bereit und implementiert die `tool_api`-Ports `ToolEndpoint`/`DeviceProofs` |
| M2 | `id_fsc` | Identifizierung (Tool `ident-fsc`); eigener `@RestController` |
| M3 | `auth_sms` | SMS-Verfahren (Tools `enroll-sms`, `auth-sms`, `auth-sms-lookup`); eigene `@RestController` |
| M4 | `account` | Konten, Identifikationen und Authentifizierungsmethoden; implementiert den `tool_api`-Port `AccountDirectory` |
| M5 | `ext_stammdaten` | Externe Stammdaten: verwaltet `Person`-Entitäten mit Adressdaten; implementiert den `tool_api`-Port `PersonDirectory` |
| M6 | `auth_password` | Passwort-Verfahren (Tools `enroll-password`, `auth-password`, `auth-password-lookup`); setzt über `ToolDescriptor.requires` eine bestätigte Adresse voraus (`ClaimRequirement(EMAIL, PROVEN)`) ([Tool-Architektur](03-tool-architektur.md)) |
| M7 | `auth_email` | E-Mail-Verfahren (Tools `enroll-email`, `auth-email`, `auth-email-lookup`) mit eigenem `EmailCodeGenerator`. Abhängigkeiten nur auf `tool_api`/`tool_spi`: liest Account-IDs und Ankerwerte über `AccountDirectory`, liefert `EMAIL`-Claims; kein direkter Zugriff auf `account` |
| M8 | `tool_api` | Gemeinsame SPI zwischen Orchestrator und Methodenmodulen: `ToolEndpoint`, `AccountDirectory`, `PersonDirectory`, `DeviceProofs`, Envelope-DTOs (`ChannelResponse`, `Next`, …), `ToolSwitchController` ([Tool-Architektur](03-tool-architektur.md) Abschnitt 4) |
| M9 | `tool_spi` | Selbstbeschreibung eines Tools (`ToolDescriptor`, `ToolOutcome`, `FactorType`), ohne Abhängigkeiten — jedes Modul, auch `tool_api`, darf darauf zugreifen |
| M10 | `id_eid` | Zweite Identifizierung (Tool `ident-eid`, Mock der Online-Ausweisfunktion); bestätigt nur die Kartendaten, löst niemanden auf (ADR-18); eigener `@RestController` |
| M10a | `id_kvnr` | Zuordnung einer bestätigten Identität zur Registerperson (Tool `ident-kvnr`, ADR-18); eigener `@RestController` |
| M11 | `auth_qr` | QR-Login des Web-Kanals, bestätigt über den App-Kanal (Tools `enroll-qr`, `auth-qr`, `auth-qr-lookup`, `confirm-qr-login`, [Orchestrierung](04-orchestrierung.md) `CONFIRM_PEER_LOGIN`); eigene `QrLoginRequest`-Persistenz, kein `account`-Zugriff; eigene `@RestController` |
| M12 | `auth_device` | Geräte-Bindung als eigenes Auth-Mittel (Tools `enroll-device`, `auth-device`); eigene `@RestController`, keine `account`-Abhängigkeit |
| M13 | `demo_seed` | Demo-only Bootstrap: legt für die vom `keycloak`-Profil geseedeten Testpersonen ein Orchestrator-Konto mit bestätigter Adresse und den Methoden `password` (KNOWLEDGE) und `sms` (POSSESSION) an — dem Paar für ein Step-up auf LoA2, create-only: Testpersonen mit bestehendem PERSON_ID-/EMAIL-Anker-Konto werden übersprungen (`AccountService.recordClaim`/`recordClaims`/`addAuthenticationMethod`/`createUnidentifiedAccount`/`resolveAccountByPersonId`/`resolveAccountByEmail` über `account`, `PasswordCredentialPort`/`SmsCredentialPort`/`PersonDirectory` über `tool_api`; PERSON_ID/EMAIL/PHONE_NUMBER-Claims tragen `ClaimSource.DEMO_BOOTSTRAP`; eine Transaktion); kein `@RestController` |
| M14 | `auth_kobil` | Gerätebindung über den externen Dienstleister KOBIL (Tools `enroll-kobil`, `auth-kobil`, [Abläufe](06-ablaeufe.md) Abschnitt 7); backend-verwahrter PIN (ADR-21/ADR-22), PIN-Freigabe als eigene Sub-Ressource; eigene `@RestController`, keine `account`-Abhängigkeit — aber als einziges Methodenmodul eine vierte erlaubte Kante: `kobil_mock` |
| M15 | `kobil_mock` | Simuliertes **Fremdsystem**, kein Tool-Modul: `allowedDependencies = []` (kennt weder `tool_spi` noch `tool_api` noch die Journey), eigenes Schema, zwei Schnittstellen — die HTTP-Fassade `/mock-kobil/*` für die App (Pendant zum MC SDK) und `KobilSsms` für unser Backend. Untersteht nicht unserer Aufbewahrung |

### Modulabhängigkeiten (C4 Component View)

```
         ┌─────────────┐
         │   Browser   │
         └──────┬──────┘
                │ HTTP / REST (ein Port, ein Pfadraum: /orchestrator/api/v1/...)
                ▼
┌────────────────────────────────────────────────────────────────────────┐
│  @RestController - verteilt über mehrere Module, URLs unverändert       │
│                                                                          │
│   orchestrator          id_fsc / id_eid / auth_sms / auth_password /    │
│   (Channel-Endpunkte,    auth_email / auth_device                       │
│    Journey/Policy)       (jeweils die eigenen Tool-Endpunkte)           │
│                                                                          │
│                    tool_api.ToolSwitchController (generisch, toolId-los)│
└───────────────────────────────┬──────────────────────────────────────┬─┘
                                 │ implementiert / ruft auf              │
                                 ▼                                      ▼
                     ┌────────────────────────────┐         ┌───────────────────┐
                     │           tool_api          │◄────────│   tool_spi        │
                     │ ToolEndpoint, AccountDirect- │        │ ToolDescriptor,   │
                     │ ory, PersonDirectory,        │        │ ToolOutcome, ...  │
                     │ DeviceProofs, Envelope-DTOs   │        └───────────────────┘
                     └───────────▲──────────────────┘
                                 │ implementieren die Ports
              ┌──────────────────┼───────────────────┐
              │                  │                    │
       ┌─────────────┐   ┌─────────────┐      ┌───────────────────┐
       │ orchestrator │   │   account   │      │  ext_stammdaten   │
       │ (ToolEndpoint,│   │(AccountDir- │      │ (PersonDirectory) │
       │  DeviceProofs)│   │  ectory)    │      │                    │
       └─────────────┘   └─────────────┘      └───────────────────┘
```

- Kein Methodenmodul referenziert den `orchestrator` und umgekehrt (`orchestrator/ModuleMetadata.kt`: `allowedDependencies = ["tool_spi", "tool_api", "account", "ext_stammdaten"]`). Die einzige gemeinsame Kante ist `tool_api` — ein Methodenmodul kennt nur dessen Interfaces, nie eine konkrete Orchestrator-Klasse.
- Die HTTP-Pfade (`/orchestrator/api/v1/tools/...`) sind unabhängig vom Kotlin-Package des jeweiligen `@RestController` (`id_fsc.api.v1`, `id_eid.api.v1`, `id_kvnr.api.v1`, `auth_sms.api.v1`, `auth_password.api.v1`, `auth_email.api.v1`, `auth_device.api.v1`, `auth_kobil.api.v1`) — Spring routet nach `@RequestMapping`, nicht nach Package. Einzige Ausnahme: `kobil_mock.api.v1` liegt bewusst NICHT unter `/orchestrator/api`, sondern unter `/mock-kobil` — es ist der Fremddienst, nicht diese Anwendung.
- Die Methodenmodule sind voneinander und von `account` entkoppelt, einschließlich `auth_email`. Account-Lookups laufen über `tool_api.AccountDirectory`, Schreibungen über Claims in `ToolOutcome` und deren Übernahme durch die Journey. E-Mail-spezifische Lookup-Komfortfunktionen sind Extensions auf dem Port.
- `auth_sms` kapselt interne Datenbank-IDs hinter einer opaken `EnrollmentRef` ([06-ablaeufe.md](06-ablaeufe.md)).
- Die Package-Grenzen werden durch `@ApplicationModule(allowedDependencies = ...)` je Modul abgesichert und von `DpopApplicationTests.modulithStructureIsValid` geprüft — eine unerlaubte Kante bricht den Build. Da Kotlin keine Package-Annotationen kennt, trägt je eine `ModuleMetadata.kt` die Deklaration (`@ApplicationModule` ist `@Target({PACKAGE, TYPE})`); ein `package-info.java` ist nicht nötig.
- Das Frontend kommuniziert ausschließlich über HTTP mit der Applikation als Ganzes; welches Modul einen Endpunkt implementiert, ist für es nicht sichtbar.

### Anforderungen an die Modulstruktur

| ID | Anforderung | Kriterium |
|----|-------------|-----------|
| M-1 | Jedes Modul besitzt ein eigenes Package. | Package-Struktur unter `com.example.dpop.<modul>` |
| M-2 | Jedes Modul enthält mindestens eine Service-Klasse. | `@Service` in jedem Modul vorhanden |
| M-3 | Methodenmodule und Orchestrator sind nur über die gemeinsame SPI `tool_api` gekoppelt, nie direkt. | Konstruktor-Injection nur gegen `tool_api`-Interfaces (`ToolEndpoint`, `AccountDirectory`, `PersonDirectory`, `DeviceProofs`); kein Methodenmodul importiert `orchestrator` und umgekehrt |
| M-4 | Die Modulstruktur ist verifizierbar. | `ApplicationModules.verify()` in Tests |

---

## 4) Persistenz

- Als Datenbank wird **H2** verwendet: dateibasiert unter `./data/dpopdb` im Betrieb, **In-Memory** im Testprofil.
- Das Schema wird mit **Flyway**-Migrationen aufgebaut, der Zugriff erfolgt über **Spring Data JPA**.
- **Ein Datenbankschema je Modul** (`account`, `orchestrator`, `auth_sms`, …): Jede Tabelle liegt im
  Schema ihres Moduls, Fremdschlüssel nur innerhalb eines Schemas
  ([12-entscheidungen.md](12-entscheidungen.md) ADR-16). Der Flyway-Verlauf bleibt in `PUBLIC`.
- `kobil_mock` hat aus demselben Grund ein eigenes Schema, aber nicht aus derselben Rolle: Es ist
  kein Modul dieser Anwendung, sondern ein simuliertes Fremdsystem. Genau darum geht es bei der Trennung: Läge es im Schema von `auth_kobil`, könnte das Tool an der
  Schnittstelle vorbei nachsehen, und der Ablauf würde nichts mehr zeigen.
- Im Modul `ext_stammdaten` existiert eine `Person`-Entität mit `id`, `kvnr` (eindeutig), `name`, `vorname`, `strasse`, `hausnummer`, `plz`, `ort`, `geburtsdatum`.
- Bei Applikationsstart werden Testpersonen und gültige FSC-Codes per Flyway-Migration eingespielt.

Die Session- und Tool-Entitäten beschreibt [02-domaenenmodell.md](02-domaenenmodell.md) (Tabellenmodell
in Abschnitt 7), Aufbewahrung und Löschung [07-betrieb.md](07-betrieb.md).

### H2-Konsole: nur beim Host-Start

Die H2-Konsole unter `/h2-console` ist bewusst eingeschaltet, aber `web-allow-others` bleibt
`false` (Begründung im Kommentar in `application.yml`): Ohne Spring Security im Classpath bewacht
allein H2s eigene Localhost-Prüfung diesen Pfad, hinter dem Passwort-Hashes, Geräteschlüssel und
alle Sessions liegen.

Diese Prüfung vergleicht die Absenderadresse: Bei `./gradlew bootRun` ist das `127.0.0.1`, und die
Konsole funktioniert. **Aus dem Container (`compose.yml`) geht sie nicht:** Der Request erreicht den
Orchestrator über die Portweiterleitung `8080:8080` mit der Bridge-Gateway-Adresse, für H2 eine
„remote connection" — abgewiesen mit *„remote connections ('webAllowOthers') are disabled on this
server"*. Das Sicherheitsnetz greift also wie vorgesehen.

Für den DB-Blick deshalb den Orchestrator auf dem Host starten und nur Keycloak aus Compose
laufen lassen; die Variante `host` (Default von `KEYCLOAK_SETUP_VARIANT`) richtet Keycloak dafür
bereits auf `host.containers.internal:8080` aus. Wer
die Daten eines Container-Laufs braucht, kopiert die Datei aus dem gestoppten Volume
`orchestrator-data` heraus. `web-allow-others` ist keine Option: Der Port ist auf dem Host
veröffentlicht, das würde jedem, der ihn erreicht, vollen Lese- und Schreibzugriff geben.

| ID | Anforderung | Kriterium |
|----|-------------|-----------|
| P-1 | H2 mit dateibasierter DB und In-Memory-Tests. | `application.yml` und `application-test.yml` entsprechend konfiguriert |
| P-2 | Schema-Aufbau erfolgt mit Flyway. | Migrationen unter `src/main/resources/db/migration/` |
| P-3 | Zugriff auf Personen erfolgt über Spring Data JPA. | `PersonRepository extends JpaRepository` |
| P-4 | Die Adresse einer Person ist in einzelne Attribute aufgeteilt. | Entität enthält `strasse`, `hausnummer`, `plz`, `ort` |
| P-5 | Testdaten werden beim Start eingespielt. | Flyway-Migration oder Initialisierungsroutine vorhanden |
| P-6 | FSC-Testdaten stehen beim Start zur Verfügung. | Flyway-Migration legt gültige FSC-Codes für die Testpersonen an |

---

## 5) Architekturbeschränkungen

| ID | Beschränkung | Begründung |
|----|--------------|------------|
| A1 | Build-Tool: Gradle mit Kotlin-DSL | Einheitliche, typsichere Build-Konfiguration |
| A2 | Gradle Wrapper muss enthalten sein | Reproduzierbarkeit ohne lokale Gradle-Installation |
| A3 | JVM-Version 21 (Bytecode-Target), Kotlin 2.2.21 | Voraussetzung für Spring Boot 4.x; Kotlin als Implementierungssprache |
| A4 | Aktuelle Spring Boot-Version verwenden | Sicherheit und Aktualität |
| A5 | Versionen zentral in `gradle/libs.versions.toml` pflegen | Zentrale Versionsverwaltung, konsistente Abhängigkeiten |
| A6 | Frontend-Build ist in den Gradle-Build integriert | Einheitlicher Build-Prozess für Backend und Frontend |
| A7 | Frontend-Build-Output landet in `src/main/resources/static` | Spring Boot liefert das Frontend als statische Ressource aus |
| A8 | Datenbank: H2 (dateibasiert im Betrieb, In-Memory in Tests) | Einfache lokale Entwicklung und schnelle Tests |
| A9 | Schema-Management mit Flyway | Versionierter und reproduzierbarer Datenbankaufbau |
| A10 | Datenzugriff mit Spring Data JPA | Standardisierte Persistenzschicht |
| A11 | Lesbarkeit hat Vorrang vor maximal generischem API-Wiring | Endpunkte, DTOs und Handler bleiben tool-spezifisch explizit (`ident-fsc`, `enroll-sms`, `auth-sms`) |

---

## 6) Lösungsstrategie und Versionen

- **Framework**: Spring Boot mit eingebettetem Tomcat
- **Sprache**: Kotlin als Backend-Implementierungssprache
- **Modularisierung**: Spring Modulith zur Architekturverifikation
- **Build**: Gradle mit Kotlin-DSL (`build.gradle.kts`, `settings.gradle.kts`)
- **Versionsverwaltung**: Gradle Version Catalog in `gradle/libs.versions.toml`
- **Persistenz**: H2 + Spring Data JPA + Flyway
- **Frontend**: React + TypeScript mit Vite
- **Frontend-Integration**: Vite-Build schreibt in `src/main/resources/static`; Gradle führt `npm install` und `npm run build` aus
- **Test**: JUnit 5 mit Spring Boot Test und Spring Modulith Test-Starter

| Komponente | Version |
|------------|---------|
| Spring Boot | `4.1.0` |
| Spring Modulith | `2.1.1` |
| Dependency Management Plugin | `1.1.7` |
| Gradle (Wrapper) | `9.7.0` |
| Kotlin | `2.2.21` |
| JVM Target | `21` |
| React | `19.3.0` |
| React DOM | `19.3.0` |
| TypeScript | `7.0.2` |
| Vite | `8.3.0` |
| H2 | (von Spring Boot verwaltet) |
| Flyway | (von Spring Boot verwaltet) |

---

## 7) Build und Verifikation

- `./gradlew build` baut Backend und Frontend und führt alle Tests aus.
- `./gradlew bootRun` startet die Applikation auf Port 8080 (blockierend; für Verifikation eignen sich Integrationstests besser).
- Integrationstests starten den eingebetteten Server auf einem zufälligen Port und prüfen den DPoP-Session-Flow.
- `ApplicationModules.verify()` bestätigt die Einhaltung der Modulabhängigkeiten.

### Vorbedingungen in Integrationstests

Der Registrierungsablauf (Identifikation → E-Mail-Bestätigung → Enrollments) wird nur dort per
HTTP durchgeklickt, wo er selbst Prüfgegenstand ist: `RegistrationFlowIntegrationTest`,
`RequiredActionIntegrationTest` (Reihenfolge der Pflichten) und `JourneyLogIntegrationTest` (das
Journey-Log entsteht nur durch einen echten Durchlauf).

Alle anderen Suiten brauchen nur sein *Ergebnis* — „ein Konto mit sms und Passwort, an dieses
Gerät gebunden". Das stellt `AccountFixtures` (Test-Sourceset) über die Domain-Services her, nicht
über SQL: so gelten dieselben Regeln wie im Produktivpfad (Mindestniveaus der Anker,
Ersetzen einer vorhandenen Methode, Herkunft der Claims). Dasselbe Vorgehen nutzt `demo_seed`
(`KcDemoAccountSeeder`).

Einstiegspunkte in `IntegrationTestSupport`:

| Helper | Vorbedingung |
| --- | --- |
| `seedRegisteredAccount()` | Konto existiert (sms + Passwort, bestätigte Adresse, Gerät gebunden), kein Kanal |
| `loginAsSeededAccount()` | dazu ein angemeldeter loa2-Kanal (`amr = [sms, password]`) |
| `registerAndAuthenticate()` | echter Registrierungsdurchlauf — trägt zusätzlich einen eigenen `fsc`-Nachweis |

Der Unterschied zwischen den letzten beiden ist fachlich: Ein angemeldeter Kanal besitzt keine
eigene Identifikationsevidenz. Tests, die diese brauchen (etwa das Entfernen einer Methode, die
sonst die aktuelle Anmeldeevidenz wäre), müssen `registerAndAuthenticate()` verwenden.
