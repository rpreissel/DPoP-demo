import type { Next } from './types'

/**
 * Fixed local routing table (docs/10-frontend.md #3). Keyed by (type, toolId|context, step) -
 * never by URL. Backend URLs stay implementation detail; a new tool only needs a new entry here.
 */
const toolRoutes: Record<string, Record<string, string>> = {
  'ident-fsc': { input: 'ident-fsc-form' },
  'enroll-sms': { enroll: 'sms-enroll-form', tanInput: 'tan-input-form' },
  'auth-sms': { auth: 'tan-input-form' },
}

const flowRoutes: Record<string, Record<string, string>> = {
  registration: { selectIdentificationMethod: 'select-method' },
  enrollment: { selectMethod: 'select-method' },
  auth: { selectMethod: 'select-method' },
  authentication: { authenticated: 'authentication-completed' },
}

/** Determines which UI component to show, based solely on `next` - never on a URL. */
export function getUIComponent(next: Next | undefined): string | null {
  if (!next) return null
  if (next.type === 'tool' && next.toolId) {
    return toolRoutes[next.toolId]?.[next.step] ?? null
  }
  if (next.type === 'flow' && next.context) {
    return flowRoutes[next.context]?.[next.step] ?? null
  }
  return null
}
