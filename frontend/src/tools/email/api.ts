import { submitViaPatch } from '../shared/defaultApi'
import type { ToolRenderContext } from '../types'

export function enrollEmail(ctx: ToolRenderContext, email: string) {
  return submitViaPatch(ctx, { email })
}

export function submitEmailCode(ctx: ToolRenderContext, code: string) {
  return submitViaPatch(ctx, { code })
}

export function requestEmailLookup(ctx: ToolRenderContext, email: string) {
  return submitViaPatch(ctx, { email })
}
