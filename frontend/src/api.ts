import { createDpopProof, type DpopKeyPair } from './dpop'
import type { ActiveMethodView, ChannelResponse, IdTokenClaims, TokenResponse } from './types'

/** Carries the server's own error/message (docs/07-betrieb.md #1) instead of a raw fetch string. */
export class ApiError extends Error {
  readonly status: number
  readonly errorCode: string | undefined

  constructor(status: number, errorCode: string | undefined, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.errorCode = errorCode
  }
}

/** One request/response round-trip, for the debug log. DPoP proof headers are omitted deliberately - not relevant to a demo walkthrough, only URL/method/data are. */
export interface ApiCallLogEntry {
  method: string
  path: string
  requestBody?: unknown
  status?: number
  responseBody?: unknown
  error?: string
}

type ApiCallListener = (entry: ApiCallLogEntry) => void
const apiCallListeners: ApiCallListener[] = []

/** Every `call()` invocation is reported here - the single source of truth for the debug log, instead of each caller hand-writing its own log entry (which drifted out of sync with what was actually sent). */
export function onApiCall(listener: ApiCallListener): () => void {
  apiCallListeners.push(listener)
  return () => {
    const index = apiCallListeners.indexOf(listener)
    if (index !== -1) apiCallListeners.splice(index, 1)
  }
}

function notifyApiCall(entry: ApiCallLogEntry) {
  for (const listener of apiCallListeners) listener(entry)
}

