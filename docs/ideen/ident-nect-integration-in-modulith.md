# Idee: ext-ident in den Modulith integrieren (`id_nect` als vollständiges Modul)

Status: **Konzept, nicht umgesetzt**. Untersucht, ob der `ext-ident`-Microservice aufgelöst
und seine Fachlogik als Modulith-Modul in das DPoP-demo-Projekt integriert werden kann.

Gegenüberstellung mit der Variante „ext-ident als externen Service anbinden":
[ident-nect-als-tool.md](ident-nect-als-tool.md).

---

## 1) Ausgangslage

Der Microservice `ext-ident` besteht aus drei Teilmodulen:

| Modul | Verantwortung | Umfang |
|---|---|---|
| `ext-ident-server` | Order-Validierung, Case-Lifecycle, Nect-Anbindung, TKeasy-Matching, Confirmation-Erzeugung, Terminierung | ~45 Klassen, 2 JPA-Entitäten, 3 DB-Tabellen |
| `nect-rest-client` | HTTP-Client für die Nect-API (Cases anlegen, Status, Daten abrufen) | ~15 Klassen, kein eigener Zustand |
| `ext-ident-devtools` | Mock/Proxy für Nect auf Teststages | ~20 Klassen, 1 Entität, Thymeleaf-UI |

Die Domäne ist **überschaubar**: ein Aggregat (`IdentCase` mit `StateEntries`), ein externer
Partner (Nect), ein interner Abgleich (TKeasy/Stammdaten). Die Komplexität liegt weniger im
Modell als in den Integrationspunkten (JWT-Signatur, PKCE, Callback-Handling, Scheduling).

---

## 2) Kernidee

Statt ext-ident als Blackbox über HTTP anzusprechen, wird seine Fachlogik als Modulith-Modul
`id_nect` direkt in die DPoP-demo-Codebasis übernommen. Das Modul folgt denselben Konventionen
wie `id_fsc`, `id_eid`, `auth_sms` etc. und kommuniziert über `ToolOutcome` mit dem Orchestrator.

### Was übernommen wird

| ext-ident-Komponente | Wird zu | Bemerkung |
|---|---|---|
| `IdentCase` + `StateEntry` | `id_nect/internal/NectIdentCase` | Entität im Modulith, eigene Tabellen |
| `IdentCaseCreator` | `id_nect/internal/NectCaseCreator` | Case bei Nect anlegen |
| `IdentCaseCompletor` | `id_nect/internal/NectCaseCompletor` | Ausweisdaten abrufen, Abgleich |
| `IdentCaseUpdateHandler` | `id_nect/internal/NectCallbackHandler` | Nect-Callback empfangen |
| `IdentCaseTerminator` | `id_nect/internal/NectCaseTerminator` | Abgelaufene Cases bereinigen |
| `NectClient` | `id_nect/internal/nect/NectClient` | HTTP-Client für Nect-API |
| `NectCallbackApi` | `id_nect/api/v1/NectCallbackController` | Protected Endpoint für Nect |
| Order-Validierung | **entfällt** | Kein JWT-Order-Austausch mehr nötig |
| Confirmation-Erzeugung | **entfällt** | Direkt `ToolOutcome.Completed.Identified` |
| PKCE | **entfällt** | Kein Inter-Service-Vertrauen nötig |
| TKeasy-Matching | `PersonDirectory` / `ext_stammdaten` | Bestehender Port im Modulith |
| Backoffice-API | **entfällt oder separat** | Nicht Teil des Tool-Flows |
| Billing-API | **entfällt** | Betriebsthema, nicht Teil des Demo |

### Was wegfällt

Durch die Integration entfallen alle Inter-Service-Sicherheitsmechanismen:

- **Order-JWT** (Signatur, Validierung, Issuer-Whitelist) — der Orchestrator aktiviert
  das Tool direkt, kein signiertes Ticket nötig.
- **Confirmation-JWT** — das Tool meldet `Completed.Identified` als Kotlin-Objekt,
  nicht als signiertes Token.
- **PKCE** — kein Frontend-zu-Fremdsystem-Vertrauen nötig.
- **API-Key-Filter** — kein separater Service, kein API-Key.
- **Internal-Token-Verifikation** — Modulith-intern, nicht nötig.
- **JWKS-Endpoint** — kein Schlüsselaustausch zwischen Services.

