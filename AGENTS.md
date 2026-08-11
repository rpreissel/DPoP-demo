# Projektspezifische Agent-Anweisungen

## Projektkontext (für neue Sessions)

- **Projekt**: `dpop-demo` – Spring Boot Modulith-Applikation mit React/TypeScript-Frontend.
- **Arbeitsverzeichnis**: `/Users/rene/Develop/opencode/DPoP-demo`
- **Details**: Siehe `Anforderungen.md` für vollständige Anforderungen, Architektur und verwendete Versionen.

## Anforderungsdokumentation

- Aus allen Benutzer-Prompts abgeleitete Anforderungen werden zentral in `Anforderungen.md` dokumentiert.
- Die Dokumentation folgt einer Arc42-/C4-nahen Struktur.
- Prompt-Wortlaute werden nicht wörtlich zitiert, sondern in strukturierte Anforderungen überführt.
- Bei Änderungen oder neuen Anforderungen ist `Anforderungen.md` entsprechend zu aktualisieren.

## Konventionen

- Keine globalen Installationen ohne explizite Genehmigung.
- Keine Commit-/Push-Operationen ohne explizite Anweisung.
- Für laufende Prozesse (z. B. `bootRun`) lieber Integrationstests oder Hintergrundausführung verwenden, statt blockierende Befehle in Timeouts laufen zu lassen.
