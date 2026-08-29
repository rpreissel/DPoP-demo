import type { ReactNode } from 'react'
import type { JourneyDebugStep } from '../types'
import { shorten } from '../format'

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
}

interface Level {
  label: string
  detail: string
}

/** Nests levels via the same containment visual as the Willkommen explanation (App.css .nesting-box) - Channel outermost, Tool innermost, matching how they actually contain one another. */
function nest(levels: Level[]): ReactNode {
  return levels.reduceRight<ReactNode>((inner, level, index) => {
    const depth = Math.min(index + 1, 3)
    return (
      <div className={`nesting-box nesting-box--${depth}`} key={index}>
        <span className="nesting-label">
          {level.label} <em>{level.detail}</em>
        </span>
        {inner}
      </div>
    )
  }, null)
}

/**
 * Live counterpart to the Willkommen tab's conceptual Channel/Journey/Tool diagram - the actual
 * running structure for this channel, including any SUSPENDED parent journey (e.g. a step-up gate
 * parked mid-way while its sub-journey runs) that would otherwise be invisible from the outside.
 */
export function JourneyStructureView({ channelSessionId, channelState, journeys, activeTool }: JourneyStructureViewProps) {
  if (!channelSessionId) return null

  const levels: Level[] = [{ label: 'Channel', detail: `${shorten(channelSessionId)} · ${channelState ?? '-'}` }]

  journeys?.forEach((j, index) => {
    levels.push({
      label: index === 0 ? 'Journey' : 'SubJourney',
      detail: `${j.intent} · ${j.lifecycle} · ${j.stateType}`,
    })
  })

  if (activeTool) {
    levels.push({ label: 'Tool', detail: activeTool.step ? `${activeTool.toolId} · ${activeTool.step}` : activeTool.toolId })
  }

  if (levels.length === 1) return null

  return (
    <div className="card">
      <h3 className="section-heading">Struktur</h3>
      <div className="nesting-diagram">{nest(levels)}</div>
    </div>
  )
}
