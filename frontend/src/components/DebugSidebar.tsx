import type { ActiveMethodView } from '../types'

export interface DebugEvent {
  id: number
  time: string
  label: string
  request?: unknown
  response?: unknown
  error?: string
}

interface DebugSidebarProps {
  channel: {
    channelSessionId?: string
    channelState?: string
    currentAcr?: string
    currentAmr?: string[]
    activeMethods?: ActiveMethodView[]
    next?: unknown
    stepData?: unknown
    demo?: unknown
    activeTool?: unknown
  }
  log: DebugEvent[]
}

/**
 * Always-visible inspector, docked to the right edge and using the full viewport height - not a
 * togglable section, since a demo app's whole point is showing what's happening under the hood at
 * every step. Identity (JWK thumbprint) already has its own card in the main view, so it isn't
 * duplicated here. Request headers (incl. the DPoP proof) are omitted from the log - noisy and
 * not relevant to following the demo; URL, method and body are what matters.
 */
export function DebugSidebar({ channel, log }: DebugSidebarProps) {
  return (
    <aside className="debug-sidebar">
      <h2>Debug</h2>

      <section>
        <h3>Kanal</h3>
        <pre>{JSON.stringify(channel, null, 2)}</pre>
      </section>

      <section>
        <h3>Verlauf ({log.length})</h3>
        <ul className="debug-log">
          {log.map((entry) => (
            <li key={entry.id}>
              <div className="debug-log-header">
                <span className="debug-log-time">{entry.time}</span>
                <span className="debug-log-label">{entry.label}</span>
              </div>
              {entry.request !== undefined && (
                <div className="debug-log-block">
                  <span className="debug-log-block-label">Request</span>
                  <pre>{JSON.stringify(entry.request, null, 2)}</pre>
                </div>
              )}
              {entry.response !== undefined && (
                <div className="debug-log-block">
                  <span className="debug-log-block-label">Response</span>
                  <pre>{JSON.stringify(entry.response, null, 2)}</pre>
                </div>
              )}
              {entry.error && (
                <div className="debug-log-block">
                  <span className="debug-log-block-label">Fehler</span>
                  <pre>{entry.error}</pre>
                </div>
              )}
            </li>
          ))}
        </ul>
      </section>
    </aside>
  )
}
