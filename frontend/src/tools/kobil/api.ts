import { describeError, postToolSubResource } from '../../api'
import { submitViaPatch } from '../shared/defaultApi'
import type { ToolRenderContext } from '../types'

/** enroll-kobil's confirmation and auth-kobil's OTP are both ordinary step submissions. */
export function submitKobilStep(ctx: ToolRenderContext, body: Record<string, unknown>) {
  return submitViaPatch(ctx, body)
}

/**
 * Releasing the PIN is the tool's own sub-resource, not a step PATCH: it is a one-shot creation
 * whose response is the only place the PIN appears (docs/05-api.md).
 *
 * Only reachable on the App channel: the Keycloak facade has no KOBIL renderer, since a phone SDK
 * cannot be driven from a server-rendered login page.
 */
export function releaseKobilPin(ctx: ToolRenderContext, unlock: Record<string, unknown>) {
  if (!ctx.toolSessionId || ctx.proof.kind !== 'dpop') return
  return postToolSubResource(ctx.proof.dpop, ctx.toolSessionId, ctx.toolId, 'pin-releases', { unlock })
    .then(ctx.onResult)
    .catch((err) => ctx.onError(describeError('Freigabe fehlgeschlagen', err)))
}
