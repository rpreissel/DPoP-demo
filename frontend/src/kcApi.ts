import { ApiError, type ApiCallLogEntry } from './api'
import { createPeerAuthAssertion, type KcSigningKey } from './kcSigning'
import type { ChannelResponse } from './types'

// api.ts's own notifyApiCall/apiCallListeners are module-private, so this keeps its own small
// listener list rather than a shared one - the Mock-Keycloak screen wires its calls into the
// same DebugSidebar via onKcApiCall below.
type ApiCallListener = (entry: ApiCallLogEntry) => void
const listeners: ApiCallListener[] = []

async function call<T>(method: string, path: string, anchor: { kcAuthSessionId?: string; kcSessionId?: string }, key: KcSigningKey, body?: unknown): Promise<T> {
  const url = `${window.location.origin}${path}`
  const assertion = await createPeerAuthAssertion(key, method, url, anchor)
  let response: Response
  try {
    response = await fetch(path, {
      method,
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${assertion}` },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch (err) {
    for (const l of listeners) l({ method, path, requestBody: body, error: err instanceof Error ? err.message : String(err) })
    throw err
  }
  if (!response.ok) {
    const text = await response.text()
    let errorCode: string | undefined
    let message = text || `${method} ${path} failed: ${response.status}`
    try {
      const parsed = JSON.parse(text) as { error?: string; message?: string }
      errorCode = parsed.error
      message = parsed.message ?? message
    } catch {
      // Response body wasn't the documented {error, message} shape - fall back to raw text.
    }
    for (const l of listeners) l({ method, path, requestBody: body, status: response.status, error: message })
    throw new ApiError(response.status, errorCode, message)
  }
  const responseBody = await response.json()
  for (const l of listeners) l({ method, path, requestBody: body, status: response.status, responseBody })
  return responseBody as T
}

/** Mirrors api.ts's onApiCall so the Mock-Keycloak screen's calls show up in the same debug log. */
export function onKcApiCall(listener: ApiCallListener): () => void {
  listeners.push(listener)
  return () => {
    const index = listeners.indexOf(listener)
    if (index !== -1) listeners.splice(index, 1)
  }
}

/**
 * One native authenticator proof (docs/05-api.md Abschnitt 3) - just two ids, not
 * method/loa/factorTypes: those are fixed per authenticator TYPE and resolved server-side from a
 * NativeAuthenticatorDescriptor keyed by nativeToolId, the same way an orchestrator tool's own
 * evidence is priced from its ToolDescriptor rather than resent on every outcome. nativeToolId is
 * the authenticator's stable, per-TYPE config id; amrSourceId is this specific proof/execution
 * instance's own id.
 */
export interface AmrEntry {
  nativeToolId: string
  amrSourceId: string
}

/**
 * The kc-facade's one facade-specific endpoint (docs/05-api.md Abschnitt 3) - upsert
 * semantics on a Keycloak-chosen [channelSessionId]. [accountId]/[targetAcr] only matter on
 * step-up (kcSessionId anchor); omitted on initial login. [amr] simulates what native Keycloak
 * authenticators (never an orchestrator tool) already established THIS flow run, one entry per
 * method - merged into the channel's evidence and re-checked against the current floor.
 * [restoreDataToken] is a signed token a PRIOR, unrelated channel's own `fetchKcRestoreData`
 * returned - the bulk, one-shot counterpart to accountId/amr, simulating the Authenticator
 * resubmitting what it stashed in a Keycloak UserSession note (docs/05-api.md Abschnitt 3).
 */
export function upsertKcChannel(
  key: KcSigningKey,
  channelSessionId: string,
  anchor: { kcAuthSessionId?: string; kcSessionId?: string },
  accountId?: number,
  targetAcr?: string,
  amr?: AmrEntry[],
  restoreDataToken?: string,
): Promise<ChannelResponse> {
  const body: Record<string, unknown> = {}
  if (accountId !== undefined) body.accountId = accountId
  if (targetAcr) body.targetAcr = targetAcr
  if (amr && amr.length > 0) body.amr = amr
  if (restoreDataToken) body.restoreData = restoreDataToken
  return call('PATCH', `/orchestrator/api/v1/kc/channels/${channelSessionId}`, anchor, key, body)
}

/**
 * GET .../restore-data (docs/05-api.md Abschnitt 3) - what the real Authenticator's
 * end-of-flow lifecycle hook calls once a UserSessionModel exists, to get a signed token worth
 * stashing in a Keycloak session note for a later step-up's [upsertKcChannel] call to resubmit.
 * [kcSessionId] is that fresh UserSessionModel id - this backend has no other way to learn it yet,
 * so the caller supplies it explicitly; the returned token is bound to exactly this value.
 * `undefined` when the channel has nothing worth restoring yet.
 */
export async function fetchKcRestoreData(
  key: KcSigningKey,
  channelSessionId: string,
  anchor: { kcAuthSessionId?: string; kcSessionId?: string },
  kcSessionId: string,
): Promise<string | undefined> {
  const result = await call<{ restoreData?: string }>(
    'GET',
    `/orchestrator/api/v1/kc/channels/${channelSessionId}/restore-data?kcSessionId=${encodeURIComponent(kcSessionId)}`,
    anchor,
    key,
  )
  return result.restoreData
}

/**
 * A plain "what's next" call - no accountId/targetAcr/amr at all (docs/05-api.md
 * Abschnitt 3: "resume ohne tool aufruf"). Real Keycloak calls this whenever it
 * needs the orchestrator's current view of the channel without having anything new to report -
 * unlike a plain GET, this still goes through the same upsert endpoint every kc call uses.
 */
export function resumeKcChannel(key: KcSigningKey, channelSessionId: string, anchor: { kcAuthSessionId?: string; kcSessionId?: string }): Promise<ChannelResponse> {
  return upsertKcChannel(key, channelSessionId, anchor)
}

/** Same facade-neutral tool endpoints the App channel uses (docs/05-api.md Abschnitt 3) - just signed with the kc peer-auth assertion instead of DPoP. */
export function activateKcTool(key: KcSigningKey, anchor: { kcAuthSessionId?: string; kcSessionId?: string }, channelSessionId: string, toolId: string): Promise<ChannelResponse> {
  return call('POST', `/orchestrator/api/v1/channels/${channelSessionId}/tools/${toolId}`, anchor, key)
}

export function patchKcTool(
  key: KcSigningKey,
  anchor: { kcAuthSessionId?: string; kcSessionId?: string },
  toolSessionId: string,
  toolId: string,
  body: Record<string, unknown>,
): Promise<ChannelResponse> {
  return call('PATCH', `/orchestrator/api/v1/tools/${toolSessionId}/${toolId}`, anchor, key, body)
}

export function abandonKcTool(key: KcSigningKey, anchor: { kcAuthSessionId?: string; kcSessionId?: string }, toolSessionId: string, toolId: string): Promise<ChannelResponse> {
  return call('DELETE', `/orchestrator/api/v1/tools/${toolSessionId}/${toolId}`, anchor, key)
}
