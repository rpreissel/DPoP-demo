# Projektrahmen

Dieses Kapitel beschreibt die Aufgabenstellung, die Modulstruktur und die technischen
Rahmenbedingungen der Anwendung. Die fachlichen Abläufe beschreibt [01-ueberblick.md](01-ueberblick.md).

---

## 1) Aufgabenstellung

Gebaut wird eine lauffähige Anwendung mit **Spring Boot Modulith**. Sie zeigt, wie Registrierung und
Anmeldung mit DPoP abgesichert werden. Das System besteht aus:

- Einem Frontend in React und TypeScript, das DPoP-Proofs erzeugt und mit dem Backend spricht.
- Einem `orchestrator`, der den Zustand von Sitzungen und Journeys verwaltet und die fachlichen Regeln
  durchsetzt (Richtlinie, Wiederholungen, DPoP-Bindung), ohne die Methodenmodule zu kennen.
- Mehreren fachlichen Modulen (`id_fsc`, `id_eid`, `id_nect`, `id_kvnr`, `auth_sms`, `auth_password`, `auth_email`, `auth_device`, `auth_qr`, `auth_kobil`),
  die ihre eigenen Tool-Endpunkte mitbringen und den Orchestrator ausschließlich über
  `tool_api` erreichen ([Tool-Architektur](03-tool-architektur.md) Abschnitt 4).
- Zwei Datenmodulen (`account`, `ext_personenverzeichnis`), die Konto- bzw. Personendaten halten und
  ebenfalls Teile von `tool_api` implementieren.
- Einer H2-Datenbank, deren Schema Flyway-Migrationen aufbauen.

### Qualitätsziele

| Priorität | Ziel | Beschreibung |
|-----------|------|--------------|
| 1 | Modularität | Klare fachliche Module mit definierten Abhängigkeiten |
| 2 | Verifizierbarkeit | Architektur- und Modulstruktur automatisiert prüfbar |
| 3 | Aktualität | Verwendung aktueller Versionen des Spring-Ökosystems |
| 4 | Entwicklerfreundlichkeit | Sofort ausführbar über den Gradle Wrapper |

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

