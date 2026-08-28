# Projektrahmen

Aufgabenstellung, Modulstruktur und technische Rahmenbedingungen der Anwendung.
Die fachlichen Abläufe beschreiben [01-ueberblick.md](01-ueberblick.md) und die folgenden Dokumente.

---

## 1) Aufgabenstellung

Aufbau einer kompilier- und startfähigen **Spring Boot Modulith**-Applikation zur Demonstration
eines DPoP-gesicherten Registrierungs- und Anmeldeablaufs. Das System umfasst:

- Ein React/TypeScript-Frontend, das einen DPoP-Proof erzeugt und mit dem Backend kommuniziert.
- Einen `orchestrator`, der Session- und Journey-Zustände verwaltet und die fachliche Richtigkeit
  (Policy, Retry, DPoP-Bindung) durchsetzt, ohne die Methodenmodule selbst zu kennen.
- Mehrere fachliche Module (`id_fsc`, `id_eid`, `auth_sms`, `auth_password`, `auth_email`, `auth_device`),
  die ihre eigenen Tool-Endpunkte mitbringen und den Orchestrator ausschließlich über die
  gemeinsame Schnittstelle `tool_api` erreichen ([Tool-Architektur](03-tool-architektur.md)
  Abschnitt 4).
- Zwei Datenmodule (`account`, `ext_stammdaten`), die Konto- bzw. Personendaten halten und
  ebenfalls Teile von `tool_api` implementieren, statt vom Orchestrator direkt aufgerufen zu
  werden.
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
| M1 | `orchestrator` | Verwaltet Session-/Journey-Zustand, Policy und Retry; stellt die Channel-REST-API für das Frontend bereit und implementiert die `tool_api`-Ports `ToolEndpoint`/`DeviceProofs` |
| M2 | `id_fsc` | Identifizierungsfunktionalität (Tool `ident-fsc`); bringt den eigenen `@RestController` mit |
| M3 | `auth_sms` | SMS-Verfahren (Tools `enroll-sms`, `auth-sms`, `auth-sms-lookup`); bringt die eigenen `@RestController` mit |
| M4 | `account` | Verwaltung von Konten, Identifikationen und Authentifizierungsmethoden; implementiert den `tool_api`-Port `AccountDirectory` |
| M5 | `ext_stammdaten` | Zugriff auf externe Stammdaten; verwaltet `Person`-Entitäten mit Adressdaten; implementiert den `tool_api`-Port `PersonDirectory` |
| M6 | `auth_password` | Passwort-Verfahren (Tools `enroll-password`, `auth-password`, `auth-password-lookup`), voraussetzungsgebunden über `ToolDescriptor.requiresConfirmedEmail` ([Tool-Architektur](03-tool-architektur.md)) |
| M7 | `auth_email` | E-Mail-Verfahren (Tools `enroll-email`, `auth-email`, `auth-email-lookup`); bewusst eigenständiges Modul statt Teil von `auth_sms` (eigener `EmailCodeGenerator`, [Tool-Architektur](03-tool-architektur.md)) |
| M8 | `tool_api` | Gemeinsame SPI zwischen Orchestrator und Methodenmodulen: `ToolEndpoint`, `AccountDirectory`, `PersonDirectory`, `DeviceProofs`, Envelope-DTOs (`ChannelResponse`, `Next`, …); außerdem der generische, tool-lose `ToolSwitchController` ([Tool-Architektur](03-tool-architektur.md) Abschnitt 4) |
| M9 | `tool_spi` | Reine Selbstbeschreibung eines Tools (`ToolDescriptor`, `ToolOutcome`, `FactorType`), ohne Abhängigkeiten — jedes Modul, auch `tool_api`, darf darauf zugreifen |
| M10 | `id_eid` | Zweite Identifizierungsfunktionalität (Tool `ident-eid`, Mock der Online-Ausweisfunktion); bringt den eigenen `@RestController` mit |

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

- Kein Methodenmodul referenziert den `orchestrator` mehr, und der `orchestrator` referenziert kein Methodenmodul mehr (`orchestrator/ModuleMetadata.kt`: `allowedDependencies = ["tool_spi", "tool_api", "account", "ext_stammdaten"]`). Die einzige gemeinsame Kante zwischen Orchestrator und Methodenmodulen ist `tool_api` — ein Methodenmodul kennt nur dessen Interfaces, nie eine konkrete Orchestrator-Klasse.
- Die HTTP-Pfade (`/orchestrator/api/v1/tools/...`) sind identisch geblieben; nur die Kotlin-Package-Zugehörigkeit des jeweiligen `@RestController` hat sich geändert (`id_fsc.api.v1`, `id_eid.api.v1`, `auth_sms.api.v1`, `auth_password.api.v1`, `auth_email.api.v1`, `auth_device.api.v1`) — Spring routet nach `@RequestMapping`, nicht nach Package.
- Die Methodenmodule sind voneinander entkoppelt — **eine** deklarierte Ausnahme: `auth_email` darf `account` nutzen, weil die bestätigte E-Mail der Konto-*Identifikator* ist (Unique-Index, Lookup-Login für sms/password, `requiresConfirmedEmail`) und nicht ein austauschbares Credential. Begründung im KDoc von `auth_email/ModuleMetadata.kt`; kein Präzedenzfall für weitere Module.
- `auth_sms` kapselt interne Datenbank-IDs hinter einer opaken `EnrollmentRef` ([06-ablaeufe.md](06-ablaeufe.md)).
- Die Package-Grenzen werden durch `@ApplicationModule(allowedDependencies = ...)` je Modul abgesichert und von `DpopApplicationTests.modulithStructureIsValid` geprüft — eine unerlaubte Kante bricht den Build namentlich. Da Kotlin keine Package-Annotationen kennt, trägt je eine `ModuleMetadata.kt` die Deklaration (`@ApplicationModule` ist `@Target({PACKAGE, TYPE})`); ein `package-info.java` und damit ein Java-Sourceset sind nicht nötig.
- Das Frontend kommuniziert ausschließlich über HTTP mit der Applikation als Ganzes — welches Modul einen gegebenen Endpunkt implementiert, ist für das Frontend nicht sichtbar und nicht relevant.

