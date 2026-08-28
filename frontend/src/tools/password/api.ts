import { submitViaPatch } from '../shared/defaultApi'
import type { ToolRenderContext } from '../types'

export function enrollPassword(ctx: ToolRenderContext, fields: { password: string }) {
  return submitViaPatch(ctx, fields)
}

export function submitPassword(ctx: ToolRenderContext, fields: { password: string }) {
  return submitViaPatch(ctx, fields)
}

export function submitPasswordLookup(ctx: ToolRenderContext, fields: { email: string; password: string }) {
  return submitViaPatch(ctx, fields)
}
