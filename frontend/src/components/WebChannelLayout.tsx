import type { ReactNode } from 'react'
import { ChannelNav } from './ChannelNav'

interface Props {
  onBack: () => void
  children: ReactNode
}

/**
 * Chrome for the whole Web channel - the same top bar as AppChannelFrame (see ChannelNav), just
 * under the light/blue .channel-web color scheme (index.css) instead of App's dark/purple one,
 * plus a wider content column to still read as a website rather than a native app screen. No
 * tabs: the journey log and the operator settings live on /admin/.
 */
export function WebChannelLayout({ onBack, children }: Props) {
  return (
    <div className="web-shell channel-web">
      <ChannelNav badge="🌐 Web-Kanal" onBack={onBack} />
      <div className="web-page">{children}</div>
    </div>
  )
}
