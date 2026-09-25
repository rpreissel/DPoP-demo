import type { ToolModule } from '../types'
import { authDevice, enrollDevice } from './api'
import { DeviceAuthForm } from './DeviceAuthForm'
import { DeviceEnrollForm } from './DeviceEnrollForm'
import { attemptError } from '../stepData'
import { t } from '../../texts'

const ICON = '📲'
const LABEL = t('Gerät')
const HINT = t('Geräteeigener Schlüssel + PIN/Biometrie')

export const enrollDeviceTool: ToolModule = {
  toolId: 'enroll-device',
  meta: { icon: ICON, label: LABEL, hint: HINT },
  explain: () => ({
    does: t('Die App erzeugt einen Schlüssel, der das Gerät nie verlässt. Das Tool bindet dessen öffentlichen Teil an Ihr Konto - danach erkennt der Orchestrator dieses Gerät wieder.'),
    actor: t('Die App mit dem Geräteschlüssel, geprüft vom Tool enroll-device im Orchestrator.'),
  }),
  render(ctx) {
    // Needs toolSessionId to build the DPoP-proof htu - not yet available for one render right
    // after activation (matches the previous `activeTool &&` guard in App.tsx).
    if (ctx.step === 'enroll' && ctx.toolSessionId) {
      return (
        <DeviceEnrollForm
          toolSessionId={ctx.toolSessionId}
          toolId={ctx.toolId}
          onSubmit={(body) => enrollDevice(ctx, body)}
          error={attemptError(ctx)}
        />
      )
    }
    return null
  },
}

export const authDeviceTool: ToolModule = {
  toolId: 'auth-device',
  meta: { icon: ICON, label: LABEL, hint: HINT },
  explain: () => ({
    does: t('Die App beweist mit dem Schlüssel dieses Geräts, dass es das an das Konto gebundene Gerät ist. Eine Eingabe braucht es dafür nicht.'),
    actor: t('Die App signiert, das Tool auth-device im Orchestrator prüft die Signatur.'),
  }),
  render(ctx) {
    if (ctx.step === 'auth' && ctx.toolSessionId) {
      return (
        <DeviceAuthForm
          toolSessionId={ctx.toolSessionId}
          toolId={ctx.toolId}
          onSubmit={(body) => authDevice(ctx, body)}
          error={attemptError(ctx)}
        />
      )
    }
    return null
  },
}

const deviceModules: ToolModule[] = [enrollDeviceTool, authDeviceTool]
export default deviceModules
