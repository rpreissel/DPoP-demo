import type { DemoInfo } from '../types'

interface AuthenticationCompletedViewProps {
  currentAcr?: string
  currentAmr?: string[]
  demo?: DemoInfo
}

/** FE-11: accountId/personId come from the demo-only object, never a production field. */
export function AuthenticationCompletedView({ currentAcr, currentAmr, demo }: AuthenticationCompletedViewProps) {
  return (
    <div className="card success-card">
      <h2>Authentifizierung erfolgreich!</h2>
      <p>Sie sind angemeldet.</p>
      <ul className="status-list">
        {currentAcr && (
          <li>
            <span className="label">Sicherheitsniveau (ACR)</span>
            <span className="value">{currentAcr}</span>
          </li>
        )}
        {currentAmr && currentAmr.length > 0 && (
          <li>
            <span className="label">Nachgewiesene Methoden (AMR)</span>
            <span className="value">{currentAmr.join(', ')}</span>
          </li>
        )}
        {demo?.accountId != null && (
          <li>
            <span className="label">Account-ID (Demo)</span>
            <span className="value">{demo.accountId}</span>
          </li>
        )}
        {demo?.personId != null && (
          <li>
            <span className="label">Person-ID (Demo)</span>
            <span className="value">{demo.personId}</span>
          </li>
        )}
      </ul>
    </div>
  )
}
