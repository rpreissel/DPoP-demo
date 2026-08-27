import type { Next } from './types'

/**
 * Fixed local routing table (docs/10-frontend.md #3). Keyed by (type, toolId|context, step) -
 * never by URL. Backend URLs stay implementation detail; a new tool only needs a new entry here.
 */
const toolRoutes: Record<string, Record<string, string>> = {
  'ident-fsc': { input: 'ident-fsc-form' },
  'enroll-sms': { enroll: 'sms-enroll-form', tanInput: 'tan-input-form' },
  'auth-sms': { auth: 'tan-input-form' },
  'enroll-password': { enroll: 'password-enroll-form' },
  'auth-password': { auth: 'password-login-form' },
  'enroll-email': { enroll: 'email-enroll-form', codeInput: 'email-code-input-form' },
  'auth-email': { auth: 'email-code-input-form' },
  'auth-sms-lookup': { auth: 'email-lookup-form', tanInput: 'tan-input-form' },
  'auth-password-lookup': { auth: 'email-password-lookup-form' },
  'auth-email-lookup': { auth: 'email-code-lookup-form', codeInput: 'email-code-input-form' },
  'enroll-device': { enroll: 'device-enroll-form' },
  'auth-device': { auth: 'device-auth-form' },
}

/** Screens the orchestrator itself owns - selection pages, confirmations, the finished screen. */
const orchestratorRoutes: Record<string, Record<string, string>> = {
  registration: { selectIdentificationMethod: 'select-method' },
  enrollment: { selectMethod: 'select-method' },
  auth: { selectMethod: 'select-method' },
  authentication: {
    authenticated: 'authentication-completed',
    offerDeviceBinding: 'device-binding-offer',
  },
}

/**
 * The toolIds this client has a form for - what it can honestly declare as `availableTools` on
 * channel creation (docs/03-tool-architektur.md, availability). Derived from the routing table
 * itself rather than duplicated: a tool this client can't render is not "available" here, whatever
 * the backend catalog says.
 */
export const knownToolIds: string[] = Object.keys(toolRoutes)

/** Determines which UI component to show, based solely on `next` - never on a URL. */
export function getUIComponent(next: Next | undefined): string | null {
  if (!next) return null
  if (next.type === 'tool' && next.toolId) {
    return toolRoutes[next.toolId]?.[next.step] ?? null
  }
  if (next.type === 'orchestrator' && next.context) {
    return orchestratorRoutes[next.context]?.[next.step] ?? null
  }
  return null
}
