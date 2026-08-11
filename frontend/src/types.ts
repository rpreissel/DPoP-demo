export interface NextStep {
  context: string
  step: string
  identificationMethods?: string[]
  authenticationMethods?: string[]
  smsSetupId?: number
}

export interface SessionStatus {
  registrationSessionId?: string
  authorisationSessionId?: string
  next?: NextStep
}

export interface RegistrationSetupResult {
  registrationSessionId: string
  next: NextStep
}
