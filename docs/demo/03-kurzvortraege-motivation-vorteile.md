# Kurzvorträge, Motivation, Vorteile

Diese Datei ist das eigentliche Argumentationspapier für die Vorstellung.
Sie soll helfen, die Lösung nicht nur zu erklären, sondern ihre Vorteile überzeugend
herauszuarbeiten.

## Kernthese der gesamten Vorstellung

Wir zeigen ein Vorgehensmodell, mit dem fachlich anspruchsvolle Authentifizierungs- und
Freigabeprozesse beherrschbar bleiben, auch wenn Anforderungen wachsen. Der eigentliche
Mehrwert liegt nicht in einem einzelnen Demo-Flow, sondern in der Fähigkeit, Regeln,
Varianten, Sicherheitsniveaus und neue Verfahren kontrolliert weiterzuentwickeln.

## Warum das überzeugend ist

- Es reduziert die Wahrscheinlichkeit, dass neue Anforderungen zu teuren Sonderlösungen führen.
- Es macht fachliche Entscheidungen sichtbar, statt sie in Frontend- oder Backend-Speziallogik zu verstecken.
- Es verbessert die Zusammenarbeit zwischen Fachseite, Frontend und Backend.
- Es schafft eine tragfähige Grundlage für schrittweise Weiterentwicklung statt für einmalige Showcases.

## Wichtige Haltung für diese Zielgruppe

Die Fachexpertinnen und Fachexperten kennen die grundlegenden Krankenkassen-Fälle bereits aus
einer starren Lösung. Deshalb sollte die Argumentation nicht lauten: "Wir zeigen völlig neue
Fachlichkeit." Überzeugend ist vielmehr dieser Kontrast:

- gleicher fachlicher Kern
- aber andere Qualität in Änderbarkeit und Erweiterbarkeit
- weniger Sonderlogik bei Varianten und Ausnahmen
- klarere Steuerbarkeit über Regeln statt über gewachsene Einzelfälle

## Kurzvortrag 1 (15 min): Warum dieses Zielbild?

## Kernbotschaft

Wir wollen sichere Authentisierung nicht als starre Einzelfunktion bauen, sondern als
steuerbaren fachlichen Ablauf, der sich erweitern lässt.

## Leitargumente

- Nutzerreisen sind der Kern: Registrierung, Anmeldung, Freigabe, Sicherheitsanhebung.
- Orchestrierung trennt fachliche Entscheidungen (Policy, Retry, Step-up) von Verfahrensdetails.
- Methodenmodule bleiben entkoppelt und können gezielt erweitert oder ausgetauscht werden.
- DPoP ist ein Sicherheitsbaustein zur Gerätebindung, aber nicht die zentrale Erzählung.

## Überzeugungslinie

- In vielen Projekten wachsen Sicherheits- und Prozessanforderungen schrittweise, aber die technische Struktur ist dafür nicht vorbereitet.
- Das führt zu Sonderfällen, Ausnahmen und wachsender Unsicherheit bei Änderungen.
- Dieses Zielbild setzt stattdessen auf ein explizites Prozessmodell, in dem Entscheidungen nachvollziehbar und wiederverwendbar bleiben.
- Dadurch sinkt das Risiko, dass künftige Anforderungen jedes Mal als Sonderprojekt behandelt werden müssen.

## Kontrast zur starren Lösung, den du aktiv benennen solltest

- Die starre Lösung bildet den heutigen Fachfall oft grundsätzlich ab.
- Ihr Nachteil zeigt sich, sobald Varianten, neue Schutzbedarfe, weitere Kanäle oder neue Verfahren hinzukommen.
- Dann entstehen neue Sonderpfade statt klarer Regeln.
- Genau hier setzt dieses Modell an: nicht beim Erfinden neuer Fachlichkeit, sondern beim beherrschbaren Ausbau bekannter Fachlichkeit.

## Inhalte (Sprechleitfaden)

