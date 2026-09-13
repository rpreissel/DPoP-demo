# Live-Demos: Ablauf, Fokus, Fallback

Diese Datei beschreibt die Demo-Blöcke so, dass sie die neue Gesamtstory tragen, statt nur
einzelne Screens zu zeigen. Pro Demo gilt: nur so viel Oberfläche wie nötig, so viel
Nutzenargumentation wie möglich.

## Grundregel für alle Demo-Blöcke

- Die Demo illustriert das Zielbild, sie ist nicht das Zielbild.
- Nicht jeden Klick erklären, sondern die fachliche Aussage dahinter.
- Immer zuerst sagen, welches Problem der Abschnitt löst.
- Nach jedem Block beantworten: Warum ist das für zukünftige Entwicklung wertvoll?

## Hauptstory für die 90 Minuten

- Hauptfigur bleibt Nina Hartmann aus `07-demo-story-roter-faden.md`.
- Demo 1 zeigt den Hauptpfad: `FAST_ACCESS` -> `REGISTER` -> normaler Einstieg.
- Demo 2 zeigt denselben fachlichen Rahmen unter höherem Schutzbedarf: `STEP_UP`, `RE_IDENTIFY`, `MANAGE_AUTH_METHODS`.
- Demo 3 zeigt die Erweiterung auf den Web-Kanal: `KC_SELECT_METHOD`, QR als neues Feature und `CONFIRM_PEER_LOGIN`.
- `LOOKUP_LOGIN`, `DELETE_ACCOUNT` und `LOGOUT` sind Reserve- oder Schlussakzente, keine zweite Hauptgeschichte.

## Welche Journeys du wo unterbringst

| Demo-Block | Primäre Journeys | Optionale Ergänzungen |
|---|---|---|
| Demo 1 | `FAST_ACCESS`, `REGISTER` | Registrierungsvarianten, Geräte-Umbindung |
| Demo 2 | `STEP_UP`, `RE_IDENTIFY`, `MANAGE_AUTH_METHODS` | bewusstes Deaktivieren von Tools |
| Demo 3 | `KC_SELECT_METHOD`, `CONFIRM_PEER_LOGIN` | `LOOKUP_LOGIN`, QR-Opt-in |
| Reserve / Schluss | `DELETE_ACCOUNT`, `LOGOUT` | starker Governance-Abschluss |

## Demo 1 (15 min): Eine durchgängige Nutzerreise als Beweis für Steuerbarkeit

## Ziel

- Eine einzige Reise genügt, um das Prinzip verständlich zu machen.
- Sichtbar machen, dass der Ablauf fachlich lesbar und technisch kontrollierbar bleibt.
- Vertrauen erzeugen, dass Komplexität nicht in unübersichtliche Speziallogik entgleist.
- Früh zeigen, dass Registrierung kein Sonderprojekt ist, sondern Teil desselben Modells.

## Kernaussage

"Wir können mehrstufige Nutzerreisen so gestalten, dass sie fachlich nachvollziehbar und dennoch technisch beherrschbar bleiben."

## Story-Pfad

- Nina startet auf einem neuen Smartphone.
- `FAST_ACCESS` prüft, ob ein einfacher Wiedereinstieg möglich ist.
- Wenn nicht, führt derselbe Rahmen kontrolliert in `REGISTER`.
- Du zeigst einen Identifizierungsweg und erwähnst den zweiten als echte Variantenfähigkeit.
- Nach erfolgreicher Einrichtung landet Nina im normalen Nutzungskontext.
- Für die Krankenkasse rahmst du das als Wiedereinstieg in App-Services wie Postfach, Dokumente oder Anliegen.

## Worauf du beim Zeigen den Fokus legst

- Nicht auf einzelne Eingabefelder, sondern auf die Prozesslogik.
- Nicht auf technische Details, sondern auf Klarheit des nächsten sinnvollen Schritts.
- Nicht auf Spezialfälle, sondern auf die Beherrschbarkeit von Varianten.
- Wenn möglich kurz zeigen, dass ein anderer Registrierungsweg fachlich denselben Zweck erfüllt.

## Sprechpunkt (fachlich)

- "Wir zeigen eine steuerbare Nutzerreise statt isolierter Einzelmasken."

## Überzeugende Vorteile

