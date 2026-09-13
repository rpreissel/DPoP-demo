# Moderationsskript (90 Minuten)

Dieses Skript ist als Leitfaden für den Termin gedacht. Pro Abschnitt: Ziel, Moderation,
Übergang, erwartete Frage.

## 00-05 min: Begrüßung und Ziel

- Aussage: "Wir schauen heute auf den fachlichen Nutzen und die technische Tragfähigkeit für eine Krankenkassen-App und eine Krankenkassen-Webseite in einem gemeinsamen Bild."
- Ziel klären: Was sollen Fachexpertinnen/Fachexperten und Frontend-Kolleginnen/Kollegen jeweils mitnehmen?
- Erwartung setzen: "Die Demo ist nicht Selbstzweck. Uns interessiert, ob dieses Muster unsere künftige Entwicklung besser macht."
- Optionaler Auftakt: "Im Mittelpunkt steht heute nicht ein einzelner Login, sondern die Frage, wie wir Versicherten-Services, sensible Daten und mehrere Kanäle gemeinsam beherrschbar halten."
- Kontrast direkt setzen: "Die Fachfälle kennt ihr bereits. Spannend ist heute, warum dieselben Fälle in einem flexibleren Modell deutlich besser weiterentwickelbar werden."
- Übergang: "Starten wir mit dem Problem, das wir lösen wollen."

## 05-20 min: Warum dieses Zielbild?

- Leitfrage: Wie schaffen wir fachlich steuerbare Nutzerreisen statt einzelner Login-Masken?
- Fokus: Journey-Logik, Entscheidungsstellen, Rollen von Fachseite und Technik.
- Inhaltsanker: Versichertenreise, Regelpunkte, Sicherheitsniveau, Ausnahmefälle, App plus Web.
- Wirkungsziel: Das Publikum soll erkennen, dass hier ein Lösungsprinzip vorgestellt wird, nicht nur ein einzelner Flow.
- Konkretisierung für Krankenkassen: Zugang zu Alltagsservices soll leicht bleiben, Vorgänge mit Gesundheits- oder Kontodaten aber gezielt höhere Sicherheit verlangen.
- Moderator-Hinweis: Die starre Lösung nicht fachlich abwerten, sondern ihren Nachteil in Änderungsaufwand, Variantenfähigkeit und Transparenz benennen.
- Übergang: "Wir sehen das jetzt live im App-Kanal."

## 20-35 min: Demo-Block 1

- Moderator-Hinweis: Erst Ergebnis zeigen, dann erklären, dann Nutzen einordnen.
- Schlüsselsatz: "Das Frontend folgt dem nächsten Schritt (`next`) statt selbst zu raten."
- Inhaltsanker: Wiedereinstieg in die Krankenkassen-App, Journey-Log als Kommunikationshilfe, Variantenwechsel im Registrierungsprozess.
- Wirkungsziel: Die Reise soll als verständlich und beherrschbar wahrgenommen werden, nicht als technisch verspielt.
- Konkretes Framing: neues Smartphone, Versicherte will wieder in App und ihre alltäglichen Services nutzen.
- Kontrastsatz nach 2-3 Minuten: "Der Fall selbst ist nicht neu. Neu ist, dass Varianten hier nicht als Sonderlogik anwachsen, sondern im selben Modell geführt werden."
- Übergang: "Der eigentliche Wert zeigt sich aber nicht nur im Ablauf, sondern im Nutzen für spätere Weiterentwicklung."

## 35-50 min: Fachlicher und organisatorischer Nutzen

- Fokus: Kosten von Sonderlogik, Wert von Steuerbarkeit, Nutzen für Fachseite und Delivery.
- Schlüsselsatz: "Wir investieren hier nicht nur in Funktionalität, sondern in spätere Änderbarkeit."
- Inhaltsanker: geringere Kopplung, klarere Regeln, bessere Abstimmbarkeit, weniger Umbauaufwand, sauberere Trennung zwischen Alltagsservice und sensiblen Vorgängen.
- Wirkungsziel: Klar machen, dass dieses Vorgehen nicht technikverliebt, sondern wirtschaftlich sinnvoll ist.
- Management-Linie: Gerade in einer Krankenkasse steigen die Kosten mit jeder Ausnahme, jedem zusätzlichen Kanal und jedem neuen Schutzbedarf.
- Zuspitzung für Fachexperten: "Die eigentliche Schwäche der starren Lösung liegt nicht im ersten Release, sondern in jedem späteren Änderungswunsch."
- Übergang: "Jetzt zeigen wir Sicherheitsniveau und Verfahren im laufenden Betrieb."

## 50-60 min: Demo-Block 2

