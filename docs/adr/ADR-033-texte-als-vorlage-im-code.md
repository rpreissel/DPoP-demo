# ADR-33: Texte als deutsche Vorlage im Code, ausgeliefert als Referenz, formuliert per Prompt

**Entscheidung** (**umgesetzt**): Alle Texte des Backends, die Nutzer sehen, bleiben im Code, als
deutsche **Formulierung für Entwickler** in `Text("…")` mit Platzhaltern der Form `{name}`. Das Backend
liefert nie den Wortlaut aus, sondern eine Referenz `{ key, args, texts }` (`key` = die ersten 12 Hex-Zeichen des SHA-256 der
Vorlage). Jede Sprache – **auch Deutsch** – ist eine redigierte Fassung in
`src/main/resources/texts/<bundle>/texts_<lang>.properties`, geschrieben vom Claude-Code-Skill
`/translate-texts` nach einem Prompt pro Sprache (`.claude/skills/translate-texts/prompts/<lang>.md`).
Clients holen das Bundle beim Start per `GET …/texts/{lang}` mit ETag und setzen den Wortlaut selbst
ein.

## Warum keine Schlüsselkataloge

Ausgelagerte Texte mit Enums als Schlüsseln hätten jede der rund 250 Stellen unlesbar gemacht
(`AuthSmsText.TAN_INVALID` statt des Satzes) und bei jeder Änderung Arbeit an zwei Stellen verlangt. Die Vorlage im
Code ist der Schlüssel: Ändert sich der Satz, ändert sich die ID, und die Übersetzungen fallen als
fehlend auf.

## Warum auch Deutsch „übersetzt“ wird

Die Vorlage ist für Entwickler geschrieben (Umschrift, Jargon wie „Enrollment“, „loa2“, teils
Englisch). Was Versicherte lesen, formuliert `prompts/de.md` daraus. So bleiben Fragen der Formulierung aus dem
Code heraus, und Ton und Begriffe sind an einer Stelle festgelegt.

## Einsammeln: aus den kompilierten Klassen, nicht aus dem Quelltext

`TextCatalog` (Testcode) liest mit ASM jeden Aufruf von `Text.<init>` und verfolgt über eine
Datenflussanalyse, welcher Wert als Vorlage ankommt. Der Compiler hat die Literale bereits gelesen
und `"a " + "b"` zu einer Konstanten zusammengefasst; einen Umweg über eine lokale Variable (Kotlin legt die
Vorlage dort ab, wenn ein Argument ein Inline-Lambda enthält) verfolgt die Analyse mit. Eine Vorlage,
die keine Konstante ist, lässt `TextCatalogTest` mit Klasse, Methode und Zeile scheitern Die Regel „nur
Literale, Parameter über `{name}`“ wird damit geprüft und ist nicht nur eine Vereinbarung.

Eine Prüfung zur Laufzeit ergänzt das: Im Test prüft `Text.toRef` jede ausgelieferte ID gegen den
Katalog. Nur zu sammeln, was die Tests erzeugen, hätte seltene Fehlertexte stillschweigend
ausgelassen.

## Übersetzen per Claude Code, nicht im Build

Der Build braucht kein Netz und liefert immer dasselbe Ergebnis: `TextTranslationsTest` prüft je
Bundle und Sprache, ob alles übersetzt ist, ob nichts Überzähliges vorhanden ist und ob die
Platzhalter übereinstimmen. Schlägt er fehl, nennt er `/translate-texts <lang>`. Der Skill bearbeitet
nur die neuen, geänderten und verwaisten Einträge. Die Dateien werden eingecheckt und im Diff geprüft
(`# Quelle:` über jedem Eintrag).

## Bundles und Endpunkte

- `app` für diese Anwendung: `GET /orchestrator/api/v1/texts/{lang}` (ohne DPoP).
- Die simulierten Fremdsysteme bringen eigene Texte mit, wie ein echter Dienst:
  `/mock-nect/texts/{lang}`, `/mock-kobil/texts/{lang}`, `/mock-personenverzeichnis/texts/{lang}`. Welches
  Bundle gilt, richtet sich nach dem obersten Paket der Klasse, in der die Vorlage steht.
- Antwort: Map `id → Wortlaut`, starkes ETag über den Inhalt, `Cache-Control: no-cache`,
  `Content-Language`; `If-None-Match` mit passendem ETag → 304. Die Frage „Gibt es Neues?“ und
  das Herunterladen sind so eine einzige Anfrage. Unbekannte Sprache → Deutsch, Region wird ignoriert (`en-GB` → `en`).

