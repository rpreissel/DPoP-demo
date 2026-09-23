import { useEffect, useState } from 'react'
import '../../App.css'
import { describeError, fetchServerInfo, type ServerInfo } from '../../api'
import { markAsStartWindow } from '../../startWindow'
import { useHashTab } from '../../useHashTab'

const TAB_KEYS = ['uebersicht', 'begriffe', 'status'] as const
type Tab = (typeof TAB_KEYS)[number]
const TABS: { key: Tab; label: string }[] = [
  { key: 'uebersicht', label: 'Übersicht' },
  { key: 'begriffe', label: 'Begriffe & Doku' },
  { key: 'status', label: 'Server-Status' },
]

/**
 * The landing page (docs/10-frontend.md #0) - no channel logic, no dpop key. The only thing it
 * reads from the orchestrator is the public server status (its own tab); every switch lives on
 * the admin page. The tiles open each app in its own browser tab via a NAMED target (not
 * `_blank`) so a tab already open for that app is reused instead of piling up new ones -
 * deliberately without `rel="noopener"`, which would sever the browsing-context relationship
 * those named-target lookups rely on (same-origin, so the tabnabbing risk that `noopener`
 * normally guards against does not apply here).
 */
export function WelcomeApp() {
  const [tab, setTab] = useHashTab<Tab>(TAB_KEYS, 'uebersicht')
  // Whether the Web channel exists at all (only with the `keycloak` profile) - null while loading,
  // then the tile stays a link until we know otherwise.
  const [keycloak, setKeycloak] = useState<boolean | null>(null)

  // This tab is where "← Startseite" in every app tab switches back to (startWindow.ts).
  useEffect(markAsStartWindow, [])

  useEffect(() => {
    fetchServerInfo()
      .then((info) => setKeycloak(info.keycloakProfile))
      .catch(() => setKeycloak(null))
  }, [])

  return (
    <div className="app">
      <header className="app-header">
        <h1>Identity Journey</h1>
        <p>Sich ausweisen, anmelden und bei Bedarf zusätzlich absichern (Step-up) zum Ausprobieren.</p>
      </header>

      <div className="app-tabs" role="tablist">
        {TABS.map((t) => (
          <button key={t.key} role="tab" aria-selected={tab === t.key} className={tab === t.key ? 'active' : ''} onClick={() => setTab(t.key)}>
            {t.label}
          </button>
        ))}
      </div>

      {tab === 'uebersicht' && (
        <div className="card welcome-card">
          <h2>Worum geht es hier?</h2>
          <p>
            Ein <strong>Identitätsdienst</strong> (der Orchestrator) bedient zwei Zugänge zu denselben
            Konten: eine <strong>Smartphone-App</strong> und eine <strong>Website</strong>. Man weist sich
            einmal aus - per Freischaltcode aus einem Brief oder per eID - und meldet sich danach mit SMS,
            E-Mail, Passwort oder einem Geräteschlüssel an, bei Bedarf mit zweitem Faktor (Step-up).
            Am Ende steht ein <strong>AccessToken</strong>: der Ausweis, mit dem eine Anwendung Sie
            erkennt.
          </p>
          <p>
            Jeder Browser-Tab dieser Demo spielt eine Rolle: das Smartphone, die Website, das externe
            Register, den Betreiber. Welche das ist, steht auf jeder Kachel.
          </p>
          <ul className="method-choice-list channel-choice-list">
            <li>
              <a className="method-choice" href="/app/" target="dpop-demo-app-kanal" aria-label="Zum App-Kanal">
                <span className="method-choice-icon" aria-hidden="true">
                  📱
                </span>
                <span className="method-choice-text">
                  <span className="method-choice-label">App-Kanal - spielt das Smartphone</span>
                  <span className="method-choice-hint">
                    Der Tab ist die App auf dem Handy: Er erzeugt einen Geräteschlüssel und signiert jede Anfrage
                    damit (DPoP), damit ein abgefangenes Token auf einem anderen Gerät nichts nützt. Registrieren,
                    Anmelden, Verfahren verwalten, Step-up.
                  </span>
                </span>
              </a>
            </li>
            <li>
              {keycloak === false ? (
                <div className="method-choice method-choice-disabled" aria-disabled="true">
                  <span className="method-choice-icon" aria-hidden="true">
                    🌐
                  </span>
                  <span className="method-choice-text">
                    <span className="method-choice-label">Web-Kanal (nicht verfügbar)</span>
                    <span className="method-choice-hint">
                      Nur mit echtem Keycloak - Server mit Profil <code>keycloak</code> starten (<code>./gradlew bootRunKc</code>)
                    </span>
                  </span>
                </div>
              ) : (
                <a className="method-choice" href="/web/" target="dpop-demo-web-kanal" aria-label="Zum Web-Kanal">
                  <span className="method-choice-icon" aria-hidden="true">
                    🌐
                  </span>
                  <span className="method-choice-text">
                    <span className="method-choice-label">Web-Kanal - spielt eine Website</span>
                    <span className="method-choice-hint">
                      Der Tab ist eine gewöhnliche Website mit „Anmelden“-Knopf. Der Login läuft über ein echtes
                      Keycloak, das im Hintergrund denselben Orchestrator fragt - gleiche Konten, gleiche
                      Verfahren, auch „mit der App bestätigen“ per QR-Code.
                    </span>
                  </span>
                </a>
              )}
            </li>
            <li>
              <a className="method-choice" href="/ext/" target="dpop-demo-register" aria-label="Zum Personenregister">
                <span className="method-choice-icon" aria-hidden="true">
                  🏛️
                </span>
                <span className="method-choice-text">
                  <span className="method-choice-label">Personenregister - spielt ein fremdes System</span>
                  <span className="method-choice-hint">
                    Das externe Register mit den Stammdaten der Versicherten. Es stellt die Freischaltcodes aus und
                    verschickt sie als Brief - der Briefkasten zeigt, was bei der Person ankäme.
                  </span>
                </span>
              </a>
            </li>
            <li>
              <a className="method-choice" href="/admin/" target="dpop-demo-admin" aria-label="Zur Admin-Seite">
                <span className="method-choice-icon" aria-hidden="true">
                  🛠️
                </span>
                <span className="method-choice-text">
                  <span className="method-choice-label">Admin - spielt den Betreiber</span>
                  <span className="method-choice-hint">
                    Blick hinter die Kulissen: jeder Schritt jeder Journey aller Konten, Verfahren sperren,
                    Demo zurücksetzen (mit Anmeldung).
                  </span>
                </span>
              </a>
            </li>
          </ul>
        </div>
      )}

      {tab === 'uebersicht' && (
        <div className="card">
          <h2>Was ist echt, was simuliert?</h2>
          <div className="real-vs-sim">
            <div>
              <h3>Echt</h3>
              <ul>
                <li>Der Orchestrator: Journeys, Konten, Verfahren, Tokens</li>
                <li>Die DPoP-Signaturen - der Geräteschlüssel entsteht per WebCrypto im Browser</li>
                <li>Keycloak und der Website-Login (OIDC mit PKCE) - nur mit Profil <code>keycloak</code></li>
                <li>Die Sicherheitsniveaus (loa1/loa2) und der Step-up</li>
              </ul>
            </div>
            <div>
              <h3>Simuliert</h3>
              <ul>
                <li>Das Smartphone - ein Browser-Tab</li>
                <li>SMS- und E-Mail-Versand - der Code steht direkt im Formular</li>
                <li>Der Brief mit dem Freischaltcode - der Briefkasten im Register</li>
                <li>Das Auslesen der eID-Karte und der Dienstleister KOBIL</li>
                <li>Das Personenregister selbst</li>
              </ul>
            </div>
          </div>
        </div>
      )}

      {tab === 'begriffe' && (
        <>
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
              </a>, den Aufbau dahinter erklären die Einstiegsabschnitte und die Beispiel-Story oben.
            </p>
          </div>
          <div className="card">
            <h2>Quellcode und Dokumentation</h2>
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
                <span className="label">Beispiel-Story</span>
                <a className="value" href="https://github.com/rpreissel/DPoP-demo/blob/main/docs/11-beispiel-story.md" target="_blank" rel="noreferrer">
                  docs/11-beispiel-story.md
                </a>
              </li>
              <li>
                <span className="label">Konzepte für Frontend-Entwickler</span>
                <a className="value" href="https://github.com/rpreissel/DPoP-demo/blob/main/docs/10-frontend.md#einstieg-wie-next-die-app-steuert" target="_blank" rel="noreferrer">
                  docs/10-frontend.md (Einstieg)
                </a>
              </li>
              <li>
                <span className="label">Konzepte für Backend-Entwickler</span>
                <a className="value" href="https://github.com/rpreissel/DPoP-demo/blob/main/docs/03-tool-architektur.md#einstieg-zusammenspiel-an-einem-schritt" target="_blank" rel="noreferrer">
                  docs/03-tool-architektur.md (Einstieg)
                </a>
              </li>
            </ul>
          </div>
        </>
      )}

      {tab === 'status' && <ServerStatus />}
    </div>
  )
}

