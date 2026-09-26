import { ADMIN_PATH, adminAuthHeader, clearAdminCredentials } from './adminAuth'
import { createDpopProof, type DpopKeyPair } from './dpop'
import type { ActiveMethodView, ChannelResponse, DeviceLinkResponse, ErrorResponse, IdTokenClaims, JourneyTraceResponse, TokenResponse } from './types'
import { ErrorResponseErrorEnum } from './generated/models'
import { resolveText, t } from './texts'

/**
 * Reads an error body. The shape is the contract's `ErrorResponse`; anything else (a proxy's HTML
 * page, an empty body) falls back to the raw text, so an error never gets lost in parsing.
 * `errorCode` stays a plain string: the contract says a client must expect codes it does not know.
 */
export function parseErrorBody(text: string, fallback: string): { errorCode: string | undefined; message: string } {
  try {
    const parsed = JSON.parse(text) as Partial<ErrorResponse>
    // The server sends a text reference; it becomes words in the reader's language here.
    return { errorCode: parsed.error, message: parsed.text ? resolveText(parsed.text) : fallback }
  } catch {
    return { errorCode: undefined, message: fallback }
  }
}

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

/**
 * A genuine race on the same session's optimistically-locked row (two tabs, a doubled effect, a
 * client retry overlapping the original) - not just a StrictMode artifact (OrchestratorException
 * Handler.handleConcurrentModification's own doc: "the loser should retry against freshly-read
 * state"). Retried here, once, rather than serializing writes server-side: the conflict means the
 * write never applied at all (optimistic locking fails BEFORE any commit), so a retry is always
 * safe regardless of HTTP method, and a per-session lock would pay a serialization cost on every
 * request just to avoid a case this rare.
 */
const CONCURRENT_MODIFICATION_RETRY_DELAY_MS = 150

