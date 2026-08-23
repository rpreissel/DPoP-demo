/** Pure address, never mixed with content (docs/05-api.md #2). */
export interface Next {
  type: 'tool' | 'flow'
  toolId?: string
  context?: string
  step: string
}

/** Whatever the current step needs to render: missing fields, selection options, or a retry reason. */
export interface StepData {
  missingFields?: string[]
  options?: string[]
  error?: string
  [key: string]: unknown
}

export interface ChannelResponse {
  channelSessionId: string
  state: string
  currentAcr?: string
  currentAmr?: string[]
  stepData?: StepData
  next?: Next
}

/** Demo-only values, never part of the production contract (docs/05-api.md #2). */
export interface DemoInfo {
  accountId?: number
  personId?: number
  /** The just-issued TAN, shown so testers don't need server-log access. */
  tan?: string
}

export interface ToolStateResponse {
  toolSessionId: string
  stepData?: StepData
  next: Next
  demo?: DemoInfo
}
