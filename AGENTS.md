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

## Git-Verhalten

- Repository ist initialisiert; Commit-Hash-Verlauf ist vorhanden.
- Nach wichtigen Änderungen (Feature, Bugfix, Build-Fix, Architekturänderung) einen Commit erstellen.
- Commit-Nachrichten kurz und auf Deutsch oder Englisch im Präsens, z. B. `feat: Person-Entität mit Adressattributen hinzufügen` oder `fix: Flyway-Initialisierung korrigieren`.
- Keine `git push` oder GitHub-Operationen ohne explizite Anweisung.
- Vor einem Commit `git status`, `git diff --staged` prüfen und nur beabsichtigte Dateien stagen.
- `.gitignore` beachtet: Gradle-Build-Output, IDE-Dateien, H2-Datenbankdateien, `node_modules`, generiertes Frontend-Output unter `src/main/resources/static/` sowie lokale `.opencode/`-Metadaten.

## Konventionen

- Keine globalen Installationen ohne explizite Genehmigung.
- Keine Push-/Remote-Operationen ohne explizite Anweisung.
- Für laufende Prozesse (z. B. `bootRun`) lieber Integrationstests oder Hintergrundausführung verwenden, statt blockierende Befehle in Timeouts laufen zu lassen.
