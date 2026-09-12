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

Aus Backend-Sicht relevant: der Orchestrator ist ein Modulith mit eigenen Tool-Modulen,
spricht für den Tokenfluss und die Account-Pflege mit Keycloak, und einzelne Tool-Module
delegieren ihrerseits an externe Dienste.

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

### Zwei Kanäle, ein Modell

Das Bild oben zeigt bewusst nur den App-Kanal (eigene UI, DPoP-Proof pro Request) — aus
Backend-Sicht ist das aber nur eine von zwei Fassaden vor demselben Orchestrator. Ein
`Channel` trägt seinen `channelType` (`APP`/`KEYCLOAK`) fest für seine ganze Lebenszeit
([02-domaenenmodell.md](../02-domaenenmodell.md)), aber Journey/Tool/`next` — alles, was
oben und im Sequenzdiagramm unten beschrieben ist — läuft für beide identisch.

```mermaid
flowchart LR
  subgraph AppFacade["App-Fassade"]
    App["native App<br/>eigene UI je Tool"]
  end
  subgraph WebFacade["Web-Fassade"]
    Browser["Browser<br/>Keycloak-eigenes Theme"]
    Ext["Keycloak-Extension<br/>(WebToolRenderer je Tool)"]
    Browser <--> Ext
  end
  App -- "DPoP-Proof pro Request" --> O["Orchestrator<br/>next / stepData / Journey"]
  Ext -- "server-zu-server, signierter Peer-Auth-Proof" --> O
```

Der Unterschied liegt allein darin, *wer rendert* und *wie der Request abgesichert ist*:
Im App-Kanal spricht die App direkt mit dem Orchestrator und weist sich per DPoP-Proof
aus; im Web-Kanal läuft der Browser komplett gegen Keycloaks eigenen Login (Redirect,
`loa1`/`loa2` als `acr_values`) — erst Keycloaks serverseitige Extension ruft den
Orchestrator auf, mit einem eigenen signierten Server-zu-Server-Nachweis statt eines
DPoP-Proofs, und rendert jeden Tool-Schritt in Keycloaks eigenem Theme statt einer
nativen UI-Komponente. Der Browser selbst bekommt den Orchestrator nie zu Gesicht.

Das gilt auch für die Tokens am Ende der Journey: der App-Kanal bekommt sein
`AccessToken` (und ein nie das Backend verlassendes `RefreshToken`) vom Orchestrator
selbst ausgestellt — Mock-JWT oder echtes Keycloak-Token, je nach Profil. Der Web-Kanal
bekommt seins direkt aus Keycloaks eigenem Token-Endpoint; der Orchestrator sieht davon
serverseitig nur das, was die Extension ihm für den jeweiligen Tool-Schritt meldet, nie
das Token selbst.

### Channel, Journey, Tool

```mermaid
flowchart TD
  C["Channel<br/><em>APP oder KEYCLOAK</em>"] --> J["Journey<br/><em>ein Ziel, z. B. Anmelden</em>"]
  J --> T["Tool<br/><em>Verfahren, das gerade dran ist, z. B. auth-sms</em>"]
```

Ineinander geschachtelt, keine Kette von Vorher/Nachher — genau das bildet `next` in
jeder `ChannelResponse` ab. Journeys sind nach ihrem Ziel benannt (`LOGIN`, `STEP_UP`,
`MANAGE_AUTH_METHODS`, `DELETE_ACCOUNT`, dazu Web-spezifisch `KC_SELECT_METHOD` und
`CONFIRM_PEER_LOGIN` für die kanalübergreifende QR-Anmeldung), Tools nach Verfahren und
Zweck (`enroll-sms` richtet ein, `auth-sms` benutzt ein bereits eingerichtetes).

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

## Nutzen für dich als Backend-Entwickler

- **Dein Modul bleibt dein Modul** — ein Tool-Modul wie `auth_sms` hat sein eigenes Schema
  (`ToolDB`), auf das nichts außerhalb zugreift. Du kannst darin fachliche Logik ändern, ohne
  Journey oder andere Module überhaupt lesen zu müssen.
- **Der Vertrag ist klein und stabil** — dein `ToolHandler` liefert nur einen `ToolOutcome`;
  was das für Journey/Zustand/nächsten Schritt bedeutet, entscheidet ausschließlich die
  `IntentStrategy`. Du musst beim Schreiben eines Tools nie die volle Zustandsmaschine im Kopf
  haben.
- **App- und Web-Kanal sind für dich identisch** — Journey, Tool und `next` laufen für beide
  Fassaden gleich; du schreibst keine kanalspezifischen Sonderfälle in dein Modul.
- **Der Tokenfluss ist kein Baustellen-Code** — Standard-OIDC gegen Keycloak, serverseitig
  einmal implementiert; dein Modul liefert nur das Ergebnis eines Verfahrens, nie ein Token
  selbst.

---

Vollständige Zustandsdiagramme je Intent, Sub-Journey-Mechanik, Versuchsbudget, MFA-Regel:
[../04-orchestrierung.md](../04-orchestrierung.md). Tool-Katalog und `ToolOutcome`-Vertrag im
Detail: [../03-tool-architektur.md](../03-tool-architektur.md). Modulliste und
-abhängigkeiten: [../08-projektrahmen.md](../08-projektrahmen.md).
