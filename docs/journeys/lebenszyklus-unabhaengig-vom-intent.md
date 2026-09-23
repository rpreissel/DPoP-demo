> Eine Journey aus dem Katalog. Die gemeinsame Lesehilfe zu den Diagrammen steht in
> [../04-orchestrierung.md](../04-orchestrierung.md), Abschnitt 3.

# Lebenszyklus, unabhängig vom Intent

Die intent-eigenen Zustände beschreiben den Weg; `JourneyLifecycle`, ob die Journey noch läuft.

```mermaid
stateDiagram-v2
  [*] --> STARTED
  STARTED --> SUSPENDED: wartet auf eine Sub-Journey
  SUSPENDED --> STARTED: Sub-Journey abgeschlossen
  STARTED --> SUCCEEDED: Zielzustand erreicht
  STARTED --> FAILED: Versuchsbudget erschöpft
  STARTED --> CANCELLED: Nutzer bricht ab
  STARTED --> EXPIRED: ttl erreicht
  SUCCEEDED --> CONSUMED: Ergebnis auf Kanal und AuthContext angewandt
  CANCELLED --> [*]
  CONSUMED --> [*]
  EXPIRED --> [*]
  FAILED --> [*]
```

---
