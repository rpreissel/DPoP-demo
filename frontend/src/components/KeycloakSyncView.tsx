import { useState } from 'react'
import { ApiError, syncKeycloak, type KeycloakSyncResult } from '../api.ts'

/**
 * Explicit full reconciliation between orchestrator accounts and Keycloak users (docs/ideen/
 * web-keycloak-kanal.md) - the event-driven sync (KeycloakAccountSyncListener) already keeps
 * things in step as changes happen; this covers what that can't: a missed event, or a Keycloak
 * user left over from a since-deleted account. Only exists as a backend endpoint under the
 * `keycloak` Spring profile - a 404 here means that profile isn't active, not a real error.
 */
export function KeycloakSyncView() {
  const [result, setResult] = useState<KeycloakSyncResult | null>(null)
  const [unavailable, setUnavailable] = useState(false)
  const [error, setError] = useState('')
  const [syncing, setSyncing] = useState(false)

  async function sync() {
    setSyncing(true)
    setError('')
    setResult(null)
    try {
      setResult(await syncKeycloak())
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) {
        setUnavailable(true)
      } else {
        setError(err instanceof Error ? err.message : String(err))
      }
    } finally {
      setSyncing(false)
    }
  }

  return (
    <div className="card">
      <h2>Admin: Keycloak-Sync</h2>
      <p>
        Gleicht jeden Account in Keycloak ab (anlegen/aktualisieren) und löscht Keycloak-User, deren{' '}
        <code>orchestratorAccountId</code> zu keinem Account mehr passt. Läuft normalerweise automatisch bei jeder
        Account-Änderung - dieser Button deckt nur den Nachhol-/Aufräumfall ab.
      </p>
      {unavailable ? (
        <p className="hint">Nur verfügbar, wenn das Backend mit dem Spring-Profil "keycloak" läuft.</p>
      ) : (
        <>
          <button className="secondary" onClick={sync} disabled={syncing}>
            {syncing ? 'Synchronisiere…' : 'Sync with Keycloak'}
          </button>
          {error && <p className="error-card">{error}</p>}
          {result && (
            <p className="hint">
              {result.upserted} Account(s) synchronisiert, {result.deletedOrphans} verwaiste Keycloak-User gelöscht.
            </p>
          )}
        </>
      )}
    </div>
  )
}