### Anforderungen an die Modulstruktur

| ID | Anforderung | Kriterium |
|----|-------------|-----------|
| M-1 | Jedes Modul besitzt ein eigenes Package. | Package-Struktur unter `com.example.dpop.<modul>` |
| M-2 | Jedes Modul enthält mindestens eine Service-Klasse. | `@Service` in jedem Modul vorhanden |
| M-3 | Methodenmodule und Orchestrator sind nur über die gemeinsame SPI `tool_api` gekoppelt, nie direkt. | Konstruktor-Injection ausschließlich gegen `tool_api`-Interfaces (`ToolEndpoint`, `AccountDirectory`, `PersonDirectory`, `DeviceProofs`); kein Methodenmodul importiert `orchestrator`, und `orchestrator` importiert kein Methodenmodul |
| M-4 | Die Modulstruktur ist verifizierbar. | `ApplicationModules.verify()` in Tests |

---

## 4) Persistenz

- Als Datenbank wird **H2** verwendet: dateibasiert unter `./data/dpopdb` im Betrieb, **In-Memory** im Testprofil.
- Das Schema wird mit **Flyway**-Migrationen aufgebaut, der Zugriff erfolgt über **Spring Data JPA**.
- Im Modul `ext_stammdaten` existiert eine `Person`-Entität mit `id`, `kvnr` (eindeutig), `name`, `vorname`, `strasse`, `hausnummer`, `plz`, `ort`, `geburtsdatum`.
- Bei Applikationsstart werden Testpersonen sowie gültige FSC-Codes per Flyway-Migration eingespielt, damit der Registrierungsflow direkt durchspielbar ist.

Die Session- und Tool-Entitäten sind in [02-domaenenmodell.md](02-domaenenmodell.md) beschrieben,
Aufbewahrung und Löschung in [07-betrieb.md](07-betrieb.md).

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
| A11 | Lesbarkeit hat Vorrang vor maximal generischem API-Wiring | Endpunkte, DTOs und Handler bleiben tool-spezifisch explizit (`ident-fsc`, `enroll-sms`, `auth-sms`), auch wenn dadurch mehr, aber klarerer Code entsteht |

---

## 6) Lösungsstrategie und Versionen

- **Framework**: Spring Boot 4.x mit eingebettetem Tomcat
- **Sprache**: Kotlin 2.2.x als Backend-Implementierungssprache
- **Modularisierung**: Spring Modulith 2.x zur Architekturverifikation
- **Build**: Gradle 9.x mit Kotlin-DSL (`build.gradle.kts`, `settings.gradle.kts`)
- **Versionsverwaltung**: Gradle Version Catalog in `gradle/libs.versions.toml`
- **Persistenz**: H2 + Spring Data JPA + Flyway
- **Frontend**: React 19.x + TypeScript 6.x mit Vite 8.x
- **Frontend-Integration**: Vite-Build schreibt in `src/main/resources/static`; Gradle führt `npm install` und `npm run build` aus
- **Test**: JUnit 5 mit Spring Boot Test und Spring Modulith Test-Starter

| Komponente | Version |
|------------|---------|
| Spring Boot | `4.1.0` |
| Spring Modulith | `2.1.0` |
| Dependency Management Plugin | `1.1.7` |
| Gradle (Wrapper) | `9.7.0` |
| Kotlin | `2.2.21` |
| JVM Target | `21` |
| React | `19.2.8` |
| React DOM | `19.2.8` |
| TypeScript | `6.0.2` |
| Vite | `8.2.0` |
| H2 | (von Spring Boot verwaltet) |
| Flyway | (von Spring Boot verwaltet) |

---

## 7) Build und Verifikation

- `./gradlew build` baut Backend und Frontend und führt alle Tests aus.
- `./gradlew bootRun` startet die Applikation auf Port 8080 (blockierend; für Verifikation eignen sich Integrationstests besser).
- Integrationstests starten den eingebetteten Server auf einem zufälligen Port und prüfen den vollständigen DPoP-Session-Flow.
- `ApplicationModules.verify()` bestätigt die Einhaltung der Modulabhängigkeiten.
