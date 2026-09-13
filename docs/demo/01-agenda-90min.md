# Agenda für die 90-Minuten-Demo

Zielgruppe: Fachexpertinnen/Fachexperten + App-Frontend-Kolleginnen/Kollegen  
Dauer: 90 Minuten  
Ziel: Fachlichen Nutzen, technische Tragfähigkeit und konkrete Wiederverwendbarkeit für eigene Vorhaben sichtbar machen.

## Leitidee

Die Veranstaltung soll nicht primär demonstrieren, dass "ein Login funktioniert", sondern
warum dieses Vorgehensmodell für künftige fachliche und technische Entwicklung wertvoll ist:
mehr Steuerbarkeit, mehr Erweiterbarkeit, mehr Nachvollziehbarkeit und weniger teure Umbauten.

Wichtig für diese Runde: Die fachlichen Fälle sind den Fachexpertinnen und Fachexperten weitgehend
bekannt. Überzeugend ist deshalb nicht der Fall an sich, sondern der Unterschied zur starren
Lösung: gleiche Fachlichkeit, aber bessere Änderbarkeit, klarere Regeln und weniger Sonderlogik.

## Gesamtüberblick

| Zeit | Block | Ziel |
|---|---|---|
| 00-05 | Einstieg | Erwartungsrahmen, Erfolgskriterien und Nutzenversprechen setzen |
| 05-20 | Warum dieses Zielbild? | Welches Problem wir lösen und warum es für kommende Vorhaben relevant ist |
| 20-35 | Demo-Block 1: Eine durchgängige Nutzerreise | Das Wirkprinzip an einem verständlichen Ablauf sichtbar machen |
| 35-50 | Fachlicher und organisatorischer Nutzen | Konkrete Vorteile für Fachseite, Frontend und Delivery herausarbeiten |
| 50-60 | Demo-Block 2: Sicherheitsniveau und Varianten | Zeigen, dass Regeln und Ausnahmen beherrschbar bleiben |
| 60-70 | Architektur als Enabler | Warum die Struktur spätere Änderungen günstiger und sicherer macht |
| 70-80 | Frontend-Learnings und Delivery-Vorteile | Was das Vorgehen für UI, Tests und Änderbarkeit verbessert |
| 80-88 | Transfer auf eigene Vorhaben | Einsatzfelder, Pilotkandidaten und Nutzen für euch konkretisieren |
| 88-90 | Abschluss | Entscheidung, nächster Schritt, klare Mitnahme |

## Inhalte je Block

### 00-05 min: Einstieg

- Zielbild in einem Satz: "Fachlich steuerbare und technisch erweiterbare Authentifizierungsreisen".
- Klare Erwartung: Die Demo ist Mittel zum Zweck; im Mittelpunkt stehen Nutzen, Übertragbarkeit und Entwicklungsfähigkeit.
- Erfolgskriterien bestätigen: Was wäre für Fachseite und Frontend ein gutes Ergebnis?

### 05-20 min: Warum dieses Zielbild?

- Ausgangslage: Fachliche Anforderungen an Anmeldung, Freigabe und Sicherheitsanhebung wachsen meist schneller als die vorhandenen Flows.
- Problem klassischer Lösungen: Viele Sonderfälle, viel UI-Speziallogik, schwer zu ändern, schwer abzustimmen.
- Zielbild: Steuerung über Intents und Journeys statt über starre Einzelfälle.
- Nutzenversprechen: Neue Anforderungen lassen sich als Regel- oder Prozessanpassung einführen, nicht als kostspieliger Umbau.

### Schärfung für Fachexperten in diesem Block

- Nicht argumentieren: "Das kann die alte Lösung gar nicht fachlich."
- Sondern argumentieren: "Die alte Lösung kann den Fall auch, aber jede Variante, Ausnahme und Erweiterung wird dort schneller teuer und unübersichtlich."
- Herausstellen: Der Unterschied liegt in Steuerbarkeit, Transparenz und Geschwindigkeit späterer Änderungen.

### 20-35 min: Demo-Block 1 - Eine durchgängige Nutzerreise

- Eine kompakte Reise als Anschauungsobjekt, nicht als technischer Selbstzweck.
- Sichtbar machen: Der Prozess bleibt lesbar, selbst wenn mehrere Entscheidungen hintereinander fallen.
- Gesprächspunkt: Wo würden heutige Sonderlogiken in ein klareres Ablaufmodell überführt?
- Kontrastlinie: Der Fall ist bekannt, aber die Art, wie Varianten und Folgeänderungen beherrscht werden, ist eine andere.

### 35-50 min: Fachlicher und organisatorischer Nutzen

