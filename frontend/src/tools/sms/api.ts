import { submitViaPatch } from '../shared/defaultApi'
import type { ToolRenderContext } from '../types'

export function enrollSmsNumber(ctx: ToolRenderContext, phoneNumber: string) {
  return submitViaPatch(ctx, { phoneNumber })
}

export function submitSmsTan(ctx: ToolRenderContext, tan: string) {
  return submitViaPatch(ctx, { tan })
}

export function requestSmsLookup(ctx: ToolRenderContext, email: string) {
  return submitViaPatch(ctx, { email })
}