---

## 3) Modulstruktur im Modulith

```
src/main/kotlin/com/example/dpop/id_nect/
├── ModuleMetadata.kt                    # allowedDependencies
├── Descriptors.kt                       # IdentNectDescriptor
├── api/v1/
│   ├── IdentNectToolController.kt       # Tool-Schritte (GET, PATCH)
│   └── NectCallbackController.kt        # PUT /callbacks/nect/{caseId}
├── internal/
│   ├── NectIdentCase.kt                 # JPA-Entität
│   ├── NectCaseStateEntry.kt            # Zustandshistorie
│   ├── NectIdentCaseRepository.kt       # Spring Data
│   ├── NectToolHandler.kt               # Flow-Logik
│   ├── NectCaseCreator.kt               # Case bei Nect anlegen
│   ├── NectCaseCompletor.kt             # Case einlösen, Daten abrufen
│   ├── NectCallbackHandler.kt           # Callback verarbeiten
│   ├── NectCaseTerminator.kt            # Abgelaufene Cases bereinigen
│   └── nect/
│       ├── NectClient.kt                # HTTP-Client (portiert)
│       ├── NectProperties.kt            # Konfiguration
│       └── model/                       # DTOs für Nect-API
```

### Modul-Abhängigkeiten

```kotlin
object IdNectModuleMetadata : ModuleMetadata {
    override val allowedDependencies = listOf("tool_spi", "tool_api")
}
```

Keine Abhängigkeit auf `account`, `ext_stammdaten` oder `orchestrator` — genau wie
`id_fsc` und `id_eid`. Stammdaten-Abgleich läuft über `PersonDirectory` aus `tool_api`.

---

## 4) Tool-Descriptor

```kotlin
internal const val NECT_METHOD = "nect"

@Component
object IdentNectDescriptor : ToolDescriptor {
    override val toolId = "ident-nect"
    override val method = NECT_METHOD
    override val role = ToolRole.IDENTIFICATION
    override val factorTypes = setOf(FactorType.POSSESSION)
    override val maxAcr = Acr.LOA2
}
```

---

## 5) Ablauf im Tool

### Schritte

| nextStep | Wer agiert | Was passiert |
|---|---|---|
| `redirect` | Frontend | Nutzer wird zur Nect-Website weitergeleitet |
| `waiting` | Frontend | Hinweis „Identifikation läuft", Polling oder Callback-basiert |
| `result` | Backend | Nach Nect-Callback: Ausweisdaten abrufen, Abgleich, Outcome |

### Sequenz

```
Frontend              id_nect-Handler         NectClient           Nect
    │                       │                     │                 │
    ├─ GET (Tool starten) ─>│                     │                 │
    │                       ├─ createCase ────────>│                 │
    │                       │                     ├─ POST /cases ──>│
    │                       │                     │<── caseId ──────┤
    │                       │                     │                 │
    │                       │  NectIdentCase       │                 │
    │                       │  in DB speichern     │                 │
    │                       │                     │                 │
    │<── InProgress("redirect", {nect_url}) ──────│                 │
    │                       │                     │                 │
    ├── Browser → Nect ────────────────────────────────────────────>│
    │                       │                     │    Ident        │
    │                       │                     │                 │
    │              PUT /callbacks/nect/{caseId} <─────── Callback ──┤
    │                       │                     │                 │
    │                       │  State speichern     │                 │
    │                       │                     │                 │
    │<──────────── Nect redirect (callback_uri) ───────────────────┤
    │                       │                     │                 │
    ├─ PATCH (zurück) ─────>│                     │                 │
    │                       │  Status prüfen       │                 │
    │                       │                     │                 │
    │                       ├─ getData ──────────>│                 │
    │                       │                     ├─ GET /cases/x ─>│
    │                       │                     │<── Daten ───────┤
    │                       │                     │                 │
    │                       │  PersonDirectory     │                 │
    │                       │  .matchesStammdaten  │                 │
    │                       │                     │                 │
    │<── Completed.Identified(personId, amr=E_PASS, acr=loa2) ─────│
    │                       │                     │                 │
```

### Unterschied zu Variante 1 (ext-ident als Service)

- **Kein Order-JWT** — der Orchestrator aktiviert das Tool, das Tool erstellt den
  Nect-Case direkt.