async function call<T>(dpop: DpopKeyPair, method: string, path: string, body?: unknown, isRetry = false): Promise<T> {
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
    const { errorCode, message } = parseErrorBody(text, text || `${method} ${path} failed: ${response.status}`)
    if (!isRetry && errorCode === ErrorResponseErrorEnum.CONCURRENT_MODIFICATION) {
      await new Promise((resolve) => setTimeout(resolve, CONCURRENT_MODIFICATION_RETRY_DELAY_MS))
      return call(dpop, method, path, body, true)
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

/** Whether this device is already linked to an account (docs/05-api.md) - a pure read, no channel/journey created. */
export function getDeviceLink(dpop: DpopKeyPair): Promise<DeviceLinkResponse> {
  return call(dpop, 'GET', '/orchestrator/api/v1/app/channels/device-link')
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

/** Starts a LOGOUT journey with a confirmation prompt (interactive clients). */
export function startLogout(dpop: DpopKeyPair, channelSessionId: string): Promise<ChannelResponse> {
  return call(dpop, 'POST', `/orchestrator/api/v1/channels/${channelSessionId}/logouts`)
}

/** Direct logout without confirmation (non-interactive clients, hard logout). */
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

/** Confirm a WEB-channel QR login from this already-AUTHENTICATED channel (AuthIntent.CONFIRM_PEER_LOGIN, docs/04-orchestrierung.md) - gates on loa2 (step-up offered first if below it), then offers confirm-qr-login. */
export function startPeerLogin(dpop: DpopKeyPair, channelSessionId: string): Promise<ChannelResponse> {
  return call(dpop, 'POST', `/orchestrator/api/v1/channels/${channelSessionId}/peer-logins`)
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

/**
 * "Zurück": leaves the running tool WITHOUT declining it - the journey shows its selection again,
 * this tool still among the options. Where there is no selection, the same as [abandonTool].
 */
export function backFromTool(dpop: DpopKeyPair, toolSessionId: string, toolId: string): Promise<ChannelResponse> {
  return call(dpop, 'POST', `/orchestrator/api/v1/tools/${toolSessionId}/${toolId}/back`)
}

/**
 * toolId always comes from next.toolId or a chosen stepData.options entry - never constructed by
 * the client. [body] is only ever used by confirm-qr-login, to pass an already-known pairing code
 * (session.ts's pending-pairing-code) straight into activation so its own `input` step can be
 * skipped server-side (ConfirmQrLoginToolController) - every other tool activates with none.
 */
export function activateTool(
  dpop: DpopKeyPair,
  channelSessionId: string,
  toolId: string,
  body?: Record<string, unknown>
): Promise<ChannelResponse> {
  return call(dpop, 'POST', `/orchestrator/api/v1/channels/${channelSessionId}/tools/${toolId}`, body)
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

/**
 * A POST to a resource a tool defines under its own URL namespace (docs/05-api.md: everything
 * below `/tools/{toolSessionId}/{toolId}` is the tool's own design). Generic on purpose - the
 * sub-path belongs to the tool's own api.ts, not to this shared client, which only knows how to
 * sign and how to read a ChannelResponse back.
 */
export function postToolSubResource(
  dpop: DpopKeyPair,
  toolSessionId: string,
  toolId: string,
  subPath: string,
  body: Record<string, unknown>
): Promise<ChannelResponse> {
  return call(dpop, 'POST', `/orchestrator/api/v1/tools/${toolSessionId}/${toolId}/${subPath}`, body)
}

export type MethodRole =
  | 'IDENTIFIED_AUTH'
  | 'LOOKUP_AUTH'
  | 'IDENTIFICATION'
  | 'CORRELATION'
  | 'ENROLLMENT'
  | 'ATTESTATION'
  | 'PEER_APPROVAL'

export interface ToolAvailabilityEntry {
  toolId: string
  method: string
  /** Which kind of selection list the tool appears in - the order only matters within one role. */
  role: MethodRole
  enabled: boolean
  reason?: string
}

/** APP = App-Kanal, KEYCLOAK = Web-Kanal. */
export type ChannelType = 'APP' | 'KEYCLOAK'

/** One channel type's tools, in the order that channel offers them. */
export interface ChannelToolAvailability {
  channel: ChannelType
  tools: ToolAvailabilityEntry[]
}

/**
 * No DPoP: these endpoints (docs/03-tool-architektur.md, availability) aren't bound to a device or
 * channel. Operator endpoints under ADMIN_PATH get the admin login's Basic header instead; a 401
 * there logs the admin page out, so it shows its login form again.
 */
async function callPlain<T>(method: string, path: string, body?: unknown): Promise<T> {
  const admin = path.startsWith(ADMIN_PATH)
  const auth = admin ? adminAuthHeader() : null
  const response = await fetch(path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(auth ? { Authorization: auth } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (admin && response.status === 401) clearAdminCredentials()
  if (!response.ok) throw new ApiError(response.status, undefined, `${method} ${path} failed: ${response.status}`)
  // A Kotlin `Unit`-returning controller method (e.g. every admin PUT here) comes back as
  // 200 with an empty body, not 204 - relying on the status code alone made `.json()` throw
  // "Unexpected end of JSON input" on every such call. Reading as text first and only parsing
  // when there's actually something to parse covers both cases.
  const text = await response.text()
  return (text === '' ? undefined : JSON.parse(text)) as T
}

export function fetchToolCatalog(): Promise<{ toolId: string; method: string; role: string }[]> {
  return callPlain('GET', '/orchestrator/api/v1/tools/catalog')
}

export function fetchToolAvailability(): Promise<ChannelToolAvailability[]> {
  return callPlain('GET', `${ADMIN_PATH}/tools/availability`)
}

export function setToolAvailability(toolId: string, channel: ChannelType, enabled: boolean, reason?: string): Promise<void> {
  return callPlain('PUT', `${ADMIN_PATH}/tools/${toolId}/availability/${channel}`, { enabled, reason })
}

/** First entry is offered first; applies to every selection screen of that channel type. */
export function setToolOrder(channel: ChannelType, toolIds: string[]): Promise<void> {
  return callPlain('PUT', `${ADMIN_PATH}/tools/order/${channel}`, { toolIds })
}

export interface RegistrationOrderState {
  enrollFirst: boolean
}

/** REGISTER's "Enrollment zuerst" experiment (docs/04-orchestrierung.md) - global, takes effect for the next brand-new REGISTER journey. */
export function fetchRegistrationOrder(): Promise<RegistrationOrderState> {
  return callPlain('GET', '/orchestrator/admin/registration-order')
}

export function setRegistrationOrder(enrollFirst: boolean): Promise<void> {
  return callPlain('PUT', '/orchestrator/admin/registration-order', { enrollFirst })
}

/** Which login theme Keycloak shows (docs/ideen/keycloakify-statt-freemarker.md). */
export type LoginTheme = 'FREEMARKER' | 'KEYCLOAKIFY'

/** Only under the server's `keycloak` profile - 404s otherwise. */
export function fetchLoginTheme(): Promise<{ theme: LoginTheme }> {
  return callPlain('GET', `${ADMIN_PATH}/login-theme`)
}

/** Realm-wide, from the next page Keycloak renders. */
export function setLoginTheme(theme: LoginTheme): Promise<void> {
  return callPlain('PUT', `${ADMIN_PATH}/login-theme`, { theme })
}

/**
 * The same realm-wide switch without an admin login (DemoLoginThemeController) - the website's demo
 * column offers it to every visitor, so the two themes can be compared where the pages open.
 */
export function setDemoLoginTheme(theme: LoginTheme): Promise<void> {
  return callPlain('PUT', '/orchestrator/demo/login-theme', { theme })
}

/** Same shape as JourneyTraceResponse, plus who each account id is (register display name). */
export interface AdminJourneyTraceResponse extends JourneyTraceResponse {
  accounts: { accountId: number; displayName?: string | null }[]
}

export function fetchAdminJourneyTrace(limit = 500): Promise<AdminJourneyTraceResponse> {
  return callPlain('GET', `${ADMIN_PATH}/journey-trace?limit=${limit}`)
}

export interface AdminAccount {
  accountId: number
  personId?: number | null
  displayName?: string | null
  email?: string | null
  methods: string[]
}

export function fetchAdminAccounts(): Promise<AdminAccount[]> {
  return callPlain('GET', `${ADMIN_PATH}/accounts`)
}

export function deleteAdminAccount(accountId: number): Promise<void> {
  return callPlain('DELETE', `${ADMIN_PATH}/accounts/${accountId}`)
}

export function resetDemo(): Promise<{ deletedAccounts: number; seededAccounts: number }> {
  return callPlain('POST', `${ADMIN_PATH}/demo-reset`)
}

/** Probes the stored credentials - any admin GET does; 401 clears them (see callPlain). */
export function checkAdminLogin(): Promise<unknown> {
  return fetchRegistrationOrder()
}

/** The real Keycloak as this browser reaches it - only under the server's `keycloak` profile. */
export interface KeycloakInfo {
  /** Public address (what the browser resolves), not the server-to-server one. */
  baseUrl: string
  realm: string
  browserClientId: string
  qrTestClientId: string
  loginTheme: LoginTheme
}

export interface ServerInfo {
  /** null/absent without the `keycloak` profile - then there is no Web channel. */
  keycloak?: KeycloakInfo | null
  registrationEnrollFirst: boolean
  disabledTools: { toolId: string; channel: ChannelType; reason?: string | null }[]
  /** Demo mode - among other things, responses carry the demo values (TANs, personas). */
  demoMode: boolean
  /** Health and metrics of the actuator (management port), read by the backend. */
  operations: OperationsInfo
}

export interface OperationsInfo {
  status: string
  components: { name: string; status: string }[]
  /** `dpop.*` meters per tag set; `http.client.requests` per host with `meanMillis`. */
  metrics: { name: string; tags: Record<string, string>; value: number; meanMillis?: number | null }[]
}

/** Public, read-only (no login) - the welcome page's "Server-Status" tab. */
export function fetchServerInfo(): Promise<ServerInfo> {
  return callPlain('GET', '/orchestrator/demo/server-info')
}

/**
 * Renders any thrown error into the UI's error card. ApiErrors carry the server's own message
 * (docs/07-betrieb.md #1); PROCESS_GONE (session/process expired or consumed) additionally gets a
 * concrete next step, since "Process for this tool session is gone" alone isn't actionable.
 *
 * Only PROCESS_GONE, not every 410: PROCESS_ABORTED shares the status but means the server ended
 * the process on purpose and its message already says what to do (e.g. "bitte zuerst regulär
 * anmelden" when a QR link opens on a device with no account) - "Vergessen" would not help there.
 */
export function describeError(prefix: string, err: unknown): string {
  // A wording may end in its own full stop ("Der Start hat nicht geklappt.") - never "geklappt.:".
  prefix = prefix.replace(/[.:]\s*$/, '')
  if (err instanceof ApiError) {
    const hint = err.errorCode === ErrorResponseErrorEnum.PROCESS_GONE
      ? ' ' + t('Bitte in der Struktur bei Channel auf "Vergessen" klicken, um neu zu starten.')
      : ''
    return `${prefix}: ${err.message}${hint}`
  }
  return `${prefix}: ${err instanceof Error ? err.message : String(err)}`
}
