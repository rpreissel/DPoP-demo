---
applyTo: "**"
---
# Mermaid Instructions (Kurzfassung)

Nutze diese Regeln nur bei Diagramm-Anfragen.

## Pflichtablauf

1. Diagramm als `.mmd` Datei schreiben/aktualisieren.
2. Vor Ausgabe immer `mermaid-diagram-validator` aufrufen.
3. Danach immer `mermaid-diagram-preview` aufrufen.
4. Bei unbekanntem Diagrammtyp zuerst `get-syntax-docs-mermaid` verwenden.

## Wichtige Regeln

- Keine unvalidierte Mermaid-Syntax an Nutzer zurückgeben.
- Bei `mermaidChart.repairDiagram` vorher auf AI-Credit-Verbrauch hinweisen.
- Von Mermaid Sync verwaltete Diagramme nicht manuell überschreiben.

## Referenz

- Vollständige Befehlsliste: https://marketplace.visualstudio.com/items?itemName=MermaidChart.vscode-mermaid-chart
