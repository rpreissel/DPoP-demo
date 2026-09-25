# Schnelleinstieg für Agents (Token-sparend)

Ziel: In unter zwei Minuten genug Überblick, um sicher zu implementieren, ohne den ganzen
`docs/`-Ordner zu laden.

## 1) Was ist das Projekt?

- `dpop-demo`: Spring Boot Modulith (Kotlin) mit React/TypeScript.
- Zweck: ein mit DPoP gesicherter Ablauf für Registrierung und Login, ausgerichtet auf ein
  Keycloak-konformes Zielbild.
- Grundprinzip: Der Orchestrator steuert die Journeys; die Methodenmodule hängen nur über
  Schnittstellen (SPI) an ihm.

## 2) Kernbegriffe (das Nötigste)

- **ChannelSession**: der Kontext eines Kanals (App oder Web). Sie überdauert einzelne Verfahren,
  ist aber bewusst kurzlebig (ADR-3); welches Gerät zu welchem Konto gehört, steht dauerhaft in
  `DeviceAccountLink`.
- **AuthIntent**: das Ziel des Nutzers, z. B. Registrieren oder Step-up.
- **AuthJourney**: ein laufender Ablauf zu einem Intent.
- **ToolSession**: ein einzelner Durchlauf eines Verfahrensschritts.
- **AuthEvidence** / **AuthContext**: die Nachweise einer Sitzung (daraus folgen ACR und AMR)
  bzw. die Tokens des App-Kanals.

## 3) Wo finde ich welches Detail?

| Frage | Datei |
|---|---|
| Modulgrenzen, Technik, Versionen | `08-projektrahmen.md` |
| Entscheidungen und Regeln des Orchestrators | `04-orchestrierung.md` |
| Ein einzelner Ablauf im Detail | `journeys/<intent>.md` (nicht das ganze Kapitel 04 laden) |
| Warum etwas so ist | `12-entscheidungen.md` (Index) -> `adr/ADR-NNN-*.md` |
| API-Verträge und Routen | `05-api.md` |
| Schrittfolgen der Verfahren | `06-ablaeufe.md` |
| DPoP-Prüfung und Geräteverknüpfung | `09-dpop.md` |
| Verhalten des Frontends, Routing | `10-frontend.md` |

## 4) Arbeitsregeln für Agents

- Die Doku beschreibt das Zielbild; weicht der Code ab, hat die Doku Vorrang.
- Änderungen immer im passenden Dokument ergänzen, nicht in Sammelnotizen.
- Nur die benötigten Kapitel öffnen; `docs/README.md` dient der Orientierung und muss nicht
  gelesen werden.
