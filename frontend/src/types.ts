export interface NextRouting {
  context: string
  step: string
  methods?: string[]
  enrollmentRef?: string
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

export interface OrchestratorResponse {
  channelSessionId: string
  processState?: ProcessState
  attemptState?: AttemptState
  next: NextRouting
}

export interface SessionStatus {
  channelSessionId?: string
  next?: NextRouting
  processState?: ProcessState
  attemptState?: AttemptState
}
