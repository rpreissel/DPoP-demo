export interface NextRouting {
  context: string
  step: string
  methods?: string[]
  enrollmentRef?: string
  accountId?: number
  personId?: number
}

export interface ProcessState {
  purpose?: string
  status?: string
  personId?: number
  accountId?: number
}

export interface AttemptState {
  attemptId?: string
  attemptType?: string
  status?: string
  missingFields?: string[]
  result?: unknown
}

export interface DemoHints {
  tan?: string
  note?: string
}

export interface OrchestratorResponse {
  channelSessionId: string
  processState?: ProcessState
  attemptState?: AttemptState
  next: NextRouting
  _demo?: DemoHints
}

export interface SessionStatus {
  channelSessionId?: string
  next?: NextRouting
  processState?: ProcessState
  attemptState?: AttemptState
  demo?: DemoHints
}
