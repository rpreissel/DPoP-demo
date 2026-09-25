import type { PageContext } from '../KcContext'
import { ToolForm } from '../components/ToolForm'
import { t } from '../../texts'

/** `tool-qr-enroll.ftl`: allowing the signed-in app to confirm website logins by QR code. */
export function ToolQrEnroll({ kcContext }: { kcContext: PageContext<'tool-qr-enroll.ftl'> }) {
  return (
    <ToolForm
      kcContext={kcContext}
      title={kcContext.title}
      hint={t('Erlaubt, dass Sie künftig eine Anmeldung auf der Website mit Ihrer angemeldeten App per QR-Code bestätigen. Ein zusätzliches Passwort brauchen Sie dafür nicht.')}
      submitLabel={t('Aktivieren')}
      cancel
    />
  )
}
