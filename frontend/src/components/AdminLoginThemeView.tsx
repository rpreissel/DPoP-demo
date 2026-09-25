import { t } from '../texts'
import { Tx } from '../Tx'
import { useEffect, useState } from 'react'
import { ApiError, fetchLoginTheme, setLoginTheme, type LoginTheme } from '../api.ts'

/**
 * Switches Keycloak's login pages between the FreeMarker and the Keycloakify theme
 * (docs/ideen/keycloakify-statt-freemarker.md) - realm-wide and at once, even for a login already
 * under way. The endpoint only exists under the `keycloak` Spring profile; a 404 means "no
 * Keycloak here", like KeycloakSyncView.
 */
export function AdminLoginThemeView() {
  const [theme, setTheme] = useState<LoginTheme | null>(null)
  const [unavailable, setUnavailable] = useState(false)
  const [error, setError] = useState('')
  const [switching, setSwitching] = useState(false)

  function reload() {
    fetchLoginTheme()
      .then((state) => setTheme(state.theme))
      .catch((err) => {
        if (err instanceof ApiError && err.status === 404) setUnavailable(true)
        else setError(err instanceof Error ? err.message : String(err))
      })
  }

  useEffect(reload, [])

  async function toggle() {
    if (theme === null) return
    setSwitching(true)
    try {
      setError('')
      await setLoginTheme(theme === 'KEYCLOAKIFY' ? 'FREEMARKER' : 'KEYCLOAKIFY')
      reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setSwitching(false)
    }
  }

  return (
    <div className="card">
      <h2>{t('Anmeldeseiten')}</h2>
      <p>
        {t(
          'Welche Oberfläche Keycloak für Anmeldung, Registrierung und die Verwaltung der Verfahren zeigt. ' +
            'Gilt sofort für alle Clients, auch mitten in einer laufenden Anmeldung.',
        )}
      </p>
      {unavailable ? (
        <p className="hint">
          <Tx text="Nur verfügbar, wenn das Backend mit dem Spring-Profil {profil} läuft." profil={'"keycloak"'} />
        </p>
      ) : (
        <>
          {error && <p className="error-card">{error}</p>}
          {theme === null ? (
            !error && <p>{t('Lädt…')}</p>
          ) : (
            <ul className="status-list">
              <li>
                <span className="label">{t('Oberfläche')}</span>
                <span className="value-with-action">
                  <span className="value">{theme === 'KEYCLOAKIFY' ? 'Keycloakify (React)' : 'FreeMarker'}</span>
                  <button className="secondary small" onClick={toggle} disabled={switching}>
                    {t('Umschalten')}
                  </button>
                </span>
              </li>
            </ul>
          )}
        </>
      )}
    </div>
  )
}