- Fachliche Regeln sind im Ablauf explizit sichtbar und gut diskutierbar; die UI bleibt schlank, weil sie dem Prozess folgt statt ihn selbst zu erfinden.
- Varianten wie `ident-fsc`/`ident-eid` oder ein Zweitaccount-/Geräte-Umbindungsfall lassen sich als Produktentscheidung einbauen, nicht als Systembruch.

## Was du dazu sagen kannst

- "Wenn wir heute einen Sonderfall ergänzen wollen, müssen wir oft an mehreren Stellen gleichzeitig denken. Hier wird daraus ein klarer Prozessschritt, der auch beim nächsten Änderungswunsch stabil bleibt."
- "Wir können in der Registrierung sogar Varianten zulassen und vergleichen, ohne zwei getrennte Prozesswelten zu bauen."

## Wenn du mehr zeigen willst

- Alternative Identifizierung als Experiment erwähnen: gleicher Zweck, anderer Weg.
- Geräte-Umbindung nur kurz als bewusst modellierten Sonderfall nennen.

## Fallback

- Wenn der Flow stockt: nicht debuggen, sondern mit vorbereitetem Screenshot und der Kernaussage weiterarbeiten.

---

## Demo 2 (10 min): Sicherheitsniveau als fachliche Steuerungsgröße

## Ziel

- Verdeutlichen, dass Sicherheitsanforderungen nicht pauschal, sondern kontextbezogen steuerbar sind.
- Zeigen, dass dieselbe Lösung einfache und anspruchsvollere Vorgänge abdecken kann.
- Fachlichen Nutzen von Step-up und Methodenverwaltung nachvollziehbar machen.
- Zeigen, dass auch knappe oder deaktivierte Tool-Landschaften im Modell beherrschbar bleiben.

## Kernaussage

"Nicht jeder Schritt braucht dieselbe Absicherung. Entscheidend ist, dass wir das gezielt und kontrolliert anheben können."

## Story-Pfad

- Nina möchte eine sensiblere Aktion ausführen.
- Das vorhandene Niveau reicht nicht, daher startet `STEP_UP`.
- Wenn die vorhandenen Verfahren nicht genügen, erwähnst du `RE_IDENTIFY` als kontrollierte Rückfallebene.
- Danach wechselst du in `MANAGE_AUTH_METHODS`: Nina ergänzt ein Verfahren und deaktiviert ein anderes bewusst.

## Worauf du beim Zeigen den Fokus legst

- Höheres Sicherheitsniveau als Fachentscheidung, nicht als technisches Detail.
- Methodenverwaltung als dauerhafte Fähigkeit des Kontos, nicht als Einzelfall im UI.
- Relevanz für reale Prozesse: Bankverbindung ändern, sensible Gesundheitsinformationen aufrufen, leistungsrelevante Änderungen bestätigen.
- Tool-Verfügbarkeit als steuerbare Produkt- und Betriebsentscheidung.

## Sprechpunkt (fachlich)

- "Wir trennen Basiszugang und erhöhte Absicherung; das ist für risikobasierte Fachprozesse zentral."

## Überzeugende Vorteile

- Policies sind verfahrensunabhängig und additiv umsetzbar - Sicherheit wird präzise dort erhöht, wo sie fachlich nötig ist.
- Wird ein Tool lokal ausgeblendet oder zentral deaktiviert, bleibt der Prozess kontrolliert: Governance ist steuerbar statt zufällig gewachsen.

## Was du dazu sagen kannst

- "Wir vermeiden die typische Entweder-oder-Falle: entweder alles streng oder alles bequem. Stattdessen steuern wir gezielt nach Risiko - die Regel bleibt verständlich, auch wenn die technische Umsetzung komplexer wird."
- "Wenn ein vorhandenes Verfahren heute nicht verfügbar ist oder bewusst abgeschaltet wird, darf das kein Architekturunfall sein, sondern muss in dieselbe Steuerungslogik passen."
- "Hier kann man auch die NIST-Denke kurz einordnen: Identität und aktuelle Authentifizierungsstärke sind unterschiedliche Fragen."

## Wenn du mehr zeigen willst

- Kurz sagen, dass `loa2` fachlich dem angestrebten Niveau von NIST AAL2 entspricht.
- Ein deaktiviertes Tool als Governance-Beispiel einbauen, nicht als Störfall.

## Fallback