- **Kein PKCE** — kein Inter-Service-Kanal, keine Browser-vermittelte Kommunikation
  zwischen zwei Backends.
- **Kein Confirmation-JWT** — `ToolOutcome` ist der Rückkanal.
- **Nect-Callback direkt** — der Modulith empfängt den Callback selbst
  (`PUT /callbacks/nect/{caseId}`), kein Umweg über ext-ident.
- **Stammdaten-Abgleich über bestehenden Port** — `PersonDirectory.matchesStammdaten`
  statt eigenem TKeasy-Client.

---

## 6) Nect-Callback im Modulith

Der Nect-Callback ist der einzige Punkt, an dem eine externe Partei den Modulith
kontaktiert (außer dem regulären Frontend). Das erfordert:

### Eigener Security-Chain-Eintrag

```kotlin
// In der Security-Konfiguration des Modulith
http.securityMatcher("/callbacks/nect/**")
    .authorizeHttpRequests { it.anyRequest().permitAll() }
    .csrf { it.disable() }
```

Nect-Callbacks sind nicht authentifiziert (kein API-Key, kein Token) — die Sicherheit
liegt in der Unvorhersagbarkeit der `caseId` (UUID) und der serverseitigen
Zustandsprüfung. Das ist das bestehende Sicherheitsmodell von ext-ident.

### Callback-URI-Konfiguration bei Nect

```yaml
id-nect:
  nect:
    server-callback-uri: https://dpop-demo.example.net/callbacks/nect/{{uuid}}
```

---

## 7) Was mit den Devtools passiert

Drei Optionen:

**A) Devtools als separaten Prozess behalten** — der NectClient zeigt auf die Devtools
statt auf das echte Nect. Minimaler Aufwand, funktioniert sofort.

**B) Devtools-Logik als Test-Profil integrieren** — ein `@Profile("devtools")`-Modul im
Modulith, das einen Mock-NectClient bereitstellt und Nect-Callbacks simuliert.
Komfortabler, aber Aufwand.

**C) Testcontainer / WireMock** — Nect-API-Mocks in Integrationstests, Devtools entfallen.
Sauberer für CI, aber weniger interaktiv.

**Empfehlung: A für den Anfang**, B oder C bei Bedarf.

---

## 8) Datenbank-Integration

### Neue Tabellen im Modulith

```sql
-- Flyway-Migration: V<next>__create_nect_ident_tables.sql

CREATE TABLE nect_ident_case (
    id              BIGSERIAL PRIMARY KEY,
    person_id       VARCHAR(50),
    nect_case_id    UUID NOT NULL UNIQUE,
    transaction_id  UUID NOT NULL UNIQUE,
    callback_uri    VARCHAR(500),
    process         VARCHAR(100),
    loa             VARCHAR(20),
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    terminated_at   TIMESTAMP
);

CREATE TABLE nect_ident_case_state (
    id              BIGSERIAL PRIMARY KEY,
    ident_case_id   BIGINT NOT NULL REFERENCES nect_ident_case(id),
    state           VARCHAR(50) NOT NULL,
    reason          VARCHAR(50),
    success         BOOLEAN,
    received_at     TIMESTAMP NOT NULL DEFAULT now()
);
```

Die Tabellen liegen in derselben Datenbank wie der Rest des Modulith (H2 im Dev,
PostgreSQL in Produktion). Kein Schema-Prefix nötig — der Tabellenname `nect_ident_case`
ist hinreichend spezifisch.

---

## 9) Konfiguration

```yaml
id-nect:
  nect:
    loa-endpoint-mapping:
      SUBSTANTIAL:
        url: https://api.nect.com/sp/xxx
        apikey: ${NECT_APIKEY_SUBSTANTIAL}
      HIGH:
        url: https://api.nect.com/sp/yyy
        apikey: ${NECT_APIKEY_HIGH}
    server-callback-uri: https://dpop-demo.example.net/callbacks/nect/{{uuid}}
    jumppage: https://jump.nect.com
  termination:
    expiration-hours: 48
    cron: "0 0 0 * * *"
```

---

## 10) Frontend

Das Frontend-Modul `ident-nect` ist bei beiden Varianten nahezu identisch — der
Nutzer sieht denselben Ablauf:

