import { stepDataOf } from '../../types'
import type { ToolModule } from '../types'
import { releaseKobilPin, submitKobilStep } from './api'
import { KobilAuthStep } from './KobilAuthStep'
import { KobilEnrollForm } from './KobilEnrollForm'
import { attemptError } from '../stepData'

const ICON = '🛡️'
const LABEL = 'KOBIL'
const HINT = 'Gerätebindung über KOBIL, entsperrt per Biometrie oder Passwort'

export const enrollKobilTool: ToolModule = {
  toolId: 'enroll-kobil',
  meta: { icon: ICON, label: LABEL, hint: HINT },
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
