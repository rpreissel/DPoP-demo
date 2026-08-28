import { useEffect, useState } from 'react'
import type { DpopKeyPair } from '../dpop.ts'
import { getIdClaims, getToken } from '../api.ts'
import type { IdTokenClaims, TokenResponse } from '../types'
import { shorten } from '../format.ts'
import { parseJwtPayload } from '../jwt.ts'

interface TokenPanelProps {
  dpop: DpopKeyPair
  channelSessionId: string
}

/** Forces the backend to mint a fresh AccessToken - larger than ACCESS_TTL on the backend, so no still-valid token ever qualifies. */
const FORCE_REFRESH_MIN_VALIDITY_SECONDS = 10_000

function formatRemaining(expiresAt: string): string {
  const seconds = Math.round((new Date(expiresAt).getTime() - Date.now()) / 1000)
  if (seconds <= 0) return 'abgelaufen'
  if (seconds < 120) return `${seconds}s`
  return `${Math.round(seconds / 60)}min`
}

/**
 * Mock Keycloak AccessToken (docs/05-api.md #2) - self-contained like AdminToolAvailabilityView:
 * loads its own data, doesn't route through App.tsx's applyResponse. Backend decides on every
 * getToken() call whether the current AccessToken still qualifies or a new one gets minted; this
 * panel never sees or sends a RefreshToken value, only its expiry.
 */
export function TokenPanel({ dpop, channelSessionId }: TokenPanelProps) {
  const [token, setToken] = useState<TokenResponse | null>(null)
  const [claims, setClaims] = useState<IdTokenClaims | null>(null)
  const [error, setError] = useState('')

  function loadToken(minValiditySeconds?: number) {
    getToken(dpop, channelSessionId, minValiditySeconds)
      .then(setToken)
      .catch((err) => setError(err instanceof Error ? err.message : String(err)))
  }

  useEffect(() => {
    loadToken()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dpop, channelSessionId])

  function loadClaims() {
    getIdClaims(dpop, channelSessionId)
      .then(setClaims)
      .catch((err) => setError(err instanceof Error ? err.message : String(err)))
  }

  const payload = token ? parseJwtPayload(token.accessToken) : null

  return (
    <div className="card">
      <h3 className="section-heading">Mock-Keycloak-AccessToken</h3>
      {error && <div className="hint">{error}</div>}
      {token && (
        <>
          <ul className="status-list">
            <li>
              <span className="label">AccessToken</span>
              <span className="value" title={token.accessToken}>{shorten(token.accessToken, 12, 8)}</span>
            </li>
            <li>
              <span className="label">Typ</span>
              <span className="value">{token.tokenType}</span>
            </li>
            <li>
              <span className="label">Gültig noch</span>
              <span className="value">{formatRemaining(token.accessExpiresAt)}</span>
            </li>
            <li>
              <span className="label">RefreshToken gültig noch</span>
              <span className="value">{formatRemaining(token.refreshExpiresAt)}</span>
            </li>
          </ul>
          {payload && (
            <>
              <h4>Geparste Claims</h4>
              <ul className="status-list">
                {Object.entries(payload).map(([key, value]) => (
                  <li key={key}>
                    <span className="label">{key}</span>
                    <span className="value">{Array.isArray(value) ? value.join(', ') : String(value)}</span>
                  </li>
                ))}
              </ul>
            </>
          )}
        </>
      )}
      <div className="form-actions">
        <button className="secondary" onClick={() => loadToken(FORCE_REFRESH_MIN_VALIDITY_SECONDS)}>
          AccessToken aktualisieren
        </button>
        <button className="secondary" onClick={loadClaims}>
          ID-Claims laden
        </button>
      </div>
      {claims && (
        <ul className="status-list">
          {Object.entries(claims).map(([key, value]) => (
            <li key={key}>
              <span className="label">{key}</span>
              <span className="value">{Array.isArray(value) ? value.join(', ') : String(value)}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