/** Read-only: under which conditions this demo is running right now. Switched on /admin/. */
function ServerStatus() {
  const [info, setInfo] = useState<ServerInfo | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchServerInfo()
      .then(setInfo)
      .catch((err) => setError(describeError('Server-Status laden fehlgeschlagen', err)))
  }, [])

  if (error) return <div className="card error-card"><p>{error}</p></div>
  if (!info) return <div className="card"><p>Lädt…</p></div>

  return (
    <div className="card">
      <h2>Server-Status</h2>
      <p>
        Unter diesen Bedingungen läuft die Demo gerade. Ändern lässt sich das auf der <a href="/admin/">Admin-Seite</a>.
      </p>
      <ul className="status-list">
        <li>
          <span className="label">Identitätsanbieter (Web-Kanal)</span>
          <span className="value">
            {info.keycloakProfile ? `Echtes Keycloak${info.keycloakRealm ? `, Realm ${info.keycloakRealm}` : ''}` : 'keins - Web-Kanal nicht verfügbar'}
          </span>
        </li>
        {info.keycloakBaseUrl && (
          <li>
            <span className="label">Keycloak</span>
            <a className="value" href={info.keycloakBaseUrl} target="_blank" rel="noreferrer">
              {info.keycloakBaseUrl}
            </a>
          </li>
        )}
        <li>
          <span className="label">Registrierungsreihenfolge</span>
          <span className="value">{info.registrationEnrollFirst ? 'Enrollment zuerst' : 'Identifikation zuerst'}</span>
        </li>
        <li>
          <span className="label">Gesperrte Verfahren</span>
          <span className="value">
            {info.disabledTools.length === 0
              ? 'keine'
              : info.disabledTools.map((t) => `${t.toolId} [${t.channel === 'APP' ? 'App' : 'Web'}]${t.reason ? ` (${t.reason})` : ''}`).join(', ')}
          </span>
        </li>
        <li>
          <span className="label">Demo-Werte in Antworten</span>
          <span className="value">{info.demoDisclosure ? 'an (TANs, Testpersonen, Vorbelegung)' : 'aus'}</span>
        </li>
      </ul>
    </div>
  )
}
