import type { SessionStatus } from '../types'

interface SessionStatusViewProps {
  status: SessionStatus
}

export function SessionStatusView({ status }: SessionStatusViewProps) {
  const phase = status.next?.context === 'authentication'
    ? 'authentication'
    : status.next?.context === 'registration'
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
        {status.channelSessionId && (
          <li>
            <span className="label">Channel Session</span>
            <span className="value">{status.channelSessionId}</span>
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
