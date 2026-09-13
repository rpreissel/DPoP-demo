# Demo-Story als roter Faden

Dieses Dokument gibt dir keinen einzelnen Flow, sondern eine durchgehende Geschichte mit
Hauptpfad und sinnvollen Abzweigen. So kannst du im Termin fast das gesamte Zielbild zeigen,
ohne dass es wie eine lose Aufzählung von Features wirkt.

## Die Leitidee

Die Demo soll nicht beweisen, dass "ein Login klappt". Sie soll zeigen, dass ein
fachlich gesteuertes Modell viele Varianten, Sicherheitsniveaus, Kanäle und spätere
Erweiterungen tragen kann, ohne dass daraus neue Unordnung entsteht.

Für Fachexpertinnen und Fachexperten ist dabei wichtig: Die meisten gezeigten Fälle sind fachlich
nicht neu. Neu ist, dass dieselben Fälle nicht in einer starren Prozesslogik festhängen, sondern
über Regeln, Zustände und Varianten beherrschbar weiterentwickelt werden können.

## Die Geschichte in einem Satz

Nina Hartmann richtet nach einem Gerätewechsel ihren Zugang zur Krankenkassen-App neu ein,
nutzt später auch das Web-Portal, erlebt unterschiedliche Sicherheitsanforderungen bei
Service- und Schutzbedarfsfällen, aktiviert neue Verfahren, bestätigt einen Web-Login per QR
in der App und sieht dabei, dass neue Regeln, neue Features und sogar bewusst deaktivierte
Tools innerhalb desselben Modells beherrschbar bleiben.

## Die Hauptfigur

- Name: Nina Hartmann
- Rolle: Versicherte einer Krankenkasse
- Arbeitskontext: Sie nutzt sowohl die Krankenkassen-App auf dem Smartphone als auch das Web-Portal
- Erwartung: schneller Zugriff auf Alltagsfunktionen, klare Führung im Ausnahmefall, höhere Sicherheit nur dort, wo Gesundheits- oder Kontodaten besonders geschützt werden müssen

## Warum diese Story funktioniert

- Sie verbindet beide Kanäle in einer einzigen fachlichen Erzählung.
- Sie integriert normale Nutzung, Sonderfälle und Governance-Fragen ohne Themenbruch.
- Sie erlaubt dir, fast alle Journeys sinnvoll einzubauen.
- Sie macht neue Features wie QR nicht zu einem Technik-Gimmick, sondern zu einer nachvollziehbaren Produkterweiterung.
- Sie gibt dir gute Stellen, um Experimente, Tool-Deaktivierungen und NIST einzuordnen.
- Sie passt zu einer Krankenkasse, weil sie Alltagsservice, sensible Daten und kanalübergreifende Kommunikation gleichzeitig abbildet.
- Sie hilft dir, den Unterschied zwischen bekanntem Fachfall und neuer Modellqualität sichtbar zu machen.

## Welche Journeys du damit abdeckst

| Story-Teil | Journey / Intent | Kanal | Warum das wichtig ist |
|---|---|---|---|
| Neues Gerät, schneller Einstieg oder Fallback | `FAST_ACCESS` | App | zeigt den Standard-Einstieg und den Wechsel zwischen Komfort und geführtem Prozess |
| Neu aufsetzen oder zweites Konto | `REGISTER` | App und Web | zeigt frische Registrierung, Varianten und Geräte-Umbindung |
| Login ohne vorhandene Gerätebindung | `LOOKUP_LOGIN` | App | zeigt eine alternative Einstiegsmöglichkeit ohne Spezialfrontend |
| Web-Login / Methodenauswahl | `KC_SELECT_METHOD` | Web | zeigt, dass auch der Web-Kanal denselben fachlichen Rahmen nutzt |
| Höheres Sicherheitsniveau für kritische Aktion | `STEP_UP` | App oder Web | zeigt risikobasiertes Anheben statt pauschaler Hürden |
| Erneuter Identitätsnachweis als Rückfallebene | `RE_IDENTIFY` | App | zeigt, dass der Prozess nicht unkontrolliert scheitert, wenn Methoden nicht reichen |
| Verfahren hinzufügen oder deaktivieren | `MANAGE_AUTH_METHODS` | App | zeigt Pflege, Ausbau und Governance des Kontos |
| Web-Login in der App bestätigen | `CONFIRM_PEER_LOGIN` | Web plus App | zeigt einen echten kanalübergreifenden Fall |
| Konto löschen | `DELETE_ACCOUNT` | App | zeigt einen besonders sensiblen Abschlussfall |
| Abmelden | `LOGOUT` | App oder Web | zeigt den sauberen, expliziten Abschluss |

