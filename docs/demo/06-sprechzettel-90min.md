# Sprechzettel für 90 Minuten

Dieses Dokument ist für den eigentlichen Termin gedacht. Es liefert dir pro Abschnitt
eine klare Botschaft, überzeugende Formulierungen und einen sicheren Übergang.

## 1) Einstieg (0-5 min)

## Ziel

Den Rahmen setzen: Wir zeigen heute kein Technik-Spielzeug, sondern ein Vorgehensmodell für bessere künftige Entwicklung.

## Das kannst du sagen

- "Ich möchte heute nicht nur zeigen, dass ein Ablauf funktioniert, sondern warum dieses Muster uns bei künftigen Anforderungen helfen kann."
- "Der entscheidende Punkt ist nicht der Demo-Flow selbst, sondern die Fähigkeit, Regeln, Varianten und Sicherheitsniveaus kontrolliert weiterzuentwickeln."
- "Wenn ihr nach diesen 90 Minuten klarer sagen könnt, wo dieses Muster uns echten Nutzen bringt, war der Termin erfolgreich."
- "Für uns heißt das konkret: Wie gestalten wir Krankenkassen-App und Krankenkassen-Webseite so, dass Alltagsservices leicht bleiben und sensible Daten trotzdem sauber geschützt sind?"
- "Die Fachfälle an sich sind euch bekannt. Entscheidend ist heute der Unterschied zwischen einer starren Lösung und einem Modell, das spätere Änderungen besser trägt."

## Übergang

- "Starten wir mit dem Problem, das viele solcher Lösungen im Alltag schwierig macht."

---

## 2) Warum dieses Zielbild? (5-20 min)

## Ziel

Das Problembild sauber aufbauen und das Zielbild als plausible Antwort positionieren.

## Das kannst du sagen

- "Viele Authentifizierungs- und Freigabeprozesse starten einfach und werden dann mit jeder Ausnahme teurer und unübersichtlicher."
- "Neue Anforderungen landen oft als Sonderfall im Frontend, als Zusatzregel im Backend oder als Abstimmungsschleife zwischen beiden."
- "Unser Zielbild ist deshalb: fachlich steuerbare Journeys statt lose verketteter Einzellösungen."
- "Der Vorteil davon ist, dass neue Anforderungen eher als neue Regel oder neuer Prozessschritt eingeführt werden, nicht als erneuter Umbau an vielen Stellen."
- "Gerade in einer Krankenkasse trifft das schnell auf App, Webseite, schutzbedürftige Daten, Servicefälle und unterschiedliche Sicherheitsniveaus zugleich."
- "Die starre Lösung kann den heutigen Fall oft auch. Ihr Problem beginnt dort, wo die zweite Variante, der nächste Kanal oder ein neuer Schutzbedarf dazukommt."

## Punkte, die überzeugen

- Das ist ein Kostenargument, nicht nur ein Architekturargument.
- Das ist ein Governance-Argument, weil Regeln sichtbarer und auditierbarer werden.
- Das ist ein Teamargument, weil Fachseite und Umsetzung auf dasselbe Modell schauen können.

## Übergang

- "Damit das nicht abstrakt bleibt, zeige ich einmal eine Reise von Anfang bis Ende."

---

## 3) Demo-Block 1: Eine durchgängige Nutzerreise (20-35 min)

## Ziel

An einer kompakten Reise zeigen, dass das Modell verständlich bleibt, obwohl mehrere Entscheidungen zusammenkommen.

## Welcher Story-Teil hier läuft

Nina startet auf einem neuen Smartphone. `FAST_ACCESS` entscheidet, ob ein einfacher Wiedereinstieg in die Krankenkassen-App reicht oder ob kontrolliert `REGISTER` startet. Damit zeigst du nicht nur Zugang, sondern dieselbe Steuerungslogik für Komfort und geführte Einrichtung.

## Das kannst du sagen

- "Wichtig ist hier nicht jeder einzelne Klick, sondern dass der Ablauf jederzeit nachvollziehbar bleibt."
- "Man sieht, welcher nächste Schritt fachlich sinnvoll ist, statt dass die Oberfläche erraten muss, wie es weitergeht."
- "Genau das hilft uns später bei Änderungen, weil Verhalten nicht in versteckten UI-Sonderlogiken verschwindet."
- "Wenn wir an dieser Stelle einen anderen Registrierungsweg zulassen wollen, ist das keine neue Prozesswelt, sondern eine Variante desselben Ziels."
- "Schon daran sieht man: Dieses Modell ist experimentierfähig, ohne chaotisch zu werden."
- "Im Krankenkassen-Kontext ist das wichtig, weil schon der Wiedereinstieg auf ein neues Gerät sicher und gleichzeitig verständlich sein muss."
- "Der fachliche Fall ist bekannt. Der Unterschied ist: Wir ergänzen hier Varianten kontrolliert, statt die bestehende Lösung immer weiter zu verbiegen."

