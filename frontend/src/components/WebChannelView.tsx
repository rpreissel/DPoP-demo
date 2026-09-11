import { useEffect, useRef, useState } from 'react'
import {
  completeLoginIfRedirected,
  redirectToLogin,
  redirectToLogout,
  redirectToManageMethods,
  redirectToQrTestLogin,
  redirectToStepUp,
  refreshTokens,
  type TokenSet,
} from '../webOidc'
import { parseJwtPayload } from '../jwt'
import { shorten } from '../format'
import { DiagramHint } from './DiagramHint'
import { JOURNEY_DIAGRAMS } from '../journeyDiagrams'
import { Disclosure } from './Disclosure'

const SESSION_KEY = 'web-kanal-tokens'

function loadStoredTokens(): TokenSet | null {
  const raw = sessionStorage.getItem(SESSION_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as TokenSet
  } catch {
    return null
  }
}

function storeTokens(tokens: TokenSet | null) {
  if (tokens) sessionStorage.setItem(SESSION_KEY, JSON.stringify(tokens))
  else sessionStorage.removeItem(SESSION_KEY)
}

function formatRemaining(expiresAt: number): string {
  const seconds = Math.round((expiresAt - Date.now()) / 1000)
  if (seconds <= 0) return 'abgelaufen'
  if (seconds < 120) return `${seconds}s`
  return `${Math.round(seconds / 60)}min`
}

interface Props {
  onTokens: (tokens: TokenSet | null) => void
}

/**
 * The Web-Kanal demo's own "Demo" sub-tab: a REAL browser login against the REAL Keycloak
 * (authorization_code + PKCE, see webOidc.ts) - login at loa1 or loa2, the resulting
 * AccessToken/IdToken shown and refreshable, a step-up to loa2 if not already there, and a real
 * Keycloak logout. Unlike the App channel's TokenPanel (a Mock/real-via-orchestrator AccessToken
 * for a DPoP-bound App client), this token comes straight from Keycloak's own token endpoint -
 * there is no orchestrator round-trip in this tab at all, by design (docs/12-entscheidungen.md
 * ADR-8: the orchestrator only ever hears from Keycloak's own server-side
 * extension, never from this browser tab).
 */
