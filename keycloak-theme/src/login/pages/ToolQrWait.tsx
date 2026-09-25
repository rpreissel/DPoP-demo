import { useEffect } from 'react'
import type { PageContext } from '../KcContext'
import { ToolForm } from '../components/ToolForm'
import { t } from '../../texts'

/**
 * `tool-qr-wait.ftl`: waiting for the app to confirm. Shows the QR code and the pairing code -
 * typing the code is an equal way in, not a fallback - and the comparison code, which is only
 * compared by eye against the app (QR-jacking countermeasure). Re-submits the empty form every
 * three seconds to ask for the app's decision; an InProgress outcome charges no attempt.
 */
export function ToolQrWait({ kcContext }: { kcContext: PageContext<'tool-qr-wait.ftl'> }) {
  const { title, hint, pairingCode, verificationCode, deepLink, qrDataUri } = kcContext

  useEffect(() => {
    const poll = setTimeout(() => (document.getElementById('kc-orchestrator-tool-form') as HTMLFormElement | null)?.submit(), 3000)
    return () => clearTimeout(poll)
  }, [])

  return (
    <ToolForm kcContext={kcContext} title={title} hint={hint} submitLabel={null} backLabel={t('Abbrechen')}>
      <div className="orc-qr">
        <img src={qrDataUri} alt={t('QR-Code')} width={220} height={220} />
        <p>
          {t('Pairing-Code')}: <strong className="orc-qr-code">{pairingCode}</strong>
        </p>
        {verificationCode && (
          <>
            <p>
              {t('Vergleichscode:')} <strong className="orc-qr-code">{verificationCode}</strong>
            </p>
            <p className="orc-hint">{t('Bestätigen Sie in der App nur, wenn dort derselbe Code angezeigt wird.')}</p>
          </>
        )}
        {/* Named target: a click must not navigate this waiting page away (docs/10-frontend.md #0). */}
        <a href={deepLink} target="dpop-demo-app-kanal">
          {deepLink}
        </a>
        <p className="orc-hint">{t('Demo-Link: öffnet die App direkt (ohne Kamera) mit vorbefülltem Pairing-Code.')}</p>
      </div>
    </ToolForm>
  )
}
