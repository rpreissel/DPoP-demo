import { stepDataOf } from '../../types'
import type { ToolModule } from '../types'
import { releaseKobilPin, submitKobilStep } from './api'
import { KobilAuthStep } from './KobilAuthStep'
import { KobilEnrollForm } from './KobilEnrollForm'
import { attemptError } from '../stepData'
import { t } from '../../texts'

const ICON = '🛡️'
const LABEL = t('KOBIL')
const HINT = t('An das Gerät gebunden über KOBIL, entsperrt per Biometrie oder Passwort')

export const enrollKobilTool: ToolModule = {
  toolId: 'enroll-kobil',
  meta: { icon: ICON, label: LABEL, hint: HINT },
  explain: () => ({
    does: t('Das KOBIL-SDK auf dem Gerät wird aktiviert und an Ihr Konto gebunden. Entsperrt wird es später per Biometrie oder Passwort.'),
    actor: t('Das KOBIL-SDK in der App (simuliert), bestätigt vom Tool enroll-kobil.'),
  }),
  render(ctx) {
    if (ctx.step !== 'activate') return null
    const activation = stepDataOf(ctx.stepData, 'kobil-activation')
    return (
      <KobilEnrollForm
        tenantId={activation?.tenantId}
        kobilUserId={activation?.kobilUserId}
        activationCode={activation?.activationCode}
        pin={activation?.pin}
        unlockSecret={activation?.unlockSecret}
        onSubmit={(body) => submitKobilStep(ctx, body)}
        error={attemptError(ctx)}
      />
    )
  },
}

export const authKobilTool: ToolModule = {
  toolId: 'auth-kobil',
  meta: { icon: ICON, label: LABEL, hint: HINT },
  explain: (step) =>
    step === 'otp'
      ? {
          does: t('Das KOBIL-SDK erzeugt einen Einmalcode, den das Tool prüft.'),
          actor: t('Das KOBIL-SDK in der App (simuliert), geprüft vom Tool auth-kobil.'),
        }
      : {
          does: t('Sie entsperren den KOBIL-Schlüssel auf dem Gerät, per Biometrie oder Passwort.'),
          actor: t('Sie, mit dem KOBIL-SDK in der App (simuliert).'),
        },
  render(ctx) {
    if (ctx.step !== 'unlock' && ctx.step !== 'otp') return null
    // Needs toolSessionId: both the release sub-resource and the OTP PATCH address it.
    if (!ctx.toolSessionId) return null
    // Two shapes, one per step: `unlock` offers the ways to unlock, `otp` carries the released PIN.
    const unlock = stepDataOf(ctx.stepData, 'kobil-unlock')
    const otp = stepDataOf(ctx.stepData, 'kobil-otp')
    const user = unlock ?? otp
    return (
      <KobilAuthStep
        step={ctx.step}
        tenantId={user?.tenantId}
        kobilUserId={user?.kobilUserId}
        kobilPin={otp?.kobilPin}
        unlockOptions={unlock?.unlockOptions ?? []}
        onRelease={(unlock) => releaseKobilPin(ctx, unlock)}
        onSubmitOtp={(body) => submitKobilStep(ctx, body)}
        error={attemptError(ctx)}
      />
    )
  },
}

const kobilModules: ToolModule[] = [enrollKobilTool, authKobilTool]
export default kobilModules
