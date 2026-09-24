import type { KeycloakInfo } from './api'
import { t } from './texts'

/**
 * The Web-Kanal demo UI's own OIDC client (docs/05-api.md Abschnitt 3) - a REAL browser
 * redirect against the REAL Keycloak, authorization_code + PKCE (S256), token exchange done
 * directly here in the frontend. Deliberately not routed through the orchestrator: the
 * `dpop-demo-web` client is PUBLIC (infra/tofu/keycloak/main.tf) precisely so no client secret
 * ever has to ship inside the browser bundle - PKCE is what makes a public client's authorization
 * code still safe to redeem.
 *
 * Keycloak itself talks to the orchestrator's kc-facade server-to-server (the extension, signed
 * peer-auth), never through this browser. This module is the other direction - the browser
 * itself, acting as the actual RP a real deployment would have.
 */

/**
 * Wo Keycloak für den Browser liegt, welches Realm, welche Clients - vom Server (ServerInfo.keycloak),
 * abgeleitet aus demselben Setup-Parametersatz, aus dem die Migration Realm und Clients anlegt. Früher
 * standen hier Konstanten (https://localhost:8543, Realm "Demo"); jede andere Umgebung (anderer Port,
 * OpenShift-Route, KEYCLOAK_REALM) brach damit den Web-Kanal.
 *
 * `qrTestClientId` ist der Demo/Test-only zweite Client (keycloak-migrations V10/V11): derselbe Realm,
 * aber LoA-1 zeigt dort den orchestrator-eigenen kc_select_method-Screen (inkl. auth-qr-lookup) statt
 * des produktionsnahen nativen Passwortformulars - der Haupt-Client lässt QR-Login nur ab LoA-2
 * (Step-up) erreichen, weil LoA-1 dort bewusst festverdrahtet natives Passwort ist (Keycloak-eigene
 * LoA-Subflow-Konfiguration).
 */
export type WebOidcConfig = KeycloakInfo

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

/** Keycloak came back with `?error=...` instead of a code - `cancelled` for the user's own "Abbrechen" (access_denied). */
export class LoginNotCompletedError extends Error {
  readonly cancelled: boolean

  constructor(error: string, description: string | null) {
    const cancelled = error === 'access_denied'
    super(cancelled ? t('Anmeldung abgebrochen.') : t('Anmeldung fehlgeschlagen: {grund}', { grund: description ?? error }))
    this.name = 'LoginNotCompletedError'
    this.cancelled = cancelled
  }
}

export type WebOidc = ReturnType<typeof createWebOidc>

