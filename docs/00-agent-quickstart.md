# Agent Quickstart (Token-sparend)

Ziel: In <2 Minuten genug Kontext, um sicher zu implementieren, ohne den kompletten `docs/`-Baum zu laden.

## 1) Was ist das Projekt?

- `dpop-demo`: Spring Boot Modulith (Kotlin) + React/TypeScript.
- Zweck: DPoP-gesicherter Registrierungs-/Login-Flow gegen Keycloak-konformes Zielbild.
- Architekturprinzip: Orchestrator steuert Journeys; Methodenmodule sind nur über SPI gekoppelt.

## 2) Kernbegriffe (minimal)

- **ChannelSession**: langlebiger Kanal-Kontext (App/Web)
- **AuthIntent**: Nutzerziel (z. B. Register, Step-up)
- **AuthJourney**: laufender Ablauf pro Intent
- **ToolSession**: konkrete Instanz eines Verfahrensschritts
- **AuthContext**: IAM/Token-Kontext inkl. ACR/AMR

## 3) Wo finde ich welches Detail?

| Frage | Datei |
|---|---|
| Modulgrenzen, Stack, Versionen | `08-projektrahmen.md` |
| Orchestrator-Entscheidungen/Policy | `04-orchestrierung.md` |
| API-Verträge und Routen | `05-api.md` |
| Schrittfolgen der Verfahren | `06-ablaeufe.md` |
| DPoP-Validierung und Binding | `09-dpop.md` |
| Frontend-Verhalten/Routing | `10-frontend.md` |

## 4) Arbeitsregeln für Agents

- Doku beschreibt das Zielbild; bei Abweichung hat Doku Vorrang.
- Änderungen immer im passenden Dokument ergänzen, nicht in Sammelnotizen.
- Nur benötigte Kapitel öffnen; `docs/README.md` ist Navigation, kein Pflicht-Read.
