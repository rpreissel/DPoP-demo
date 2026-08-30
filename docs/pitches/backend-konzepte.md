# Orchestrator-Konzepte für Backend-Entwickler

Worum es hier geht: wie Tool und Orchestrator zusammenspielen, mit den Begriffen, die der
Code dafür präzise verwendet. Details in
[../03-tool-architektur.md](../03-tool-architektur.md) und
[../04-orchestrierung.md](../04-orchestrierung.md).

---

## Worum es eigentlich geht

```mermaid
flowchart LR
  N["Neuer Nutzer"] -- einmalig --> R["Registrierung"]
  R --> L
  B["Wiederkehrender Nutzer"] --> L["Login"]
  L --> T["AccessToken"]
  T -- "direkt, ohne Orchestrator" --> F["Fachlichkeit / Microservices"]
```

Das eigentliche Ziel ist immer dasselbe: ein `AccessToken`, mit dem die App danach die
Fachlichkeit — Microservices, andere Backends — **direkt** aufruft, ohne Umweg über den
Orchestrator. Registrierung ist kein eigener Zweck, sondern nur die einmalige Voraussetzung
dafür, dass ein neuer Nutzer danach einloggen kann. Das `AccessToken` selbst stammt aus einem
Standard-OIDC-Tokenfluss gegen Keycloak, den der Orchestrator serverseitig abwickelt.

## Die Einordnung im Gesamtbild

Dasselbe Bild wie im Frontend-Pendant, nur jetzt aus Backend-Sicht relevant: der Orchestrator
ist ein Modulith mit eigenen Tool-Modulen, spricht für den Tokenfluss und die Account-Pflege
mit Keycloak, und einzelne Tool-Module delegieren ihrerseits an externe Dienste.

```mermaid
flowchart LR
  subgraph App["App"]
    NE["Orchestrator-Engine"]
    UI1["SMS-UI"]
    UI2["Passwort-UI"]
    UI3["Geräte-UI"]
  end

  subgraph Backend["Orchestrator-Modulith"]
    O["Orchestrator<br/>next / stepData / Journey"]
    AC["account"]
    M1["auth_sms"]
    M2["auth_password"]
    M3["auth_device"]
  end

  KC["Keycloak"]
  EXT1["externer SMS-Versand"]
  KOBIL["Kobil"]
  KC ~~~ EXT1 ~~~ KOBIL

  NE --> O
  O --> KC
  O --> AC
  AC --> KC

  UI1 --> M1
  UI2 --> M2
  UI3 --> M3

  M1 -.-> EXT1
  UI3 -. "geräteeigenes SDK, kein Umweg möglich" .-> KOBIL
  M3 -.-> KOBIL
```

Der Rest dieses Dokuments zoomt in genau die `Backend`-Box hinein: Wie hängen Orchestrator und
ein Tool-Modul wie `auth_sms` an einem konkreten Schritt zusammen?

## Zusammenspiel an einem Schritt

```mermaid
sequenceDiagram
  participant TC as ToolController (Methodenmodul)
  participant TH as ToolHandler
  participant JS as JourneyService
  participant IS as IntentStrategy
  participant AC as account
  participant KC as Keycloak

  TC->>TH: Eingabe verarbeiten (z.B. TAN prüfen)
  TH-->>TC: ToolOutcome.Completed

  TC->>JS: applyOutcome(context, ToolOutcome.Completed)
  JS->>IS: interpret(state, tool, outcome) : Effect
  IS-->>JS: Effect
  JS->>AC: Effect ausführen (Account finden/anlegen, Methode eintragen)
  AC->>KC: Account anlegen/syncen
  AC-->>JS: JourneyContext aktualisiert
  JS->>IS: decide(state, Completed(...), ctx) : Decision
  IS-->>JS: Decision
  JS-->>TC: ChannelResponse (next/stepData)
```

Was `ToolHandler` intern tut, um zu diesem `ToolOutcome` zu kommen, ist bewusst nicht Teil
dieses Bildes: eigene, tool-spezifische Fachlogik gegen ein eigenes Schema (`ToolDB`), auf das
nichts außerhalb des Moduls zugreift.

---

Vollständige Zustandsdiagramme je Intent, Sub-Journey-Mechanik, Versuchsbudget, MFA-Regel:
[../04-orchestrierung.md](../04-orchestrierung.md). Tool-Katalog und `ToolOutcome`-Vertrag im
Detail: [../03-tool-architektur.md](../03-tool-architektur.md). Modulliste und
-abhängigkeiten: [../08-projektrahmen.md](../08-projektrahmen.md).
