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
        Diese Demo zeigt eine Anmeldung, die an <strong>dieses Gerät</strong> gebunden ist (DPoP): statt
        eines Passworts allein beweist Ihr Browser den Besitz eines Schlüssels, der nur hier existiert.
        Zusätzliche Nachweise - SMS, E-Mail, Passwort, Geräte-PIN - lassen sich kombinieren, um ein
        höheres Sicherheitsniveau zu erreichen (Step-up), ganz ohne erneuten Login.
      </p>
      <p className="welcome-cta">👇 Wählen Sie unten, wie Sie starten möchten - es passiert nichts automatisch.</p>
    </div>
  )
}
