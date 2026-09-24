import { ApiError } from './api'
import { NECT_TEXTS, resolveText } from './texts'

/**
 * The simulated Nect service's own API (`/mock-nect`) - what Nect's jump page talks to. Not this
 * application's contract: no generated types, no DPoP, and nothing here reaches /orchestrator.
 */
export type NectProcedure = 'eid' | 'epass' | 'eudi'

/** What a relying party can ask Nect for (NectAttribute on the backend). */
export type NectRequestable = 'family_name' | 'given_names' | 'birth_date' | 'address' | 'eid_pseudonym' | 'document_id'

export interface NectAttributes {
  name?: string
  vorname?: string
  geburtsdatum?: string
  /** Street and house number in one line, as eID and PID deliver it. */
  strasse?: string
  plz?: string
  ort?: string
  restrictedId?: string
  documentNumber?: string
  issuingState?: string
}

export interface NectCaseView {
  caseId: string
  status: 'OPEN' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
  requested: NectRequestable[]
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
    // Nect answers for itself ({"error": <text reference>}), in its own texts - not with our ErrorResponse.
    throw new ApiError(response.status, undefined, parsed?.error ? resolveText(parsed.error, NECT_TEXTS) : `${method} ${path}: ${response.status}`)
  }
  return parsed as T
}

export const nectApi = {
  fall: (caseId: string) => call<NectCaseView>('GET', `/cases/${caseId}`),
  /** [expiryDate]: ePass only - Nect checks it itself and does not hand it on. */
  abschliessen: (caseId: string, procedure: NectProcedure, attributes: NectAttributes, pin?: string, expiryDate?: string) =>
    call<NectRedirect>('POST', `/cases/${caseId}/result`, { procedure, attributes, pin, expiryDate }),
  scheitern: (caseId: string, reason: string) => call<NectRedirect>('POST', `/cases/${caseId}/failure`, { reason }),
  abbrechen: (caseId: string) => call<NectRedirect>('POST', `/cases/${caseId}/cancellation`),
}
