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

/** A section heading with a hover/focus-revealed diagram of that section's journey shape - same pattern as AuthenticationCompletedView's own (App-Kanal), kept as its own small copy per module rather than shared. */
function SectionHeading({ text, diagram }: { text: string; diagram: keyof typeof JOURNEY_DIAGRAMS }) {
  return (
    <h3 className="section-heading">
      {text}
      <DiagramHint spec={JOURNEY_DIAGRAMS[diagram]} inline>
        <span className="diagram-hint-trigger" tabIndex={0} aria-label={`Ablauf "${text}" als Diagramm anzeigen`}>
          ℹ️
        </span>
      </DiagramHint>
    </h3>
  )
}

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

  /** exp is in whole seconds since epoch; treat anything undecodable as already expired - the only consequence is skipping a refresh attempt that might have worked, never a wrong logout. */
  function isExpired(token: string | undefined): boolean {
    const exp = token ? parseJwtPayload(token)?.exp : undefined
    return typeof exp !== 'number' || exp * 1000 <= Date.now()
  }

  /**
   * A stored idToken from a long-idle session is very likely expired by the time the user
   * actually clicks "Abmelden" - Keycloak's end_session_endpoint validates id_token_hint's
   * signature/expiry and rejects an expired one outright ("Invalid parameter: id_token_hint", a
   * dead-end error page instead of a logout). Checked locally first rather than always firing a
   * refresh: a still-valid idToken needs no refresh at all, and an already-expired refresh token
   * makes attempting one pointless - only the genuinely ambiguous case (idToken expired, refresh
   * token looks alive) is worth the round-trip, with a catch as a safety net for it turning out
   * revoked anyway. Either way, logging out without a hint (client_id + post_logout_redirect_uri
   * alone) beats handing Keycloak a token it will only reject.
   */
  async function logout() {
    const clientId = tokens?.clientId
    let idToken = tokens?.idToken
    if (isExpired(idToken)) {
      idToken = undefined
      if (tokens?.refreshToken && !isExpired(tokens.refreshToken)) {
        try {
          idToken = (await refreshTokens(tokens.refreshToken, tokens.clientId)).idToken
        } catch {
          idToken = undefined
        }
      }
    }
    setTokens(null)
    storeTokens(null)
    redirectToLogout(idToken, clientId)
  }

  const accessClaims = tokens ? parseJwtPayload(tokens.accessToken) : null
  const idClaims = tokens?.idToken ? parseJwtPayload(tokens.idToken) : null
  const currentAcr = typeof accessClaims?.acr === 'string' ? accessClaims.acr : undefined

  return (
    <>
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
                  <DiagramHint spec={JOURNEY_DIAGRAMS.webLoginLoa1} inline openDown>
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
                  <DiagramHint spec={JOURNEY_DIAGRAMS.webLoginLoa2} inline openDown>
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
                  <DiagramHint spec={JOURNEY_DIAGRAMS.webLoginQrTest} inline openDown>
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

    </div>
      {tokens && (
        <>
          <div className="card success-card">
            <div className="identity-row">
              {/* "name" ist ein Standard-OIDC-Claim aus dem "profile"-Scope (Default-Scope beider
                  Browser-Clients, keycloak-migrations V4/V11) - Keycloaks eingebauter "full name"-
                  Protocol-Mapper aus firstName/lastName, die KeycloakAccountSyncListener beim
                  Account-Sync setzt. */}
              <p>{typeof idClaims?.name === 'string' ? <>Angemeldet als <strong>{idClaims.name}</strong>.</> : 'Sie sind angemeldet.'}</p>
              <button className="secondary small" onClick={logout}>
                Abmelden
              </button>
            </div>
            <ul className="status-list">
              <li>
                <span className="label">Sicherheitsniveau</span>
                <span className="value value-plain">{currentAcr ?? '–'}</span>
              </li>
            </ul>

            {currentAcr !== 'loa2' && (
              <>
                <SectionHeading text="Sicherheitsniveau erhöhen" diagram="stepUp" />
                <p>Ein Step-up fordert einen zusätzlichen Nachweis an (MFA), ohne sich neu anzumelden.</p>
                <div className="form-actions">
                  <button className="secondary" onClick={stepUp}>
                    Sicherheitsniveau jetzt erhöhen
                  </button>
                </div>
              </>
            )}

            <SectionHeading text="Anmeldeverfahren verwalten" diagram="manageMethods" />
            <p>Öffnet Keycloaks eigene Verwaltung Ihrer Anmeldeverfahren (Required Action).</p>
            <div className="form-actions">
              <button className="secondary" onClick={manageMethods}>
                Anmeldeverfahren verwalten
              </button>
            </div>
          </div>

          <div className="card">
            <h3 className="section-heading">AccessToken</h3>
            <ul className="status-list">
              <li>
                <span className="label">Gültig noch</span>
                <span className="value value-plain">{formatRemaining(tokens.expiresAt)}</span>
              </li>
            </ul>
            <div className="form-actions">
              <button className="secondary" onClick={refresh} disabled={!tokens.refreshToken}>
                AccessToken aktualisieren
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
          </div>
        </>
      )}
    </>
  )
}
