import type { SessionStatus } from '../types'

interface SessionStatusViewProps {
  status: SessionStatus
}

export function SessionStatusView({ status }: SessionStatusViewProps) {
  const phase = status.authorisationSessionId
    ? 'authentication'
    : status.registrationSessionId
      ? 'registration'
      : 'new'

  const phaseLabel = {
    new: 'Neuer Client',
    registration: 'Registrierung',
    authentication: 'Anmeldung',
  }[phase]

  return (
    <div className="card">
      <h2>Session-Status</h2>
      <ul className="status-list">
        <li>
          <span className="label">Aktuelle Phase</span>
          <span className={`badge badge--${phase === 'authentication' ? 'authentication' : 'registration'}`}>
            {phaseLabel}
          </span>
        </li>
        {status.registrationSessionId && (
          <li>
            <span className="label">Registration Session</span>
            <span className="value">{status.registrationSessionId}</span>
          </li>
        )}
        {status.authorisationSessionId && (
          <li>
            <span className="label">Authorisation Session</span>
            <span className="value">{status.authorisationSessionId}</span>
          </li>
        )}
        {status.next && (
          <li>
            <span className="label">Nächster Schritt</span>
            <span className="value">{status.next.context} / {status.next.step}</span>
          </li>
        )}
      </ul>
    </div>
  )
}