export function WebChannelView({ onTokens }: Props) {
  const [tokens, setTokens] = useState<TokenSet | null>(() => loadStoredTokens())
  const [error, setError] = useState('')
  const completingRef = useRef(false)

  useEffect(() => {
    onTokens(tokens)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tokens])

  // Picks up `?code=...` after the redirect back from Keycloak - guarded against StrictMode's
  // double effect-invocation (same pattern as App.tsx's activatingToolIdRef): a second concurrent
  // exchange would try to redeem the same, by-then-already-used code again and fail.
  useEffect(() => {
    if (completingRef.current) return
    completingRef.current = true
    completeLoginIfRedirected()
      .then((fresh) => {
        if (fresh) {
          setTokens(fresh)
          storeTokens(fresh)
        }
      })
      .catch((err) => setError(err instanceof Error ? err.message : String(err)))
      .finally(() => {
        completingRef.current = false
      })
  }, [])

  function login(acrValue: '1' | '2') {
    setError('')
    redirectToLogin(acrValue).catch((err) => setError(err instanceof Error ? err.message : String(err)))
  }

  function loginQrTest() {
    setError('')
    redirectToQrTestLogin().catch((err) => setError(err instanceof Error ? err.message : String(err)))
  }

  function refresh() {
    if (!tokens?.refreshToken) return
    setError('')
    refreshTokens(tokens.refreshToken, tokens.clientId)
      .then((fresh) => {
        setTokens(fresh)
        storeTokens(fresh)
      })
      .catch((err) => setError(err instanceof Error ? err.message : String(err)))
  }

  function stepUp() {
    setError('')
    redirectToStepUp(tokens?.clientId).catch((err) => setError(err instanceof Error ? err.message : String(err)))
  }

  function manageMethods() {
    setError('')
    redirectToManageMethods().catch((err) => setError(err instanceof Error ? err.message : String(err)))
  }

  function logout() {
    const clientId = tokens?.clientId
    setTokens(null)
    storeTokens(null)
    redirectToLogout(tokens?.idToken, clientId)
  }

  const accessClaims = tokens ? parseJwtPayload(tokens.accessToken) : null
  const idClaims = tokens?.idToken ? parseJwtPayload(tokens.idToken) : null
  const currentAcr = typeof accessClaims?.acr === 'string' ? accessClaims.acr : undefined

  return (
    <div className="card">
      <h2>Web-Kanal: Login über echtes Keycloak</h2>
      <p>
        Ein echter Browser-Redirect zu Keycloak (Authorization Code + PKCE) - kein Mock, kein Umweg über den
        Orchestrator. Das AccessToken/IdToken kommt direkt von Keycloaks eigenem Token-Endpoint.
      </p>

      {error && <div className="error-card">{error}</div>}

      {!tokens && (
        <ul className="method-choice-list">
          <li>
            <button className="method-choice" onClick={() => login('1')} aria-label="Login (loa1)">
              <span className="method-choice-icon" aria-hidden="true">
                🔑
              </span>
              <span className="method-choice-text">
                <span className="method-choice-label">
                  Login (loa1)
                  <DiagramHint spec={JOURNEY_DIAGRAMS.webLoginLoa1} inline>
                    <span className="diagram-hint-trigger" tabIndex={0} aria-label="Ablauf von Login (loa1) als Diagramm anzeigen">
                      ℹ️
                    </span>
                  </DiagramHint>
                </span>
                <span className="method-choice-hint">Ein Faktor (Passwort oder Code) reicht für dieses Sicherheitsniveau.</span>
              </span>
            </button>
          </li>
          <li>
            <button className="method-choice" onClick={() => login('2')} aria-label="Login (loa2)">
              <span className="method-choice-icon" aria-hidden="true">
                🔐
              </span>
              <span className="method-choice-text">
                <span className="method-choice-label">
                  Login (loa2)
                  <DiagramHint spec={JOURNEY_DIAGRAMS.webLoginLoa2} inline>
                    <span className="diagram-hint-trigger" tabIndex={0} aria-label="Ablauf von Login (loa2) als Diagramm anzeigen">
                      ℹ️
                    </span>
                  </DiagramHint>
                </span>
                <span className="method-choice-hint">Verlangt zusätzlich einen zweiten Faktor - höheres Sicherheitsniveau.</span>
              </span>
            </button>
          </li>
          <li>
            <button className="method-choice" onClick={loginQrTest} aria-label="Login (loa1, QR-Test-Client)">
              <span className="method-choice-icon" aria-hidden="true">
                🧪
              </span>
              <span className="method-choice-text">
                <span className="method-choice-label">
                  Login (loa1, QR-Test-Client)
                  <DiagramHint spec={JOURNEY_DIAGRAMS.webLoginQrTest} inline>
                    <span className="diagram-hint-trigger" tabIndex={0} aria-label="Ablauf von Login (loa1, QR-Test-Client) als Diagramm anzeigen">
                      ℹ️
                    </span>
                  </DiagramHint>
                </span>
                <span className="method-choice-hint">
                  Demo/Test only: eigener Client, dessen loa1 den Verfahren-wählen-Screen des Orchestrators zeigt
                  (inkl. QR-Login), statt des normalen Client-Flows mit nativem Passwort zuerst.
                </span>
              </span>
            </button>
          </li>
        </ul>
      )}

      {tokens && (
        <>
          {/* "name" ist ein Standard-OIDC-Claim aus dem "profile"-Scope (Default-Scope beider
              Browser-Clients, keycloak-migrations V4/V11) - Keycloaks eingebauter "full name"-
              Protocol-Mapper aus firstName/lastName, die KeycloakAccountSyncListener beim
              Account-Sync setzt. */}
          {typeof idClaims?.name === 'string' && (
            <p>
              Angemeldet als <strong>{idClaims.name}</strong>.
            </p>
          )}
          <ul className="status-list">
            <li>
              <span className="label">Gültig noch</span>
              <span className="value value-plain">{formatRemaining(tokens.expiresAt)}</span>
            </li>
            <li>
              <span className="label">Sicherheitsniveau (acr)</span>
              <span className="value value-plain">{currentAcr ?? '–'}</span>
            </li>
          </ul>

          <div className="form-actions">
            <button className="secondary" onClick={refresh} disabled={!tokens.refreshToken}>
              AccessToken aktualisieren
            </button>
            {currentAcr !== 'loa2' && (
              <button className="secondary" onClick={stepUp}>
                Sicherheitsniveau auf loa2 erhöhen
              </button>
            )}
            <button className="secondary" onClick={manageMethods}>
              Anmeldeverfahren verwalten
            </button>
            <button className="secondary" onClick={logout}>
              Abmelden (Keycloak-Logout)
            </button>
          </div>

          <Disclosure summary="Technische Details (Token, Claims)">
            <ul className="status-list">
              <li>
                <span className="label">AccessToken</span>
                <span className="value" title={tokens.accessToken}>{shorten(tokens.accessToken, 12, 8)}</span>
              </li>
            </ul>

            {accessClaims && (
              <>
                <h4>AccessToken-Claims</h4>
                <ul className="status-list">
                  {Object.entries(accessClaims).map(([key, value]) => (
                    <li key={key}>
                      <span className="label">{key}</span>
                      <span className="value">{Array.isArray(value) ? value.join(', ') : typeof value === 'object' ? JSON.stringify(value) : String(value)}</span>
                    </li>
                  ))}
                </ul>
              </>
            )}

            {idClaims && (
              <>
                <h4>IdToken-Claims</h4>
                <ul className="status-list">
                  {Object.entries(idClaims).map(([key, value]) => (
                    <li key={key}>
                      <span className="label">{key}</span>
                      <span className="value">{Array.isArray(value) ? value.join(', ') : typeof value === 'object' ? JSON.stringify(value) : String(value)}</span>
                    </li>
                  ))}
                </ul>
              </>
            )}
          </Disclosure>
        </>
      )}
    </div>
  )
}
