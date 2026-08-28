import type { Next } from './types'

/** Screens the orchestrator itself owns - selection pages, confirmations, the finished screen. Not tools; see src/tools/registry.ts for those. */
const orchestratorRoutes: Record<string, Record<string, string>> = {
  registration: { selectIdentificationMethod: 'select-method' },
  enrollment: { selectMethod: 'select-method' },
  auth: { selectMethod: 'select-method' },
  authentication: {
    authenticated: 'authentication-completed',
    offerDeviceBinding: 'device-binding-offer',
  },
}

/** Determines which orchestrator screen to show, based solely on `next` - never on a URL. Tool steps go through src/tools/registry.ts's renderToolStep instead. */
export function getUIComponent(next: Next | undefined): string | null {
  if (!next) return null
  if (next.type === 'orchestrator' && next.context) {
    return orchestratorRoutes[next.context]?.[next.step] ?? null
  }
  return null
}
