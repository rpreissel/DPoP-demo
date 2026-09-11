import '../../App.css'

/**
 * The static landing page (docs/10-frontend.md #1) - no channel logic, no dpop key, nothing that
 * talks to the orchestrator. "Zum App-Kanal"/"Zum Web-Kanal" open the respective app in its own
 * browser tab via a NAMED target (not `_blank`) so a tab already open for that app is reused
 * instead of piling up new ones - deliberately without `rel="noopener"`, which would sever the
 * browsing-context relationship those named-target lookups rely on (same-origin, so the
 * tabnabbing risk that `noopener` normally guards against does not apply here).
 */
export function WelcomeApp() {
  return (
    <div className="app">
      <header className="app-header">
        <h1>Identity Journey</h1>
        <p>Sich ausweisen, anmelden und bei Bedarf zusätzlich absichern (Step-up) zum Ausprobieren.</p>
      </header>

      <div className="card welcome-card">
        <h2>Worum geht es hier?</h2>
        <p>
          Diese Demo zeigt zwei Wege, wie man sich anmeldet und danach ein <strong>AccessToken</strong>{' '}
          bekommt - den eigentlichen Ausweis, mit dem eine Anwendung Sie erkennt. Der{' '}
          <strong>App-Kanal</strong> simuliert eine eigene App: Sie weisen sich einmalig aus
          (Freischaltcode oder eID) und melden sich danach mit SMS, E-Mail, Passwort oder einem
          Geräteschlüssel an - auch mehrere davon kombiniert für ein höheres Sicherheitsniveau. Der{' '}
          <strong>Web-Kanal</strong> simuliert dagegen einen ganz normalen Website-Login über einen
          echten Identitätsanbieter (Keycloak).
        </p>
        <ul className="method-choice-list channel-choice-list">
          <li>
            <a className="method-choice" href="/app/" target="dpop-demo-app-kanal" aria-label="Zum App-Kanal">
              <span className="method-choice-icon" aria-hidden="true">
                📱
              </span>
              <span className="method-choice-text">
                <span className="method-choice-label">Zum App-Kanal</span>
                <span className="method-choice-hint">Nativer, DPoP-gebundener Client - wie eine mobile App</span>
              </span>
            </a>
          </li>
          <li>
            <a className="method-choice" href="/web/" target="dpop-demo-web-kanal" aria-label="Zum Web-Kanal">
              <span className="method-choice-icon" aria-hidden="true">
                🌐
              </span>
              <span className="method-choice-text">
                <span className="method-choice-label">Zum Web-Kanal</span>
                <span className="method-choice-hint">Echter Browser-Client gegen echtes Keycloak</span>
              </span>
            </a>
          </li>
        </ul>
        <ul className="status-list">
          <li>
            <span className="label">Quellcode</span>
            <a className="value" href="https://github.com/rpreissel/DPoP-demo" target="_blank" rel="noreferrer">
              github.com/rpreissel/DPoP-demo
            </a>
          </li>
          <li>
            <span className="label">Dokumentation</span>
            <a className="value" href="https://github.com/rpreissel/DPoP-demo/tree/main/docs" target="_blank" rel="noreferrer">
              docs/ (Domänenmodell, Orchestrierung, API, DPoP, ...)
            </a>
          </li>
          <li>
            <span className="label">Konzepte für Frontend-Entwickler</span>
            <a className="value" href="https://github.com/rpreissel/DPoP-demo/blob/main/docs/pitches/frontend-konzepte.md" target="_blank" rel="noreferrer">
              docs/pitches/frontend-konzepte.md
            </a>
          </li>
          <li>
            <span className="label">Konzepte für Backend-Entwickler</span>
            <a className="value" href="https://github.com/rpreissel/DPoP-demo/blob/main/docs/pitches/backend-konzepte.md" target="_blank" rel="noreferrer">
              docs/pitches/backend-konzepte.md
            </a>
          </li>
        </ul>
      </div>

      <div className="card">
        <h2>Wichtige Begriffe für die Demo</h2>
        <p>
          Ihr <strong>Konto</strong> ist der Zugang, mit dem Sie in dieser Demo angemeldet sind - er
          entsteht bei der Registrierung. Dabei weisen Sie sich einmalig per{' '}
          <strong>Identifikation</strong> aus ("das bin ich": Freischaltcode oder eID) - die Person
          dahinter bleibt vom Konto getrennt gedacht: Identifizieren Sie sich später mit derselben
          Test-Identität erneut, landet die Demo auf demselben Konto statt einem neuen. Für die
          spätere, wiederholte Anmeldung ("ich bin's wieder") dienen dagegen{' '}
          <strong>Anmeldeverfahren</strong>: SMS, E-Mail, Passwort oder ein geräteeigener Schlüssel,
          einzeln oder kombiniert.
        </p>
        <p>
          Wie stark Ihre Identität gerade nachgewiesen ist, drückt das <strong>Sicherheitsniveau</strong>{' '}
          aus. Ein Verfahren reicht oft schon; für empfindlichere Aktionen verlangt die Demo einen
          zusätzlichen Nachweis, den <strong>Step-up</strong> - ohne sich komplett neu anzumelden.
        </p>
        <p>
          Zwei Dinge werden dabei leicht verwechselt, weil beide etwas mit dem Gerät zu tun haben. Dass
          dieses Gerät wiedererkannt wird, beweist nur, <em>welches Gerät</em> gerade spricht - nicht,
          dass davor wirklich die Person sitzt, für die es sich ausgibt ("Automatisch anmelden" nutzt
          das aus, "Neu anmelden" verzichtet bewusst darauf). Ein eigens eingerichteter{' '}
          <strong>Geräteschlüssel</strong> dagegen ist ein echtes Anmeldeverfahren, erst freigeschaltet
          durch System-PIN oder Biometrie - gleichwertig zu SMS, E-Mail oder Passwort.
        </p>
        <p>
          Jeder Ablauf - Login, Registrierung, Sicherheitsniveau erhöhen, Anmeldeverfahren verwalten,
          Konto löschen - wird dabei vollständig vom Backend geführt: Die Oberfläche entscheidet nie
          selbst, was als Nächstes kommt, sie zeigt nur den jeweils aktuellen Schritt an.
        </p>
        <p>
          Ist der App-Kanal angemeldet, lässt sich ein <strong>AccessToken</strong> abrufen -
          der eigentliche Ausweis, mit dem eine echte Anwendung Sie erkennen würde. Der Web-Kanal
          bekommt seins dagegen direkt von Keycloak selbst.
        </p>
        <p>
          Details zu allem oben im{' '}
          <a href="https://github.com/rpreissel/DPoP-demo/blob/main/docs/02-domaenenmodell.md" target="_blank" rel="noreferrer">
            Domänenmodell
          </a>, den Aufbau dahinter erklären die Konzepte-Pitches oben.
        </p>
      </div>
    </div>
  )
}
