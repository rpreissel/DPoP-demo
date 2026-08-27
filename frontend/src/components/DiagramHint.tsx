import type { ReactNode } from 'react'
import { JourneyDiagram, type JourneyDiagramSpec } from './JourneyDiagram'

interface DiagramHintProps {
  spec: JourneyDiagramSpec
  children: ReactNode
  /** For wrapping a small inline trigger (e.g. an ℹ️ icon) instead of a full-width block like a card button. */
  inline?: boolean
}

/**
 * Wraps anything (a button, a status line) with a hover/focus-revealed preview of a journey's
 * shape - the diagram itself stays out of the way until someone actually wants it, instead of
 * permanently occupying space next to content most visitors will only glance at once.
 */
export function DiagramHint({ spec, children, inline }: DiagramHintProps) {
  return (
    <span className={`diagram-hint${inline ? ' diagram-hint--inline' : ' diagram-hint--block'}`}>
      {children}
      <span className="diagram-hint-popover">
        <JourneyDiagram {...spec} />
      </span>
    </span>
  )
}
