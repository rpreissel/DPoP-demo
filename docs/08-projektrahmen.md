# Projektrahmen

Aufgabenstellung, Modulstruktur und technische Rahmenbedingungen der Anwendung.
Die fachlichen Abläufe beschreiben [01-ueberblick.md](01-ueberblick.md) und die folgenden Dokumente.

---

## 1) Aufgabenstellung

Aufbau einer kompilier- und startfähigen **Spring Boot Modulith**-Applikation zur Demonstration
eines DPoP-gesicherten Registrierungs- und Anmeldeablaufs. Das System umfasst:

- Ein React/TypeScript-Frontend, das einen DPoP-Proof erzeugt und mit dem Backend kommuniziert.
- Einen `orchestrator`, der Session-Zustände verwaltet und Identifikation sowie Authentifizierung orchestriert.
- Mehrere fachliche Module (`id_fsc`, `auth_sms`, `account`, `ext_stammdaten`), die über definierte Schnittstellen vom Orchestrator genutzt werden.
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
| M1 | `orchestrator` | Koordiniert Abläufe über die anderen Module; stellt die REST-API für das Frontend bereit |
| M2 | `id_fsc` | Identifizierungsfunktionalität (Tool `ident-fsc`) |
| M3 | `auth_sms` | SMS-Verfahren (Tools `enroll-sms` und `auth-sms`) |
| M4 | `account` | Verwaltung von Konten, Identifikationen und Authentifizierungsmethoden |
| M5 | `ext_stammdaten` | Zugriff auf externe Stammdaten; verwaltet `Person`-Entitäten mit Adressdaten |
| M6 | `auth_password` | Passwort-Verfahren (Tools `enroll-password` und `auth-password`), voraussetzungsgebunden über `ToolDescriptor.requiresConfirmedEmail` ([Tool-Architektur](03-tool-architektur.md)) |
| M7 | `auth_email` | E-Mail-Verfahren (Tools `enroll-email` und `auth-email`); bewusst eigenständiges Modul statt Teil von `auth_sms` (eigener `EmailCodeGenerator`, [Tool-Architektur](03-tool-architektur.md)) |

### Modulabhängigkeiten (C4 Component View)

```
         ┌─────────────┐
         │   Browser   │
         └──────┬──────┘
                │ HTTP / REST
                ▼
┌─────────────────────────────────────────────────────────────┐
│                        orchestrator                         │
│              (REST-API: /orchestrator/api/v1)               │
└──────────┬─────────────┬──────────────┬─────────────────────┘
           │             │              │                     │
           ▼             ▼              ▼                     ▼
     ┌─────────┐   ┌──────────┐   ┌──────────┐   ┌──────────────────┐
     │ id_fsc  │   │ auth_sms │   │ account  │   │ ext_stammdaten   │
     └─────────┘   └──────────┘   └──────────┘   └──────────────────┘
```

- Der `orchestrator` ist das einzige Modul, das die anderen Module referenzieren darf.
- Die Module `id_fsc`, `auth_sms`, `account` und `ext_stammdaten` sind voneinander entkoppelt.
- `auth_sms` kapselt interne Datenbank-IDs hinter einer opaken `EnrollmentRef` ([06-ablaeufe.md](06-ablaeufe.md)).
- Die Package-Grenzen werden durch `@ApplicationModule` (Spring Modulith) abgesichert.
- Das Frontend kommuniziert ausschließlich über den `orchestrator` mit dem Backend.

### Anforderungen an die Modulstruktur

| ID | Anforderung | Kriterium |
|----|-------------|-----------|
| M-1 | Jedes Modul besitzt ein eigenes Package. | Package-Struktur unter `com.example.dpop.<modul>` |
| M-2 | Jedes Modul enthält mindestens eine Service-Klasse. | `@Service` in jedem Modul vorhanden |
| M-3 | Der `orchestrator` orchestriert alle anderen Module. | Konstruktor-Injection aller Modul-Services |
| M-4 | Die Modulstruktur ist verifizierbar. | `ApplicationModules.verify()` in Tests |

---

## 4) Persistenz

- Als Datenbank wird **H2** verwendet: dateibasiert unter `./data/dpopdb` im Betrieb, **In-Memory** im Testprofil.
- Das Schema wird mit **Flyway**-Migrationen aufgebaut, der Zugriff erfolgt über **Spring Data JPA**.
- Im Modul `ext_stammdaten` existiert eine `Person`-Entität mit `id`, `kvnr` (eindeutig), `name`, `vorname`, `strasse`, `hausnummer`, `plz`, `ort`.
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
