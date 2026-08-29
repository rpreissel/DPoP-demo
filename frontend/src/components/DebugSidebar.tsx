import { useState } from 'react'
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
  open: boolean
  onToggle: () => void
}

/** Strips a top-level `demo` key, if present - the only place it ever appears in these JSON blobs. */
function withoutDemo(value: unknown): unknown {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return value
  const { demo, ...rest } = value as Record<string, unknown>
  return rest
}

/**
 * Docked to the right edge, full viewport height, visible by default - a demo app's whole point is
 * showing what's happening under the hood at every step. Collapsible (App.tsx's `debugOpen`) so a
 * first-time visitor isn't confronted with raw JSON as the first thing taking up half the screen;
 * the explanatory intro line says what this panel even is before showing any of it. Identity (JWK
 * thumbprint) already has its own card in the main view, so it isn't duplicated here. Request
 * headers (incl. the DPoP proof) are omitted from the log - noisy and not relevant to following the
 * demo; URL, method and body are what matters.
 *
 * `demo` (accountId/personId/journeys/tan/password/email) is hidden by default - it's the one part
 * of these payloads that isn't really "what the backend just did", only a demo-only convenience
 * aid, and easily the most noise once a journey chain is running.
 */
export function DebugSidebar({ channel, log, open, onToggle }: DebugSidebarProps) {
  const [showDemo, setShowDemo] = useState(false)

  return (
    <aside className={`debug-sidebar${open ? '' : ' collapsed'}`}>
      <div className="debug-sidebar-header">
        <h2>Technischer Einblick</h2>
        <button className="icon-button" onClick={onToggle} aria-label={open ? 'Technischen Einblick einklappen' : 'Technischen Einblick ausklappen'}>
          {open ? '»' : '«'}
        </button>
      </div>
      {open && (
        <>
          <p className="debug-sidebar-intro">Jeder API-Aufruf, den die Oberfläche gerade macht - so sieht das Backend-Protokoll live aus.</p>

          <label className="debug-sidebar-toggle">
            <input type="checkbox" checked={showDemo} onChange={(e) => setShowDemo(e.target.checked)} />
            Demo-Infos einblenden (accountId, personId, Journey-Kette, TAN/Passwort-Vorbelegung)
          </label>

          <section>
            <h3>Kanal</h3>
            <pre>{JSON.stringify(showDemo ? channel : withoutDemo(channel), null, 2)}</pre>
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
                      <pre>{JSON.stringify(showDemo ? entry.response : withoutDemo(entry.response), null, 2)}</pre>
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
        </>
      )}
    </aside>
  )
}