## Was das beim Publikum auslösen soll

- Fachseite: "So kann ich Regeln und Ausnahmen besser diskutieren."
- Frontend: "So muss ich weniger Prozesslogik im Client nachbauen."

## Wenn du nachlegen willst

- "Selbst ein Zweitaccount- oder Geräte-Umbindungsfall wird hier nicht stillschweigend gelöst, sondern bewusst geführt."

## Übergang

- "Der eigentliche Wert zeigt sich aber nicht nur im Ablauf selbst, sondern in den Vorteilen für spätere Weiterentwicklung."

---

## 4) Fachlicher und organisatorischer Nutzen (35-50 min)

## Ziel

Die Vorteile explizit benennen, bevor sich die Wahrnehmung zu stark auf Demo-Details verengt.

## Die stärksten Aussagen

- "Wir investieren hier nicht nur in einen Flow, sondern in spätere Änderbarkeit."
- "Je mehr Varianten und Regeln künftig hinzukommen, desto mehr zahlt sich diese Struktur aus."
- "Der Gewinn liegt nicht nur in besserer Technik, sondern in geringeren Abstimmungskosten und weniger Seiteneffekten."
- "Die starre Lösung verliert meist nicht beim ersten Anwendungsfall, sondern bei jeder späteren Erweiterung. Genau dort setzen wir an."

## Nutzen für Fachexpertinnen/Fachexperten

- Regeln werden sichtbarer und damit früher diskutierbar.
- Sicherheitsanforderungen lassen sich kontextbezogen formulieren.
- Ausnahmefälle werden bewusste Entscheidungen statt implizite Sonderfälle.
- Schutzbedarfe rund um Gesundheits- und Kontodaten lassen sich sauberer fachlich begründen.

## Nutzen für Frontend und Delivery

- Weniger implizite Prozesslogik im Client.
- Kleinere, lokalere Änderungen.
- Bessere Testbarkeit und nachvollziehbarere Reviews.
- Weniger Brüche zwischen Krankenkassen-App und Krankenkassen-Webseite.

## Übergang

- "Ein besonders wichtiger Punkt ist dabei, dass nicht jeder Vorgang dieselbe Sicherheitslogik braucht."

---

## 5) Demo-Block 2: Sicherheitsniveau und Varianten (50-60 min)

## Ziel

Zeigen, dass Sicherheitslogik präzise steuerbar ist, ohne das Gesamtsystem unnötig zu verkomplizieren.

## Welcher Story-Teil hier läuft

Nina will jetzt eine sensiblere Aktion ausführen, zum Beispiel ihre Bankverbindung für Erstattungen ändern oder auf besonders schützenswerte Gesundheitsinformationen zugreifen. Das löst `STEP_UP` aus. Wenn die vorhandenen Verfahren nicht genügen, kannst du `RE_IDENTIFY` als kontrollierte Rückfallebene erwähnen. Danach gehst du in `MANAGE_AUTH_METHODS`, um Ausbau und bewusste Deaktivierung von Verfahren zu zeigen.

## Das kannst du sagen

- "Wir wollen nicht pauschal jeden Schritt maximal absichern, sondern gezielt dort, wo der fachliche Schutzbedarf steigt."
- "Das ist wichtig, weil Nutzerfreundlichkeit und Sicherheit nicht zwangsläufig Gegensätze sein müssen."
- "Wenn solche Regeln sauber modelliert sind, bleiben sie auch bei neuen Anforderungen verständlich."
- "Wenn ein Verfahren heute nicht zur Verfügung steht oder bewusst abgeschaltet wird, darf daraus kein Sonderchaos entstehen. Genau das verhindert dieses Modell."
- "Hier kann man die NIST-Denke in einem Satz einordnen: Identität und aktuelle Authentifizierungsstärke sind zwei unterschiedliche Fragen."
- "Gerade bei einer Krankenkasse ist das zentral, weil nicht jede Aktion denselben Schutzbedarf hat, aber die Regel trotzdem nachvollziehbar bleiben muss."

## Der kurze NIST-Satz

- "Unser `loa2` steht fachlich für einen Nachweis auf einem Niveau wie NIST AAL2; entscheidend ist die Trennung zwischen Identität und aktueller Stärke des Nachweises."

## Wenn du nachlegen willst

