# Überblick

Die tragenden Konzepte in Kurzform. Jeder Abschnitt verweist auf das Dokument, das ihn ausführt.

---

## Drei Session-Ebenen

Der Kern des Modells sind drei ineinander geschachtelte Sessions mit fallender Lebensdauer:

| Ebene | Steht für | Lebensdauer |
|---|---|---|
| `ChannelSession` | Der Kanal (App oder Web), DPoP-gebunden | langlebig, überdauert einzelne Verfahren |
| `AuthJourney` | Ein Durchlauf eines `AuthIntent`: eine geführte Wegstrecke mit einem Ziel | so lange die Journey läuft |
| `ToolSession` | Ein einzelner Tool-Durchlauf, z. B. die TAN-Eingabe | kurz, oft Minuten |

Eine Journey durchläuft dabei beliebig viele Tools: Der Weg über die Identifizierung ist etwa
`ident-fsc` -> `enroll-sms`; verlangt der Kanal ein Mehr-Faktor-Niveau, kommen weitere Schritte
hinzu, bis die Policy erfüllt ist. Welche Verfahren in welcher Reihenfolge angeboten werden,
entscheidet der Intent ([04-orchestrierung.md](04-orchestrierung.md)).

Details: [02-domaenenmodell.md](02-domaenenmodell.md)

---

## Tools beschreiben sich selbst

Jedes Verfahren ist ein *Tool* mit einem flachen Bezeichner (`toolId`) wie `enroll-sms`.
Die Module bringen ihre Beschreibung selbst mit — Kategorie, Methode, Faktorart und maximal
erreichbares Sicherheitsniveau. Es gibt keine zentral gepflegte Liste, die man beim Hinzufügen
eines Verfahrens vergessen könnte.

Über die Modulgrenze geht ausschließlich ein `ToolOutcome`: läuft noch, abgeschlossen oder
fehlgeschlagen. Fachdaten wie TAN oder KVNR verlassen das Modul nie.

Details: [03-tool-architektur.md](03-tool-architektur.md)

---

## Der Client folgt `next`, er entscheidet nicht

Jede Antwort enthält ein `next`-Objekt — eine reine Adresse, entweder auf ein konkretes Tool
oder auf eine Flow-Seite (Auswahl, Abschluss). Der Client bildet `next` über eine feste
Routing-Tabelle auf einen Endpunkt ab. Er leitet nichts aus URLs ab, konstruiert keine
`toolId` selbst und entscheidet nie, welches Verfahren als Nächstes kommt.

Alles, was ein Schritt zum Anzeigen braucht — fehlende Felder, Auswahloptionen, Fehlergründe —
steht in `stepData`.

Details: [05-api.md](05-api.md)

---

## Der Orchestrator entscheidet, die Module liefern zu

Nach jedem abgeschlossenen Tool entscheidet der Orchestrator, wie es weitergeht: Er verarbeitet
das Ergebnis kategoriespezifisch (Account anlegen, Methode registrieren, Nachweis übernehmen)
und fragt dann die `AuthPolicy`, ob genug Faktoren erbracht sind. Erst danach steht der nächste
Schritt fest.

Die Policy ist auch die einzige Stelle, die weiß, was eine *Kombination* von Nachweisen bedeutet.
Ein Modul kennt nur sich selbst.

Details: [04-orchestrierung.md](04-orchestrierung.md)

---

## Zwei Kanäle, ein Tool-API

### App (Orchestrator-first)

1. App sendet Request mit DPoP -> Backend erstellt/liest `ChannelSession(APP)`.
2. Backend startet eine `AuthJourney` mit dem Intent des Kanals (Default `FAST`).
3. Orchestrator bietet die Verfahren an, die der aktuelle Zustand der Journey zulässt (FSC/SMS/eID).
4. Bei Erfolg erzeugt Backend den `AuthContext` (Keycloak-Tokenfluss serverseitig).
5. `ChannelSession.state` wechselt auf `AUTHENTICATED`.
6. Braucht die App ein höheres Niveau, hebt sie es per `PATCH /app/channels/{channelSessionId}` mit `requiredAcr` an ([05-api.md](05-api.md), App-Fassade Beispiel 9). Alternativ ist die Untergrenze schon beim Anlegen des Kanals gesetzt.
7. Backend vergleicht die Forderung mit `currentAcr`/`currentAmr` aus dem `AuthContext`. Reicht es nicht: `STEP_UP_REQUIRED` -> neue `AuthJourney(STEP_UP)`, und die Antwort enthält direkt den fälligen `next`-Schritt.

### Web (Keycloak-first)

1. Browser/BFF hat Keycloak-Session.
2. Keycloak-Authenticator startet bei Bedarf Step-up beim Orchestrator.
3. Backend erstellt eine `AuthJourney(STEP_UP)` und referenziert die `ChannelSession(WEB)`.
4. Nach fachlichem Erfolg aktualisiert Keycloak den IAM-Kontext.
5. Backend synchronisiert `AuthContext.currentAcr/currentAmr`.
6. `ChannelSession.state` bleibt oder wird wieder `AUTHENTICATED`.

Beide Kanäle nutzen nach dem Start dieselben kanalneutralen Tool-URLs. Nur der Einstieg
unterscheidet sich: Die App legt einen Kanal an, Keycloak startet eine Journey.

Details: [05-api.md](05-api.md)

---

## Sicherheitsniveaus werden dreifach gedeckelt

Ein Account kann nie mehr Vertrauen erzeugen, als bei seiner Identifikation festgestellt wurde;
eine Methode nie mehr, als bei ihrer Einrichtung vorhanden war; ein Durchlauf nie mehr, als das
Verfahren technisch trägt. Diese Kette verhindert, dass sich über eine in schwacher Session
hinterlegte Methode dauerhaft ein höheres Niveau erschleichen lässt.

Details: [04-orchestrierung.md](04-orchestrierung.md)
