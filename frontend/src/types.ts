export interface NextStep {
  context: string
  step: string
  identificationMethods?: string[]
  authenticationMethods?: string[]
  smsSetupId?: number
  tan?: string
}

export interface SessionStatus {
  registrationSessionId?: string
  authorisationSessionId?: string
  sessionId?: string
  next?: NextStep
}

export interface RegistrationSetupResult {
  registrationSessionId?: string
  authorisationSessionId?: string
  sessionId?: string
  next: NextStep
}
