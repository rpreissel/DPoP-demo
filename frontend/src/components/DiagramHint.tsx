import type { ReactNode } from 'react'
import { JourneyDiagram, type JourneyDiagramCurrentStep, type JourneyDiagramSpec } from './JourneyDiagram'

interface DiagramHintProps {
  spec: JourneyDiagramSpec
  children: ReactNode
  /** For wrapping a small inline trigger (e.g. an ℹ️ icon) instead of a full-width block like a card button. */
  inline?: boolean
  /** Highlights the box a REAL running instance is currently at - omitted for the static hover previews, which have no live instance to point at. */
  current?: JourneyDiagramCurrentStep
  /**
   * Opens the popover below the trigger instead of the default above. The default suits a trigger
   * with room to spare above it (e.g. deep in JourneyStructureView's debug panel); a trigger that
   * sits near the very top of the page (the journey-context banner, the entry screen's own
   * method-choice list) has nowhere for an upward popover to go - it renders partly above y=0,
   * permanently unreachable (no scroll brings position:absolute content above its own anchor into
   * view). Set this at those top-of-page call sites instead of flipping the default everywhere.
   */
  openDown?: boolean
}

/**
 * Wraps anything (a button, a status line) with a hover/focus-revealed preview of a journey's
 * shape - the diagram itself stays out of the way until someone actually wants it, instead of
 * permanently occupying space next to content most visitors will only glance at once.
 */
export function DiagramHint({ spec, children, inline, current, openDown }: DiagramHintProps) {
  return (
    <span className={`diagram-hint${inline ? ' diagram-hint--inline' : ' diagram-hint--block'}`}>
      {children}
      <span className={`diagram-hint-popover${openDown ? ' diagram-hint-popover--down' : ''}`}>
        <JourneyDiagram {...spec} current={current} />
      </span>
    </span>
  )
}
