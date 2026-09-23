import { submitViaPatch } from '../shared/defaultApi'
import type { ToolRenderContext } from '../types'

/** Reports the case Nect returned with; the backend fetches the result from Nect itself. */
export function reportNectCase(ctx: ToolRenderContext, caseId: string) {
  return submitViaPatch(ctx, { caseId })
}

/** Opens a fresh Nect case - after a failed, cancelled or abandoned one. */
export function retryNect(ctx: ToolRenderContext) {
  return submitViaPatch(ctx, { retry: true })
}
