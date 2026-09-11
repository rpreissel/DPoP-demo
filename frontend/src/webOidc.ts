/**
 * The Web-Kanal demo UI's own OIDC client (docs/05-api.md Abschnitt 3) - a REAL browser
 * redirect against the REAL Keycloak, authorization_code + PKCE (S256), token exchange done
 * directly here in the frontend. Deliberately not routed through the orchestrator: the
 * `dpop-demo-web` client is PUBLIC (infra/tofu/keycloak/main.tf) precisely so no client secret
 * ever has to ship inside the browser bundle - PKCE is what makes a public client's authorization
 * code still safe to redeem.
 *
 * Distinct from `kcSigning.ts`/`kcApi.ts` (Mock-Keycloak tab): those simulate Keycloak calling the
 * orchestrator's kc-facade server-to-server, entirely without a browser. This module is the
 * opposite direction - the browser itself, acting as the actual RP a real deployment would have.
 */

const KEYCLOAK_BASE = 'https://localhost:8543'
const REALM = 'dpop-demo'
const CLIENT_ID = 'dpop-demo-web'
/**
 * Demo/Test-only zweiter Client (keycloak-migrations V10/V11): derselbe Realm, aber LoA-1 zeigt
 * dort den orchestrator-eigenen kc_select_method-Screen (inkl. auth-qr-lookup) statt des
 * produktionsnahen nativen Passwortformulars - der Haupt-Client (dpop-demo-web) lässt QR-Login nur
 * ab LoA-2 (Step-up) erreichen, weil LoA-1 dort bewusst festverdrahtet natives Passwort ist
 * (Keycloak-eigene LoA-Subflow-Konfiguration).
 */
const QR_TEST_CLIENT_ID = 'dpop-demo-web-qr-test'
const SESSION_STORAGE_KEY = 'web-kanal-oidc'

export interface TokenSet {
  accessToken: string
  idToken?: string
  refreshToken?: string
  expiresAt: number
  /** Welcher Client dieses TokenSet geholt hat - refresh/logout müssen denselben Client ansprechen. */
  clientId: string
}

interface StoredVerifier {
  codeVerifier: string
  redirectUri: string
  clientId: string
}

function redirectUri(): string {
  // The path itself doesn't matter (valid_redirect_uris allows the whole origin) - only that
  // Keycloak sends the browser back to the SAME Web-Kanal tab so the code-exchange effect below
  // can pick the `code` param back up from `window.location`.
  return `${window.location.origin}${window.location.pathname}#web`
}

async function sha256Base64Url(input: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(input))
  return base64UrlEncode(new Uint8Array(digest))
}

function base64UrlEncode(bytes: Uint8Array): string {
  let binary = ''
  for (const b of bytes) binary += String.fromCharCode(b)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function randomString(length: number): string {
  const bytes = new Uint8Array(length)
  crypto.getRandomValues(bytes)
  return base64UrlEncode(bytes)
}

/** Redirects the browser to Keycloak's own login - `acrValue` picks the LoA-1/LoA-2 Condition-LoA branch (infra/tofu/keycloak/main.tf's orchestrator_loa_1/orchestrator_loa_2 subflows), same as the acr_values query param the Mock-Keycloak tab's equivalent server-to-server call would carry as targetAcr. `clientId` defaults to the normal browser client; pass QR_TEST_CLIENT_ID (see redirectToQrTestLogin) to exercise the demo/test flow instead. */
export async function redirectToLogin(acrValue: '1' | '2', clientId: string = CLIENT_ID) {
  const codeVerifier = randomString(64)
  const codeChallenge = await sha256Base64Url(codeVerifier)
  const uri = redirectUri()
  sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify({ codeVerifier, redirectUri: uri, clientId } satisfies StoredVerifier))

  const url = new URL(`${KEYCLOAK_BASE}/realms/${REALM}/protocol/openid-connect/auth`)
  url.searchParams.set('client_id', clientId)
  url.searchParams.set('redirect_uri', uri)
  url.searchParams.set('response_type', 'code')
  url.searchParams.set('scope', 'openid')
  url.searchParams.set('acr_values', acrValue)
  url.searchParams.set('code_challenge', codeChallenge)
  url.searchParams.set('code_challenge_method', 'S256')
  window.location.assign(url.toString())
}

/** Same redirect, but for an ALREADY-authenticated session (step-up to loa2) - Keycloak's own Condition-LoA subflow decides whether that needs a fresh prompt or the existing SSO cookie already covers it. `clientId` must match whichever client the current session was authenticated against. */
export function redirectToStepUp(clientId: string = CLIENT_ID) {
  return redirectToLogin('2', clientId)
}

