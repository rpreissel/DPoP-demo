/** Pure address, never mixed with content (docs/05-api.md #2). */
export interface Next {
  /** Both values name the owner of the next screen, and thus which endpoint to call next. */
  type: 'tool' | 'orchestrator'
  toolId?: string
  context?: string
  step: string
  /** The tool resource's full address; set once a ToolSession exists for this step (not right after a selection page). */
  toolSessionId?: string
}

/**
 * Whatever the current step needs to render: missing fields, selection options, or a retry
 * reason. `title`/`description` accompany `options` - backend-authored heading for the selection
 * screen (OfferingState.selectionTitle), same reasoning as `prompt`: the client can't guess what
 * the user is actually being asked just from a shared `context`/`step` address.
 */
export interface StepData {
  missingFields?: string[]
  options?: string[]
  title?: string
  description?: string
  error?: string
  prompt?: Prompt
  [key: string]: unknown
}

/**
 * What an AnswerableState shows while it waits for `POST .../answer` - authored entirely by the
 * backend (docs/orchestrator/journey/state/Prompt.kt): the app channel is a mobile app with
 * week-long release cycles, so screen text must be able to change without an app release. `@t` is
 * the Jackson polymorphism discriminator; `Confirm` is the only variant today.
 */
export interface Prompt {
  '@t': 'Confirm'
  title: string
  description?: string
  confirmLabel: string
  cancelLabel: string
  destructive?: boolean
}

/**
 * One active authentication method instance. `id` addresses it for DELETE .../methods/{id} -
 * method name alone isn't unique when a method allows multiple instances (docs/03-tool-architektur.md,
 * e.g. several active `device` entries, one per physical device). `label` is a user-chosen display
 * name, set only for multi-instance methods - undefined for singleton ones (email/sms/password).
 */
export interface ActiveMethodView {
  id: string
  method: string
  label?: string
}

export interface ChannelBlock {
  channelSessionId: string
  state: string
  currentAcr?: string
  currentAmr?: string[]
  /** All active methods on the account, regardless of whether this session proved them - distinct from currentAmr (session evidence). */
  activeMethods?: ActiveMethodView[]
}

/** One journey in the running chain for a channel - see backend `JourneyDebugStep`. */
export interface JourneyDebugStep {
  journeyId: string
  intent: string
  lifecycle: string
  stateType: string
  /** Demo-only: why this journey's current step looks the way it does - only ever set on the innermost (actually active) journey, null whenever the step already explains itself (e.g. a Prompt). */
  note?: string
}

/** Demo-only values, never part of the production contract (docs/05-api.md #2). */
export interface DemoInfo {
  accountId?: number
  personId?: number
  /** The running journey chain for this channel, outermost first. Empty once nothing is running. */
  journeys?: JourneyDebugStep[]
  /** The just-issued TAN, shown so testers don't need server-log access. */
  tan?: string
  /** Fixed demo password (same value for enroll/login/lookup), prefilled so testers never have to remember one. */
  password?: string
  /** Fixed demo email (same value for enroll-email and both lookup-based logins), prefilled so testers never have to remember one. */
  email?: string
}

/**
 * Mock Keycloak AccessToken (docs/05-api.md #2) - accessToken is a spec-shaped unsecured JWT
 * (alg=none), parse and display its payload directly, no verification needed. refreshToken is
 * deliberately NOT part of this shape - it's a credential and never leaves the backend.
 */
export interface TokenResponse {
  accessToken: string
  tokenType: string
  accessExpiresAt: string
  refreshExpiresAt: string
}

/** Fachliche ID-Token-Claims - a resource separate from the AccessToken's own claims. */
export interface IdTokenClaims {
  sub?: string
  acr?: string
  amr?: string[]
  auth_time?: number
  accountId?: number
  personId?: number
  email?: string
  email_verified?: boolean
  [key: string]: unknown
}

/** The one response envelope for every endpoint, channel- and tool-level alike (docs/05-api.md #2). */
export interface ChannelResponse {
  channel: ChannelBlock
  next?: Next
  stepData?: StepData
  demo?: DemoInfo
}

/**
 * One row of the rich, per-step journey trace (GET /journey-log) - distinct from the backend's
 * minimized session_event audit trail. Demo/debug only: shows everything the backend could
 * determine about a journey's path, grouped client-side by channelSessionId/journeyId.
 */
export interface JourneyLogEntryView {
  channelSessionId: string
  /** Null for a channel-level event with no journey of its own (e.g. logout with nothing running). */
  journeyId?: string
  /** Set when journeyId ran as another journey's precondition (docs/04-orchestrierung.md #6). */
  parentJourneyId?: string
  intent?: string
  eventType: string
  detail: Record<string, unknown>
  createdAt: string
}

export interface JourneyLogResponse {
  entries: JourneyLogEntryView[]
}