- **M1** `orchestrator` — Verwaltet den Zustand von Sitzungen und Journeys, die Regeln und die Wiederholungen; stellt die REST-API der Kanäle bereit und implementiert die `tool_api`-Ports `ToolEndpoint`/`DeviceProofs`
- **M2** `id_fsc` — Identifizierung (Tool `ident-fsc`); eigener `@RestController`; prüft den Freischaltcode beim Personenverzeichnis über den Port `ActivationCodes` (ADR-31, Nachtrag)
- **M3** `auth_sms` — SMS-Verfahren (Tools `enroll-sms`, `auth-sms`, `auth-sms-lookup`); eigene `@RestController`
- **M4** `account` — Konten, Identifikationen und Authentifizierungsmethoden; implementiert den `tool_api`-Port `AccountDirectory`
- **M5** `ext_personenverzeichnis` — Simuliertes **Personenverzeichnis** (Fremdsystem): verwaltet `Person`-Entitäten (Schlüssel ist die Partnernummer, `P` und neun Ziffern, zufällig vergeben; Versicherungsnummer nur für Versicherte, KVNR nur zusammen mit ihr, beide änderbar) mit Adressdaten, stellt Freischaltcodes aus (ADR-31) und meldet Änderungen als `PersonChanged` (ADR-34). Drei Schnittstellen: den `tool_api`-Port `PersonDirectory`, die Klasse `Freischaltcodes` für `id_fsc` und die HTTP-Schnittstelle `/mock-personenverzeichnis/*` für die Seite `/personenverzeichnis/`; der Orchestrator liest zusätzlich die Klasse `Personenverzeichnis` direkt (Demo-Personen, Keycloak-Abgleich)
- **M6** `auth_password` — Passwort-Verfahren (Tools `enroll-password`, `auth-password`, `auth-password-lookup`); setzt über `ToolDescriptor.requires` eine bestätigte Adresse voraus (`ClaimRequirement(EMAIL, PROVEN)`) ([Tool-Architektur](03-tool-architektur.md))
- **M7** `auth_email` — E-Mail-Verfahren (Tools `enroll-email`, `auth-email`, `auth-email-lookup`) mit eigenem `EmailCodeGenerator`. Abhängigkeiten nur auf `tool_api`/`tool_spi`: liest Account-IDs und Ankerwerte über `AccountDirectory`, liefert `EMAIL`-Claims; kein direkter Zugriff auf `account`
- **M8** `tool_api` — Gemeinsame SPI zwischen Orchestrator und Methodenmodulen: `ToolEndpoint`, `AccountDirectory`, `PersonDirectory`, `DeviceProofs`, Envelope-DTOs (`ChannelResponse`, `Next`, …). Enthält bewusst keinen Controller: `tool_api` ist ein Vertrag, keine Web-Schicht, und jedes Methodenmodul hängt davon ab ([Tool-Architektur](03-tool-architektur.md) Abschnitt 4)
- **M9** `tool_spi` — Selbstbeschreibung eines Tools (`ToolDescriptor`, `ToolOutcome`, `FactorType`), einzige Abhängigkeit `texts` — jedes Modul, auch `tool_api`, darf darauf zugreifen
- **M10** `id_eid` — Zweite Identifizierung (Tool `ident-eid`, Mock der Online-Ausweisfunktion); bestätigt nur die Kartendaten, löst niemanden auf (ADR-18); eigener `@RestController`
- **M10a** `id_kvnr` — Zuordnung einer bestätigten Identität zu ihrer Person im Personenverzeichnis (per KVNR, sonst Partnernummer) (Tool `ident-kvnr`, ADR-18); eigener `@RestController`
- **M11** `auth_qr` — Anmeldung per QR-Code auf der Website, bestätigt in der App (Tools `enroll-qr`, `auth-qr`, `auth-qr-lookup`, `confirm-qr-login`, [`CONFIRM_PEER_LOGIN`](journeys/confirm-peer-login.md)); speichert `QrLoginRequest` selbst, kein Zugriff auf `account`; eigene `@RestController`
- **M12** `auth_device` — Geräteschlüssel als eigenes Anmeldeverfahren (Tools `enroll-device`, `auth-device`); eigene `@RestController`, keine Abhängigkeit von `account`
- **M13** `demo_seed` — Nur für die Demo: Legt für die Testpersonen des `keycloak`-Profils je ein Konto an, mit bestätigter Adresse und den Verfahren `password` (KNOWLEDGE), `sms` (POSSESSION) und `qr` (Zustimmung zum QR-Login). `password` und `sms` zusammen reichen für einen Step-up auf loa2. Es legt nur an und ändert nie: Gibt es für eine Testperson schon ein Konto mit ihrem PERSON_ID- oder EMAIL-Anker, wird sie übersprungen. Dafür nutzt es `AccountService` aus `account` (`recordClaim`, `recordClaims`, `addAuthenticationMethod`, `createUnidentifiedAccount`, `resolveAccountByPersonId`, `resolveAccountByEmail`) und aus `tool_api` die Ports `PasswordCredentialPort`, `SmsCredentialPort`, `QrCredentialPort` und `PersonDirectory`. Die Claims zu PERSON_ID, EMAIL und PHONE_NUMBER tragen `ClaimSource.DEMO_BOOTSTRAP`; alles geschieht in einer Transaktion. Stellt `DemoAccountSeed` bereit, das der Orchestrator nach dem Zurücksetzen der Demo erneut aufruft; kein `@RestController`
- **M14** `auth_kobil` — Gerätebindung über den externen Dienstleister KOBIL (Tools `enroll-kobil`, `auth-kobil`, [Abläufe](06-ablaeufe.md) Abschnitt 7); im Backend verwahrter PIN (ADR-21/ADR-22), PIN-Freigabe als eigene Unterressource; eigene `@RestController`, keine `account`-Abhängigkeit — aber eine ausdrücklich erlaubte Abhängigkeit zum Fremdsystem `kobil_mock` (wie `id_fsc` und `id_nect`)
- **M1a** `orchestrator.kernel` — Kein eigenes Modulith-Modul, sondern das unterste Paket **innerhalb** von `orchestrator`: die gemeinsamen Begriffe (`AuthIntent`, `AmrSource`, `AcrLevels`, `OrchestratorException`, `FeatureFlagProvider`). Es hängt von nichts ab, und genau das ist seine Aufgabe. Es gibt das Paket, weil fast jeder Paketzyklus im Orchestrator nur ein *Name* am falschen Ort war: `AmrSource` sagt zum Beispiel, woher ein Nachweis stammt (eine Frage der Richtlinie), lag aber neben der JPA-Entität, die den Nachweis speichert
- **M15** `kobil_mock` — Simuliertes **Fremdsystem**, kein Tool-Modul: einzige Abhängigkeit `texts` (kennt weder `tool_spi` noch `tool_api` noch die Journey), eigenes Schema, zwei Schnittstellen — die HTTP-Schnittstelle `/mock-kobil/*` für die App (Gegenstück zum MC SDK) und `KobilSsms` für unser Backend. Untersteht nicht unserer Aufbewahrung
- **M16** `id_nect` — Identifizierung über Nect (Tool `ident-nect`): Der Nutzer wechselt auf die Seite von Nect und kommt mit einer Vorgangsnummer zurück; das Ergebnis holt das Backend selbst ab. Bestätigt wie `id_eid` nur die Daten des Dokuments (ADR-18); `amr` je Verfahren `nect-eid`/`nect-epass`/`nect-eudi`. Erlaubte Abhängigkeit zum Fremdsystem: `nect_mock`
- **M17** `nect_mock` — Simulierter **Identifizierungsdienst** Nect, kein Tool-Modul: einzige Abhängigkeit `texts`, eigenes Schema, zwei Schnittstellen — `NectIdent` für unser Backend und die HTTP-Schnittstelle `/mock-nect/*` für die Sprungseite `/nect/` (eID, Reisepass, EUDI-Wallet). Untersteht nicht unserer Aufbewahrung
- **M18** `texts` — Bibliothek für mehrsprachige Nutzertexte (ADR-33): `Text` (deutsche Vorlage im Code, ausgeliefert als Referenz) und `TextBundle` (Sprachdateien per ETag). `allowedDependencies = []`; jedes Modul mit Nutzertexten deklariert diese Abhängigkeit, auch die simulierten Fremdsysteme

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
│   orchestrator          id_fsc / id_eid / id_nect / id_kvnr /           │
│   (Channel-Endpunkte,    auth_sms / auth_password / auth_email /        │
│    Journey/Policy)       auth_device / auth_qr / auth_kobil             │
│                          (jeweils die eigenen Tool-Endpunkte)           │
│                                                                          │
│         orchestrator.api.v1.tool.ToolSwitchController (generisch)      │
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
       │ orchestrator │   │   account   │      │  ext_personenverzeichnis   │
       │ (ToolEndpoint,│   │(AccountDir- │      │ (PersonDirectory) │
       │  DeviceProofs)│   │  ectory)    │      │                    │
       └─────────────┘   └─────────────┘      └───────────────────┘