- Ausgangslage: Klassische Login-Flows werden bei neuen Anforderungen schnell unübersichtlich.
- Zielbild: Ein Ablaufmodell, das Fachregeln explizit macht und Änderungen kontrollierbar hält.
- Entscheidungslogik: "Nachweis reicht" vs. "zusätzlicher Nachweis nötig" als fachliche Regel.
- Transferfrage ans Publikum: "Welche Entscheidungspunkte kennen wir aus unseren Prozessen schon heute?"

## Kurzvortrag 2 (10 min): Architektur in alltagstauglich

## Kernbotschaft

Das System ist so geschnitten, dass Fachseite und Frontend parallel arbeiten können,
ohne sich laufend gegenseitig zu blockieren.

## Leitargumente

- Modulith mit klaren Grenzen statt unkontrollierter Querverbindungen.
- API-Fassade über den Orchestrator für konsistente Client-Kommunikation.
- Verifizierbare Modulregeln im Build verhindern Architekturdrift.

## Inhalte (Sprechleitfaden)

- Verantwortungsmodell: Fachlogik im Ablauf, Verfahren in Modulen, UI als geführte Darstellung.
- Integrationsprinzip: Neue Methode andocken, ohne bestehende Reisen aufzubrechen.
- Teamwirkung: weniger Abstimmungsrunden, klarere Übergabepunkte zwischen Teams.
- Betriebswirkung: reproduzierbarer Build und nachvollziehbare Änderungen.

## Warum das für Entscheider relevant ist

- Änderbarkeit ist kein technisches Luxusproblem, sondern ein Kosten- und Risikothema.
- Klare Verantwortungen verkürzen Abstimmungen und reduzieren Missverständnisse.
- Verifizierbare Struktur schützt vor schleichender Architektur-Erosion.
- Ein gutes Architekturmodell senkt die Hürde, weitere Verfahren oder Regeln später tatsächlich nachzuziehen.

## Kurzvortrag 3 (15 min): Frontend-Learnings

## Kernbotschaft

Die UI bleibt robust, weil die Navigation aus serverseitigem `next` gesteuert wird und nicht
aus fragilen Client-Annahmen.

## Leitargumente

- Drei getrennte Apps (`/`, `/app/`, `/web/`) mit klarem Nutzungskontext.
- Gemeinsame Bibliotheksteile für Wiederverwendung ohne Vermischung der Kanäle.
- Neue Tools brauchen vor allem Routing-Mapping statt globalem UI-Umbau.

## Inhalte (Sprechleitfaden)

- UX-Prinzip: Der nächste sinnvolle Schritt kommt aus dem Prozessmodell (`next`).
- Entwicklungsprinzip: Kleine, lokale UI-Erweiterungen statt riskanter Gesamtumbauten.
- Qualitätsprinzip: Bessere Testbarkeit durch klar definierte Zustände und Übergänge.
- Transferfrage ans Frontend-Team: "Welche eurer heutigen Flows würden von `next`-Navigation sofort profitieren?"

## Warum das für das Frontend besonders wertvoll ist

- Weniger implizite Logik im Client bedeutet weniger schwer auffindbare Fehler.
- Zustands- und Übergangsorientierung macht Verhalten besser testbar.
- Neue Verfahren lassen sich eher als Ergänzung als als Umbau realisieren.
- Frontend-Teams behalten mehr Stabilität, auch wenn fachliche Anforderungen dynamischer werden.

---

## Motivation für die Zielgruppen

## Fachexperten

- Fachliche Regeln werden als Journey-Schritte sichtbar und diskutierbar.
- Sicherheitsniveau kann kontextbezogen angehoben werden (z. B. vor kritischen Aktionen).
- Demo erlaubt schnelle Validierung von Prozessideen vor Produktiv-Umsetzung.
- Fachentscheidungen werden nicht erst spät in technischer Umsetzung sichtbar, sondern früh besprechbar.
- Fachliche Ausnahmen können als bewusste Regel modelliert werden, nicht als stiller Sonderfall.
- Bekannte Fachfälle müssen nicht bei jeder Erweiterung neu in starre Sonderprozesse zerlegt werden.

## Frontend-Team

