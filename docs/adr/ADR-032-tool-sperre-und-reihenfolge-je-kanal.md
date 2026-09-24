# ADR-32: Tool-Sperre und Reihenfolge je Kanaltyp

**Entscheidung** (**umgesetzt**): Der Betreiber sperrt Tools nicht mehr für alle Kanäle zugleich,
sondern je Kanaltyp (App, Web), und legt je Kanaltyp eine Rangfolge der Tools fest. Beides liegt in
`orchestrator.tool_availability` mit dem Schlüssel `(tool_id, channel)`; eine Sperre für alle
Kanäle bedeutet „in beiden Kanälen gesperrt“.

## Warum eine Ebene statt zwei

Eine Sperre für alle Kanäle neben der je Kanal hätte zwei Schalter für dieselbe Frage ergeben, die man in
der Oberfläche und beim Lesen des Codes auseinanderhalten muss. Der seltene Fall „überall sperren“
ist zwei Klicks.

## Eine Rangfolge je Kanal, nicht je Auswahlart

Jede Auswahl in einem Kanal übernimmt dieselbe Rangfolge. Sie zeigt ohnehin nur die Tools einer
**Rolle** (`MethodRole`): Identifizieren, Person im Personenverzeichnis zuordnen, Einrichten, Anmelden
bei bekanntem Konto, Anmelden über eine E-Mail-Adresse (`*-lookup`), E-Mail bestätigen,
Anmeldung auf der Website bestätigen. In dieser Reihenfolge zeigt die Admin-Seite die Gruppen, so wie
ein Nutzer ihnen begegnet. Eine Rangfolge zwischen den Rollen hätte keine Wirkung. Die Admin-Seite
gruppiert deshalb je Kanal nach Rolle, und ▲/▼ tauschen nur innerhalb einer Gruppe; gespeichert
wird trotzdem eine Liste je Kanal. Nicht eingeordnete Tools stehen hinter den eingeordneten, in
der voreingestellten Reihenfolge (Rolle, dann Verfahren), die auch die Admin-Seite anzeigt. Vorher
war es die zufällige Reihenfolge, in der Spring die Beans registriert.

## Sortiert wird beim Ausliefern

Die Optionen sortiert `JourneyRouting.stepFor`, nicht die Auswahl der Kandidaten. Das Angebot einer
Journey (`Offer.offered`) ist für ihre Lebensdauer eingefroren (docs/04-orchestrierung.md); Würde
dort sortiert, gälte eine geänderte Reihenfolge erst für neue Journeys. So gilt sie ab dem nächsten
Bildschirm, genau wie die Sperre.

## Verhältnis zur Client-Deklaration

Unverändert gilt: Welche Tools ein Client darstellen kann, gibt er selbst an (`availableTools`). Die
Sperre durch den Betreiber ist eine zweite, davon unabhängige Ebene. Angeboten wird nur, was beide
zulassen.

## Folgen

- Der Kanaltyp ist dafür als `orchestrator.kernel.ChannelType` zu den gemeinsamen Begriffen gewandert
  (vorher `ChannelSession.Channel`). Sonst hätte `tool` von `session` abgehangen, und es wäre ein
  Zyklus zwischen den Paketen entstanden.
- `V3__orchestrator.sql` wurde direkt geändert (ADR-30).

## Voreinstellung

Die Demo bringt eine Voreinstellung je Kanal mit (`demo.tool-defaults` in `application.yml`):
Reihenfolge und Sperren, übernommen aus einer auf der Admin-Seite eingestellten Konfiguration.
`ToolDefaultsInitializer` wendet sie beim Start an, wenn es noch keine Einstellungen für die Tools
gibt. Bei einer dauerhaft gespeicherten Datenbank überstehen eigene Änderungen also einen Neustart. „Demo zurücksetzen“
stellt genau diese Voreinstellung wieder her.