---

## So erzählst du die Story im Termin

## Phase 1: Nina startet auf einem neuen Smartphone

### Was passiert fachlich?

Nina hat ein neues Smartphone. Sie möchte möglichst schnell wieder in ihre Krankenkassen-App,
um Postfach, Dokumente und laufende Anliegen zu sehen. Der App-Kanal startet in `FAST_ACCESS`:
Wenn das Gerät schon bekannt ist, wird der Einstieg einfach. Wenn nicht, führt derselbe
Einstieg kontrolliert in die Registrierung.

### Das kannst du sagen

- "Unsere Geschichte beginnt mit einem normalen Alltagsfall in einer Krankenkasse: neues Gerät, gleiche Versicherte, aber ein neuer Zugangskontext."
- "Spannend ist nicht, dass irgendjemand klicken kann, sondern dass derselbe Einstieg je nach Lage sinnvoll weiterführt."
- "Genau hier zeigt sich, ob ein Zielbild alltagstauglich ist: Es darf Komfort bieten, ohne Sonderlogik zu verstecken."
- "Diesen Fall kennen wir fachlich schon. Der Unterschied liegt darin, dass er nicht an einen starren Ablauf gekettet bleibt."

### Was das Publikum verstehen soll

- `FAST_ACCESS` ist kein einzelner Screen, sondern eine fachliche Führungsstrategie.
- Komfort und Kontrolle schließen sich nicht aus.

---

## Phase 2: Registrierung als geführte Journey, nicht als Sonderprojekt

### Was passiert fachlich?

Da das neue Gerät noch nicht passend angebunden ist, landet Nina in `REGISTER`. Zwei Stellen
zeigen hier echte Variantenfähigkeit: die Identifizierung selbst (z. B. Freischaltcode oder eID -
gleiches Ziel, unterschiedlicher Weg) und die Reihenfolge der Journey als Ganzes (ein Feature-Flag
entscheidet, ob erst identifiziert oder erst ein Anmeldeverfahren eingerichtet wird - Identität
kommt dann optional erst am Ende). Danach richtet Nina ein erstes oder weiteres Anmeldeverfahren ein.

Für die Krankenkasse: Es geht nicht um irgendeine Registrierung, sondern um einen verlässlichen
Wiedereinstieg in einen Kontext mit personenbezogenen und teilweise besonders sensiblen Daten.

### Das kannst du sagen

- "Registrierung ist nicht fest in eine einzige Ausprägung einbetoniert - selbst die Reihenfolge
  von Identifizierung und Einrichtung ist eine austauschbare Regel, kein Codeast."
- "Genau daraus entstehen später echte Experimente statt teurer Parallellösungen."
- Optional, für den Zweitaccount-Fall: "Ist auf diesem Gerät schon ein anderes Konto verknüpft,
  überschreibt das System nichts stillschweigend, sondern fragt bewusst nach der Umbindung."

### Was das Publikum verstehen soll

- Registrierung ist ein fachlich steuerbarer Prozess; Varianten bedeuten keine neuen Architekturzweige.
- Auch heikle Fälle wie Geräte-Umbindung sind explizit modelliert.

---

## Phase 3: Alltag ohne unnötige Reibung

