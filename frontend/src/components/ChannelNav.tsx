import type { ReactNode } from 'react'
import { goToStart } from '../startWindow'

export interface NavTab<K extends string> {
  key: K
  label: string
}

interface Props<K extends string> {
  badge: string
  /** Omitted (or empty) for a page without tabs - then only badge, back button and actions show. */
  tabs?: readonly NavTab<K>[]
  sub?: K
  onSelectTab?: (sub: K) => void
  /** Right-hand extras next to the tabs (e.g. the admin page's logout). */
  actions?: ReactNode
}

/**
 * Shared top bar for every page with tabs (App/Web channel, Admin, Personenregister) - identical
 * markup everywhere so the pages read as "the same kind of thing, different color", not unrelated
 * UIs. The back button is its own labeled control (not folded into the badge) so "go back to
 * Startseite" stays recognizable on every page - and it switches to the start tab rather than
 * reloading the start page here (goToStart).
 */
export function ChannelNav<K extends string>({ badge, tabs = [], sub, onSelectTab, actions }: Props<K>) {
  return (
    <nav className="channel-topbar" aria-label={`${badge}-Bereiche`}>
      <div className="channel-topbar-brand">
        <button className="secondary small back-button" onClick={goToStart} aria-label="Zurück zur Startseite">
          ← Startseite
        </button>
        <span className="channel-badge">{badge}</span>
      </div>
      {tabs.length > 0 && (
      <div className="app-tabs channel-topbar-tabs" role="tablist">
        {tabs.map((tab) => (
          <button
            key={tab.key}
            role="tab"
            aria-selected={sub === tab.key}
            className={sub === tab.key ? 'active' : ''}
            onClick={() => onSelectTab?.(tab.key)}
          >
            {tab.label}
          </button>
        ))}
      </div>
      )}
      {actions && <div className="channel-topbar-actions">{actions}</div>}
    </nav>
  )
}
