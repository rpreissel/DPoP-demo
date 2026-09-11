import type { ReactNode } from 'react'
import { ChannelNav } from './ChannelNav'

type SubTab = 'demo' | 'journeylog' | 'settings' | 'mock'

interface Props {
  sub: SubTab
  onSelectTab: (sub: SubTab) => void
  onBack: () => void
  children: ReactNode
}

const TABS = [
  { key: 'demo' as const, label: 'Demo' },
  { key: 'mock' as const, label: 'Mock-Keycloak (Dev)' },
  { key: 'journeylog' as const, label: 'Journey-Log' },
  { key: 'settings' as const, label: 'Einstellungen' },
]

/**
 * Chrome for the whole Web channel (Demo/Mock-Keycloak/Journey-Log/Einstellungen) - the same
 * top-bar shape as AppChannelFrame (see ChannelNav), just under the light/blue .channel-web color
 * scheme (index.css) instead of App's dark/purple one, plus a wider content column to still read
 * as a website rather than a native app screen.
 */
export function WebChannelLayout({ sub, onSelectTab, onBack, children }: Props) {
  return (
    <div className="web-shell channel-web">
      <ChannelNav badge="🌐 Web-Kanal" tabs={TABS} sub={sub} onSelectTab={onSelectTab} onBack={onBack} />
      <div className={sub === 'journeylog' ? 'web-page web-page-wide' : 'web-page'}>{children}</div>
    </div>
  )
}
