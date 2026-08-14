export interface NextStep {
  context: string
  step: string
  accountId?: number
  personId?: number
  identificationMethods?: string[]
  authenticationMethods?: string[]
  smsSetupId?: number
  tan?: string
}

export interface SessionStatus {
  sessionId?: string
  next?: NextStep
}

export interface FlowSetupResult {
  sessionId?: string
  next: NextStep
}
