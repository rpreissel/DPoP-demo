import type { ReactNode } from 'react'
import { ChannelNav } from './ChannelNav'

interface Props {
  children: ReactNode
}

/**
 * Chrome for the whole App channel - the same top bar as WebChannelLayout (see ChannelNav), just
 * under the dark/purple .channel-app color scheme (index.css) instead of Web's light/blue one. No
 * tabs: the channel shows only what its user sees; the journey log and the operator settings
 * live on /admin/.
 */
export function AppChannelFrame({ children }: Props) {
  return (
    <div className="app-frame channel-app">
      <ChannelNav badge="📱 App-Kanal" />
      <div className="app">{children}</div>
    </div>
  )
}
