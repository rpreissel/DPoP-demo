# Anforderungen

## 1. Einführung und Ziele

### 1.1 Aufgabenstellung

Aufbau einer kompilier- und startfähigen **Spring Boot Modulith**-Applikation zur Demonstration einer modularen Geschäftsanwendung.

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
  - `name`
  - `vorname`
  - `strasse`
  - `hausnummer`
  - `plz`
  - `ort`
- Bei Applikationsstart werden Testdaten in die `person`-Tabelle eingespielt.

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
- Integrationstests starten den eingebetteten Server auf einem zufälligen Port und prüfen den Endpunkt `/orchestrator/process`.

## 8. Abnahmekriterien

- [x] `./gradlew build` läuft erfolgreich durch.
- [x] `ApplicationModules.verify()` bestätigt die Einhaltung der Modulabhängigkeiten.
- [x] Der Integrationstest für `/orchestrator/process` liefert eine Antwort aus dem `orchestrator` und enthält Personen-Daten aus `ext_stammdaten`.
- [x] Das Frontend ist über Spring Boot (`./gradlew bootRun`) erreichbar.
- [x] Das Frontend kann autark über `npm run dev` im Verzeichnis `frontend/` betrieben werden.
