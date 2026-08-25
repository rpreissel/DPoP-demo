/** Pure address, never mixed with content (docs/05-api.md #2). */
export interface Next {
  type: 'tool' | 'flow'
  toolId?: string
  context?: string
  step: string
  /** The tool resource's full address; set once a ToolSession exists for this step (not right after a selection page). */
  toolSessionId?: string
}

/** Whatever the current step needs to render: missing fields, selection options, or a retry reason. */
export interface StepData {
  missingFields?: string[]
  options?: string[]
  error?: string
  [key: string]: unknown
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

/** Demo-only values, never part of the production contract (docs/05-api.md #2). */
export interface DemoInfo {
  accountId?: number
  personId?: number
  /** The just-issued TAN, shown so testers don't need server-log access. */
  tan?: string
  /** Fixed demo password (same value for enroll/login/lookup), prefilled so testers never have to remember one. */
  password?: string
  /** Fixed demo email (same value for enroll-email and both lookup-based logins), prefilled so testers never have to remember one. */
  email?: string
}

/** The one response envelope for every endpoint, channel- and tool-level alike (docs/05-api.md #2). */
export interface ChannelResponse {
  channel: ChannelBlock
  next?: Next
  stepData?: StepData
  demo?: DemoInfo
}
