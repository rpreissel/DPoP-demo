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
                    <span className="method-choice-label">Zum Web-Kanal</span>
                    <span className="method-choice-hint">Echter Browser-Client gegen echtes Keycloak</span>
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
                  <span className="method-choice-label">Personenregister (Fremdsystem)</span>
                  <span className="method-choice-hint">Stammdaten und Freischaltcodes ändern - so, wie es ein externes Register täte</span>
                </span>
              </a>
            </li>
            <li>
              <a className="method-choice" href="/admin/" target="dpop-demo-admin" aria-label="Zur Admin-Seite">
                <span className="method-choice-icon" aria-hidden="true">
                  🛠️
                </span>
                <span className="method-choice-text">
                  <span className="method-choice-label">Admin</span>
                  <span className="method-choice-hint">Einstellungen, Journey-Log aller Konten, Demo zurücksetzen (mit Anmeldung)</span>
                </span>
              </a>
            </li>
          </ul>
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
              : info.disabledTools.map((t) => `${t.toolId}${t.reason ? ` (${t.reason})` : ''}`).join(', ')}
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
