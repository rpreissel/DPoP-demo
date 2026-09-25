import type { ReactNode } from 'react'
import type { KcContext } from './KcContext'

/**
 * The page frame of the FreeMarker theme, rebuilt: empty white top bar, the realm's name as the
 * large heading on the grey stage, one white card with the page title, messages and the form.
 */
export function Layout({ kcContext, title, children, info }: { kcContext: KcContext; title: ReactNode; children: ReactNode; info?: ReactNode }) {
  const { realm, message } = kcContext
  return (
    <div className="orc-page">
      <div className="orc-topbar" />
      <main className="orc-main">
        <p className="orc-realm">{realm.displayName || realm.name}</p>
        <section className="orc-card">
          <h1 className="orc-title">{title}</h1>
          {message && message.type !== 'success' && (
            <div className={`orc-alert orc-alert-${message.type}`} role={message.type === 'error' ? 'alert' : 'status'}>
              {message.summary}
            </div>
          )}
          {children}
          {info && <div className="orc-info">{info}</div>}
        </section>
      </main>
      <div className="orc-band" />
      <div className="orc-footer" />
    </div>
  )
}
