import type { ReactNode } from 'react'

/**
 * An implied browser window: title bar with the three dots, an address bar, then the page. Only
 * what the real website would show goes in here - everything about the demo stays in the demo
 * column next to it (DemoArea), same split as the App channel's PhoneFrame.
 */
export function BrowserFrame({ url, children }: { url: string; children: ReactNode }) {
  return (
    <div className="browser">
      <div className="browser__bar" aria-hidden="true">
        <span className="browser__dots">
          <i />
          <i />
          <i />
        </span>
        <span className="browser__address">
          <span className="browser__lock">🔒</span>
          {url}
        </span>
      </div>
      <div className="browser__page">{children}</div>
    </div>
  )
}