- Fokus: App-Nutzung -> sensible Aktion -> Step-up -> Methodenverwaltung.
- Schlüsselsatz: "Höheres Risiko erzwingt höhere Sicherheit, aber nur wenn nötig."
- Inhaltsanker: Was triggert Step-up bei Gesundheits- oder Kontodaten, welche Folgen hat Methodenverwaltung für reale Versichertenprozesse?
- Wirkungsziel: Das Publikum soll sehen, dass Sicherheit differenziert steuerbar ist statt pauschal schwerfällig.
- NIST-Hinweis: hier kurz IAL und AAL einordnen, ohne in Sicherheitsvortrag abzurutschen.
- Übergang: "Damit das nicht nur fachlich gut klingt, braucht es eine Struktur, die Änderungen auch tatsächlich günstig macht."

## 60-70 min: Architektur als Enabler

- Fokus: Orchestrator, Methodenmodule, SPI, klare Rollen.
- Schlüsselsatz: "Die technische Struktur ist der Grund, warum neue Anforderungen nicht jedes Mal alles berühren müssen."
- Inhaltsanker: verifizierbare Modulgrenzen, additive Erweiterungen, geringere Seiteneffekte, saubere Erweiterbarkeit für neue Verfahren wie QR.
- Wirkungsziel: Die Architektur als praktischen Enabler statt als Folienarchitektur vermitteln.
- Übergang: "Was bedeutet dieses Modell konkret für die Frontend-Arbeit?"

## 70-80 min: Frontend-Learnings und Delivery-Vorteile

- Fokus: Drei Apps, klare Verantwortungen, Navigation über `next`.
- Schlüsselsatz: "Erweiterungen bleiben klein, weil Integrationspunkte klar sind."
- Inhaltsanker: Wiederverwendung, Testbarkeit, Auswirkungen auf Delivery-Geschwindigkeit, saubere Aufteilung zwischen Krankenkassen-App und Krankenkassen-Webseite.
- Wirkungsziel: Dem Frontend-Team ein klares Bild geben, warum dieses Modell ihre Arbeit leichter statt schwerer macht.
- Übergang: "Lasst uns den Mehrwert für unsere nächsten Vorhaben sammeln."

## 80-88 min: Transfer auf eigene Vorhaben

- Frage 1: Welche zwei Versichertenprozesse könnten wir mit diesem Muster zuerst pilotieren?
- Frage 2: Wo erwarten wir in App oder Webseite den größten Entwicklungshebel?
- Frage 3: Welche heutige Komplexität rund um Zugang, Service und sensible Daten könnten wir mit diesem Modell gezielt abbauen?
- Frage 4: Welche kanalübergreifenden Fälle zwischen Krankenkassen-App und Krankenkassen-Webseite wären damit erstmals sauber beherrschbar?
- Ergebnis festhalten: 2-3 priorisierte Übernahmekandidaten.

## 88-90 min: Abschluss

- Zusammenfassung in drei Sätzen: fachlicher Nutzen, technische Tragfähigkeit, nächster Schritt.
- Verbindliche Nacharbeit festlegen (Owner + Termin für Folgeschritt).
- Optional: Einladung zu kurzem Deep-Dive mit interessierten Entwicklern.
- Schlusslinie: "Wenn wir dieses Muster übernehmen, investieren wir nicht nur in sicheren Zugang, sondern in beherrschbare Weiterentwicklung unserer digitalen Krankenkassen-Services."

## Formulierungen für mehr Überzeugungskraft

- "Die Stärke dieser Lösung zeigt sich nicht im Happy Path, sondern in der sauberen Beherrschung von Varianten und Änderungen."
- "Wir kaufen hier nicht nur Funktionalität, sondern spätere Änderbarkeit."
- "Der Gewinn liegt darin, dass neue Anforderungen nicht automatisch neue Unordnung erzeugen."
- "Wenn wir dieses Muster übernehmen, investieren wir in geringere Komplexitätskosten der nächsten Ausbaustufen."
- "Gerade in einer Krankenkasse müssen Alltagsservice und Schutzbedarf gleichzeitig funktionieren; genau dafür ist dieses Modell stark."
- "Die Fachlichkeit ist bekannt. Der Mehrwert liegt darin, dass wir bekannte Fachlichkeit nicht weiter in starre Sonderfälle zerlegen müssen."

---

## Moderations-Tipps für Störfälle

- Bei Demo-Ausfall: sofort auf vorbereiteten Screenshot-/Ablaufpfad umschalten, nicht debuggen.
- Bei Detaildiskussion: Frage parken, Zeitpunkt für Vertiefung benennen.
- Bei Zeitdruck: Demo 3 vollständig streichen und den Transfer-Block priorisieren.
