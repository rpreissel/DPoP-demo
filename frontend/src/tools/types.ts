import type { ReactNode } from 'react'
import type { DpopKeyPair } from '../dpop'
import type { ChannelResponse, DemoInfo, StepData } from '../types'

/**
 * Everything a tool's own render() needs to draw its current step and call its own api.ts -
 * assembled once in App.tsx from `next`/`activeTool`, never from a per-uiComponent prop list
 * (that per-component prop wiring is exactly what made adding/removing a tool touch App.tsx).
 */
export interface ToolRenderContext {
  step: string
  toolId: string
  /** Only set once a ToolSession exists for this step (docs/05-api.md #2) - device tools need it to build their DPoP-proof htu, others ignore it. */
  toolSessionId?: string
  dpop: DpopKeyPair
  stepData?: StepData
  demo?: DemoInfo
  /** App.tsx: applyResponse(response, toolId) */
  onResult: (response: ChannelResponse) => void
  /** App.tsx: setError(message) */
  onError: (message: string) => void
}

export interface ToolMeta {
  icon: string
  label: string
  hint: string
}

/** One toolId's registration: its display meta and its own step -> form rendering. */
export interface ToolModule {
  toolId: string
  meta: ToolMeta
  /** Returns null when `ctx.step` isn't one of this tool's own steps. */
  render(ctx: ToolRenderContext): ReactNode | null
}
