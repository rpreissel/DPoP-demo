import type { ReactNode } from 'react'
import type { CallerProof } from '../callerProof'
import type { ChannelResponse, DemoInfo, StepData } from '../types'

/**
 * Everything a tool's own render() needs to draw its current step and call its own api.ts -
 * assembled once in App.tsx (or MockKeycloakView.tsx) from `next`/`activeTool`, never from a
 * per-uiComponent prop list (that per-component prop wiring is exactly what made adding/removing
 * a tool touch App.tsx).
 */
export interface ToolRenderContext {
  step: string
  toolId: string
  /** Only set once a ToolSession exists for this step (docs/05-api.md #2) - device tools need it to build their DPoP-proof htu, others ignore it. */
  toolSessionId?: string
  /** App channel (DPoP) or Mock-Keycloak (peer-auth) - see [CallerProof]; only tools/shared/defaultApi.ts's submitViaPatch reads this. */
  proof: CallerProof
  stepData?: StepData
  /**
   * A note from the journey to show above the tool's form (e.g. why this step appears at all).
   * Its own field, not read from `stepData`: it usually comes from the step BEFORE the tool - the
   * orchestrator's `message` step - and has to survive the tool's own response replacing it.
   */
  message?: string
  demo?: DemoInfo
  /** App.tsx: applyResponse(response, toolId) */
  onResult: (response: ChannelResponse) => void
  /** App.tsx: setError(message) */
  onError: (message: string) => void
  /**
   * Abandons this tool - the same backend-handled step abandon the surrounding view offers, handed
   * to the tool itself so an OPTIONAL step can put the way out where the decision is made: next to
   * its own submit button, not in a toolbar at the other end of the page. Only tools that declare
   * [ToolMeta.skipLabel] render it (and then the surrounding view leaves it out, so there is
   * exactly one of them on screen).
   */
  onSkip?: () => void
}

export interface ToolMeta {
  icon: string
  label: string
  hint: string
  /**
   * Label for the abandon button while THIS tool is running, when "Anderes Verfahren" would be
   * the wrong word: a step that is optional rather than one of several ways to do the same thing
   * (ident-kvnr - abandoning it means "jetzt nicht", and the run carries on without the register
   * binding). Purely wording; the button itself is the same backend-handled abandon
   * (DELETE .../tools/{id}/{toolId}) in both cases.
   */
  skipLabel?: string
}

/** One toolId's registration: its display meta and its own step -> form rendering. */
export interface ToolModule {
  toolId: string
  meta: ToolMeta
  /** Returns null when `ctx.step` isn't one of this tool's own steps. */
  render(ctx: ToolRenderContext): ReactNode | null
}