1. **`redirect`-Schritt**: Button/Link zur Nect-Website.
2. **Rückkehr**: Browser kommt über die `callback_uri` zurück, Frontend meldet
   sich beim eigenen Backend.

Der Unterschied: Bei der Integration kommuniziert das Frontend **ausschließlich**
mit dem eigenen Backend (wie alle anderen Tools). Bei Variante 1 (ext-ident als
Service) müsste es ggf. auch ext-ident direkt ansprechen.

---

## 11) Portierungsaufwand

| Arbeitspaket | Geschätzter Aufwand | Bemerkung |
|---|---|---|
| NectClient portieren (Java → Kotlin) | 1–2 Tage | ~15 Klassen, hauptsächlich DTOs |
| IdentCase-Domäne portieren | 1 Tag | 2 Entitäten, Repository, State-Logik |
| ToolController + Handler schreiben | 1–2 Tage | Neuer Code nach bestehendem Muster |
| Nect-Callback-Endpoint | 0,5 Tage | Einfacher PUT-Controller |
| Stammdaten-Abgleich über PersonDirectory | 0,5 Tage | Port existiert bereits |
| Terminierung-Job | 0,5 Tage | @Scheduled + ShedLock |
| Frontend-Modul (Redirect-Handling) | 1–2 Tage | Neues Muster |
| Flyway-Migrationen | 0,5 Tage | 2 Tabellen |
| Konfiguration + Nect-Zugänge | 0,5 Tage | Properties, Secrets |
| **Gesamt** | **~7–9 Tage** | |

### Was dabei **nicht** portiert wird

- Order/Confirmation-JWT-Mechanik (nicht nötig im Modulith)
- PKCE-Handling (nicht nötig)
- Backoffice-API (kein Anwendungsfall)
- Billing-API (kein Anwendungsfall)
- API-Key-Security (kein separater Service)
- Internal-Token-Verifikation (modulith-intern)
- JWKS-Endpoint (kein Schlüsselaustausch)

Das sind ca. **40 % des ext-ident-Codes**, die ersatzlos entfallen.

---

## 12) Variantenvergleich: Anbindung vs. Integration

### Langfristige Bewertungskriterien

| Kriterium | Variante 1: ext-ident als Service | Variante 2: Integration in Modulith |
|---|---|---|
| **Architektonische Konsistenz** | ⚠️ Einziges Tool mit externer Backend-Abhängigkeit; bricht das Muster „Tools sind modulith-interne Module" | ✅ Fügt sich nahtlos in das bestehende Muster ein; kein Sonderfall |
| **Betriebskomplexität** | ⚠️ Zwei Systeme betreiben, überwachen, deployen; Netzwerkverbindung pflegen | ✅ Ein Deployment-Artefakt; Nect-Anbindung als einzige externe Abhängigkeit |
| **Ausfallverhalten** | ⚠️ ext-ident-Ausfall blockiert Identifikation; Circuit-Breaker / Retry nötig | ✅ Weniger Fehlerquellen; nur Nect selbst als externer Punkt |
| **Latenz** | ⚠️ Zusätzlicher Netzwerk-Hop (DPoP-Backend → ext-ident → Nect) | ✅ Direkter Aufruf (DPoP-Backend → Nect) |
| **Sicherheitskomplexität** | ⚠️ Order-JWT, Confirmation-JWT, PKCE, JWKS, Issuer-Whitelist — alles für Inter-Service-Vertrauen | ✅ Entfällt komplett; Tool-Vertrag ist typsicher und in-process |
| **Schlüsselmanagement** | ⚠️ Eigener Signaturschlüssel, JWKS-Rotation, Audience-Konfiguration auf beiden Seiten | ✅ Nur Nect-API-Keys (wären bei beiden Varianten nötig) |
| **Testbarkeit** | ⚠️ Abhängig von ext-ident-Teststages oder eigenen Mocks; Integrationstest über Netzwerk | ✅ NectClient mockbar im Modulith; Devtools als separater Prozess oder WireMock |
| **Unabhängige Deploybarkeit** | ✅ ext-ident separat deploybar; andere Teams können es unabhängig weiterentwickeln | ⚠️ Nect-Logik ist Teil des Modulith; Änderungen erfordern Modulith-Release |
| **Wiederverwendbarkeit** | ✅ ext-ident bleibt als eigenständiger Service für andere Konsumenten nutzbar | ⚠️ Nect-Integration ist modulith-spezifisch; andere Systeme müssten eigene Anbindung bauen |
| **Domänenkohäsion** | ⚠️ Identifikationslogik lebt in zwei Systemen; Zuständigkeit unklar | ✅ Gesamte Identifikation (FSC, eID, Nect) unter einem Dach |
| **Initiale Umsetzung** | ✅ ~3–5 Tage (weniger Code, ext-ident existiert) | ⚠️ ~7–9 Tage (Portierung, aber Code ist bekannt) |
| **Langfristiger Wartungsaufwand** | ⚠️ Koordination zwischen zwei Codebases; API-Vertrag pflegen; Versionsabgleich | ✅ Eine Codebasis; Refactoring über IDE; keine API-Versionierung |
| **Flexibilität bei Nect-Änderungen** | ⚠️ Änderungen an Nect-API erfordern ext-ident-Release + ggf. Anpassung der Order/Confirmation | ✅ Änderungen direkt im Modul; ein Release |
| **Rückbaubarkeit** | ✅ Modul entfernen, ext-ident läuft weiter | ✅ Modul entfernen; kein externer Service übrig |