### Was passiert fachlich?

Nach erfolgreicher Einrichtung arbeitet Nina im Alltag weiter. Auf einem bekannten Gerät greift nun
wieder `FAST_ACCESS`: bekannte Situation, passendes Verfahren, schneller Einstieg.

Im Krankenkassen-Kontext sind das zum Beispiel alltägliche Vorgänge wie Nachrichten lesen,
Dokumente abrufen oder den Bearbeitungsstand eines Anliegens prüfen.

### Das kannst du sagen

- "Eine gute Lösung zeigt ihre Stärke nicht nur im Ausnahmefall, sondern darin, dass der Alltag leicht bleibt - Sicherheit steigt nur dort, wo sie fachlich begründet ist."

---

## Phase 4: Kritische Aktion braucht mehr als nur Basiszugang

### Was passiert fachlich?

Nina möchte nun eine sensiblere Aktion ausführen, etwa eine Änderung ihrer Bankverbindung für
Erstattungen, den Zugriff auf besonders schützenswerte Gesundheitsinformationen oder die
Bestätigung einer leistungsrelevanten Änderung. Dafür reicht das vorhandene Sicherheitsniveau
nicht. Das System startet `STEP_UP`.

### NIST-Hinweis, den du sauber einbauen kannst

- "Fachlich gefragt ist hier nicht nur: Wer ist die Person? Sondern auch: Wie stark ist der aktuelle
  Nachweis in dieser Situation? Das ist die NIST-Unterscheidung zwischen IAL und AAL - unser
  `loa2` steht fachlich für ein Niveau wie NIST AAL2."

### Das kannst du sagen

- "Jetzt zeigt sich der eigentliche Mehrwert: Das höhere Niveau ist keine technische Laune,
  sondern eine fachliche Regel über Schutzbedarf, ohne den Rest der Anwendung schwerfälliger zu machen."

---

## Phase 5: Wenn vorhandene Verfahren nicht reichen, greift nicht Chaos, sondern Re-Identifizierung

### Was passiert fachlich?

Wenn Ninas vorhandene Verfahren für den Step-up nicht ausreichen oder ausgeschöpft sind,
endet der Prozess nicht in einer Sackgasse. Stattdessen kann kontrolliert eine
`RE_IDENTIFY`-Sub-Journey angeboten werden.

### Das kannst du sagen

- "Ein starkes Modell erkennt man daran, was passiert, wenn der erste Plan nicht funktioniert: kein
  wilder Abbruch, sondern eine explizite, wiederverwendete Rückfallebene - Teil des Modells, keine Notlösung daneben."

---

## Phase 6: Nina erweitert und bereinigt ihre Verfahren bewusst

### Was passiert fachlich?

Nach der sensiblen Aktion möchte Nina ihr Konto robuster machen. Sie geht in
`MANAGE_AUTH_METHODS`, ergänzt eine weitere Methode und deaktiviert bewusst eine,
die sie nicht mehr nutzen will.

Im Krankenkassen-Fall ist das gut anschlussfähig, weil Versicherte Geräte wechseln, Verfahren
verlieren oder aus Sicherheitsgründen bewusst alte Zugänge entfernen wollen.

### Hier kannst du bewusstes Tool-Disablen überzeugend einbauen

Zwei Ebenen: lokal im Kanal (ein Client bietet nur an, was er rendern soll und was nicht
abgewählt wurde) und global im Betrieb (das Backend sperrt ein Tool zur Laufzeit, z. B. bei
Verdacht auf Kompromittierung).

- "Verfügbarkeit ist steuerbar, kein versteckter Nebeneffekt des Frontends - sperren wir ein
  Verfahren zentral, bricht nicht das ganze Modell auseinander. Genau daran sieht man: Governance ist mitgedacht, nicht angeklebt."

---

## Phase 7: Login ohne Gerätebindung als bewusste Alternative

### Was passiert fachlich?

