/** Swagger UI isn't proxied by the vite dev server (only /orchestrator is, see vite.config.ts) - in dev it lives on the backend's own port, in a same-origin deployment it's just window.location.origin. */
const BACKEND_ORIGIN = window.location.port === '5173' ? 'http://localhost:8080' : window.location.origin

/** Matches src/main/resources/application.yml - H2 console has no reliable cross-version query-param prefill, so these are shown for manual copy-paste instead. */
const H2_JDBC_URL = 'jdbc:h2:file:./data/dpopdb'
const H2_USER = 'sa'

/**
 * Links into the ONE shared orchestrator backend behind both channels - identical either way, so
 * this lives once here instead of being duplicated per channel's Einstellungen tab.
 */
export function DeveloperToolsCard() {
  return (
    <div className="card">
      <h2>Entwickler-Werkzeuge</h2>
      <ul className="status-list">
        <li>
          <span className="label">API-Doku</span>
          <a className="value" href={`${BACKEND_ORIGIN}/swagger-ui/index.html`} target="_blank" rel="noreferrer">
            Swagger/OpenAPI UI
          </a>
        </li>
        <li>
          <span className="label">H2-Konsole</span>
          <span className="value" title={`JDBC URL: ${H2_JDBC_URL}\nUser: ${H2_USER}\nPassword: (leer)`}>
            <a href={`${BACKEND_ORIGIN}/h2-console`} target="_blank" rel="noreferrer">
              öffnen
            </a>{' '}
            ({H2_JDBC_URL}, User {H2_USER}, kein Passwort)
          </span>
        </li>
      </ul>
    </div>
  )
}
