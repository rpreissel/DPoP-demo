---
name: translate-texts
description: Schreibt die ausgelieferten Texte (de, en, …) aus den Text("…")-Vorlagen im Backend-Code - neue, geänderte und verwaiste Einträge je Bundle. Aufruf `/translate-texts <lang>` oder ohne Argument für alle Sprachen. Nutzen, wenn TextTranslationsTest rot ist oder Nutzertexte im Code geändert wurden.
---

# Texte übersetzen (docs/adr/ADR-033)

Die Backend-Texte stehen im Code als deutsche **Entwicklerformulierung** (`Text("…")`). Ausgeliefert wird
nie diese Vorlage, sondern pro Sprache eine redigierte Fassung in
`src/main/resources/texts/<bundle>/texts_<lang>.properties` – **auch für Deutsch**. Diese Dateien schreibst du.

## Ablauf

1. `./gradlew exportTexts` – schreibt den Quellkatalog je Bundle nach `build/texts/<bundle>/texts_source.properties`
   (`id=Vorlage`, darüber `# Klasse.methode:Zeile`). Bundles: `app` (die Anwendung), `nect`, `kobil`, `register`
   (simulierte Fremdsysteme mit eigenem Ton).
2. Für jede Zielsprache `<lang>` (Argument, sonst alle Dateien in `prompts/`) und jedes Bundle:
   - Lies `prompts/<lang>.md` in diesem Skill-Verzeichnis – das ist die verbindliche Anweisung für Ton, Anrede und Begriffe.
   - Vergleiche Quellkatalog und bestehende Zieldatei:
     - **neu**: ID im Katalog, nicht in der Zieldatei → formulieren.
     - **geändert**: Die Vorlage hat sich geändert ⇒ sie hat eine neue ID (Hash der Vorlage); die alte ID ist verwaist.
     - **verwaist**: ID in der Zieldatei, nicht mehr im Katalog → entfernen.
     - Bestehende, weiter gültige Einträge **nicht** neu formulieren (stabile Texte, kleiner Diff) – außer der
       Prompt hat sich geändert und der Nutzer verlangt eine Überarbeitung.
   - Formuliere aus der **Vorlage** (nicht aus einer anderen Sprache). Der Kommentar `# Klasse.methode:Zeile` im
     Quellkatalog sagt, wo und in welcher Situation der Text erscheint – bei Unklarheit die Stelle im Code lesen.
3. Datei schreiben (UTF-8, nach ID sortiert), Format je Eintrag:
   ```
   # Quelle: <Vorlage, einzeilig>
   <id>=<Text>
   ```
   Kopfzeile: `# <bundle>, <lang> - geschrieben von /translate-texts nach prompts/<lang>.md`.
   Escaping wie java.util.Properties: `\` als `\\`, Zeilenumbruch als `\n`, führendes Leerzeichen als `\ `.
4. **Platzhalter** `{name}` exakt übernehmen – gleiche Menge wie in der Vorlage, Namen nie übersetzen. Ihre Werte:
   Methodennamen/IDs/Zahlen erscheinen roh; Platzhalter, die selbst Texte sind (z. B. `{grund}`, `{faktoren}`),
   werden vom Client in derselben Sprache eingesetzt – Satzbau darauf abstimmen.
5. `./gradlew :test --tests 'com.example.dpop.texts.TextTranslationsTest'` muss grün sein.
6. Kurz berichten: je Sprache/Bundle Anzahl neu/entfernt, auffällige Umformulierungen.
