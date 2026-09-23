import type { ToolModule } from '../types'
import { confirmEnrollQr, submitDecision, submitPairingCode } from './api'
import { ConfirmQrLoginForm } from './ConfirmQrLoginForm'
import { EnrollQrForm } from './EnrollQrForm'
import { PairingCodeInputForm } from './PairingCodeInputForm'
import { attemptError } from '../stepData'
import { stepDataOf } from '../../types'

export const enrollQr: ToolModule = {
  toolId: 'enroll-qr',
  meta: { icon: '📷', label: 'QR-Login', hint: 'Web-Login per QR-Code erlauben' },
  render(ctx) {
    if (ctx.step === 'enroll') {
      return <EnrollQrForm onConfirm={() => confirmEnrollQr(ctx)} error={attemptError(ctx)} />
    }
    return null
  },
}

export const confirmQrLogin: ToolModule = {
  toolId: 'confirm-qr-login',
  meta: { icon: '📷', label: 'QR-Login', hint: 'Web-Login per QR bestätigen' },
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
