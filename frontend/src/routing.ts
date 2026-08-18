import type { NextRouting } from './types'

export interface UIRoute {
  component: string
  methods?: string[]
}

/**
 * Fixed local routing table. Decisions are based on context/step/methods, not backend URLs.
 * All backend URLs are implementation details, not part of routing logic.
 */
export const routingTable: Record<string, Record<string, UIRoute>> = {
  registration: {
    selectMethod: { component: 'identification-method-selection', methods: ['fsc'] },
  },
  fsc: {
    input: { component: 'fsc-form' },
  },
  authentication: {
    selectMethod: { component: 'authentication-method-selection', methods: ['sms'] },
    setup: { component: 'authentication-setup', methods: ['sms'] },
    tanInput: { component: 'tan-input-form' },
    authenticated: { component: 'authentication-completed' },
  },
  sms: {
    setup: { component: 'sms-setup-form' },
    tanInput: { component: 'tan-input-form' },
  },
}

/**
 * Determine which UI component to show based on next routing.
 * @param next routing information from backend
 * @returns component name or null if no matching route
 */
export function getUIComponent(next: NextRouting | undefined): string | null {
  if (!next?.context || !next?.step) return null

  const contextRoutes = routingTable[next.context]
  if (!contextRoutes) return null

  const route = contextRoutes[next.step]
  if (!route) return null

  return route.component
}

/**
 * Get available methods for the current routing state.
 * Use backend methods if provided, otherwise fall back to route configuration.
 */
export function getAvailableMethods(next: NextRouting | undefined): string[] {
  if (!next) return []

  // Prefer backend-provided methods
  if (next.methods && next.methods.length > 0) {
    return next.methods
  }

  // Fall back to routing table configuration
  const contextRoutes = routingTable[next.context]
  const route = contextRoutes?.[next.step]
  return route?.methods ?? []
}
