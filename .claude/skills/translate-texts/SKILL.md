---
name: translate-texts
description: Schreibt die ausgelieferten Texte (de, en, …) aus den Vorlagen im Code - Backend Text("…"), Frontend t("…")/<Tx text="…">, Keycloak-Extension - neue, geänderte und verwaiste Einträge je Bundle. Aufruf `/translate-texts <lang>` oder ohne Argument für alle Sprachen. Nutzen, wenn TextTranslationsTest rot ist oder Nutzertexte im Code geändert wurden.
---

# Texte übersetzen (docs/adr/ADR-033)

Nutzertexte stehen im Code als deutsche **Entwicklerformulierung**: im Backend `Text("…")`, im React-Frontend
`t("…")` bzw. `<Tx text="…">`. Ausgeliefert wird nie diese Vorlage, sondern pro Sprache eine redigierte Fassung in
`src/main/resources/texts/<bundle>/texts_<lang>.properties` – **auch für Deutsch**. Diese Dateien schreibst du.
Backend- und Frontend-Texte eines Bundles stehen in derselben Datei (die App lädt ein Bundle).

## Ablauf

1. `./gradlew exportTexts` – schreibt den Quellkatalog je Bundle nach `build/texts/<bundle>/texts_source.properties`
   (`id=Vorlage`, darüber die Fundstellen: `# Klasse.methode:Zeile` im Backend, `# frontend/src/…:Zeile` im Frontend). Bundles: `app` (die Anwendung), `nect`, `kobil`, `personenverzeichnis`
   (simulierte Fremdsysteme mit eigenem Ton), `keycloak` (eigene Texte der Login-Seite).
2. Für jede Zielsprache `<lang>` (Argument, sonst alle Dateien in `prompts/`) und jedes Bundle:
   - Lies `prompts/<lang>.md` in diesem Skill-Verzeichnis – das ist die verbindliche Anweisung für Ton, Anrede und Begriffe.
   - Vergleiche Quellkatalog und bestehende Zieldatei:
     - **neu**: ID im Katalog, nicht in der Zieldatei → formulieren.
     - **geändert**: Die Vorlage hat sich geändert ⇒ sie hat eine neue ID (Kurzname plus Hash der Vorlage, z. B.
       `journey-trace-laden-fehlgeschlagen-cf9829`); die alte ID ist verwaist.
     - **verwaist**: ID in der Zieldatei, nicht mehr im Katalog → entfernen.
     - Bestehende, weiter gültige Einträge **nicht** neu formulieren (stabile Texte, kleiner Diff) – außer der
       Prompt hat sich geändert und der Nutzer verlangt eine Überarbeitung.
   - Formuliere aus der **Vorlage** (nicht aus einer anderen Sprache). Die Fundstellen im Quellkatalog sagen, wo
     und in welcher Situation der Text erscheint – bei Unklarheit die Stelle im Code lesen. Frontend-Texte sind
     oft Beschriftungen (Button, Label, Überschrift, Platzhalter): Art aus der Fundstelle ableiten.
   - **Gleiche ID, gleicher Wortlaut:** Dieselbe Vorlage hat überall denselben Hash – ein Tool-Name wie „SMS“
     oder „Passwort“ steht in `app` (Frontend) und `keycloak` (Login-Seite) mit derselben ID. Eine ID, die schon
     in einem anderen Bundle derselben Sprache formuliert ist, bekommt **denselben** Wortlaut (erst dort
     nachsehen, dann übernehmen). `TextTranslationsTest` prüft das.
   - **Bundle `keycloak`** (Login-Seite): Zieldatei ist
     `keycloak-extension/src/main/resources/theme/orchestrator/login/messages/messages_<lang>.properties`
     (gleiches Format, Kopfzeile `# keycloak, <lang> - …`). Prüfung: `./gradlew :keycloak-extension:test`.
3. Datei schreiben (UTF-8, nach ID sortiert), Format je Eintrag:
   ```
   # Quelle: <Vorlage, einzeilig>
   <id>=<Text>
   ```
   Kopfzeile: `# <bundle>, <lang> - geschrieben von /translate-texts nach prompts/<lang>.md`.
   Escaping wie java.util.Properties: `\` als `\\`, Zeilenumbruch als `\n`, führendes Leerzeichen als `\ `.
4. **Platzhalter** `{name}` exakt übernehmen – gleiche Menge wie in der Vorlage, Namen nie übersetzen. Ihre Werte:
   Methodennamen/IDs/Zahlen erscheinen roh; Platzhalter, die selbst Texte sind (z. B. `{grund}`, `{faktoren}`),
   werden vom Client in derselben Sprache eingesetzt – Satzbau darauf abstimmen. Bei `<Tx>` steht ein Platzhalter
   für Markup (z. B. `{code}` = hervorgehobener Wert, `{modus}` = fett gesetztes Label): Reihenfolge darf sich
   ändern, der Platzhalter bleibt.
5. `./gradlew :test --tests 'com.example.dpop.texts.TextTranslationsTest' -PstrictTexts` muss grün sein (ohne
   `-PstrictTexts` meldet der Test fehlende Übersetzungen nur als Warnung).
6. Kurz berichten: je Sprache/Bundle Anzahl neu/entfernt, auffällige Umformulierungen.