Nun kannst du einen zweiten realistischen Fall einbauen: Nina hat ihr Smartphone gerade nicht zur Hand
oder startet auf einem anderen Gerät. Statt den Prozess zu verbiegen, nutzt sie `LOOKUP_LOGIN`.

Für das Publikum ist das plausibel, weil Versicherte eben nicht immer vom bereits bekannten Gerät
aus starten, aber trotzdem einen nachvollziehbaren Zugang zum Web oder zu einem anderen Gerät brauchen.

### Das kannst du sagen

- "Wir fesseln nicht alles an einen einzigen Einstieg: Passt Gerätebindung gerade nicht, gibt es
  einen eigenen, fachlich klaren Login ohne Gerätebindung - kein Workaround, sondern ein bewusst
  modellierter alternativer Zugang."

---

## Phase 8: Web-Kanal als gleichwertiger Teil des Zielbilds

### Was passiert fachlich?

Nina wechselt nun auf die Krankenkassen-Webseite. Dort beginnt der Einstieg nicht mit App-Logik,
sondern im Web-spezifischen Auswahlkontext `KC_SELECT_METHOD`. Das ist wichtig, weil die Webseite
eigene Voraussetzungen und eigene Darstellungsformen hat, aber fachlich nicht aus einem anderen
Universum kommt.

### Das kannst du sagen

- "Wir zeigen kein zweites System, sondern denselben fachlichen Rahmen in einem anderen Kanal: eigene
  Einstiegssituation, aber keine eigene Prozesswahrheit - das reduziert Kopplung zwischen Teams."

---

## Phase 9: QR als neues Feature, nicht als neue Prozesswelt

### Was passiert fachlich?

Jetzt führst du QR als neue Ausbaustufe ein:

- Nina oder das Produkt aktiviert QR bewusst als Verfahren.
- Auf der Krankenkassen-Webseite wird ein Login oder eine geschützte Bestätigung angestoßen.
- In der App bestätigt Nina diesen Vorgang über `CONFIRM_PEER_LOGIN`.

Die Web-Seite startet den Vorgang, die App führt die Bestätigung aus, und beide Kanäle bleiben
Teil derselben fachlichen Geschichte.

### Warum QR in der Story so stark ist

QR ist kein Effekt für die Demo, sondern ein Beispiel für eine spätere Produkterweiterung: ein
neuer Einstieg und eine neue kanalübergreifende Bestätigung passen ins bestehende Modell, weil
neue Features eher Regeln und Zustände ergänzen als neue Prozessinseln erzeugen.

### Das kannst du sagen

- "Entscheidend ist nicht der QR-Code, sondern dass ein neuer Cross-Channel-Fall ohne
  Architekturbruch ergänzt werden kann - additiv, kontrolliert, nachvollziehbar. Auch hier wird
  nicht blind bestätigt: Es braucht ein passendes Sicherheitsniveau und einen frischen Nachweis,
  wenn die Situation es verlangt."

---

## Phase 10: Besonders sensibler Abschlussfall

### Was passiert fachlich?

Wenn du bis zum Ende noch einen starken Governance-Fall zeigen willst, endet die Geschichte mit
`DELETE_ACCOUNT` und anschließend `LOGOUT`.

Im Krankenkassen-Kontext kannst du das als bewussten Self-Service-Abschluss rahmen: ein klarer,
hoch sensibler Vorgang, bei dem Zustimmung, frischer Nachweis und sauberer Abschluss zwingend
zusammengehören.

### Das kannst du sagen

- "Die Qualität eines Modells zeigt sich nicht nur bei einfachen Einstiegen, sondern bei den
  heiklen Enden eines Prozesses: erst klare Zustimmung, dann stärkerer Nachweis, dann ein
  sauberer Abschluss - keine impliziten Regeln, keine versteckten Seiteneffekte."

---

## Die Story in 10 Sätzen für den Termin

