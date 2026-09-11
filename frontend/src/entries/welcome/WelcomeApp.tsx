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
        <p>
          Identifikation, Authentifizierung und Step-up zum Ausprobieren - mehrere Verfahren, deren
          Ablauf das Backend als Journey steuert.
        </p>
      </header>

      <div className="card welcome-card">
        <h2>Worum geht es hier?</h2>
        <p>
          Diese Demo zeigt zwei Wege, wie ein Client Identität nachweist und ein AccessToken bekommt.
          Der <strong>App-Kanal</strong> ist ein DPoP-gebundener nativer Client: Identifikation
          (Freischaltcode/eID) oder Authentifizierung per SMS, E-Mail, Passwort oder Geräteschlüssel,
          einzeln oder kombiniert für höheres Sicherheitsniveau (Step-up) - der Orchestrator steuert
          den Ablauf serverseitig als Journey und liefert das AccessToken selbst (Mock oder echt, je
          nach Profil). Der <strong>Web-Kanal</strong> ist ein echter Browser-Client gegen echtes
          Keycloak: Login per Redirect (loa1/loa2), AccessToken/IdToken direkt von Keycloak, Step-up
          und Logout ebenfalls dort - der Browser spricht nie direkt mit dem Orchestrator (nur
          Keycloaks eigene Erweiterung, server-seitig).
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
          Ihr <strong>Konto</strong> (technisch ein <code>Account</code>) ist der Zugang, mit dem Sie in
          dieser Demo angemeldet sind - er entsteht bei der Registrierung. Dabei
          weisen Sie sich einmalig per <strong>Identifikation</strong> aus ("das bin ich": Freischaltcode
          oder eID) gegen ein <strong>externes Personenregister</strong> - die <strong>Person</strong>{' '}
          selbst kommt aus diesem fremden System und gehört nicht dem Konto; das Konto verweist nur
          darauf. Identifizieren Sie sich später mit derselben Test-Identität erneut, findet die Demo
          dieselbe Person wieder und landet auf demselben Konto statt einem neuen - deshalb bleibt die
          Person beim Löschen eines Kontos auch unangetastet. Für die spätere, wiederholte Anmeldung
          ("ich bin's wieder") dienen dagegen <strong>Anmeldeverfahren</strong>: SMS, E-Mail, Passwort
          oder ein geräteeigener Schlüssel, einzeln oder kombiniert.
        </p>
        <p>
          Wie stark Ihre Identität gerade nachgewiesen ist, drückt das <strong>Sicherheitsniveau</strong>{' '}
          aus (in der Demo <code>loa1</code>/<code>loa2</code> genannt). Ein Verfahren reicht oft schon;
          für empfindlichere Aktionen verlangt die Demo einen zusätzlichen Nachweis, den{' '}
          <strong>Step-up</strong> - ohne sich komplett neu anzumelden.
        </p>
        <p>
          Zwei Dinge werden dabei leicht verwechselt, weil beide "Gerät" im Namen tragen. Die{' '}
          <strong>DPoP-Bindung</strong> beweist nur <em>welches Gerät</em> gerade spricht - niemals, dass
          der Nutzer davor tatsächlich der ist, für den er sich ausgibt. Sie bindet jede Anfrage
          kryptografisch an dieses Gerät und lässt ein bereits bekanntes Gerät automatisch wiedererkennen
          ("Automatisch anmelden" schlägt dann direkt den zuletzt genutzten Login vor, "Neu anmelden"
          verzichtet bewusst darauf) - bleibt dabei aber reine Wiedererkennung, kein Identitätsnachweis.
          Der <strong>Geräteschlüssel</strong> als
          Anmeldeverfahren ("device") ist dagegen ein echter Identitätsnachweis: ein nicht extrahierbarer,
          geräteeigener Schlüssel, den erst ein System-PIN oder Biometrie freischaltet - das zählt als
          vollwertiges Verfahren wie SMS/E-Mail/Passwort.
        </p>

        <div className="nesting-diagram">
          <div className="nesting-box nesting-box--1">
            <span className="nesting-label">Channel <em>(Sitzung, dieses Gerät)</em></span>
            <div className="nesting-box nesting-box--2">
              <span className="nesting-label">Journey <em>(ein Ziel, z. B. Anmelden)</em></span>
              <div className="nesting-box nesting-box--3">
                <span className="nesting-label">Tool <em>(Verfahren, z. B. auth-sms)</em></span>
              </div>
            </div>
          </div>
        </div>
        <p>
          Ein <strong>Channel</strong> ist die Verbindung zwischen App und Backend für diesen Besuch,
          verankert am DPoP-Schlüssel dieses Geräts (im Demo-Tab als "Sitzung" angezeigt); innerhalb
          läuft eine <strong>Journey</strong> - der vom
          Backend geführte Ablauf für genau ein Ziel, nicht die Oberfläche entscheidet den nächsten
          Schritt. Eine Journey besteht wiederum aus einem oder mehreren <strong>Tools</strong>, dem
          konkreten Verfahren, das gerade dran ist - benannt danach, ob es ein Verfahren einrichtet
          (<code>enroll-sms</code>) oder ein bereits eingerichtetes benutzt (<code>auth-sms</code>).
          Channel, Journey und Tool sind also ineinander geschachtelt, keine Kette von Vorher/Nachher.
          Ein Tool wird der Journey dabei nur angeboten, wenn es <strong>beide</strong> Seiten erlauben:
          das Frontend muss es überhaupt darstellen können, und das Backend darf es nicht gesperrt haben -
          beides einzeln einstellbar unter Einstellungen ("Verfügbare Tools auf diesem Client" bzw.
          "Admin: Tool-Verfügbarkeit").
        </p>
        <p>
          Welches Ziel eine Journey verfolgt, sehen Sie an ihren Aktionen im Demo-Tab: <strong>Login</strong>{' '}
          (Registrieren, Automatisch anmelden oder Neu anmelden - alle drei Wege führen zum selben Ziel:
          einem angemeldeten Channel mit Zugang zum AccessToken), <strong>Step-up</strong>{' '}
          (Sicherheitsniveau erhöhen, ohne sich neu anzumelden), <strong>Manage</strong> (weiteres
          Verfahren einrichten oder eines deaktivieren) und <strong>Konto löschen</strong> sind je eigene
          Journeys mit eigenem Ziel.
        </p>
        <p>
          Ist ein App-Kanal-Channel angemeldet, lässt sich ein <strong>AccessToken</strong> und ein{' '}
          <strong>RefreshToken</strong> abrufen - je nach Backend-Profil ein Mock-JWT oder (Profil{' '}
          <code>keycloak</code>) ein echter, von Keycloak signierter Token. Das AccessToken geht ins
          Frontend; das RefreshToken verlässt das Backend nie und wird dort im Hintergrund genutzt, um
          bei Bedarf ein neues AccessToken zu holen, ohne dass Sie sich erneut anmelden müssen. Der
          Web-Kanal bekommt seine Tokens dagegen direkt von Keycloak selbst, siehe dessen eigenen Tab.
        </p>
        <p>
          Details zu allem oben im{' '}
          <a href="https://github.com/rpreissel/DPoP-demo/blob/main/docs/02-domaenenmodell.md" target="_blank" rel="noreferrer">
            Domänenmodell
          </a>.
        </p>
      </div>
    </div>
  )
}
