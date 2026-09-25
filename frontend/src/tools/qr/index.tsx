import type { ToolModule } from '../types'
import { confirmEnrollQr, submitDecision, submitPairingCode } from './api'
import { ConfirmQrLoginForm } from './ConfirmQrLoginForm'
import { EnrollQrForm } from './EnrollQrForm'
import { PairingCodeInputForm } from './PairingCodeInputForm'
import { attemptError } from '../stepData'
import { stepDataOf } from '../../types'
import { t } from '../../texts'

export const enrollQr: ToolModule = {
  toolId: 'enroll-qr',
  meta: { icon: '📷', label: t('QR-Login'), hint: t('Web-Login per QR-Code erlauben') },
  explain: () => ({
    does: t('Erlaubt, dass Sie sich im Browser per QR-Code anmelden. Freigegeben wird eine solche Anmeldung dann mit diesem Gerät.'),
    actor: t('Sie bestätigen in der App, das Tool enroll-qr richtet es ein.'),
  }),
  render(ctx) {
    if (ctx.step === 'enroll') {
      return <EnrollQrForm onConfirm={() => confirmEnrollQr(ctx)} error={attemptError(ctx)} />
    }
    return null
  },
}

export const confirmQrLogin: ToolModule = {
  toolId: 'confirm-qr-login',
  meta: { icon: '📷', label: t('QR-Login'), hint: t('Web-Login per QR bestätigen') },
  explain: (step) =>
    step === 'confirm'
      ? {
          does: t('Stimmt der Prüfcode mit dem im Browser überein, geben Sie die Anmeldung dort frei. Der Browser bekommt dann seine eigene Sitzung.'),
          actor: t('Sie in der App. Der Browser wartet auf Ihre Freigabe.'),
        }
      : {
          does: t('Der Code aus dem Browser sagt dem Orchestrator, welche wartende Browser-Anmeldung gemeint ist.'),
          actor: t('Sie in der App. Der Browser wartet, bis Sie den Code eingegeben haben.'),
        },
  render(ctx) {
    if (ctx.step === 'input') {
      return <PairingCodeInputForm onSubmit={(pairingCode) => submitPairingCode(ctx, pairingCode)} error={attemptError(ctx)} />
    }
    if (ctx.step === 'confirm') {
      return (
        <ConfirmQrLoginForm
          verificationCode={stepDataOf(ctx.stepData, 'qr-pairing')?.verificationCode}
          onAccept={() => submitDecision(ctx, 'accept')}
          onReject={() => submitDecision(ctx, 'reject')}
          error={attemptError(ctx)}
        />
      )
    }
    return null
  },
}

const qrModules: ToolModule[] = [enrollQr, confirmQrLogin]
export default qrModules
