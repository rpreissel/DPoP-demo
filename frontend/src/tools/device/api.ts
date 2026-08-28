import { submitViaPatch } from '../shared/defaultApi'
import type { ToolRenderContext } from '../types'

export function enrollDevice(ctx: ToolRenderContext, body: Record<string, unknown>) {
  return submitViaPatch(ctx, body)
}

export function authDevice(ctx: ToolRenderContext, body: Record<string, unknown>) {
  return submitViaPatch(ctx, body)
}
