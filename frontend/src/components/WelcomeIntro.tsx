import { JourneyDiagram } from './JourneyDiagram'

/**
 * Plain-language orientation for a first-time visitor - shown only before any channel exists
 * (App.tsx), gone once the actual flow starts so it never competes with a real step. Everything
 * technical (DPoP, ACR, AMR, toolId) is explained once, here, in an approachable way instead of
 * being assumed knowledge on every later screen.
 *
 * The three diagrams are a representative SHAPE, not a spec - the backend decides the real order
 * case by case (docs/04-orchestrierung.md); e.g. registration can chain more than one factor
 * before email confirmation, and "Verbinden (automatisch)" falls through to identification on an
 * unlinked device. Kept deliberately simple (no branching) to match what a first glance needs.
 */
export function WelcomeIntro() {
  return (
    <div className="card welcome-card">
      <h2>Worum geht es hier?</h2>
      <p>
        Jede Anfrage in dieser Demo ist kryptografisch an <strong>dieses Gerät</strong> gebunden (DPoP) - das
        schützt vor gestohlenen Tokens und lässt ein wiederkehrendes Gerät automatisch erkennen. Ihre
        Identität selbst weisen Sie klassisch nach: durch Identifikation (Freischaltcode) oder
        Authentifizierung per SMS, E-Mail, Passwort oder einem geräteeigenen Schlüssel - einzeln oder
        kombiniert für ein höheres Sicherheitsniveau (Step-up).
      </p>
      <p>
        Welche Verfahren dabei zur Wahl stehen und in welcher Reihenfolge, entscheidet nicht diese
        Oberfläche, sondern das Backend anhand des jeweiligen Vorgangs (Registrierung, Login, Step-up,
        Verwaltung) - jeder dieser Vorgänge läuft als eigene, serverseitig gesteuerte Journey.
      </p>
      <div className="journey-diagram-list">
        <JourneyDiagram title="Verbinden (automatisch)" steps={['Gerät erkannt?', 'Faktor bestätigen', 'Fertig']} />
        <JourneyDiagram title="Neuen Account registrieren" steps={['Identifikation', '2. Faktor einrichten', 'E-Mail bestätigen', 'Fertig']} />
        <JourneyDiagram title="Login ohne DPoP" steps={['E-Mail + Code/Passwort', 'Gerät merken? (optional)', 'Fertig']} />
      </div>
      <p className="welcome-cta">👇 Wählen Sie unten, wie Sie starten möchten - es passiert nichts automatisch.</p>
    </div>
  )
}