async function call<T>(dpop: DpopKeyPair, method: string, path: string, body?: unknown): Promise<T> {
  const url = `${window.location.origin}${path}`
  const proof = await createDpopProof(dpop.keyPair, method, url)
  let response: Response
  try {
    response = await fetch(path, {
      method,
      headers: { 'Content-Type': 'application/json', DPoP: proof },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch (err) {
    notifyApiCall({ method, path, requestBody: body, error: err instanceof Error ? err.message : String(err) })
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
    notifyApiCall({ method, path, requestBody: body, status: response.status, error: message })
    throw new ApiError(response.status, errorCode, message)
  }
  if (response.status === 204) {
    notifyApiCall({ method, path, requestBody: body, status: response.status })
    return undefined as T
  }
  const responseBody = await response.json()
  notifyApiCall({ method, path, requestBody: body, status: response.status, responseBody })
  return responseBody as T
}

/**
 * Always creates a brand-new channel for this device (docs/02-domaenenmodell.md #3) - never a
 * resume. [intent] is the backend's AuthIntent name, case-insensitively (docs/04-orchestrierung.md,
 * lookup-based login): omitted/"fast_access" keeps today's behaviour (DeviceAccountLink found ->
 * LOGIN, else REGISTRATION); "lookup_login" always offers lookup-based login (email + credential),
 * even on an already linked device; "register" always starts fresh REGISTRATION, even on an
 * already linked device (a second account on this device).
 */
export function createChannel(
  dpop: DpopKeyPair,
  requiredAcr?: string,
  intent?: string,
  availableTools?: string[],
): Promise<ChannelResponse> {
  const body: Record<string, unknown> = { availableTools }
  if (requiredAcr) body.requiredAcr = requiredAcr
  if (intent) body.intent = intent
  return call(dpop, 'POST', '/orchestrator/api/v1/app/channels', body)
}

export function getChannel(dpop: DpopKeyPair, channelSessionId: string): Promise<ChannelResponse> {
  return call(dpop, 'GET', `/orchestrator/api/v1/channels/${channelSessionId}`)
}

export function raiseRequiredAcr(dpop: DpopKeyPair, channelSessionId: string, requiredAcr: string): Promise<ChannelResponse> {
  return call(dpop, 'POST', `/orchestrator/api/v1/channels/${channelSessionId}/step-ups`, { requiredAcr })
}

/** Abandons the running AuthJourney; the response already offers a fresh start where applicable. */
export function cancelJourney(dpop: DpopKeyPair, channelSessionId: string): Promise<ChannelResponse> {
  return call(dpop, 'DELETE', `/orchestrator/api/v1/channels/${channelSessionId}/journey`)
}

/** Ends this channel for good (docs/02-domaenenmodell.md #3: logout, terminal) - cancels any active process and discards the AuthContext. Call createChannel again afterwards for a new session. */
export function logoutChannel(dpop: DpopKeyPair, channelSessionId: string): Promise<void> {
  return call(dpop, 'DELETE', `/orchestrator/api/v1/channels/${channelSessionId}`)
}

/** The account's active authentication methods, addressable as their own resource (docs/05-api.md #2). */
export function getMethods(dpop: DpopKeyPair, channelSessionId: string): Promise<{ methods: ActiveMethodView[] }> {
  return call(dpop, 'GET', `/orchestrator/api/v1/channels/${channelSessionId}/methods`)
}

/** Voluntary enrollment on an already-AUTHENTICATED channel (AuthIntent.MANAGE) - offers the existing enroll-* tools, finishes after exactly one. Call again to add another. */
export function startManageMethods(dpop: DpopKeyPair, channelSessionId: string): Promise<ChannelResponse> {
  return call(dpop, 'POST', `/orchestrator/api/v1/channels/${channelSessionId}/enrollments`)
}

/** Deactivates an active method instance (addressed by its own id, not by method name - a method can have several active instances, e.g. multiple devices); rejected (409) if it would drop the account below this channel's required level. */
export function deactivateMethod(dpop: DpopKeyPair, channelSessionId: string, methodInstanceId: string): Promise<ChannelResponse> {
  return call(dpop, 'DELETE', `/orchestrator/api/v1/channels/${channelSessionId}/methods/${methodInstanceId}`)
}

/** Starts the account-deletion journey on an already-AUTHENTICATED channel: an unconditional yes/no confirmation (rendered via PromptView), then a fresh re-proof of any active factor. */
export function startAccountDeletion(dpop: DpopKeyPair, channelSessionId: string): Promise<ChannelResponse> {
  return call(dpop, 'POST', `/orchestrator/api/v1/channels/${channelSessionId}/account-deletions`)
}

/**
 * Covers both first issuance and refresh (docs/05-api.md #2) - call again whenever a fresh token
 * might be needed. minValiditySeconds is the caller's tolerance; the backend alone decides
 * whether the current AccessToken still qualifies or a new one gets minted.
 */
export function getToken(dpop: DpopKeyPair, channelSessionId: string, minValiditySeconds?: number): Promise<TokenResponse> {
  const query = minValiditySeconds !== undefined ? `?minValiditySeconds=${minValiditySeconds}` : ''
  return call(dpop, 'GET', `/orchestrator/api/v1/channels/${channelSessionId}/token${query}`)
}

/** The fachliche ID-token claims - a resource separate from the AccessToken's own claims. */
export function getIdClaims(dpop: DpopKeyPair, channelSessionId: string): Promise<IdTokenClaims> {
  return call(dpop, 'GET', `/orchestrator/api/v1/channels/${channelSessionId}/idclaims`)
}

/** Answers whatever AnswerableState/Prompt the current step is waiting on (docs/05-api.md, Prompt) - one generic endpoint for every such confirmation. */
export function answerPrompt(dpop: DpopKeyPair, channelSessionId: string, accept: boolean): Promise<ChannelResponse> {
  return call(dpop, 'POST', `/orchestrator/api/v1/channels/${channelSessionId}/answer`, { answer: accept ? 'accept' : 'decline' })
}

/**
 * Declines the currently running tool without giving up the journey (docs/04-orchestrierung.md):
 * on a fallback state the chain moves on, on a mandatory one the full choice comes back.
 */
export function abandonTool(dpop: DpopKeyPair, toolSessionId: string, toolId: string): Promise<ChannelResponse> {
  return call(dpop, 'DELETE', `/orchestrator/api/v1/tools/${toolSessionId}/${toolId}`)
}

/** toolId always comes from next.toolId or a chosen stepData.options entry - never constructed by the client. */
export function activateTool(dpop: DpopKeyPair, channelSessionId: string, toolId: string): Promise<ChannelResponse> {
  return call(dpop, 'POST', `/orchestrator/api/v1/channels/${channelSessionId}/tools/${toolId}`)
}

export function patchTool(
  dpop: DpopKeyPair,
  toolSessionId: string,
  toolId: string,
  body: Record<string, unknown>
): Promise<ChannelResponse> {
  return call(dpop, 'PATCH', `/orchestrator/api/v1/tools/${toolSessionId}/${toolId}`, body)
}

export function getTool(dpop: DpopKeyPair, toolSessionId: string, toolId: string): Promise<ChannelResponse> {
  return call(dpop, 'GET', `/orchestrator/api/v1/tools/${toolSessionId}/${toolId}`)
}

export interface ToolAvailabilityEntry {
  toolId: string
  method: string
  enabled: boolean
  reason?: string
}

/** No DPoP: these endpoints (docs/03-tool-architektur.md, availability) aren't bound to a device or channel. */
async function callPlain<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!response.ok) throw new ApiError(response.status, undefined, `${method} ${path} failed: ${response.status}`)
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

export function fetchToolCatalog(): Promise<{ toolId: string; method: string; role: string }[]> {
  return callPlain('GET', '/orchestrator/api/v1/tools/catalog')
}

export function fetchToolAvailability(): Promise<ToolAvailabilityEntry[]> {
  return callPlain('GET', '/orchestrator/api/v1/admin/tools/availability')
}

export function setToolAvailability(toolId: string, enabled: boolean, reason?: string): Promise<void> {
  return callPlain('PUT', `/orchestrator/api/v1/admin/tools/${toolId}/availability`, { enabled, reason })
}

/**
 * Renders any thrown error into the UI's error card. ApiErrors carry the server's own message
 * (docs/07-betrieb.md #1); GONE (session/process expired or exhausted) additionally gets a
 * concrete next step, since "Process for this tool session is gone" alone isn't actionable.
 */
export function describeError(prefix: string, err: unknown): string {
  if (err instanceof ApiError) {
    const hint = err.status === 410 ? ' Bitte in der Struktur bei Channel auf "Vergessen" klicken, um neu zu starten.' : ''
    return `${prefix}: ${err.message}${hint}`
  }
  return `${prefix}: ${err instanceof Error ? err.message : String(err)}`
}
