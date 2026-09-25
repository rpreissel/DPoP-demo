import { t } from '../texts'
import type { ReactNode } from 'react'
import { ChannelNav } from './ChannelNav'

interface Props {
  children: ReactNode
}

/**
 * Chrome for the whole Web channel - the same top bar as the App channel (see ChannelNav), just
 * under the light/blue .channel-web color scheme (index.css) instead of App's dark/purple one,
 * plus a wider content column to still read as a website rather than a native app screen. No
 * tabs: the journey log and the operator settings live on /admin/.
 */
export function WebChannelLayout({ children }: Props) {
  return (
    <div className="web-shell channel-web">
      <ChannelNav badge={`🌐 ${t('Web-Kanal')}`} />
      <div className="web-page">{children}</div>
    </div>
  )
}
