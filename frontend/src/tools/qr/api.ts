import { submitViaPatch } from '../shared/defaultApi'
import type { ToolRenderContext } from '../types'

export function submitPairingCode(ctx: ToolRenderContext, pairingCode: string) {
  return submitViaPatch(ctx, { pairingCode })
}

export function submitDecision(ctx: ToolRenderContext, decision: 'accept' | 'reject') {
  return submitViaPatch(ctx, { decision })
}

export function confirmEnrollQr(ctx: ToolRenderContext) {
  return submitViaPatch(ctx, {})
}
