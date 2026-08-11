import type { SessionStatus } from '../types'

interface SessionStatusViewProps {
  status: SessionStatus
}

export function SessionStatusView({ status }: SessionStatusViewProps) {
  return (
    <div className="card">
      <h2>Session Status</h2>
      <pre>{JSON.stringify(status, null, 2)}</pre>
    </div>
  )
}
