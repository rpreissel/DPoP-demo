import { base64UrlEncode } from './dpop'

/**
 * Client-side stand-in for the real Keycloak Java SPI plugin's peer-auth signing (bd
 * DPoP-demo-f9o.9, docs/05-api.md Abschnitt 3 / docs/12-entscheidungen.md ADR-7) - demo/test only, never a real trust
 * boundary. The private key is fetched from the backend's own `/mock-keycloak/signing-key`
 * (a capability no real Keycloak ever exposes) rather than generated here, so the same key the
 * backend's `PeerAuthValidator` already trusts (via `kc.peer-auth.jwks-uri`) gets used, without
 * either side hand-copying key material.
 */
export interface KcSigningKey {
  privateKey: CryptoKey
  kid: string
}

interface JwkResponse {
  kty: string
  crv: string
  x: string
  y: string
  d: string
  kid: string
}

let cached: Promise<KcSigningKey> | undefined

async function fetchSigningKey(): Promise<KcSigningKey> {
  const response = await fetch('/mock-keycloak/signing-key')
  if (!response.ok) throw new Error(`Mock-Keycloak signing key fetch failed: ${response.status}`)
  const jwk = (await response.json()) as JwkResponse
  const privateKey = await crypto.subtle.importKey(
    'jwk',
    { kty: jwk.kty, crv: jwk.crv, x: jwk.x, y: jwk.y, d: jwk.d },
    { name: 'ECDSA', namedCurve: 'P-256' },
    false,
    ['sign'],
  )
  return { privateKey, kid: jwk.kid }
}

/** Fetched once per page load and reused - the backend regenerates its key on every restart, so a stale cache is never worse than a fresh reload would already require anyway. */
export function getOrFetchKcSigningKey(): Promise<KcSigningKey> {
  if (!cached) cached = fetchSigningKey()
  return cached
}

/**
 * Exactly one of [kcAuthSessionId] (initial login) or [kcSessionId] (step-up) is set, matching
 * [PeerAuthAssertion]'s own invariant on the backend (docs/02-domaenenmodell.md Abschnitt 1).
 */
export async function createPeerAuthAssertion(
  key: KcSigningKey,
  htm: string,
  htu: string,
  anchor: { kcAuthSessionId?: string; kcSessionId?: string },
): Promise<string> {
  const header = { typ: 'JWT', alg: 'ES256', kid: key.kid }
  const nowSeconds = Math.floor(Date.now() / 1000)
  const payload = {
    iss: 'mock-keycloak',
    aud: 'dpop-demo-orchestrator',
    jti: crypto.randomUUID(),
    iat: nowSeconds,
    htm,
    htu,
    ...(anchor.kcAuthSessionId ? { kc_auth_session_id: anchor.kcAuthSessionId } : {}),
    ...(anchor.kcSessionId ? { kc_session_id: anchor.kcSessionId } : {}),
  }

  const encodedHeader = base64UrlEncode(new TextEncoder().encode(JSON.stringify(header)))
  const encodedPayload = base64UrlEncode(new TextEncoder().encode(JSON.stringify(payload)))
  const signingInput = `${encodedHeader}.${encodedPayload}`
  // WebCrypto's ECDSA signature is already the raw (r,s) concatenation JOSE's ES256 expects - no
  // DER conversion needed (same fact createDpopProof in dpop.ts already relies on).
  const signature = await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, key.privateKey, new TextEncoder().encode(signingInput))
  return `${signingInput}.${base64UrlEncode(signature)}`
}
