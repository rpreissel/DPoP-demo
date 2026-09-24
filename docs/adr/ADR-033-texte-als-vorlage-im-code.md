# ADR-33: Texte als deutsche Vorlage im Code, ausgeliefert als Referenz, formuliert per Prompt

**Entscheidung** (**umgesetzt**): Alle nutzersichtbaren Backend-Texte bleiben im Code, als deutsche
**Entwicklerformulierung** in `Text("…")` mit `{name}`-Platzhaltern. Das Backend liefert nie Wortlaut
aus, sondern eine Referenz `{ key, args, texts }` (`key` = die ersten 12 Hex-Zeichen des SHA-256 der
Vorlage). Jede Sprache – **auch Deutsch** – ist eine redigierte Fassung in
`src/main/resources/texts/<bundle>/texts_<lang>.properties`, geschrieben vom Claude-Code-Skill
`/translate-texts` nach einem Prompt pro Sprache (`.claude/skills/translate-texts/prompts/<lang>.md`).
Clients holen das Bundle beim Start per `GET …/texts/{lang}` mit ETag und lösen selbst auf.

## Warum keine Schlüsselkataloge

Ausgelagerte Texte mit Schlüssel-Enums hätten jede der rund 250 Stellen unlesbar gemacht
(`AuthSmsText.TAN_INVALID` statt des Satzes) und bei jeder Änderung zwei Orte verlangt. Die Vorlage im
Code ist der Schlüssel: Ändert sich der Satz, ändert sich die ID, und die Übersetzungen fallen als
fehlend auf.

## Warum auch Deutsch „übersetzt“ wird

Die Vorlage ist für Entwickler geschrieben (Umschrift, Jargon wie „Enrollment“, „loa2“, teils
Englisch). Was Versicherte lesen, formuliert `prompts/de.md` daraus. So bleibt der Code frei von
Redaktionsfragen, und Ton und Begriffe sind an einer Stelle festgelegt.

## Einsammeln: aus den kompilierten Klassen, nicht aus dem Quelltext

`TextCatalog` (Testcode) liest mit ASM jeden Aufruf von `Text.<init>` und verfolgt per
Datenflussanalyse, welcher Wert als Vorlage ankommt. Der Compiler hat die Literale bereits geparst
und `"a " + "b"` zu einer Konstanten gefaltet; einen Umweg über eine lokale Variable (Kotlin legt die
Vorlage dort ab, wenn ein Argument ein Inline-Lambda enthält) verfolgt die Analyse mit. Eine Vorlage,
die keine Konstante ist, lässt `TextCatalogTest` mit Klasse, Methode und Zeile scheitern – „nur
Literale, Parameter über `{name}`“ ist damit geprüft, nicht Konvention.

Eine Laufzeitwache ergänzt das: Im Test prüft `Text.toRef` jede ausgelieferte ID gegen den Katalog.
Nur mitzuschreiben, was die Testsuite erzeugt, hätte seltene Fehlertexte still ausgelassen.

## Übersetzen per Claude Code, nicht im Build

Der Build bleibt offline und reproduzierbar: `TextTranslationsTest` prüft je Bundle und Sprache
Vollständigkeit, Überhang und gleiche Platzhalter und nennt bei Rot `/translate-texts <lang>`. Der
Skill arbeitet nur die neuen, geänderten und verwaisten Einträge ab; die Dateien werden committet
und im Diff reviewt (`# Quelle:` über jedem Eintrag).

## Bundles und Endpunkte

- `app` für diese Anwendung: `GET /orchestrator/api/v1/texts/{lang}` (ohne DPoP).
- Die simulierten Fremdsysteme bringen eigene Texte mit, wie ein echter Dienst:
  `/mock-nect/texts/{lang}`, `/mock-kobil/texts/{lang}`, `/mock-stammdaten/texts/{lang}`. Das Bundle
  folgt dem Top-Level-Package der Klasse, in der die Vorlage steht.
- Antwort: Map `id → Wortlaut`, starkes ETag über den Inhalt, `Cache-Control: no-cache`,
  `Content-Language`; `If-None-Match` mit passendem ETag → 304. „Gibt es Neues?“ und der Download
  sind so ein Request. Unbekannte Sprache → Deutsch, Region wird ignoriert (`en-GB` → `en`).

## Folgen

- Vertrag: Die Freitext-Felder (`ErrorResponse.message` → `text`, `FailedAttemptStep.error`,
  `MessageStep.message`, `SelectMethodStep.title/description`, `Prompt.*`, `JourneyDebugStep.note`)
  tragen `TextRef`.
- Modul `texts` (Bibliothek, `allowedDependencies = []`); alle Module mit Nutzertexten deklarieren die
  Kante, auch die Mocks – ihre einzige.
- Kein Text im gespeicherten Journey-State: Wo ein Aufrufer die Formulierung wählt, speichert der
  State eine Variante (`ReIdentifyState.Wording`, `StepUpState.Reason`), der Getter baut den `Text`.
- Fremdsysteme melden Gründe als Code (`NectFailure`), der Verbraucher formuliert selbst.
- Ein Verb oder Satzteil ist nie ein Argument (`{write}` = „gesetzt“): jede Sprache beugt selbst,
  also zwei Vorlagen.
- Eigene Texte des React-Frontends und der Keycloak-Themes sind nicht Teil dieser Entscheidung.