/** The OIDC client for exactly this Keycloak/realm - every endpoint below is derived from [config]. */
export function createWebOidc(config: WebOidcConfig) {
  const realmBase = `${config.baseUrl}/realms/${encodeURIComponent(config.realm)}/protocol/openid-connect`

  /** Redirects the browser to Keycloak's own login - `acrValue` picks the LoA-1/LoA-2 Condition-LoA branch (infra/tofu/keycloak/main.tf's orchestrator_loa_1/orchestrator_loa_2 subflows), which Keycloak's extension forwards to the orchestrator as targetAcr. `clientId` defaults to the normal browser client; redirectToQrTestLogin passes the QR test client instead. */
  async function redirectToLogin(acrValue: '1' | '2', clientId: string = config.browserClientId) {
    const codeVerifier = randomString(64)
    const codeChallenge = await sha256Base64Url(codeVerifier)
    const uri = redirectUri()
    sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify({ codeVerifier, redirectUri: uri, clientId } satisfies StoredVerifier))

    const url = new URL(`${realmBase}/auth`)
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
  function redirectToStepUp(clientId: string = config.browserClientId) {
    return redirectToLogin('2', clientId)
  }

  /**
   * Demo/Test-only: Login über den zweiten Client (qrTestClientId), dessen LoA-1 orchestrator-
   * driven ist - einziger Weg, `auth-qr-lookup` (Kalt-Einstieg, kein bekannter Account) am Browser zu
   * sehen, ohne vorher ein natives Passwort einzugeben. `acr_values=1` reicht hier immer (kein
   * Step-up), der zweite Client hat trotzdem eine LoA-2-Ebene für den Fall, dass ein bereits über
   * diesen Client authentifizierter Kanal später stept-uppt.
   */
  function redirectToQrTestLogin() {
    return redirectToLogin('1', config.qrTestClientId)
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
  async function redirectToManageMethods() {
    const codeVerifier = randomString(64)
    const codeChallenge = await sha256Base64Url(codeVerifier)
    const uri = redirectUri()
    const clientId = config.browserClientId
    sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify({ codeVerifier, redirectUri: uri, clientId } satisfies StoredVerifier))

    const url = new URL(`${realmBase}/auth`)
    url.searchParams.set('client_id', clientId)
    url.searchParams.set('redirect_uri', uri)
    url.searchParams.set('response_type', 'code')
    url.searchParams.set('scope', 'openid')
    url.searchParams.set('kc_action', 'orchestrator-manage-methods')
    url.searchParams.set('code_challenge', codeChallenge)
    url.searchParams.set('code_challenge_method', 'S256')
    window.location.assign(url.toString())
  }

  /**
   * If the current URL carries a fresh `?code=...` from the redirect above, exchanges it for tokens
   * and scrubs the query string; `?error=...` (e.g. the user cancelled at Keycloak) is scrubbed too
   * and thrown as [LoginNotCompletedError]; otherwise a no-op. Call once, on mount.
   */
  async function completeLoginIfRedirected(): Promise<TokenSet | null> {
    const params = new URLSearchParams(window.location.search)
    const error = params.get('error')
    if (error) {
      sessionStorage.removeItem(SESSION_STORAGE_KEY)
      window.history.replaceState(null, '', window.location.pathname + window.location.hash)
      throw new LoginNotCompletedError(error, params.get('error_description'))
    }
    const code = params.get('code')
    if (!code) return null

    const storedRaw = sessionStorage.getItem(SESSION_STORAGE_KEY)
    if (!storedRaw) throw new Error(t('Kein PKCE {parameter} gefunden - Login bitte erneut starten.', { parameter: 'code_verifier' }))
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

  function refreshTokens(refreshToken: string, clientId: string = config.browserClientId): Promise<TokenSet> {
    return exchangeToken(clientId, { grant_type: 'refresh_token', refresh_token: refreshToken })
  }

  /** Ends the real Keycloak session (not just this tab's tokens) - the Web channel's own logout stays entirely Keycloak's (docs/07-betrieb.md Abschnitt 3), never routed through the orchestrator. */
  function redirectToLogout(idToken: string | undefined, clientId: string = config.browserClientId) {
    const url = new URL(`${realmBase}/logout`)
    if (idToken) url.searchParams.set('id_token_hint', idToken)
    url.searchParams.set('post_logout_redirect_uri', redirectUri())
    url.searchParams.set('client_id', clientId)
    window.location.assign(url.toString())
  }

  async function exchangeToken(clientId: string, params: Record<string, string>): Promise<TokenSet> {
    const body = new URLSearchParams({ client_id: clientId, ...params })
    const response = await fetch(`${realmBase}/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body,
    })
    const json = await response.json()
    if (!response.ok) {
      throw new Error(json.error_description ?? json.error ?? t('Token-Endpoint antwortete mit {status}', { status: response.status }))
    }
    return {
      accessToken: json.access_token,
      idToken: json.id_token,
      refreshToken: json.refresh_token,
      expiresAt: Date.now() + Number(json.expires_in ?? 60) * 1000,
      clientId,
    }
  }

  return {
    redirectToLogin,
    redirectToStepUp,
    redirectToQrTestLogin,
    redirectToManageMethods,
    completeLoginIfRedirected,
    refreshTokens,
    redirectToLogout,
  }
}
