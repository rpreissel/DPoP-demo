type SubTab = 'demo' | 'journeylog' | 'settings' | 'mock'

interface Tab {
  key: SubTab
  label: string
}

interface Props {
  badge: string
  tabs: Tab[]
  sub: SubTab
  onSelectTab: (sub: SubTab) => void
  onBack: () => void
}

/**
 * Shared top bar for both channel chromes (AppChannelFrame/WebChannelLayout) - identical markup
 * for both so the two channels' navigation reads as "the same kind of thing, different color",
 * not two unrelated UIs. The back button is its own labeled control (not folded into the badge)
 * so "go back to Startseite" stays recognizable in both channels.
 */
export function ChannelNav({ badge, tabs, sub, onSelectTab, onBack }: Props) {
  return (
    <nav className="channel-topbar" aria-label={`${badge}-Bereiche`}>
      <div className="channel-topbar-brand">
        <button className="secondary small back-button" onClick={onBack} aria-label="Zurück zur Startseite">
          ← Startseite
        </button>
        <span className="channel-badge">{badge}</span>
      </div>
      <div className="app-tabs channel-topbar-tabs" role="tablist">
        {tabs.map((tab) => (
          <button
            key={tab.key}
            role="tab"
            aria-selected={sub === tab.key}
            className={sub === tab.key ? 'active' : ''}
            onClick={() => onSelectTab(tab.key)}
          >
            {tab.label}
          </button>
        ))}
      </div>
    </nav>
  )
}
