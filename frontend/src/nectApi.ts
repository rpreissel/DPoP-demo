import { ApiError } from './api'

/**
 * The simulated Nect service's own API (`/mock-nect`) - what Nect's jump page talks to. Not this
 * application's contract: no generated types, no DPoP, and nothing here reaches /orchestrator.
 */
export type NectProcedure = 'eid' | 'epass' | 'eudi'

export interface NectAttributes {
  name?: string
  vorname?: string
  geburtsdatum?: string
  strasse?: string
  hausnummer?: string
  plz?: string
  ort?: string
  restrictedId?: string
  documentNumber?: string
  issuingState?: string
  expiryDate?: string
  walletPseudonym?: string
}

export interface NectCaseView {
  caseId: string
  status: 'OPEN' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
}

interface NectRedirect {
  redirectUri: string
}

const BASE = '/mock-nect'

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  const parsed = text === '' ? undefined : JSON.parse(text)
  if (!response.ok) {
    // Nect answers for itself ({"error": ...}), not with our ErrorResponse.
    throw new ApiError(response.status, undefined, parsed?.error ?? `${method} ${path} fehlgeschlagen: ${response.status}`)
  }
  return parsed as T
}

export const nectApi = {
  fall: (caseId: string) => call<NectCaseView>('GET', `/cases/${caseId}`),
  abschliessen: (caseId: string, procedure: NectProcedure, attributes: NectAttributes, pin?: string) =>
    call<NectRedirect>('POST', `/cases/${caseId}/result`, { procedure, attributes, pin }),
  scheitern: (caseId: string, reason: string) => call<NectRedirect>('POST', `/cases/${caseId}/failure`, { reason }),
  abbrechen: (caseId: string) => call<NectRedirect>('POST', `/cases/${caseId}/cancellation`),
}