## Folgen

- Vertrag: Die Freitext-Felder (`ErrorResponse.message` → `text`, `FailedAttemptStep.error`,
  `MessageStep.message`, `SelectMethodStep.title/description`, `Prompt.*`, `JourneyDebugStep.note`)
  tragen `TextRef`.
- Modul `texts` (Bibliothek, `allowedDependencies = []`). Alle Module mit Nutzertexten deklarieren
  diese Abhängigkeit, auch die simulierten Fremdsysteme; bei ihnen ist es die einzige.
- Kein Text im gespeicherten Zustand der Journey: Wo ein Aufrufer die Formulierung wählt, speichert
  der Zustand eine Variante (`ReIdentifyState.Wording`, `StepUpState.Reason`), und der Getter baut
  daraus den `Text`.
- Fremdsysteme melden Gründe als Code (`NectFailure`), der Verbraucher formuliert selbst.
- Ein Verb oder Satzteil ist nie ein Argument (etwa `{write}` = „gesetzt“), denn jede Sprache beugt
  anders. Stattdessen gibt es zwei Vorlagen.

## Erweiterung: Texte der Clients (Frontend, Keycloak-Login)

Dasselbe Prinzip, dieselben Prompts, dieselbe Berechnung der ID. Je Quelle unterscheiden sich nur die
Markierung, das Werkzeug zum Einsammeln und der Ort des Wortlauts:

- **React-Frontend**
  - *Markierung:* `t("…")`, `<Tx text="… {x} …" x={<strong>…</strong>} />`
  - *Eingesammelt von:* `frontend/scripts/text-catalog.mjs` (Syntaxbaum von oxc, nur Literale erlaubt)
  - *Wortlaut liegt in:* den bestehenden Bundles: `entries/nect` → `nect`, `entries/personenverzeichnis` → `personenverzeichnis`, `kobilSdk.ts` → `kobil`, sonst `app`
- **Keycloak-Java**
  - *Markierung:* `KcText.t("…")`, `KcTexts.of(session, "…")`
  - *Eingesammelt von:* `KcTextCatalog` (ASM)
  - *Wortlaut liegt in:* `theme/orchestrator/login/messages/messages_<lang>.properties`
- **Keycloak-Templates**
  - *Markierung:* `${t.of("…")}`, `${t.of("… {x}", {"x": wert})}`
  - *Eingesammelt von:* `KcTextCatalog` (strenger eigener Leser, weil FreeMarker keinen öffentlichen Syntaxbaum hat)
  - *Wortlaut liegt in:* ebenda

- **Frontend über den Orchestrator:** Die Texte des Frontends liegen im selben Bundle wie die Texte des
  Backends für diesen Client und kommen mit ihm per ETag, in einer einzigen Anfrage. Sie lassen sich
  ändern, ohne eine neue Version der App auszuliefern. Neue Vorlagen kommen natürlich erst mit neuem
  Code.
- **Ohne Wortlaut zeigt das Frontend die Vorlage** (anders als bei Referenzen aus dem Backend kennt es
  sie). Unit-Tests ohne geladenes Bundle prüfen deshalb weiterhin gegen die Vorlagen.
- **Seiten laden die Texte vor dem Code der App** (`main.tsx` lädt die App erst nach `loadAllTexts`),
  damit `t()` auch auf der obersten Ebene eines Moduls (Beschriftungen der Tools) den Wortlaut findet.
- **Keycloak wählt die Sprache selbst** (Realm de/en, `theme.properties` `locales=de,en`); `KcTexts` holt den
  Wortlaut aus den Messages des Themes und setzt Platzhalter selbst ein, ohne MessageFormat.
- **Gleiche Vorlage, gleicher Hash, gleicher Wortlaut:** Ein Tool heißt in App und Login-Seite wortgleich
  (`KcTextCatalogTest.toolNamesAreTheAppsOwn`), und `TextTranslationsTest` prüft, dass eine ID in allen
  Bundles einer Sprache gleich formuliert ist.
- Nicht markiert sind Oberflächen für Entwickler (Debug-Seitenleiste, Journey-Trace, `logEvent`), Code,
  Befehle, Pfade und Demo-Daten. Sie stehen als Werte von Platzhaltern im Satz.
