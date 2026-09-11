import type { ReactNode } from 'react'

interface DisclosureProps {
  summary: string
  children: ReactNode
  defaultOpen?: boolean
}

/**
 * Native <details>/<summary> toggle for the raw/technical part of a card (JWT claims dumps, token
 * strings) - collapsed by default so authenticated screens (TokenPanel, WebChannelView) lead with
 * status and actions for Fachexperten, while the raw data stays one click away instead of being
 * dropped.
 */
export function Disclosure({ summary, children, defaultOpen = false }: DisclosureProps) {
  return (
    <details className="disclosure" open={defaultOpen}>
      <summary className="disclosure-summary">{summary}</summary>
      <div className="disclosure-body">{children}</div>
    </details>
  )
}
