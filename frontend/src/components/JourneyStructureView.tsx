import type { ReactNode } from 'react'
import type { JourneyDebugStep, StepData } from '../types'
import { shorten } from '../format'
import { DiagramHint } from './DiagramHint'
import { JOURNEY_DIAGRAMS } from '../journeyDiagrams'

interface ActiveToolInfo {
  toolId: string
  step?: string
}

interface JourneyStructureViewProps {
  channelSessionId?: string
  channelState?: string
  /** The running journey chain, outermost first (docs/tool_api/Envelope.kt, JourneyDebugStep) - only present once demo.journeys was returned. */
  journeys?: JourneyDebugStep[]
  activeTool?: ActiveToolInfo | null
  /** What the innermost box's note explains (what/why) is drawn from - the same backend-authored text the actual screen renders, nothing invented separately here. */
  stepData?: StepData
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
}

interface Level {
  label: string
  detail: string
  /** Only the Journey level carries this - the diagram shows that journey's shape, not the channel's or tool's. */
  hint?: ReactNode
  /** Only the innermost (currently active) level carries this - what it's doing and why it's up now. */
  note?: { title: string; text?: string }
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
        {level.note && (
          <p className="nesting-note">
            <strong>{level.note.title}</strong>
            {level.note.text && ` – ${level.note.text}`}
          </p>
        )}
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
export function JourneyStructureView({ channelSessionId, channelState, journeys, activeTool, stepData, journeyKind, onClear }: JourneyStructureViewProps) {
  if (!channelSessionId) return null

  const levels: Level[] = []

  journeys?.forEach((j, index) => {
    const diagramKey = index === 0 ? (journeyKind ?? INTENT_DIAGRAM_KEY[j.intent]) : INTENT_DIAGRAM_KEY[j.intent]
    levels.push({
      label: index === 0 ? 'Journey' : 'SubJourney',
      detail: `${j.intent} · ${j.lifecycle} · ${j.stateType}`,
      hint: diagramKey ? (
        <DiagramHint spec={JOURNEY_DIAGRAMS[diagramKey]} inline>
          <span className="diagram-hint-trigger" tabIndex={0} aria-label="Ablauf dieser Journey als Diagramm anzeigen">
            ℹ️
          </span>
        </DiagramHint>
      ) : undefined,
    })
  })

  // Demo-only: why the innermost journey's tool became the automatic choice, if it did (backend
  // DemoAutoPickNote) - e.g. "only one candidate available", never authored on the frontend.
  const autoPickNote = journeys?.at(-1)?.autoPickNote

  if (activeTool) {
    levels.push({
      label: 'Tool',
      detail: activeTool.step ? `${activeTool.toolId} · ${activeTool.step}` : activeTool.toolId,
      note: autoPickNote ? { title: 'Automatisch gewählt', text: autoPickNote } : undefined,
    })
  }

  // Only attach a note where the backend actually authored one for THIS step (OfferingState.selectionTitle,
  // Prompt.title/description) - never a frontend-invented substitute (docs/03-tool-architektur.md's
  // "backend decides the text" reasoning applies here too, even though this note is demo-only).
  if (levels.length > 0 && !levels[levels.length - 1].note && stepData?.title) {
    levels[levels.length - 1] = { ...levels[levels.length - 1], note: { title: stepData.title, text: stepData.description } }
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
