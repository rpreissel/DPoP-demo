import { describeError, patchTool } from '../../api'
import { patchKcTool } from '../../kcApi'
import type { ToolRenderContext } from '../types'

/**
 * Default "finish this step via PATCH" implementation - not a shared contract every tool must go
 * through, just a convenience a tool's own api.ts may re-export when it happens to fit (see e.g.
 * tools/sms/api.ts). A tool with different needs writes its own api.ts without this import.
 *
 * The one place `ctx.proof` is actually read (docs/05-api.md Abschnitt 3) - every tool
 * module's own render() stays facade-agnostic, since the two facades' PATCH calls only differ in
 * how they're signed, never in body shape.
 */
export function submitViaPatch(ctx: ToolRenderContext, body: Record<string, unknown>) {
  if (!ctx.toolSessionId) return
  const result =
    ctx.proof.kind === 'dpop'
      ? patchTool(ctx.proof.dpop, ctx.toolSessionId, ctx.toolId, body)
      : patchKcTool(ctx.proof.key, ctx.proof.anchor, ctx.toolSessionId, ctx.toolId, body)
  return result.then(ctx.onResult).catch((err) => ctx.onError(describeError('Request failed', err)))
}