### Zusammenfassung

**Variante 1 (ext-ident als Service)** ist die pragmatische Wahl für einen **schnellen, temporären** Einsatz:
- Weniger initialer Aufwand.
- ext-ident bleibt als eigenständiger Service bestehen.
- Aber: bricht das Architekturmuster, erhöht die Betriebskomplexität und erfordert aufwändiges Inter-Service-Sicherheitsprotokoll.

**Variante 2 (Integration in Modulith)** ist die bessere Wahl für eine **langfristige** Lösung:
- Passt zum Modulith-Ansatz: ein Deployment, ein Modell, eine Codebasis.
- 40 % des ext-ident-Codes entfallen (alles, was nur dem Inter-Service-Vertrauen dient).
- Höherer Anfangsaufwand, aber langfristig weniger Wartung und weniger Fehlerquellen.
- Setzt voraus, dass kein anderer Konsument ext-ident als Service benötigt (oder dass ext-ident parallel weiterbetrieben wird).

### Empfehlung

| Szenario | Empfohlene Variante |
|---|---|
| Prototyp / Demo / zeitlich begrenzt | **Variante 1** — schnell, rückbaubar |
| Langfristige Nutzung im Modulith | **Variante 2** — architekturkonform, wartungsarm |
| ext-ident hat andere aktive Konsumenten | **Variante 1** — ext-ident darf nicht aufgelöst werden |
| ext-ident wird nur von DPoP-demo genutzt | **Variante 2** — kein Grund für zwei Systeme |
| Schrittweiser Übergang gewünscht | **Variante 1 zuerst**, später zu **Variante 2** migrieren |

---

## 13) Migrationspfad (Variante 1 → Variante 2)

Falls man mit Variante 1 beginnt und später auf Variante 2 wechselt:

1. **Backend-Modul** umbauen: ext-ident-HTTP-Client durch direkten NectClient ersetzen;
   Order/Confirmation-JWT-Logik entfernen.
2. **Nect-Callback** auf den Modulith umleiten (Konfiguration bei Nect).
3. **Datenbank-Tabellen** im Modulith anlegen; ggf. bestehende Cases migrieren.
4. **Frontend** bleibt weitgehend unverändert (Redirect-Muster identisch).
5. **ext-ident abschalten** (falls kein anderer Konsument).

Der Migrationspfad ist überschaubar, weil das Tool-Interface (`ToolDescriptor`,
`ToolOutcome`) bei beiden Varianten identisch ist — nur die interne Implementierung
ändert sich.

---

## 14) Nächste Schritte (falls Umsetzung gewünscht)

1. Entscheiden, welche Variante (oder stufenweiser Übergang).
2. Nect-Zugangsdaten und Callback-Konfiguration klären.
3. NectClient portieren (Java → Kotlin, Spring WebClient oder RestClient).
4. Modul `id_nect` anlegen nach bestehendem Muster (`id_fsc` als Vorlage).
5. Frontend-Modul mit Redirect-Handling implementieren.
6. Devtools-Anbindung für Tests konfigurieren.
