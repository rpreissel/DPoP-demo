import { t } from '../texts'

interface SessionSummaryProps {
  /** Signed in (App: channel AUTHENTICATED; Web: tokens held). */
  signedIn: boolean
  name?: string
  /** Versicherter / Partner / Interessent, where known. */
  role?: string
  acr?: string
  amr?: string[]
}

/**
 * Always at the top of the demo column: whose session this is, at which level, proven by what.
 * Also while signing in - what is proven so far shows before the session is complete.
 */
export function SessionSummary({ signedIn, name, role, acr, amr }: SessionSummaryProps) {
  const proven = !!acr || (amr?.length ?? 0) > 0
  const who = name ? (role ? `${name} (${role})` : name) : undefined
  return (
    <section className="session-summary" aria-label={t('Sitzung')}>
      <span className="session-summary__label">{t('Sitzung')}</span>
      <span className="session-summary__who">
        {signedIn ? (who ?? t('Angemeldet')) : proven ? t('Anmeldung läuft') : t('Nicht angemeldet')}
        {!signedIn && proven && who && <span className="session-summary__muted"> · {who}</span>}
      </span>
      {proven && (
        <span className="session-summary__facts">
          <span>
            <span className="session-summary__muted">acr</span> <code>{acr ?? '–'}</code>
          </span>
          <span>
            <span className="session-summary__muted">amr</span> <code>{amr && amr.length > 0 ? amr.join(', ') : '–'}</code>
          </span>
        </span>
      )}
    </section>
  )
}
