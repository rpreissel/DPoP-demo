import type { ToolModule } from '../types'
import { releaseKobilPin, submitKobilStep } from './api'
import { KobilAuthStep } from './KobilAuthStep'
import { KobilEnrollForm } from './KobilEnrollForm'

const ICON = '🛡️'
const LABEL = 'KOBIL'
const HINT = 'Gerätebindung über KOBIL, entsperrt per Biometrie oder Passwort'

/** `stepData` crosses the wire untyped; read the few values this tool needs, no wider. */
function text(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined
}

function strings(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : []
}

export const enrollKobilTool: ToolModule = {
  toolId: 'enroll-kobil',
  meta: { icon: ICON, label: LABEL, hint: HINT },
  render(ctx) {
    if (ctx.step !== 'activate') return null
    return (
      <KobilEnrollForm
        tenantId={text(ctx.stepData?.tenantId)}
        kobilUserId={text(ctx.stepData?.kobilUserId)}
        activationCode={text(ctx.stepData?.activationCode)}
        pin={text(ctx.stepData?.pin)}
        unlockSecret={text(ctx.stepData?.unlockSecret)}
        onSubmit={(body) => submitKobilStep(ctx, body)}
        error={ctx.stepData?.error}
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
    return (
      <KobilAuthStep
        step={ctx.step}
        tenantId={text(ctx.stepData?.tenantId)}
        kobilUserId={text(ctx.stepData?.kobilUserId)}
        kobilPin={text(ctx.stepData?.kobilPin)}
        unlockOptions={strings(ctx.stepData?.unlockOptions)}
        onRelease={(unlock) => releaseKobilPin(ctx, unlock)}
        onSubmitOtp={(body) => submitKobilStep(ctx, body)}
        error={ctx.stepData?.error}
      />
    )
  },
}

const kobilModules: ToolModule[] = [enrollKobilTool, authKobilTool]
export default kobilModules