1. Nina startet mit einem neuen Smartphone und braucht wieder Zugang zu ihrer Krankenkassen-App.
2. Der App-Einstieg behandelt das nicht als starres Login, sondern als fachlich gesteuerte `FAST_ACCESS`-Situation.
3. Wo kein einfacher Wiedereinstieg möglich ist, führt derselbe Rahmen kontrolliert in `REGISTER`.
4. Die Registrierung kann fachlich variieren, zum Beispiel über unterschiedliche Identifizierungswege, ohne dass zwei Prozesswelten entstehen.
5. Für normale Nutzung bleibt der Alltag leicht, für sensible Aktionen rund um Gesundheits- oder Kontodaten wird über `STEP_UP` gezielt ein höheres Niveau verlangt.
6. Reichen vorhandene Verfahren nicht aus, greift mit `RE_IDENTIFY` eine kontrollierte Rückfallebene statt eines unklaren Scheiterns.
7. Über `MANAGE_AUTH_METHODS` kann Nina ihr Konto ausbauen, Verfahren ergänzen und andere bewusst deaktivieren.
8. Mit `LOOKUP_LOGIN` gibt es einen klaren alternativen Einstieg, wenn Gerätebindung gerade nicht passt.
9. Auf der Krankenkassen-Webseite nutzt Nina denselben fachlichen Rahmen, und mit QR kommt ein neues kanalübergreifendes Feature hinzu, das über `CONFIRM_PEER_LOGIN` sauber eingebunden wird.
10. Mit `DELETE_ACCOUNT` und `LOGOUT` endet die Geschichte dort, wo gute Modelle ebenfalls stark sein müssen: bei klaren, sensiblen Abschlussfällen.

## Kernbotschaften zum Nachschlagen

- **Gesamtaussage:** "Wir demonstrieren nicht viele Einzelfälle, sondern ein tragfähiges Modell für
  viele Fälle - neue Anforderungen führen eher zu neuen Regeln als zu neuer Unordnung. Die bekannte
  Lösung bildet den Grundfall ab, skaliert aber schlecht mit Varianten; der Mehrwert hier zeigt
  sich vor allem ab der zweiten und dritten Ausbaustufe."
- **Registrierungs-Experimente:** gleiches Ziel, unterschiedliche und vergleichbare Wege;
  Erfahrungen führen zu Regelanpassungen statt Systembrüchen; zugelassene/deaktivierte Verfahren
  sind eine steuerbare Fachentscheidung.
- **QR als neues Feature:** kein Technik-Gimmick, sondern Ergänzung des vorhandenen Modells - gute
  Vorlage für weitere Cross-Channel-Szenarien zwischen App und Webseite.
- **Fachbeispiele Krankenkasse:** Alltag (Nachrichten, Dokumente, Bearbeitungsstand) vs. sensibel
  (Bankverbindung, Gesundheitsinformationen, leistungsrelevante Änderung) vs. kanalübergreifend
  (Webseite startet, App bestätigt) vs. Governance (Gerät deaktivieren, Verfahren ergänzen/sperren).
- **NIST-Satz:** "Wir trennen fachlich zwischen Identität und aktueller Authentifizierungsstärke -
  das entspricht IAL/AAL, und unser `loa2` ist der fachliche Zielpunkt für ein Niveau wie AAL2."

## Empfehlung für den Termin

- Nutze Phase 1 bis 4 als eigentlichen Live-Hauptpfad.
- Baue Phase 5 bis 9 als bewusste Erweiterungen derselben Geschichte ein, nicht als Themenwechsel.
- Verwende `DELETE_ACCOUNT` und `LOGOUT` eher als kurzer Schlussakzent oder als Reserve.
- Wiederhole am Ende bewusst: "Die Stärke liegt nicht im Happy Path, sondern in der beherrschbaren Vielfalt desselben Modells."
- Sprich im Termin konsequent von Krankenkassen-App, Web-Portal, Versicherten, Servicefällen und schutzbedürftigen Daten statt generisch von Nutzerinnen, Nutzern oder Freigaben.