- Falls der Ablauf stockt: direkt mit dem fachlichen Beispiel weitermachen und den Step-up-Gedanken verbal oder per Screenshot erläutern.

---

## Demo 3 (5 min, optional oder stark verkürzt): Ein kanalübergreifender Prozess als Blaupause

## Ziel

- Kanalübergreifende Orchestrierung demonstrieren.
- Zeigen, dass das Modell über klassische Login-Szenarien hinaus tragfähig ist.
- Verständlich machen, dass Zustimmung, Ablehnung und Wiederaufnahme gleichermaßen sauber modellierbar sind.
- QR als neues Feature zeigen, ohne daraus eine zweite Produktwelt zu machen.

## Kernaussage

"Wenn ein Prozess über mehrere Kanäle läuft, bleibt er trotzdem ein einheitlich steuerbarer Prozess."

## Story-Pfad

- Nina arbeitet nun im Web.
- Der Web-Kanal startet mit `KC_SELECT_METHOD`.
- QR wird als neue Funktion genutzt, um einen Web-Login anzustoßen.
- Die App bestätigt diesen Vorgang über `CONFIRM_PEER_LOGIN`.
- Wenn du willst, erwähnst du zusätzlich `LOOKUP_LOGIN` als alternativen Einstieg ohne Gerätebindung.
- Für die Krankenkasse sprichst du hier bewusst von Webseite und App als zwei Kanälen eines gemeinsamen digitalen Serviceangebots.

## Worauf du beim Zeigen den Fokus legst

- Nicht auf QR als Technikdetail, sondern auf das Muster einer kanalübergreifenden Freigabe.
- Nicht auf den Sonderfall, sondern auf die Übertragbarkeit auf andere Domänen.
- Nicht auf die Oberfläche, sondern auf die Stabilität der Prozessführung.
- Nicht auf Web gegen App, sondern auf denselben fachlichen Rahmen in zwei Kanälen.

## Sprechpunkt (fachlich)

- "Das ist ein allgemeines Muster für kanalübergreifende Freigaben, nicht nur für Login."

## Überzeugende Vorteile

- Wiederverwendung statt Spezialimplementierungen; klare Governance, weil Zustimmung und Ablehnung gleichwertig modelliert sind.
- Neue Features wie QR lassen sich additiv einführen - der Web-Kanal bleibt fachlich anschlussfähig, obwohl er einen anderen Einstieg hat.

## Was du dazu sagen kannst

- "Entscheidend ist hier nicht der QR-Code, sondern das Prinzip: Ein Prozess darf über mehrere Kanäle laufen, ohne unbeherrschbar zu werden - QR ist ein neues Feature, kein neues Nebensystem."

## Wenn du mehr zeigen willst

- `LOOKUP_LOGIN` als App-seitigen Alternativzugang kurz erwähnen.
- Falls keine Zeit bleibt, nur sagen: gleicher fachlicher Rahmen, anderer Kanal, neues Feature.

## Fallback

- Falls der Live-Weg hakt: den Abschnitt stark verkürzen und nur das Prozessmuster erläutern.

---

## Reserve (falls Zeit bleibt): Alternative Einstiege und sensible Abschlüsse

## Ziel

- Zeigen, dass unterschiedliche Einstiege nicht zu unterschiedlichen Grundlogiken führen müssen.
- Einen starken Governance-Schlusspunkt ermöglichen.

## Kernaussage

"Verschiedene Einstiegswege sind möglich, ohne dass das Gesamtsystem in verschiedene Denkwelten zerfällt."

## Sprechpunkt (fachlich)

- "Das Ziel ist Wahlfreiheit im Einstieg bei gleichbleibender Prozesssteuerung."

## Überzeugende Vorteile

- Bessere Anschlussfähigkeit an reale Bestandsprozesse.
- Weniger Migrationsdruck, weil nicht alles gleichzeitig umgebaut werden muss.
- Saubere Evolution statt Big-Bang-Ablösung.
- Mit `DELETE_ACCOUNT` und `LOGOUT` sind auch heikle Abschlussfälle explizit geregelt.

## Geeignete Reservefälle

- `LOOKUP_LOGIN` als Alternative ohne Gerätebindung
- `DELETE_ACCOUNT` als starker Fall für Zustimmung plus erhöhten Nachweis
- `LOGOUT` als expliziter, sauberer Abschluss
