import { t } from '../texts'
import { createContext, useContext, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'

/**
 * The App channel's split (docs/10-frontend.md): the smartphone shows only what a real app shows,
 * everything that exists only for the demo sits next to it. A demo helper that belongs to one
 * screen - the test person picker of a form, the demo code of a TAN step - is written where it
 * belongs, inside that screen's component, but wrapped in <Demo>: a portal renders it in the demo
 * column, still part of its form's React tree (so "Testperson übernehmen" fills the fields in the
 * phone), and it disappears with its screen.
 */
/** undefined: no demo column at all (tests, other channels) - demo helpers stay where they are written. */
const DemoTarget = createContext<HTMLElement | null | undefined>(undefined)

export function DemoProvider({ children }: { children: (setTarget: (el: HTMLElement | null) => void) => ReactNode }) {
  const [target, setTarget] = useState<HTMLElement | null>(null)
  return <DemoTarget.Provider value={target}>{children(setTarget)}</DemoTarget.Provider>
}

/**
 * Renders [children] in the demo column's "zu diesem Schritt" slot. Without a demo column (tests,
 * other channels) they stay where they are written; with one, nothing shows until its slot exists.
 */
export function Demo({ children }: { children: ReactNode }) {
  const target = useContext(DemoTarget)
  if (target === undefined) return <>{children}</>
  return target ? createPortal(<div className="demo-slot">{children}</div>, target) : null
}

/** A demo-only note - what is simulated, where a code comes from. Lives in the demo column, never in the phone. */
export function DemoNote({ children }: { children: ReactNode }) {
  return (
    <Demo>
      <p className="demo-note">{children}</p>
    </Demo>
  )
}

/**
 * The demo column: [forStep] is the slot <Demo> portals into (titled only while something is in
 * it), [children] the column's own sections.
 */
export function DemoArea({
  targetRef,
  session,
  intro,
  children,
}: {
  targetRef: (el: HTMLElement | null) => void
  /** Whose session this is, at which level - always first, on every screen (SessionSummary). */
  session?: ReactNode
  /** What the demo is about - above everything else, before the step at hand. */
  intro?: ReactNode
  children: ReactNode
}) {
  return (
    <aside className="demo-area" aria-label={t('Demo-Werkzeuge')}>
      <div className="demo-area__label">{t('Demo-Werkzeuge')}</div>
      {session}
      {intro}
      <section className="demo-step">
        <h3 className="demo-step__title">{t('Zu diesem Schritt')}</h3>
        <div ref={targetRef} className="demo-step__slot" />
      </section>
      {children}
    </aside>
  )
}
