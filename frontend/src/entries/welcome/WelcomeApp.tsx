import { useEffect, useState } from 'react'
import '../../App.css'
import { describeError, fetchServerInfo, type ServerInfo } from '../../api'
import { markAsStartWindow } from '../../startWindow'
import { t } from '../../texts'
import { Tx } from '../../Tx'
import { useHashTab } from '../../useHashTab'

const TAB_KEYS = ['uebersicht', 'begriffe', 'status'] as const
type Tab = (typeof TAB_KEYS)[number]
const TABS: { key: Tab; label: string }[] = [
  { key: 'uebersicht', label: t('Übersicht') },
  { key: 'begriffe', label: t('Begriffe & Doku') },
  { key: 'status', label: t('Server-Status') },
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
      .then((info) => setKeycloak(info.keycloak != null))
      .catch(() => setKeycloak(null))
  }, [])

  return (
    <div className="app">
      <header className="app-header">
        <h1>{t('Identity Journey')}</h1>
        <p>{t('Sich ausweisen, anmelden und bei Bedarf zusätzlich absichern (Step-up) zum Ausprobieren.')}</p>
      </header>

      <div className="app-tabs" role="tablist">
        {TABS.map((x) => (
          <button key={x.key} role="tab" aria-selected={tab === x.key} className={tab === x.key ? 'active' : ''} onClick={() => setTab(x.key)}>
            {x.label}
          </button>
        ))}
      </div>

      {tab === 'uebersicht' && (
        <div className="card welcome-card">
          <h2>{t('Worum geht es hier?')}</h2>
          <p>
            <Tx
              text="Ein {dienst} (der Orchestrator) bedient zwei Zugänge zu denselben Konten: eine {app} und eine {website}."
              dienst={<strong>{t('Identitätsdienst')}</strong>}
              app={<strong>{t('Smartphone-App')}</strong>}
              website={<strong>{t('Website')}</strong>}
            />{' '}
            {t(
              'Bei der Registrierung weist man sich aus - per Freischaltcode aus einem Brief, per eID oder über Nect -, bestätigt ' +
                'die E-Mail-Adresse und richtet ein Anmeldeverfahren ein, etwa SMS, Passwort, Geräteschlüssel oder KOBIL. Damit ' +
                'meldet man sich künftig an, auf der Website auch, indem man die Anmeldung mit der App per QR-Code bestätigt. Für ' +
                'empfindlichere Aktionen verlangt die Demo einen zusätzlichen Nachweis (Step-up).',
            )}{' '}
            <Tx text="Am Ende steht ein {token}: der Ausweis, mit dem eine Anwendung Sie erkennt." token={<strong>{t('AccessToken')}</strong>} />
          </p>
          <p>
            {t('Jeder Browser-Tab dieser Demo spielt eine Rolle: das Smartphone, die Website, das Personenverzeichnis, den Betreiber - und beim Ausweisen über Nect den Dienst Nect.')}{' '}
            {t('Welche das ist, steht auf jeder Kachel.')}
          </p>
          <ul className="method-choice-list channel-choice-list">
            <li>
              <a className="method-choice" href="/app/" target="dpop-demo-app-kanal" aria-label={t('Zum App-Kanal')}>
                <span className="method-choice-icon" aria-hidden="true">
                  📱
                </span>
                <span className="method-choice-text">
                  <span className="method-choice-label">{t('App-Kanal - spielt das Smartphone')}</span>
                  <span className="method-choice-hint">
                    {t(
                      'Der Tab ist die App auf dem Handy: Er erzeugt einen Geräteschlüssel und signiert jede Anfrage ' +
                        'damit (DPoP), damit ein abgefangenes Token auf einem anderen Gerät nichts nützt.',
                    )}{' '}
                    {t('Registrieren, Anmelden, Verfahren verwalten, Step-up.')}
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
                    <span className="method-choice-label">{t('Web-Kanal nicht verfügbar')}</span>
                    <span className="method-choice-hint">
                      <Tx
                        text="Nur mit echtem Keycloak - Server mit Profil {profil} starten ({befehl})"
                        profil={<code>keycloak</code>}
                        befehl={<code>./gradlew bootRunKc</code>}
                      />
                    </span>
                  </span>
                </div>
              ) : (
                <a className="method-choice" href="/web/" target="dpop-demo-web-kanal" aria-label={t('Zum Web-Kanal')}>
                  <span className="method-choice-icon" aria-hidden="true">
                    🌐
                  </span>
                  <span className="method-choice-text">
                    <span className="method-choice-label">{t('Web-Kanal - spielt eine Website')}</span>
                    <span className="method-choice-hint">
                      {t('Der Tab ist eine gewöhnliche Website mit „Anmelden“-Knopf.')}{' '}
                      {t(
                        'Der Login läuft über ein echtes Keycloak, das im Hintergrund denselben Orchestrator fragt - gleiche Konten, gleiche ' +
                          'Regeln. Nect, Geräteschlüssel und KOBIL gibt es nur in der App; dafür lässt sich die Anmeldung hier mit der App ' +
                          'per QR-Code bestätigen.',
                      )}
                    </span>
                  </span>
                </a>
              )}
            </li>
            <li>
              <a className="method-choice" href="/personenverzeichnis/" target="dpop-demo-register" aria-label={t('Zum Personenverzeichnis')}>
                <span className="method-choice-icon" aria-hidden="true">
                  🏛️
                </span>
                <span className="method-choice-text">
                  <span className="method-choice-label">{t('Personenverzeichnis - spielt ein fremdes System')}</span>
                  <span className="method-choice-hint">
                    {t('Das externe Verzeichnis aller Personen, die wir kennen - Versicherte und Partner.')}{' '}
                    {t('Es stellt die Freischaltcodes aus und verschickt sie als Brief - der Briefkasten zeigt, was bei der Person ankäme.')}{' '}
                    {t('Was Sie dort ändern, übernehmen die Konten und Keycloak.')}
                  </span>
                </span>
              </a>
            </li>
            <li>
              <a className="method-choice" href="/admin/" target="dpop-demo-admin" aria-label={t('Zur Admin-Seite')}>
                <span className="method-choice-icon" aria-hidden="true">
                  🛠️
                </span>
                <span className="method-choice-text">
                  <span className="method-choice-label">{t('Admin - spielt den Betreiber')}</span>
                  <span className="method-choice-hint">
                    {t('Blick hinter die Kulissen: jeder Schritt jeder Journey aller Konten, Konten löschen, Verfahren je Kanal sperren und sortieren, Reihenfolge der Registrierung umstellen, Demo zurücksetzen (mit Anmeldung).')}
                  </span>
                </span>
              </a>
            </li>
          </ul>
        </div>
      )}

      {tab === 'uebersicht' && (
        <div className="card">
          <h2>{t('Was ist echt, was simuliert?')}</h2>
          <div className="real-vs-sim">
            <div>
              <h3>{t('Echt')}</h3>
              <ul>
                <li>{t('Der Orchestrator: Journeys, Konten, Verfahren, Tokens')}</li>
                <li>{t('Die DPoP-Signaturen - der Geräteschlüssel entsteht per WebCrypto im Browser')}</li>
                <li>
                  <Tx text="Keycloak und der Website-Login (OIDC mit PKCE) - nur mit Profil {profil}" profil={<code>keycloak</code>} />
                </li>
                <li>{t('Die Sicherheitsniveaus (loa1 bis loa3) und der Step-up')}</li>
              </ul>
            </div>
            <div>
              <h3>{t('Simuliert')}</h3>
              <ul>
                <li>{t('Das Smartphone - ein Browser-Tab')}</li>
                <li>{t('SMS- und E-Mail-Versand - der Code steht direkt im Formular')}</li>
                <li>{t('Der Brief mit dem Freischaltcode - der Briefkasten im Personenverzeichnis')}</li>
                <li>{t('Das Auslesen der eID-Karte und die Dienstleister Nect und KOBIL')}</li>
                <li>{t('Das Personenverzeichnis selbst')}</li>
              </ul>
            </div>
          </div>
        </div>
      )}

      {tab === 'begriffe' && (
        <>
          <div className="card">
            <h2>{t('Wichtige Begriffe für die Demo')}</h2>
            <p>
              <Tx
                text="Ihr {konto} ist der Zugang, mit dem Sie in dieser Demo angemeldet sind - er entsteht bei der Registrierung."
                konto={<strong>{t('Konto')}</strong>}
              />{' '}
              <Tx
                text={
                  'Dabei weisen Sie sich per {identifikation} aus ("das bin ich": Freischaltcode, eID oder Nect) - die Person ' +
                  'dahinter bleibt vom Konto getrennt gedacht: Identifizieren Sie sich später mit derselben Test-Identität erneut, ' +
                  'landet die Demo auf demselben Konto statt einem neuen.'
                }
                identifikation={<strong>{t('Identifikation')}</strong>}
              />{' '}
              <Tx
                text={
                  'Für die spätere, wiederholte Anmeldung ("ich bin\'s wieder") dienen dagegen {verfahren}: etwa SMS, Passwort, ' +
                  'ein geräteeigener Schlüssel oder KOBIL (die letzten beiden nur in der App) oder auf der Website die Bestätigung ' +
                  'per QR-Code mit der App - einzeln oder kombiniert.'
                }
                verfahren={<strong>{t('Anmeldeverfahren')}</strong>}
              />
            </p>
            <p>
              <Tx
                text={
                  'Wen die Demo hinter einem Konto kennt, sagt seine {rolle}: {versicherter} ist, wer bei uns versichert ist - mit ' +
                  'Versicherungsnummer und meist auch KVNR. {partner} ist, wen das Personenverzeichnis nur mit seiner Partnernummer ' +
                  'kennt. {interessent} ist ein Konto, dem noch keine Person zugeordnet ist.'
                }
                rolle={<strong>{t('Rolle')}</strong>}
                versicherter={<strong>{t('Versicherter')}</strong>}
                partner={<strong>{t('Partner')}</strong>}
                interessent={<strong>{t('Interessent')}</strong>}
              />{' '}
              {t('Nach eID oder Nect ordnet ein eigener Schritt das Konto einer Person zu: zuerst über die KVNR, ohne KVNR über die Partnernummer.')}
            </p>
            <p>
              <Tx
                text="Wie stark Ihre Identität gerade nachgewiesen ist, drückt das {niveau} aus."
                niveau={<strong>{t('Sicherheitsniveau')}</strong>}
              />{' '}
              <Tx
                text={
                  'Ein Verfahren reicht oft schon; für empfindlichere Aktionen verlangt die Demo einen zusätzlichen Nachweis, ' +
                  'den {stepup} - ohne sich komplett neu anzumelden.'
                }
                stepup={<strong>{t('Step-up')}</strong>}
              />
            </p>
            <p>
              {t('Zwei Dinge werden dabei leicht verwechselt, weil beide etwas mit dem Gerät zu tun haben.')}{' '}
              <Tx
                text={
                  'Dass dieses Gerät wiedererkannt wird, beweist nur, {welches} gerade spricht - nicht, dass davor wirklich die ' +
                  'Person sitzt, für die es sich ausgibt ("Automatisch anmelden" nutzt das aus, "Neu anmelden" verzichtet bewusst darauf).'
                }
                welches={<em>{t('welches Gerät')}</em>}
              />{' '}
              <Tx
                text={
                  'Ein eigens eingerichteter {schluessel} dagegen ist ein echtes Anmeldeverfahren, erst freigeschaltet durch ' +
                  'System-PIN oder Biometrie - und allein schon so stark wie zwei andere Verfahren zusammen.'
                }
                schluessel={<strong>{t('Geräteschlüssel')}</strong>}
              />
            </p>
            <p>
              {t(
                'Jeder Ablauf - Login, Registrierung, Sicherheitsniveau erhöhen, Anmeldeverfahren verwalten, Konto löschen - wird ' +
                  'dabei vollständig vom Backend geführt: Die Oberfläche entscheidet nie selbst, was als Nächstes kommt, sie zeigt ' +
                  'nur den jeweils aktuellen Schritt an.',
              )}
            </p>
            <p>
              <Tx
                text="Ist der App-Kanal angemeldet, lässt sich ein {token} abrufen - der eigentliche Ausweis, mit dem eine echte Anwendung Sie erkennen würde."
                token={<strong>{t('AccessToken')}</strong>}
              />{' '}
              {t('Der Web-Kanal bekommt sein Token dagegen direkt von Keycloak.')}
            </p>
            <p>
              <Tx
                text="Details stehen im {link}; den Aufbau dahinter erklären die Einstiegskapitel und die Beispiel-Story unten."
                link={
                  <a href="https://github.com/rpreissel/DPoP-demo/blob/main/docs/02-domaenenmodell.md" target="_blank" rel="noreferrer">
                    {t('Domänenmodell')}
                  </a>
                }
              />
            </p>
          </div>
          <div className="card">
            <h2>{t('Quellcode und Dokumentation')}</h2>
            <ul className="status-list">
              <li>
                <span className="label">{t('Quellcode')}</span>
                <a className="value" href="https://github.com/rpreissel/DPoP-demo" target="_blank" rel="noreferrer">
                  github.com/rpreissel/DPoP-demo
                </a>
              </li>
              <li>
                <span className="label">{t('Dokumentation')}</span>
                <a className="value" href="https://github.com/rpreissel/DPoP-demo/tree/main/docs" target="_blank" rel="noreferrer">
                  {t('{pfad} (Domänenmodell, Orchestrierung, API, DPoP, ...)', { pfad: 'docs/' })}
                </a>
              </li>
              <li>
                <span className="label">{t('Beispiel-Story')}</span>
                <a className="value" href="https://github.com/rpreissel/DPoP-demo/blob/main/docs/11-beispiel-story.md" target="_blank" rel="noreferrer">
                  docs/11-beispiel-story.md
                </a>
              </li>
              <li>
                <span className="label">{t('Konzepte für Frontend-Entwickler')}</span>
                <a className="value" href="https://github.com/rpreissel/DPoP-demo/blob/main/docs/10-frontend.md#einstieg-wie-next-die-app-steuert" target="_blank" rel="noreferrer">
                  {t('{pfad} (Einstieg)', { pfad: 'docs/10-frontend.md' })}
                </a>
              </li>
              <li>
                <span className="label">{t('Konzepte für Backend-Entwickler')}</span>
                <a className="value" href="https://github.com/rpreissel/DPoP-demo/blob/main/docs/03-tool-architektur.md#einstieg-zusammenspiel-an-einem-schritt" target="_blank" rel="noreferrer">
                  {t('{pfad} (Einstieg)', { pfad: 'docs/03-tool-architektur.md' })}
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
      .catch((err) => setError(describeError(t('Server-Status laden fehlgeschlagen'), err)))
  }, [])

  if (error) return <div className="card error-card"><p>{error}</p></div>
  if (!info) return <div className="card"><p>{t('Lädt…')}</p></div>

  return (
    <div className="card">
      <h2>{t('Server-Status')}</h2>
      <p>
        {t('So ist die Demo gerade eingestellt.')}{' '}
        <Tx
          text="Reihenfolge der Registrierung und gesperrte Verfahren ändern Sie auf der {link}; Keycloak und Demo-Werte legt der Serverstart fest."
          link={<a href="/admin/">{t('Admin-Seite')}</a>}
        />
      </p>
      <ul className="status-list">
        <li>
          <span className="label">{t('Identitätsanbieter (Web-Kanal)')}</span>
          <span className="value">
            {info.keycloak ? t('Keycloak, Realm {realm}', { realm: info.keycloak.realm }) : t('Kein Keycloak - Web-Kanal nicht verfügbar')}
          </span>
        </li>
        {info.keycloak && (
          <li>
            <span className="label">{t('Keycloak')}</span>
            <a className="value" href={info.keycloak.baseUrl} target="_blank" rel="noreferrer">
              {info.keycloak.baseUrl}
            </a>
          </li>
        )}
        {info.keycloak && (
          <li>
            <span className="label">{t('Anmeldeseiten')}</span>
            <span className="value">{info.keycloak.loginTheme === 'KEYCLOAKIFY' ? 'Keycloakify (React)' : 'FreeMarker'}</span>
          </li>
        )}
        <li>
          <span className="label">{t('Registrierungsreihenfolge')}</span>
          <span className="value">{info.registrationEnrollFirst ? t('Enrollment zuerst') : t('Identifikation zuerst')}</span>
        </li>
        <li className={info.disabledTools.length === 0 ? undefined : 'status-stacked'}>
          <span className="label">{t('Gesperrte Verfahren')}</span>
          {info.disabledTools.length === 0 ? (
            <span className="value">{t('keine')}</span>
          ) : (
            // One line per channel and reason - the reason once, not after every tool.
            <ul className="status-sublist">
              {disabledGroups(info.disabledTools).map((g) => (
                <li key={`${g.channel}|${g.reason}`}>
                  <span className="status-group">
                    {g.channel === 'APP' ? t('App') : t('Web')}
                    {g.reason ? ` · ${g.reason}` : ''}
                  </span>
                  <span className="value">{g.toolIds.join(', ')}</span>
                </li>
              ))}
            </ul>
          )}
        </li>
        <li>
          <span className="label">{t('Demo-Werte in Antworten')}</span>
          <span className="value">{info.demoDisclosure ? t('an (TANs, Testpersonen, Vorbelegung)') : t('aus')}</span>
        </li>
      </ul>
    </div>
  )
}

function disabledGroups(tools: { toolId: string; channel: string; reason?: string | null }[]) {
  const groups = new Map<string, { channel: string; reason: string; toolIds: string[] }>()
  for (const d of tools) {
    const key = `${d.channel}|${d.reason ?? ''}`
    const group = groups.get(key) ?? { channel: d.channel, reason: d.reason ?? '', toolIds: [] }
    group.toolIds.push(d.toolId)
    groups.set(key, group)
  }
  return [...groups.values()]
}