- "Governance heißt hier auch: welche Tools ein Kanal anbietet und welche Verfahren zentral gesperrt werden können, ist steuerbar statt implizit."

## Übergang

- "Damit das nicht nur fachlich gut klingt, braucht es eine Struktur, die Änderungen auch tatsächlich günstig macht."

---

## 6) Architektur als Enabler (60-70 min)

## Ziel

Die Architektur als Mittel zum Nutzen erklären, nicht als Selbstzweck.

## Das kannst du sagen

- "Die technische Struktur ist hier kein Schönheitsideal, sondern der Hebel für kontrollierte Weiterentwicklung."
- "Wenn neue Verfahren oder Regeln hinzukommen, sollten sie möglichst additiv eingebracht werden können."
- "Klare Modulgrenzen sind nicht nur sauber, sie reduzieren reale Änderungs- und Abstimmungsrisiken."

## Überzeugende Punkte

- Weniger Seiteneffekte bei Änderungen.
- Höhere Planbarkeit neuer Anforderungen.
- Verifizierbare Struktur statt still wachsender Unordnung.

## Übergang

- "Besonders spürbar wird das im Frontend, weil dort sonst viele Sonderfälle zusammenlaufen."

---

## 7) Frontend-Learnings und Delivery-Vorteile (70-80 min)

## Ziel

Dem Frontend-Team einen klaren Nutzen zeigen und technische Sorge vor zusätzlicher Komplexität abbauen.

## Das kannst du sagen

- "Das Frontend wird hier nicht zum Sammelpunkt versteckter Fachentscheidungen."
- "Wenn der nächste sinnvolle Schritt aus dem Prozessmodell kommt, sinkt die Menge impliziter Client-Logik."
- "Das führt in der Praxis zu kleineren Änderungen, klareren Tests und weniger Regressionen."
- "Auch neue Dinge wie QR oder alternative Einstiege landen dadurch nicht als Router-Chaos im Client, sondern als kontrollierte Erweiterung."
- "Für App und Webseite ist das wichtig, weil wir denselben fachlichen Rahmen nutzen können, ohne zwei getrennte Prozesslogiken zu pflegen."

## Punkte, die überzeugen

- Bessere Reviewbarkeit.
- Klarere Ownership.
- Einfachere Erweiterung neuer Verfahren.

## Übergang

- "Die wichtigste Frage ist jetzt: Wo wäre dieses Muster für uns selbst am wertvollsten?"

---

## 8) Transfer auf eigene Vorhaben (80-88 min)

## Ziel

Die Gruppe aktiv auf den eigenen Kontext ziehen und aus Interesse eine Anschlussfrage machen.

## Leitfragen

- "Wo haben wir heute Prozesse, die mit jeder Ausnahme schwerer werden?"
- "Wo liegen heute fachliche Regeln verteilt in mehreren technischen Schichten?"
- "Welcher Anwendungsfall wäre klein genug für einen Pilot und zugleich sichtbar genug, um Nutzen zu beweisen?"
- "Wo könnten wir mit Varianten oder A/B-Ansätzen experimentieren, ohne sofort zwei Produktwelten pflegen zu müssen?"
- "Welche heutigen Verfahren würden wir gern gezielt ein- oder ausschalten können, ohne ganze Flows neu zu bauen?"
- "Welcher Versichertenprozess zwischen Krankenkassen-App und Webseite wäre ein besonders guter erster Pilot?"

## Abschlussformel für diesen Teil

- "Wenn wir zwei bis drei gute Kandidaten benennen können, ist aus einer Demo ein nächster konkreter Schritt geworden."

---

## 9) Abschluss (88-90 min)

## Das kannst du sagen

- "Mein wichtigster Punkt heute ist: Der Mehrwert liegt nicht in einem weiteren Flow, sondern in einer Struktur, in der künftige Anforderungen beherrschbar bleiben."
- "Wenn wir dieses Muster übernehmen, investieren wir in geringere Komplexitätskosten der nächsten Ausbaustufen."
- "Der sinnvolle nächste Schritt ist deshalb nicht, alles sofort zu übernehmen, sondern gezielt einen passenden Pilotprozess auszuwählen."
- "Die Stärke liegt nicht im Happy Path, sondern darin, dass viele Varianten, Kanäle und Governance-Entscheidungen trotzdem zusammenpassen."
- "Für eine Krankenkasse heißt das: bessere digitale Services bei gleichzeitig sauberem Umgang mit Schutzbedarf und Kanalvielfalt."
- "Die Fachlichkeit kennen wir. Neu ist, dass wir ihre Weiterentwicklung nicht länger wie ein Sammelsurium aus Sonderfällen behandeln müssten."