- Stabilere UI-Entwicklung durch explizite `next`-Signale vom Backend.
- Klare Trennung von Kanal-Apps reduziert Seiteneffekte.
- Erweiterungen sind planbar, weil Integrationspunkte klar sind.
- Weniger Routing-Sonderlogik bedeutet geringeres Regressionsrisiko.
- Änderungen werden kleiner, lokaler und besser reviewbar.

## Projekt- und Teamleitung

- Neue Anforderungen lassen sich mit höherer Planungssicherheit bewerten.
- Risiken durch Seiteneffekte und unklare Verantwortungen sinken.
- Die Lösung ist anschlussfähig für inkrementelle Weiterentwicklung statt für ein einmaliges Demo-Ergebnis.

---

## Vorteile mit Beispielen

| Vorteil | Beispiel | Fachlicher Effekt | Organisatorischer Effekt |
|---|---|---|---|
| Additive Erweiterbarkeit | Ein zusätzliches Auth-Verfahren wird als neues Modul/Tool integriert, ohne den Clientfluss neu zu bauen. | Neue Anforderungen werden inkrementell umgesetzt statt als Großumbau. | Roadmap-Punkte werden realistischer planbar. |
| Fachliche Steuerbarkeit | Eine Aktion mit höherem Risiko löst gezielt Step-up (`loa2`) aus, Standardaktionen bleiben bei `loa1`. | Sicherheit wird kontextbezogen erhöht statt pauschal. | Fach- und Security-Anforderungen lassen sich sauberer abstimmen - die Regel bleibt als Regel erkennbar, statt an bestehende Logik angebaut zu werden. |
| Nachvollziehbarkeit im Team | Journey-Log und klarer `next`-Schritt machen Abläufe in Reviews schnell erklärbar. | Schnellere Abstimmung zwischen Produkt, Fachseite und Entwicklung. | Reviews, Demos und Abnahmen werden belastbarer. |
| Realistische Demo-Basis | Web-zu-App-QR-Bestätigung zeigt einen praxisnahen Multi-Kanal-Fall als Blaupause. | Hohe Übertragbarkeit auf Freigabe- und Bestätigungsprozesse. | Ein einmal verstandenes Muster wird mehrfach genutzt. |
| Geringere Frontend-Kopplung | Neue Journey-Schritte werden über vorhandene Muster (Zustand + Komponente) ergänzt, nicht über globale Sonderpfade. | Planbarere Releases, weniger Regressionen. | Geringere Kosten für spätere Anpassungen. |
| Trennung von Regel und Umsetzung | Ob ein zusätzlicher Nachweis nötig ist, ist eine Regel der Journey, nicht über UI-/Backend-Sonderfälle verteilt. | Regeln bleiben verständlich und auditierbar. | Weniger Interpretationsspielraum zwischen Fachseite und Entwicklung. |
| Höhere Zukunftsfähigkeit | Weitere Verfahren, Freigabeschritte oder Kanalvarianten erfordern kein Neudenken des Gesamtsystems. | Das Zielbild bleibt offen für Weiterentwicklung. | Investitionen in die Struktur zahlen sich über mehrere Ausbaustufen aus - die bekannte Fachlichkeit bleibt gleich, die Folgekosten künftiger Änderungen sinken. |
| Weniger versteckte Komplexität | Verhalten steckt in Journeys und Zuständen statt in Event-Handlern, Sonder-Flags und Routings verteilt. | Verhalten lässt sich besser erklären und prüfen. | Wissensinseln im Team werden reduziert. |

---

## Formulierungen, die in der Vorstellung wirken

- "Der eigentliche Gewinn ist nicht ein weiterer Login-Flow, sondern eine Struktur, in der neue Anforderungen beherrschbar bleiben."
- "Die fachlichen Fälle kennt ihr bereits. Neu ist die Art, wie wir Änderungen und Ausnahmen beherrschbar halten."
- "Der Unterschied zur starren Lösung zeigt sich nicht im Happy Path, sondern ab der zweiten, dritten und vierten Variante."
- "Wir ersetzen bekannte Fachlichkeit nicht, wir machen ihre Weiterentwicklung günstiger und klarer."
