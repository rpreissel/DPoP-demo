> Eine Journey aus dem Katalog. Die gemeinsame Lesehilfe zu den Diagrammen steht in
> [../04-orchestrierung.md](../04-orchestrierung.md), Abschnitt 3.

# Lebenszyklus, unabhängig vom Intent

Die intent-eigenen Zustände beschreiben den Weg; `JourneyLifecycle`, ob die Journey noch läuft.

```mermaid
stateDiagram-v2
  [*] --> STARTED
  STARTED --> SUSPENDED: wartet auf eine Sub-Journey
  SUSPENDED --> STARTED: Sub-Journey abgeschlossen
  STARTED --> CONSUMED: Ziel erreicht oder Logout, Ergebnis auf den Kanal angewandt
  STARTED --> FAILED: Versuchsbudget erschöpft oder Abort (410)
  STARTED --> CANCELLED: Nutzer bricht ab
  CANCELLED --> [*]
  CONSUMED --> [*]
  FAILED --> [*]
```

`SUCCEEDED` und `EXPIRED` stehen noch im Enum, werden aber nie gesetzt: Eine erfolgreiche Journey geht
direkt auf `CONSUMED` (`AuthJourney.consume()`), und der Ablauf wird nur gelesen
(`AuthJourney.isExpired` über `expiresAt`) — eine abgelaufene Journey gilt als nicht mehr aktiv,
ohne dass ihr Zustand umgeschrieben wird.

---
