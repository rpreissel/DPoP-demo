import type { ReactNode } from 'react'
import type { JourneyDebugStep, Next } from '../types'
import { shorten } from '../format'
import { DiagramHint } from './DiagramHint'
import { CURRENT_STEP_BY_STATE_TYPE, JOURNEY_DIAGRAMS } from '../journeyDiagrams'

interface JourneyStructureViewProps {
  channelSessionId?: string
  channelState?: string
  /** The running journey chain, outermost first (docs/tool_api/Envelope.kt, JourneyDebugStep) - only present once demo.journeys was returned. */
  journeys?: JourneyDebugStep[]
  /** Where the channel is headed right now - a real ToolSession (`type: 'tool'`) or an orchestrator-owned screen (`type: 'orchestrator'`, e.g. a selection or confirmation page) alike, both shown as the innermost box. */
  next?: Next
  journeyKind?: 'auto' | 'register' | 'login'
  onClear: () => void
}

/** JourneyDebugStep.intent (AuthIntent name) -> JOURNEY_DIAGRAMS key, for SubJourneys where there's no entry-choice `journeyKind` to go by. */
const INTENT_DIAGRAM_KEY: Record<string, keyof typeof JOURNEY_DIAGRAMS> = {
  FAST_ACCESS: 'auto',
  REGISTER: 'register',
  LOOKUP_LOGIN: 'login',
  STEP_UP: 'stepUp',
  MANAGE_AUTH_METHODS: 'manageMethods',
  DELETE_ACCOUNT: 'deleteAccount',
  RE_IDENTIFY: 'reIdentify',
}

interface Level {
  label: string
  detail: string
  /** Only the Journey level carries this - the diagram shows that journey's shape, not the channel's or tool's. */
  hint?: ReactNode
  /** Only the innermost (currently active) level carries this - demo-only, why it's up now (DemoStepReason). Never the screen's own title/description - that's already visible on the actual screen, repeating it here would just be noise. */
  note?: string
}

/** Nests levels via the same containment visual as the Willkommen explanation (App.css .nesting-box), starting at `baseDepth` (the Channel box, rendered separately, occupies depth 1). */
function nest(levels: Level[], baseDepth: number): ReactNode {
  return levels.reduceRight<ReactNode>((inner, level, index) => {
    const depth = Math.min(baseDepth + index, 3)
    return (
      <div className={`nesting-box nesting-box--${depth}`} key={index}>
        <span className="nesting-label">
          {level.label} <em>{level.detail}</em> {level.hint}
        </span>
        {level.note && <p className="nesting-note">{level.note}</p>}
        {inner}
      </div>
    )
  }, null)
}

/**
 * The one place showing session identity, live status and current step - nested as Channel ⊃
 * Journey ⊃ SubJourney ⊃ Tool, including any SUSPENDED parent journey (e.g. a step-up gate parked
 * mid-way while its sub-journey runs) that would otherwise be invisible from the outside.
 */
export function JourneyStructureView({ channelSessionId, channelState, journeys, next, journeyKind, onClear }: JourneyStructureViewProps) {
  if (!channelSessionId) return null

  const levels: Level[] = []

  journeys?.forEach((j, index) => {
    const diagramKey = index === 0 ? (journeyKind ?? INTENT_DIAGRAM_KEY[j.intent]) : INTENT_DIAGRAM_KEY[j.intent]
    // Only the innermost (actually active) journey has a "current step" to point at - a SUSPENDED
    // parent is parked waiting on its sub-journey, its own diagram has nothing to highlight.
    const isInnermost = index === journeys.length - 1
    const current = isInnermost && diagramKey ? CURRENT_STEP_BY_STATE_TYPE[diagramKey]?.[j.stateType] : undefined
    levels.push({
      label: index === 0 ? 'Journey' : 'SubJourney',
      detail: `${j.intent} · ${j.lifecycle} · ${j.stateType}`,
      hint: diagramKey ? (
        <DiagramHint spec={JOURNEY_DIAGRAMS[diagramKey]} current={current} inline>
          <span className="diagram-hint-trigger" tabIndex={0} aria-label="Ablauf dieser Journey als Diagramm anzeigen">
            ℹ️
          </span>
        </DiagramHint>
      ) : undefined,
    })
  })

  // Demo-only: why the innermost journey's current state looks the way it does (backend
  // DemoStepReason) - e.g. "only one candidate available" or "several active methods, hence a
  // choice". Always explains the JOURNEY's own decision, not whichever specific tool the user
  // then picked from it - so it stays on the Journey/SubJourney box even once a tool or an
  // orchestrator screen is active underneath it, never moves down onto that box.
  if (levels.length > 0) {
    levels[levels.length - 1] = { ...levels[levels.length - 1], note: journeys?.at(-1)?.note }
  }

  if (next?.type === 'tool') {
    levels.push({ label: 'Tool', detail: `${next.toolId} · ${next.step}` })
  } else if (next?.type === 'orchestrator') {
    // Not a real ToolSession, but just as much "the current step" as one - an orchestrator-owned
    // screen (a selection, a confirmation prompt) deserves the same visibility, not just a gap.
    levels.push({ label: 'Orchestrator', detail: `${next.context} · ${next.step}` })
  }

  return (
    <div className="card">
      <h2>Struktur</h2>
      <div className="nesting-diagram">
        <div className="nesting-box nesting-box--1">
          <span className="nesting-label">
            Channel <em>{shorten(channelSessionId)} · {channelState ?? '-'}</em>
            <DiagramHint spec={JOURNEY_DIAGRAMS.channel} inline>
              <span className="diagram-hint-trigger" tabIndex={0} aria-label="Lebenszyklus eines Channels als Diagramm anzeigen">
                ℹ️
              </span>
            </DiagramHint>
            <button className="secondary small nesting-label-action" onClick={onClear}>
              Leeren
            </button>
          </span>
          {nest(levels, 2)}
        </div>
      </div>
    </div>
  )
}
