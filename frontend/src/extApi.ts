import { ApiError } from './api'
import { REGISTER_TEXTS, resolveText } from './texts'

/**
 * The simulated person register's own API (`/mock-stammdaten`, ADR-31) - not this application's
 * contract, so no generated types and no DPoP: the register knows nothing about our channels.
 */
export interface RegisterPerson {
  id?: number
  kvnr?: string
  name?: string
  vorname?: string
  geburtsdatum?: string
  strasse?: string
  hausnummer?: string
  plz?: string
  ort?: string
}

export interface Freischaltcode {
  id: number
  personId: number
  expiresAt: string
  revokedAt?: string
  valid: boolean
}

export interface Brief {
  id: number
  personId: number
  freischaltcodeId: number
  code: string
  versandtAm: string
}

const BASE = '/mock-stammdaten'

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  const parsed = text === '' ? undefined : JSON.parse(text)
  if (!response.ok) {
    // The register answers for itself ({"error": <text reference>}), in its own texts - not with our ErrorResponse.
    throw new ApiError(response.status, undefined, parsed?.error ? resolveText(parsed.error, REGISTER_TEXTS) : `${method} ${path}: ${response.status}`)
  }
  return parsed as T
}

export const registerApi = {
  personen: () => call<RegisterPerson[]>('GET', '/personen'),
  anlegen: (person: RegisterPerson) => call<RegisterPerson>('POST', '/personen', person),
  aendern: (id: number, person: RegisterPerson) => call<RegisterPerson>('PUT', `/personen/${id}`, person),
  freischaltcodes: (personId: number) => call<Freischaltcode[]>('GET', `/personen/${personId}/freischaltcodes`),
  ausstellen: (personId: number, gueltigBis: string) =>
    call<Brief>('POST', `/personen/${personId}/freischaltcodes`, { gueltigBis }),
  widerrufen: (freischaltcodeId: number) => call<void>('DELETE', `/freischaltcodes/${freischaltcodeId}`),
  briefe: () => call<Brief[]>('GET', '/briefe'),
}
