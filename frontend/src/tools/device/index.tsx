import type { ToolModule } from '../types'
import { authDevice, enrollDevice } from './api'
import { DeviceAuthForm } from './DeviceAuthForm'
import { DeviceEnrollForm } from './DeviceEnrollForm'

const ICON = '📲'
const LABEL = 'Gerät'
const HINT = 'Geräteeigener Schlüssel + PIN/Biometrie'

export const enrollDeviceTool: ToolModule = {
  toolId: 'enroll-device',
  meta: { icon: ICON, label: LABEL, hint: HINT },
  render(ctx) {
    // Needs toolSessionId to build the DPoP-proof htu - not yet available for one render right
    // after activation (matches the previous `activeTool &&` guard in App.tsx).
    if (ctx.step === 'enroll' && ctx.toolSessionId) {
      return (
        <DeviceEnrollForm
          toolSessionId={ctx.toolSessionId}
          toolId={ctx.toolId}
          onSubmit={(body) => enrollDevice(ctx, body)}
          error={ctx.stepData?.error}
        />
      )
    }
    return null
  },
}

export const authDeviceTool: ToolModule = {
  toolId: 'auth-device',
  meta: { icon: ICON, label: LABEL, hint: HINT },
  render(ctx) {
    if (ctx.step === 'auth' && ctx.toolSessionId) {
      return (
        <DeviceAuthForm
          toolSessionId={ctx.toolSessionId}
          toolId={ctx.toolId}
          onSubmit={(body) => authDevice(ctx, body)}
          error={ctx.stepData?.error}
        />
      )
    }
    return null
  },
}

const deviceModules: ToolModule[] = [enrollDeviceTool, authDeviceTool]
export default deviceModules
