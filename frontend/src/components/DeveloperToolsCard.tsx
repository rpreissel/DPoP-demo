import { t } from '../texts'
import { Tx } from '../Tx'
/** Swagger UI isn't proxied by the vite dev server (only /orchestrator is, see vite.config.ts) - in dev it lives on the backend's own port, in a same-origin deployment it's just window.location.origin. */
const BACKEND_ORIGIN = window.location.port === '5173' ? 'http://localhost:8080' : window.location.origin

/** Matches src/main/resources/application.yml - H2 console has no reliable cross-version query-param prefill, so these are shown for manual copy-paste instead. */
const H2_JDBC_URL = 'jdbc:h2:file:./data/dpopdb'
const H2_USER = 'sa'

/**
 * Links into the ONE shared orchestrator backend behind both channels - identical either way, so
 * this lives once, on the admin page's Einstellungen tab.
 */
export function DeveloperToolsCard() {
  return (
    <div className="card">
      <h2>{t('Entwickler-Werkzeuge')}</h2>
      <ul className="status-list">
        <li>
          <span className="label">{t('API-Doku')}</span>
          <a className="value" href={`${BACKEND_ORIGIN}/swagger-ui/index.html`} target="_blank" rel="noreferrer">
            Swagger/OpenAPI UI
          </a>
        </li>
        <li>
          <span className="label">{t('H2-Konsole')}</span>
          <span className="value" title={t('JDBC URL: {url}\nUser: {user}\nPassword: (leer)', { url: H2_JDBC_URL, user: H2_USER })}>
            <a href={`${BACKEND_ORIGIN}/h2-console`} target="_blank" rel="noreferrer">
              {t('öffnen')}
            </a>{' '}
            <Tx text="({url}, User {user}, kein Passwort)" url={H2_JDBC_URL} user={H2_USER} />
          </span>
        </li>
      </ul>
    </div>
  )
}
