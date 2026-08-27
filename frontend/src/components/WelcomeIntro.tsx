/**
 * Plain-language orientation for a first-time visitor - shown only before any channel exists
 * (App.tsx), gone once the actual flow starts so it never competes with a real step. Everything
 * technical (DPoP, ACR, AMR, toolId) is explained once, here, in an approachable way instead of
 * being assumed knowledge on every later screen.
 */
export function WelcomeIntro() {
  return (
    <div className="card welcome-card">
      <h2>Worum geht es hier?</h2>
      <p>
        Jede Anfrage in dieser Demo ist kryptografisch an <strong>dieses Gerät</strong> gebunden (DPoP) - das
        schützt vor gestohlenen Tokens und lässt ein wiederkehrendes Gerät automatisch erkennen. Ihre
        Identität selbst weisen Sie klassisch nach: per SMS, E-Mail, Passwort oder einem geräteeigenen
        Schlüssel, einzeln oder kombiniert für ein höheres Sicherheitsniveau (Step-up).
      </p>
      <p className="welcome-cta">👇 Wählen Sie unten, wie Sie starten möchten - es passiert nichts automatisch.</p>
    </div>
  )
}