```

- Kein Methodenmodul verweist auf den `orchestrator` und umgekehrt (`orchestrator/ModuleMetadata.kt`: `allowedDependencies = ["tool_spi", "tool_api", "account", "ext_personenverzeichnis", "kcmigrate", "demo_seed", "texts"]`; `kcmigrate` ist das Keycloak-Migrationsprojekt, `demo_seed` wird nur über `DemoAccountSeed` angesprochen). Die einzige gemeinsame Abhängigkeit ist `tool_api` — ein Methodenmodul kennt nur dessen Interfaces, nie eine konkrete Klasse des Orchestrators.
- Die HTTP-Pfade (`/orchestrator/api/v1/tools/...`) sind unabhängig vom Kotlin-Paket des jeweiligen `@RestController` (`id_fsc.api.v1`, `id_eid.api.v1`, `id_kvnr.api.v1`, `auth_sms.api.v1`, `auth_password.api.v1`, `auth_email.api.v1`, `auth_device.api.v1`, `auth_qr.api.v1`, `auth_kobil.api.v1`, `id_nect.api.v1`) — Spring leitet nach `@RequestMapping` weiter, nicht nach Paket. Ausnahmen: `kobil_mock.api.v1`, `nect_mock.api.v1` und `ext_personenverzeichnis.api.v1` liegen bewusst NICHT unter `/orchestrator/api`, sondern unter `/mock-kobil`, `/mock-nect` bzw. `/mock-personenverzeichnis` — sie sind die Fremdsysteme, nicht diese Anwendung.
- Die Methodenmodule sind voneinander und von `account` entkoppelt, einschließlich `auth_email`. Abhängigkeiten zu simulierten Fremdsystemen sind ausdrücklich erlaubt, nicht nur geduldet: `auth_kobil → kobil_mock`, `id_nect → nect_mock` (nur `NectIdent`); das Personenverzeichnis nur über Ports (ADR-31, Nachtrag). Konten werden über `tool_api.AccountDirectory` nachgeschlagen. Geschrieben wird nur über Claims im `ToolOutcome`, die die Journey übernimmt. Hilfsfunktionen, die ein Konto über die E-Mail-Adresse suchen, sind Kotlin-Erweiterungsfunktionen des Ports.
- `auth_sms` versteckt seine internen Datenbank-IDs hinter einer undurchsichtigen `EnrollmentRef` ([06-ablaeufe.md](06-ablaeufe.md)).
- Die Grenzen zwischen den Paketen sichert `@ApplicationModule(allowedDependencies = ...)` je Modul ab, und `DpopApplicationTests.modulithStructureIsValid` prüft sie; eine unerlaubte Abhängigkeit lässt den Build scheitern. Da Kotlin keine Annotationen an Paketen kennt, trägt je eine `ModuleMetadata.kt` die Deklaration (`@ApplicationModule` ist `@Target({PACKAGE, TYPE})`); ein `package-info.java` ist nicht nötig.
- Das Frontend kommuniziert ausschließlich über HTTP mit der Applikation als Ganzes; welches Modul einen Endpunkt implementiert, ist für es nicht sichtbar.

### Anforderungen an die Modulstruktur

- **M-1** — Jedes Modul hat ein eigenes Paket.
  - *Kriterium:* Paketstruktur unter `com.example.dpop.<modul>`
- **M-2** — Jedes Modul mit Laufzeitlogik stellt sie als Spring-Bean bereit.
  - *Kriterium:* `@Service`/`@Component`/`@RestController` im Modul; `texts`, `tool_spi` und `tool_api` sind reine Verträge ohne Bean
- **M-3** — Methodenmodule und Orchestrator sind nur über die gemeinsame SPI `tool_api` verbunden, nie direkt.
  - *Kriterium:* Konstruktor-Injection nur mit `tool_api`-Interfaces (`ToolEndpoint`, `AccountDirectory`, `PersonDirectory`, `ActivationCodes`, `DeviceProofs`); kein Methodenmodul importiert `orchestrator` und umgekehrt. Benannte Ausnahmen, jeweils zu einem simulierten Fremdsystem: `auth_kobil → kobil_mock`, `id_nect → nect_mock`, `auth_sms → sms_mock`, `auth_email → mail_mock` (simulierter SMS-Anbieter und Mailserver mit Postausgang statt Konsolenausgabe, Review 2026-09 M-11)
- **M-4** — Die Modulstruktur ist verifizierbar.
  - *Kriterium:* `ApplicationModules.verify()` in Tests

---

## 4) Persistenz

- Als Datenbank dient **H2**: im Betrieb als Datei unter `./data/dpopdb`, im Testprofil **im Arbeitsspeicher**.
- Das Schema wird mit **Flyway**-Migrationen aufgebaut, der Zugriff erfolgt über **Spring Data JPA**.
- **Ein Datenbankschema je Modul** (`account`, `orchestrator`, `auth_sms`, …): Jede Tabelle liegt im
  Schema ihres Moduls, Fremdschlüssel nur innerhalb eines Schemas
  ([12-entscheidungen.md](12-entscheidungen.md) ADR-16). Der Flyway-Verlauf bleibt in `PUBLIC`.
- Auch `kobil_mock` hat ein eigenes Schema, obwohl es kein Modul dieser Anwendung ist, sondern ein
  simuliertes Fremdsystem. Gerade deshalb ist die Trennung wichtig: Läge es im Schema von
  `auth_kobil`, könnte das Tool an der Schnittstelle vorbei nachsehen, und die Demo würde den echten
  Ablauf nicht mehr zeigen.
- Im Modul `ext_personenverzeichnis` existiert eine `Person`-Entität mit `id` (die Partnernummer), `versnr` (eindeutig, nur Versicherte), `kvnr` (eindeutig, nur zusammen mit `versnr`), `name`, `vorname`, `strasse`, `hausnummer`, `plz`, `ort`, `geburtsdatum`. Dazu `freischaltcode` (nur Hash, Ablauf, Widerruf) und `brief` (der simulierte Brief mit dem Klartext, ADR-31).
- Beim Start der Anwendung spielt eine Flyway-Migration Testpersonen und gültige Freischaltcodes ein.

Die Session- und Tool-Entitäten beschreibt [02-domaenenmodell.md](02-domaenenmodell.md) (Tabellenmodell
in Abschnitt 7), Aufbewahrung und Löschung [07-betrieb.md](07-betrieb.md).

### H2-Konsole: nur beim Host-Start

Die H2-Konsole unter `/h2-console` ist bewusst eingeschaltet, aber `web-allow-others` bleibt
`false` (Begründung im Kommentar in `application.yml`). Spring Security ist nicht eingebunden. Diesen
Pfad schützt deshalb allein die Prüfung von H2, ob die Anfrage vom eigenen Rechner kommt, und
dahinter liegen Passwort-Hashes, Geräteschlüssel und alle Sitzungen.

Diese Prüfung vergleicht die Absenderadresse. Bei `./gradlew bootRun` ist das `127.0.0.1`, und die
Konsole funktioniert. **Im Container (`compose.yml`) geht sie nicht:** Dort erreicht die Anfrage den
Orchestrator über die Portweiterleitung `8080:8080` mit der Adresse des Container-Netzes. Für H2 ist
das eine Verbindung von außen, und H2 lehnt sie ab mit *„remote connections ('webAllowOthers') are
disabled on this server“*. Der Schutz wirkt also wie vorgesehen.

Wer in die Datenbank sehen will, startet deshalb den Orchestrator direkt auf dem Rechner und lässt
nur Keycloak über Compose laufen. Die Variante `host` (Voreinstellung von `KEYCLOAK_SETUP_VARIANT`)
richtet Keycloak dafür bereits auf `host.containers.internal:8080` aus. Wer die Daten eines Laufs im
Container braucht, kopiert die Datei aus dem gestoppten Volume `orchestrator-data` heraus.
`web-allow-others` einzuschalten kommt nicht in Frage: Der Port ist auf dem Rechner nach außen
freigegeben, und jeder, der ihn erreicht, bekäme vollen Lese- und Schreibzugriff.

- **P-1** — H2 als Datei im Betrieb und im Arbeitsspeicher für Tests.
  - *Kriterium:* `application.yml` und `application-test.yml` entsprechend konfiguriert
- **P-2** — Das Schema baut Flyway auf, mit einem Migrationsordner je Modul.
  - *Kriterium:* `src/main/resources/db/migration/<modul>/`; `ModuleMigrationLocations` findet die Ordner selbst
- **P-3** — Auf Personen wird über Spring Data JPA zugegriffen.
  - *Kriterium:* `PersonRepository extends JpaRepository`
- **P-4** — Die Adresse einer Person ist in einzelne Attribute aufgeteilt.
  - *Kriterium:* Entität enthält `strasse`, `hausnummer`, `plz`, `ort`. Bestätigt wird die Straße dagegen als **eine** Zeile mit Hausnummer (`AttributeType.STRASSE`), so wie eID und PID sie liefern; das Personenverzeichnis setzt `strassenzeile` an seiner Schnittstelle zusammen
- **P-5** — Testdaten werden beim Start eingespielt.
  - *Kriterium:* Flyway-Migration oder Initialisierungsroutine vorhanden
- **P-6** — Freischaltcodes zum Testen stehen beim Start zur Verfügung.
  - *Kriterium:* Eine Flyway-Migration legt gültige Freischaltcodes für die Testpersonen an

---

## 5) Architekturbeschränkungen

| ID | Beschränkung | Begründung |
|----|--------------|------------|
| A1 | Build-Tool: Gradle mit Kotlin-DSL | Einheitliche, typsichere Build-Konfiguration |
| A2 | Gradle Wrapper muss enthalten sein | Reproduzierbarkeit ohne lokale Gradle-Installation |
| A3 | JVM-Version 21 (Ziel des Bytecodes), Kotlin 2.4.0 | Voraussetzung für Spring Boot 4.x; Kotlin als Implementierungssprache |
| A4 | Aktuelle Spring Boot-Version verwenden | Sicherheit und Aktualität |
| A5 | Versionen zentral in `gradle/libs.versions.toml` pflegen | Zentrale Versionsverwaltung, konsistente Abhängigkeiten |
| A6 | Frontend-Build ist in den Gradle-Build integriert | Einheitlicher Build-Prozess für Backend und Frontend |
| A7 | Das gebaute Frontend landet in `src/main/resources/static` | Spring Boot liefert das Frontend als statische Ressource aus |
| A8 | Datenbank: H2 (als Datei im Betrieb, im Arbeitsspeicher in Tests) | Einfache lokale Entwicklung und schnelle Tests |
| A9 | Schemaverwaltung mit Flyway | Versionierter und reproduzierbarer Datenbankaufbau |
| A10 | Datenzugriff mit Spring Data JPA | Standardisierte Persistenzschicht |
| A11 | Lesbarkeit hat Vorrang vor einer maximal generischen API-Anbindung | Endpunkte, DTOs und Handler bleiben tool-spezifisch explizit (`ident-fsc`, `enroll-sms`, `auth-sms`) |

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
- **Test**: Kotest auf der JUnit-Plattform mit Spring Boot Test, MockK und Spring Modulith Test-Starter

| Komponente | Version |
|------------|---------|
| Spring Boot | `4.1.0` |
| Spring Modulith | `2.1.1` |
| Dependency Management Plugin | `1.1.7` |
| Gradle (Wrapper) | `9.7.0` |
| Kotlin | `2.4.0` |
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
- `./gradlew bootRun` startet die Anwendung auf Port 8080. Der Befehl läuft, bis man ihn beendet; zum Prüfen eignen sich Integrationstests besser.
- Integrationstests starten den eingebetteten Server auf einem zufälligen Port und prüfen den Ablauf einer DPoP-gesicherten Sitzung.
- `ApplicationModules.verify()` prüft, ob die erlaubten Abhängigkeiten **zwischen** den Modulen eingehalten werden.
- `OrchestratorArchitectureTest` prüft die Schichtung innerhalb von `orchestrator`, die Modulith nicht sieht:
  - Die Teilpakete müssen zyklenfrei sein (`slices().beFreeOfCycles()`). Es gab dort fünf Zyklen (`session` ↔ `policy`, `journey`, `journeylog`; `kc` ↔ `dpop`; `session` → `api.v1`); sie sind über das Paket `kernel` beseitigt (Abschnitt 3 und [ADR-27](adr/ADR-027-gemeinsame-typen-im-kernel-paket.md)).
  - Aus einer offenen Transaktion darf kein Keycloak-Aufruf herausgehen. Sonst hält die Transaktion Zeilensperren so lange, wie der fremde Dienst zum Antworten braucht. Einzige Ausnahme ist `KcTokenProvider`: dort ist das Token die Antwort selbst.
  - Nichts außerhalb von `api` hängt an `api.v1`. Dort stehen nur Routen, Request-DTOs, Parameterbindung und die OpenAPI-Beschreibung. Die Kanal-Services, die Zugriffsprüfungen (`ChannelAccessGuard`), `DemoDisclosure` und die Antwortformen liegen darunter in `orchestrator/channel`. Die Antwortformen sind wie `ChannelResponse` in `tool_api` unversioniert, weil es eine globale Version gibt ([API](05-api.md) Abschnitt 1). Ein v2 könnte damit neben v1 stehen, ohne v1 zu importieren.
  - Nur `DemoDisclosure` erzeugt ein `DemoInfo`. Damit entfernt `demo.disclosure=false` die Klartext-TANs aus jeder Antwort, statt sie an einer von mehreren Stellen zu filtern ([ADR-28](adr/ADR-028-demo-werte-abschaltbar.md)).
- `ToolSessionCoverageTest` prüft gegen das tatsächliche Schema, dass ein Aufräumlauf jede `*_tool_session`-Tabelle leert ([Betrieb](07-betrieb.md) Abschnitt 3).
- `EventPublicationRegistryTest` prüft, dass ein fehlschlagender `@ApplicationModuleListener` eine offene Zeile hinterlässt ([Betrieb](07-betrieb.md) Abschnitt 3a).
- `checkOpenApiSnapshot` und `generateFrontendApiTypes` halten den API-Vertrag und die daraus erzeugten Frontend-Typen deckungsgleich ([API](05-api.md) Abschnitt 1).
- `checkPublishedApiCompatibility` vergleicht den Vertrag mit dem veröffentlichten Stand `api/published/v1.yaml` und schlägt bei einem Bruch fehl; `ContractScopeTest`, `StepDataExamplesTest` und `DiscriminatorMappingTest` prüfen Umfang, Beispiele und Diskriminatoren des Vertrags ([API](05-api.md) Abschnitt 1).
- Die CI führt zusätzlich `tsc -b` aus (vitest prüft keine Typen), dazu `oxlint` und die Playwright-Tests.

### Vorbedingungen in Integrationstests

Die Registrierung (Identifizierung → E-Mail-Bestätigung → Anmeldeverfahren einrichten) wird nur dort
per HTTP Schritt für Schritt durchlaufen, wo sie selbst geprüft wird: `RegistrationFlowIntegrationTest`,
`RequiredActionIntegrationTest` (Reihenfolge der Pflichten) und `JourneyLogIntegrationTest` (das
Journey-Log entsteht nur durch einen echten Durchlauf).

Alle anderen Testklassen brauchen nur ihr *Ergebnis*: „ein Konto mit SMS und Passwort, mit diesem
Gerät verknüpft“. Das stellt `AccountFixtures` (im Testcode) über die Dienste der Fachmodule her,
nicht über SQL. So gelten dieselben Regeln wie im echten Betrieb (Mindestniveaus der Anker, Ersetzen
eines vorhandenen Verfahrens, Herkunft der Claims). Dasselbe Vorgehen nutzt `demo_seed`
(`KcDemoAccountSeeder`).

Einstiegspunkte in `IntegrationTestSupport`:

| Hilfsfunktion | Vorbedingung |
| --- | --- |
| `seedRegisteredAccount()` | Konto existiert (sms + Passwort, bestätigte Adresse, Gerät verknüpft), kein Kanal |
| `loginAsSeededAccount()` | dazu ein angemeldeter loa2-Kanal (`amr = [sms, password]`) |
| `registerAndAuthenticate()` | echte Registrierung, dadurch zusätzlich mit einem eigenen `fsc`-Nachweis |

Die letzten beiden unterscheiden sich fachlich: Ein nur angemeldeter Kanal hat keinen eigenen
Nachweis einer Identifizierung. Tests, die einen solchen brauchen (etwa das Entfernen eines
Verfahrens, das sonst der Nachweis der aktuellen Anmeldung wäre), müssen `registerAndAuthenticate()`
verwenden.
