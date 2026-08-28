/**
 * Plain-language orientation for a first-time visitor - shown only before any channel exists
 * (App.tsx), gone once the actual flow starts so it never competes with a real step. Everything
 * technical (DPoP, ACR, AMR, toolId) is explained once, here, in an approachable way instead of
 * being assumed knowledge on every later screen. The per-journey diagrams live as hover previews
 * on the choice cards themselves (App.tsx, DiagramHint) rather than here - permanently occupying
 * space next to text most visitors only read once.
 */
export function WelcomeIntro() {
  return (
    <div className="card welcome-card">
      <h2>Worum geht es hier?</h2>
      <p>
        Jede Anfrage in dieser Demo ist kryptografisch an <strong>dieses Gerät</strong> gebunden (DPoP) - das
        schützt vor gestohlenen Tokens und lässt ein wiederkehrendes Gerät automatisch erkennen. Ihre
        Identität selbst weisen Sie klassisch nach: durch Identifikation (Freischaltcode oder eID) oder
        Authentifizierung per SMS, E-Mail, Passwort oder einem geräteeigenen Schlüssel - einzeln oder
        kombiniert für ein höheres Sicherheitsniveau (Step-up).
      </p>
      <p>
        Welche Verfahren dabei zur Wahl stehen und in welcher Reihenfolge, entscheidet nicht diese
        Oberfläche, sondern das Backend anhand des jeweiligen Vorgangs (Registrierung, Login, Step-up,
        Verwaltung) - jeder dieser Vorgänge läuft als eigene, serverseitig gesteuerte Journey.
      </p>
      <p className="welcome-cta">
        👇 Wählen Sie unten, wie Sie starten möchten - es passiert nichts automatisch. Beim Zeigen auf eine
        Karte sehen Sie deren Ablauf als Diagramm.
      </p>
    </div>
  )
}
