import type { SessionStatus } from '../types'

interface SessionStatusViewProps {
  status: SessionStatus
}

export function SessionStatusView({ status }: SessionStatusViewProps) {
  const phase = status.next?.context === 'authentication'
    ? 'authentication'
    : status.next?.context === 'registration' || status.registrationSessionId
      ? 'registration'
      : 'new'

  const phaseLabel = {
    new: 'Neuer Client',
    registration: 'Registrierung',
    authentication: 'Anmeldung',
  }[phase]

  const sessionId = status.sessionId ?? status.authorisationSessionId ?? status.registrationSessionId

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
        {sessionId && (
          <li>
            <span className="label">Session</span>
            <span className="value">{sessionId}</span>
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
