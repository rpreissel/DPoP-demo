import { submitViaPatch } from '../shared/defaultApi'
import type { ToolRenderContext } from '../types'

export function submitFsc(ctx: ToolRenderContext, fields: { kvnr: string; name: string; vorname: string; fsc: string }) {
  return submitViaPatch(ctx, fields)
}