/**
 * Demo/Test-only: Login über den zweiten Client (QR_TEST_CLIENT_ID), dessen LoA-1 orchestrator-
 * driven ist - einziger Weg, `auth-qr-lookup` (Kalt-Einstieg, kein bekannter Account) am Browser zu
 * sehen, ohne vorher ein natives Passwort einzugeben. `acr_values=1` reicht hier immer (kein
 * Step-up), der zweite Client hat trotzdem eine LoA-2-Ebene für den Fall, dass ein bereits über
 * diesen Client authentifizierter Kanal später stept-uppt.
 */
export function redirectToQrTestLogin() {
  return redirectToLogin('1', QR_TEST_CLIENT_ID)
}

/**
 * Web-Kanal-Selbstbedienung "Anmeldeverfahren verwalten" (docs/05-api.md, "Anmeldeverfahren
 * verwalten im Web-Kanal") - the SAME `/auth` redirect as `redirectToLogin`, on the
 * SAME client/flow, just with `kc_action` appended: Keycloak's own mechanism for "an already
 * authenticated user triggers a self-service action". No `acr_values` here - this isn't an ACR
 * negotiation, MANAGE_AUTH_METHODS's own loa2 gate lives entirely in the orchestrator's journey,
 * invisible to Keycloak. If the SSO session is still valid, no login form appears at all; if not,
 * the normal login runs first, then this action - one redirect covers both cases.
 */
export async function redirectToManageMethods() {
  const codeVerifier = randomString(64)
  const codeChallenge = await sha256Base64Url(codeVerifier)
  const uri = redirectUri()
  sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify({ codeVerifier, redirectUri: uri, clientId: CLIENT_ID } satisfies StoredVerifier))

  const url = new URL(`${KEYCLOAK_BASE}/realms/${REALM}/protocol/openid-connect/auth`)
  url.searchParams.set('client_id', CLIENT_ID)
  url.searchParams.set('redirect_uri', uri)
  url.searchParams.set('response_type', 'code')
  url.searchParams.set('scope', 'openid')
  url.searchParams.set('kc_action', 'orchestrator-manage-methods')
  url.searchParams.set('code_challenge', codeChallenge)
  url.searchParams.set('code_challenge_method', 'S256')
  window.location.assign(url.toString())
}

/** If the current URL carries a fresh `?code=...` from the redirect above, exchanges it for tokens and scrubs the query string; otherwise a no-op. Call once, on mount. */
export async function completeLoginIfRedirected(): Promise<TokenSet | null> {
  const params = new URLSearchParams(window.location.search)
  const code = params.get('code')
  if (!code) return null

  const storedRaw = sessionStorage.getItem(SESSION_STORAGE_KEY)
  if (!storedRaw) throw new Error('Kein PKCE code_verifier gefunden - Login bitte erneut starten.')
  const stored = JSON.parse(storedRaw) as StoredVerifier
  sessionStorage.removeItem(SESSION_STORAGE_KEY)

  const tokens = await exchangeToken(stored.clientId, {
    grant_type: 'authorization_code',
    code,
    redirect_uri: stored.redirectUri,
    code_verifier: stored.codeVerifier,
  })
  // Scrubs code/session_state/iss from the address bar - a reload must not try to redeem the same
  // (by-then-already-used) code a second time.
  window.history.replaceState(null, '', window.location.pathname + window.location.hash)
  return tokens
}

export async function refreshTokens(refreshToken: string, clientId: string = CLIENT_ID): Promise<TokenSet> {
  return exchangeToken(clientId, { grant_type: 'refresh_token', refresh_token: refreshToken })
}

/** Ends the real Keycloak session (not just this tab's tokens) - the Web channel's own logout stays entirely Keycloak's (docs/07-betrieb.md Abschnitt 3), never routed through the orchestrator. */
export function redirectToLogout(idToken: string | undefined, clientId: string = CLIENT_ID) {
  const url = new URL(`${KEYCLOAK_BASE}/realms/${REALM}/protocol/openid-connect/logout`)
  if (idToken) url.searchParams.set('id_token_hint', idToken)
  url.searchParams.set('post_logout_redirect_uri', redirectUri())
  url.searchParams.set('client_id', clientId)
  window.location.assign(url.toString())
}

async function exchangeToken(clientId: string, params: Record<string, string>): Promise<TokenSet> {
  const body = new URLSearchParams({ client_id: clientId, ...params })
  const response = await fetch(`${KEYCLOAK_BASE}/realms/${REALM}/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  })
  const json = await response.json()
  if (!response.ok) {
    throw new Error(json.error_description ?? json.error ?? `Token-Endpoint antwortete mit ${response.status}`)
  }
  return {
    accessToken: json.access_token,
    idToken: json.id_token,
    refreshToken: json.refresh_token,
    expiresAt: Date.now() + Number(json.expires_in ?? 60) * 1000,
    clientId,
  }
}
