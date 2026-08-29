import type { Next } from '../types'
import { shorten } from '../format'
import { metaFor } from '../tools/registry'
import { DiagramHint } from './DiagramHint'
import { JOURNEY_DIAGRAMS } from '../journeyDiagrams'

interface SessionStatusViewProps {
  channelSessionId?: string
  state?: string
  next?: Next
  journeyKind?: 'auto' | 'register' | 'login'
  onClear: () => void
}

const PHASE_LABELS: Record<string, string> = {
  ANONYMOUS: 'Neuer Client',
  REGISTERING: 'Registrierung',
  AUTHENTICATED: 'Angemeldet',
  STEP_UP_REQUIRED: 'Step-up erforderlich',
  STEP_UP_IN_PROGRESS: 'Step-up läuft',
  LOGGED_OUT: 'Abgemeldet',
  EXPIRED: 'Abgelaufen',
}

/**
 * Orchestrator-owned pages (docs/04-orchestrierung.md) - not a tool, so toolMeta doesn't cover
 * them. Keyed the same way routing.ts routes them: `context` names the KIND of screen, shared
 * across every intent that renders it - `prompt/confirm` covers every AnswerableState (device
 * binding, account deletion, ...) with one row, `auth/selectMethod` every candidate selection.
 */
const ORCHESTRATOR_STEP_LABELS: Record<string, string> = {
  'auth/selectMethod': 'Verfahren wählen',
  'enrollment/selectMethod': 'Verfahren wählen',
  'registration/selectIdentificationMethod': 'Verfahren wählen',
  'authentication/authenticated': 'Angemeldet',
  'prompt/confirm': 'Rückfrage',
}

/** In plain language, what `next` is currently waiting on - never the raw toolId/context+step pair. */
function describeNext(next: Next): string {
  if (next.type === 'tool' && next.toolId) return metaFor(next.toolId).label
  return ORCHESTRATOR_STEP_LABELS[`${next.context}/${next.step}`] ?? `${next.context ?? next.toolId} / ${next.step}`
}

export function SessionStatusView({ channelSessionId, state, next, journeyKind, onClear }: SessionStatusViewProps) {
  const badgeVariant = state === 'AUTHENTICATED' ? 'authentication' : 'registration'

  return (
    <div className="card">
      <h2>
        Session-Status
        {journeyKind && (
          <DiagramHint spec={JOURNEY_DIAGRAMS[journeyKind]} inline>
            <span className="diagram-hint-trigger" tabIndex={0} aria-label="Ablauf dieses Vorgangs als Diagramm anzeigen">
              ℹ️
            </span>
          </DiagramHint>
        )}
      </h2>
      <ul className="status-list">
        <li>
          <span className="label">Kanalstatus</span>
          <span className={`badge badge--${badgeVariant}`}>{(state && PHASE_LABELS[state]) ?? state ?? 'Neuer Client'}</span>
        </li>
        {channelSessionId && (
          <li>
            <span className="label">Channel Session</span>
            <span className="value-with-action">
              <span className="value" title={channelSessionId}>
                {shorten(channelSessionId)}
              </span>
              <button className="secondary small" onClick={onClear}>
                Leeren
              </button>
            </span>
          </li>
        )}
        {next && (
          <li>
            <span className="label">Aktueller Schritt</span>
            <span className="value value-plain">{describeNext(next)}</span>
          </li>
        )}
      </ul>
    </div>
  )
}
