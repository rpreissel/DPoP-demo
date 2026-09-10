import type { ToolModule } from '../types'
import { confirmEnrollQr, submitDecision, submitPairingCode } from './api'
import { ConfirmQrLoginForm } from './ConfirmQrLoginForm'
import { EnrollQrForm } from './EnrollQrForm'
import { PairingCodeInputForm } from './PairingCodeInputForm'

export const enrollQr: ToolModule = {
  toolId: 'enroll-qr',
  meta: { icon: '📷', label: 'QR-Login', hint: 'Web-Login per QR-Code erlauben' },
  render(ctx) {
    if (ctx.step === 'enroll') {
      return <EnrollQrForm onConfirm={() => confirmEnrollQr(ctx)} error={ctx.stepData?.error} />
    }
    return null
  },
}

export const confirmQrLogin: ToolModule = {
  toolId: 'confirm-qr-login',
  meta: { icon: '📷', label: 'QR-Login', hint: 'Web-Login per QR bestätigen' },
  render(ctx) {
    if (ctx.step === 'input') {
      return <PairingCodeInputForm onSubmit={(pairingCode) => submitPairingCode(ctx, pairingCode)} error={ctx.stepData?.error} />
    }
    if (ctx.step === 'confirm') {
      return (
        <ConfirmQrLoginForm
          verificationCode={ctx.stepData?.verificationCode as string | undefined}
          onAccept={() => submitDecision(ctx, 'accept')}
          onReject={() => submitDecision(ctx, 'reject')}
          error={ctx.stepData?.error}
        />
      )
    }
    return null
  },
}

const qrModules: ToolModule[] = [enrollQr, confirmQrLogin]
export default qrModules
