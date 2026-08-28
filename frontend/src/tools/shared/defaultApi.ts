import { describeError, patchTool } from '../../api'
import type { ToolRenderContext } from '../types'

/**
 * Default "finish this step via PATCH" implementation - not a shared contract every tool must go
 * through, just a convenience a tool's own api.ts may re-export when it happens to fit (see e.g.
 * tools/sms/api.ts). A tool with different needs writes its own api.ts without this import.
 */
export function submitViaPatch(ctx: ToolRenderContext, body: Record<string, unknown>) {
  if (!ctx.toolSessionId) return
  return patchTool(ctx.dpop, ctx.toolSessionId, ctx.toolId, body)
    .then(ctx.onResult)
    .catch((err) => ctx.onError(describeError('Request failed', err)))
}
