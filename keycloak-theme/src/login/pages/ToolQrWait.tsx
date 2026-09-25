import { useEffect } from 'react'
import type { PageContext } from '../KcContext'
import { Field } from '../components/Field'
import { ToolForm } from '../components/ToolForm'
import { t } from '../../texts'

/**
 * `tool-qr-wait.ftl`, two steps. `waitForApp`: the QR code and the pairing code - typing the code
 * is an equal way in, not a fallback - re-submitting the empty form every three seconds to ask for
 * the app's decision (an InProgress outcome charges no attempt). `enterCode`: the app approved and
 * shows a confirmation code; only typing it here logs this browser in (review 2026-09, M-2).
 */
export function ToolQrWait({ kcContext }: { kcContext: PageContext<'tool-qr-wait.ftl'> }) {
  if (kcContext.step === 'enterCode') {
    const { pageTitle: title, hint } = kcContext
    return (
      <ToolForm kcContext={kcContext} title={title} hint={hint} cancel>
        <Field
          id="confirmationCode"
          label={t('Code aus der App')}
          inputMode="numeric"
          autoComplete="one-time-code"
          autoFocus
          hint={t('Ihre App zeigt nach der Freigabe einen sechsstelligen Code. Geben Sie ihn hier ein.')}
        />
      </ToolForm>
    )
  }
  return <WaitForApp kcContext={kcContext} />
}

function WaitForApp({ kcContext }: { kcContext: Extract<PageContext<'tool-qr-wait.ftl'>, { step: 'waitForApp' }> }) {
  const { pageTitle: title, hint, pairingCode, deepLink, qrDataUri } = kcContext

  useEffect(() => {
    const poll = setTimeout(() => (document.getElementById('kc-orchestrator-tool-form') as HTMLFormElement | null)?.submit(), 3000)
    return () => clearTimeout(poll)
  }, [])

  return (
    <ToolForm kcContext={kcContext} title={title} hint={hint} submitLabel={null} cancel>
      <div className="orc-qr">
        <img src={qrDataUri} alt={t('QR-Code')} width={220} height={220} />
        <p>
          {t('Pairing-Code')}: <strong className="orc-qr-code">{pairingCode}</strong>
        </p>
        <p className="orc-hint">{t('Nach der Freigabe zeigt Ihre App einen Code, den Sie hier eingeben.')}</p>
        {/* Named target: a click must not navigate this waiting page away (docs/10-frontend.md #0). */}
        <a href={deepLink} target="dpop-demo-app-kanal">
          {deepLink}
        </a>
        <p className="orc-hint">{t('Demo-Link: öffnet die App direkt (ohne Kamera) mit vorbefülltem Pairing-Code.')}</p>
      </div>
    </ToolForm>
  )
}
