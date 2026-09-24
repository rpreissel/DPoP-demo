# ADR-32: Tool-Sperre und Reihenfolge je Kanaltyp

**Entscheidung** (**umgesetzt**): Der Betreiber sperrt Tools nicht mehr global, sondern je
Kanaltyp (App, Web), und legt je Kanaltyp eine Rangfolge der Tools fest. Beides liegt in
`orchestrator.tool_availability` mit dem Schlüssel `(tool_id, channel)`; eine globale Sperre ist
„in beiden Kanälen gesperrt“.

## Warum eine Ebene statt zwei

Eine globale Sperre neben der je Kanal hätte zwei Schalter für dieselbe Frage ergeben, die man in
der Oberfläche und beim Lesen des Codes auseinanderhalten muss. Der seltene Fall „überall sperren“
ist zwei Klicks.

## Eine Rangfolge je Kanal, nicht je Auswahlart

Jede Auswahl eines Kanals übernimmt dieselbe Rangfolge; sie zeigt ohnehin nur die Tools einer
**Rolle** (`MethodRole`): Identifizieren, Person im Personenverzeichnis zuordnen, Einrichten, Anmelden bei
bekanntem Konto, Anmelden über eine E-Mail-Adresse (`*-lookup`), E-Mail bestätigen, Web-Login
bestätigen - in dieser Reihenfolge zeigt die Admin-Seite die Gruppen, so wie ein Nutzer ihnen
begegnet. Zwischen Rollen zu priorisieren hätte keine Wirkung. Die Admin-Seite
gruppiert deshalb je Kanal nach Rolle, und ▲/▼ tauschen nur innerhalb einer Gruppe; gespeichert
wird trotzdem eine Liste je Kanal. Nicht eingeordnete Tools stehen hinter den eingeordneten, in
der Standard-Reihenfolge (Rolle, dann Verfahren), die auch die Admin-Seite anzeigt - vorher war
es die zufällige Registrierungsreihenfolge der Spring-Beans.

## Sortiert wird beim Ausliefern

`JourneyRouting.stepFor` sortiert die Optionen, nicht die Kandidatenermittlung. Das Angebot einer
Journey (`Offer.offered`) ist für ihre Lebensdauer eingefroren (docs/04-orchestrierung.md); dort
sortiert, würde eine geänderte Reihenfolge erst für neue Journeys gelten. So gilt sie ab dem
nächsten Bildschirm, wie die Sperre auch.

## Verhältnis zur Client-Deklaration

Unverändert: Welche Tools ein Client darstellen kann, erklärt er selbst (`availableTools`). Die
Betreiber-Sperre ist eine zweite, unabhängige Achse. Beide werden geschnitten.

## Folgen

- Der Kanaltyp ist dafür als `orchestrator.kernel.ChannelType` ins gemeinsame Vokabular gewandert
  (vorher `ChannelSession.Channel`); `tool` hätte sonst von `session` abgehangen, ein Paketzyklus.
- `V3__orchestrator.sql` in place umgeschrieben (ADR-30).

## Voreinstellung

Die Demo bringt eine Voreinstellung je Kanal mit (`demo.tool-defaults` in `application.yml`):
Reihenfolge und Sperren, übernommen aus einer auf der Admin-Seite eingestellten Konfiguration.
`ToolDefaultsInitializer` wendet sie beim Start an, wenn noch keine Tool-Einstellung existiert - bei
einer persistenten Datenbank überleben eigene Änderungen also einen Neustart. „Demo zurücksetzen“
stellt genau diese Voreinstellung wieder her.
