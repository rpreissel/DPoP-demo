import type { ReactNode } from 'react'
import type { ToolMeta, ToolModule, ToolRenderContext } from './types'

/**
 * Auto-discovers every tool folder's default export (its ToolModule[]) - adding or removing a
 * tool means adding or removing a `tools/<name>/index.tsx`, nothing here has to change. `eager`
 * because the module list must be known synchronously (knownToolIds is read at render time, not
 * awaited); `shared/` has no index.tsx and is simply not matched by the glob.
 */
const discovered = import.meta.glob<{ default: ToolModule[] }>('./*/index.tsx', { eager: true })

const TOOL_MODULES: ToolModule[] = Object.values(discovered).flatMap((mod) => mod.default)

const BY_ID: Record<string, ToolModule> = Object.fromEntries(TOOL_MODULES.map((module) => [module.toolId, module]))

/** The toolIds this client can render - what it can honestly declare as `availableTools` (docs/03-tool-architektur.md, availability). */
export const knownToolIds: string[] = TOOL_MODULES.map((module) => module.toolId)

export function metaFor(toolId: string): ToolMeta {
  return BY_ID[toolId]?.meta ?? { icon: '🔐', label: toolId, hint: '' }
}

/** Renders the current step of `ctx.toolId`'s own module, or null if that tool/step is unknown. */
export function renderToolStep(ctx: ToolRenderContext): ReactNode | null {
  return BY_ID[ctx.toolId]?.render(ctx) ?? null
}
