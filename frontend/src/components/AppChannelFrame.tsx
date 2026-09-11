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
  { key: 'journeylog' as const, label: 'Journey-Log' },
  { key: 'settings' as const, label: 'Einstellungen' },
]

/**
 * Chrome for the whole App channel (Demo/Journey-Log/Einstellungen) - the same top-bar shape as
 * WebChannelLayout (see ChannelNav), just under the dark/purple .channel-app color scheme
 * (index.css) instead of Web's light/blue one, so the two channels read as one consistent UI in
 * different colors rather than two unrelated designs.
 */
export function AppChannelFrame({ sub, onSelectTab, onBack, children }: Props) {
  return (
    <div className="app-frame channel-app">
      <ChannelNav badge="📱 App-Kanal" tabs={TABS} sub={sub} onSelectTab={onSelectTab} onBack={onBack} />
      <div className="app">{children}</div>
    </div>
  )
}