- Für die Fachseite: Regeln werden sichtbarer, abstimmbarer und später leichter veränderbar.
- Für das Frontend: weniger versteckte Entscheidungslogik, klarere Integrationspunkte, bessere Testbarkeit.
- Für Delivery und Organisation: geringere Kopplung zwischen Teams, kleinere Änderungspakete, kalkulierbarere Weiterentwicklung.
- Für Architektur und Governance: nachvollziehbare Entscheidungen statt gewachsener Sonderpfade.
- Wichtig in der Argumentation: Nicht "mehr Funktionen" versprechen, sondern geringere Komplexitätskosten für bekannte und künftige Fachfälle.

### Überzeugende Kernpunkte in diesem Block

- Ihr investiert nicht nur in Funktionalität, sondern in spätere Änderbarkeit.
- Der eigentliche Gewinn ist nicht ein einzelner Flow, sondern ein Muster für viele künftige Flows.
- Je mehr Varianten und Regeln künftig dazukommen, desto stärker zahlt sich dieses Modell aus.

### 50-60 min: Demo-Block 2 - Sicherheitsniveau und Varianten

- Nur so viel Demo wie nötig, um die Regelidee verständlich zu machen.
- Sichtbar machen: Höheres Sicherheitsniveau ist eine fachliche Entscheidung, kein technischer Nachtrag.
- Gesprächspunkt: Welche Aktionen in eurem Kontext sollten kontrolliert eskalieren können?

### 60-70 min: Architektur als Enabler

- Warum die technische Struktur nicht Selbstzweck ist, sondern Änderbarkeit absichert.
- Rolle von Orchestrator, Methodenmodulen und gemeinsamer SPI in der Arbeitsteilung.
- Warum verifizierbare Modulgrenzen spätere Projektkosten senken.
- Warum neue Verfahren additiv eingebracht werden können, statt bestehende Flows zu destabilisieren.

### 70-80 min: Frontend-Learnings und Delivery-Vorteile

- Drei eigenständige Apps mit klaren Aufgaben (`/`, `/app/`, `/web/`).
- Rendering-Entscheidungen über `next` statt über implizite URL-Logik.
- Erweiterbarkeit: neue Verfahren über Mapping und Komponenten statt großer Router-Umbauten.
- Delivery-Vorteil: kleinere Änderungen, weniger Regressionen, klarere Testfälle.

### Überzeugende Kernpunkte in diesem Block

- Das Frontend wird nicht zum Ort der versteckten Fachlogik.
- Mehr Klarheit in Zuständen und Übergängen bedeutet bessere Testbarkeit und bessere Reviews.
- Das Team gewinnt Stabilität, obwohl die Anforderungen dynamischer werden.

### 80-88 min: Transfer auf eigene Vorhaben

- Zwei bis drei Prozesse identifizieren, in denen dieses Muster echten Mehrwert liefern würde.
- Diskutieren, wo heute Sonderlogik, Medienbrüche oder überladene Frontend-Flows Schmerzen verursachen.
- Gemeinsame Priorisierung von 2-3 realistischen Pilotkandidaten.

### Leitfragen für die Diskussion

- Wo haben wir heute fachliche Regeln, die schwer nachvollziehbar in UI oder Backend verteilt sind?
- Wo entstehen bei Änderungen regelmäßig hohe Abstimmungskosten?
- Welche Prozesse hätten den größten Nutzen von mehr Steuerbarkeit und klareren Zuständen?

### 88-90 min: Abschluss

- Gemeinsame Entscheidung: Welchen Anwendungsfall prüfen wir als Erstes vertieft?
- Nächster Schritt mit Verantwortlichen und Termin.
- Offene Risiken, Annahmen und Fachfragen transparent festhalten.

## Lernziele pro Zielgruppe

### Fachexperten

- Verstehen, wie fachliche Policies (z. B. Sicherheitsniveau) in Journey-Logik umgesetzt werden.
- Erkennen, dass neue Anforderungen nicht automatisch zu neuen Sonderprozessen führen müssen.
- Nachvollziehen, wie dieses Vorgehen spätere Produktentscheidungen robuster vorbereitet.

### Frontend-Kollegen

- Verstehen, warum die UI-Navigation an `next` hängt statt an URL-Heuristiken.
- Erkennen, wie neue Verfahren mit geringem UI-Umbau integrierbar sind.
- Einschätzen, wie die Trennung in `/`, `/app/`, `/web/` Entwicklung, Tests und Ownership vereinfacht.

## Erfolgskriterien am Ende der 90 Minuten

- Alle Teilnehmenden können den fachlichen Mehrwert in 2-3 Sätzen erklären.
- Das Team benennt mindestens 2 konkrete Übernahmekandidaten für die eigene Entwicklung.
- Der Nutzen für Fachseite und Frontend ist jeweils in konkreten Punkten benannt.
- Offene Risiken und Fragen sind sichtbar und priorisierbar dokumentiert.
