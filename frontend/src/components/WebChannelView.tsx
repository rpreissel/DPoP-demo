import { t } from '../texts'
import { Tx } from '../Tx'
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { setDemoLoginTheme, type KeycloakInfo, type LoginTheme } from '../api'
import { createWebOidc, LoginNotCompletedError, type TokenSet } from '../webOidc'
import { parseJwtPayload } from '../jwt'
import { shorten } from '../format'
import { UnavailableTools } from './UnavailableTools'
import { Disclosure } from './Disclosure'
import { isBelowAcr } from '../acr'
import { accountRole } from '../accountRole'
import { BrowserFrame } from './BrowserFrame'
import { LanguageSwitch } from './LanguageSwitch'
import { Demo, DemoArea, DemoProvider } from './DemoArea'
import { StepExplanation } from './StepExplanation'
import { SessionSummary } from './SessionSummary'
import { ButtonDiagrams } from './ButtonDiagrams'
import '../phone.css'
import '../browser.css'

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
  if (seconds <= 0) return t('abgelaufen')
  if (seconds < 120) return t('{sekunden}s', { sekunden: seconds })
  return t('{minuten}min', { minuten: Math.round(seconds / 60) })
}

/** Which Keycloak client the website logs in with - a demo choice, made in the demo column. */
type LoginClient = 'native' | 'orchestrator'
const CLIENT_KEY = 'dpop-demo-web-login-client'
/** The portal page to show again after a round trip to Keycloak (a step-up started from it). */
const VIEW_KEY = 'dpop-demo-web-view'

type PortalView = 'home' | 'profile' | 'security' | 'protected'

function readStored<T extends string>(storage: () => Storage, key: string, allowed: readonly T[]): T | undefined {
  try {
    const value = storage().getItem(key)
    return value && (allowed as readonly string[]).includes(value) ? (value as T) : undefined
  } catch {
    return undefined
  }
}

function writeStored(storage: () => Storage, key: string, value: string | null) {
  try {
    if (value === null) storage().removeItem(key)
    else storage().setItem(key, value)
  } catch {
    // A convenience only - without storage the default applies again.
  }
}

/**
 * The Web channel as a website in a browser window (left) next to the demo column (right) - the
 * same split as the App channel's phone. The website is a plain customer portal: a normal sign-in
 * and one for a protected area (loa1 / loa2), both a REAL browser login against the REAL Keycloak
 * (authorization_code + PKCE, see webOidc.ts). Which Keycloak client it uses - Keycloak's own
 * password first, or the orchestrator's method choice - is a demo setting, so it lives in the demo
 * column. ADR-8: the orchestrator only ever hears from Keycloak's own server side, never from here.
 */
export function WebChannelView({ keycloak }: { keycloak: KeycloakInfo }) {
  const {
    completeLoginIfRedirected,
    redirectToLogin,
    redirectToLogout,
    redirectToManageMethods,
    redirectToStepUp,
    refreshTokens,
  } = useMemo(() => createWebOidc(keycloak), [keycloak])
  const [tokens, setTokens] = useState<TokenSet | null>(() => loadStoredTokens())
  const [error, setError] = useState('')
  // "Anmeldung abgebrochen" is the user's own choice, not a failure - shown as a note, not an error.
  const [notice, setNotice] = useState('')
  const [loginClient, setLoginClient] = useState<LoginClient>(() => readStored(() => localStorage, CLIENT_KEY, ['native', 'orchestrator']) ?? 'native')
  const [view, setView] = useState<PortalView>(
    () => readStored(() => sessionStorage, VIEW_KEY, ['home', 'profile', 'security', 'protected']) ?? 'home',
  )
  const [loginTheme, setLoginTheme] = useState<LoginTheme>(keycloak.loginTheme)
  const [themeError, setThemeError] = useState('')
  const completingRef = useRef(false)

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
      .catch((err) => {
        if (err instanceof LoginNotCompletedError && err.cancelled) setNotice(err.message)
        else setError(err instanceof Error ? err.message : String(err))
      })
      .finally(() => {
        completingRef.current = false
        writeStored(() => sessionStorage, VIEW_KEY, null)
      })
  }, [completeLoginIfRedirected])

  function chooseClient(client: LoginClient) {
    setLoginClient(client)
    writeStored(() => localStorage, CLIENT_KEY, client)
  }

  function chooseTheme(theme: LoginTheme) {
    setThemeError('')
    setDemoLoginTheme(theme)
      .then(() => setLoginTheme(theme))
      .catch((err) => setThemeError(err instanceof Error ? err.message : String(err)))
  }

  function clearMessages() {
    setError('')
    setNotice('')
  }

  /** Sign in at the given level; `thenShow` is the page to land on once Keycloak sends the user back. */
  function login(acrValue: '1' | '2', thenShow: PortalView = 'home') {
    clearMessages()
    writeStored(() => sessionStorage, VIEW_KEY, thenShow)
    const clientId = loginClient === 'orchestrator' ? keycloak.qrTestClientId : keycloak.browserClientId
    redirectToLogin(acrValue, clientId).catch((err) => setError(err instanceof Error ? err.message : String(err)))
  }

  function refresh() {
    if (!tokens?.refreshToken) return
    clearMessages()
    refreshTokens(tokens.refreshToken, tokens.clientId)
      .then((fresh) => {
        setTokens(fresh)
        storeTokens(fresh)
      })
      .catch((err) => setError(err instanceof Error ? err.message : String(err)))
  }

  function stepUp(thenShow: PortalView) {
    clearMessages()
    writeStored(() => sessionStorage, VIEW_KEY, thenShow)
    redirectToStepUp(tokens?.clientId).catch((err) => setError(err instanceof Error ? err.message : String(err)))
  }

  function manageMethods() {
    clearMessages()
    writeStored(() => sessionStorage, VIEW_KEY, 'security')
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
    setView('home')
    redirectToLogout(idToken, clientId)
  }

  const accessClaims = tokens ? parseJwtPayload(tokens.accessToken) : null
  const idClaims = tokens?.idToken ? parseJwtPayload(tokens.idToken) : null
  const currentAcr = typeof accessClaims?.acr === 'string' ? accessClaims.acr : undefined
  const refreshExp = tokens?.refreshToken ? parseJwtPayload(tokens.refreshToken)?.exp : undefined
  const refreshExpiresAt = typeof refreshExp === 'number' ? refreshExp * 1000 : undefined
  const belowLoa2 = isBelowAcr(currentAcr, 'loa2')
  // "name" is the standard OIDC claim from the "profile" scope (Keycloak's full-name mapper over
  // firstName/lastName, read through the user federation, ADR-38); the role as in the app (ADR-34),
  // from person_id/versnr, which the federation provides as Keycloak attributes.
  const personName = typeof idClaims?.name === 'string' ? idClaims.name : undefined
  const role = idClaims ? accountRole(idClaims.person_id, idClaims.versnr) : undefined
  const claimText = (value: unknown) => (typeof value === 'string' && value !== '' ? value : undefined)

  const signedOut = (
    <>
      <section className="portal-hero">
        <h1>{t('Willkommen im Kundenportal')}</h1>
        <p>{t('Hier erledigen Sie Ihre Anliegen online - rund um die Uhr.')}</p>
      </section>
      <div className="portal-tiles">
        <div className="portal-tile">
          <h2>{t('Meine Daten')}</h2>
          <p>{t('Kontaktdaten und Versicherungsstatus ansehen.')}</p>
          <button onClick={() => login('1', 'profile')}>{t('Meine Daten ansehen')}</button>
        </div>
        <div className="portal-tile portal-tile--protected">
          <h2>
            <span aria-hidden="true">🔒 </span>
            {t('Gesundheitsdaten')}
          </h2>
          <p>{t('Befunde und Rechnungen. Dafür brauchen Sie eine besonders gesicherte Anmeldung.')}</p>
          <button onClick={() => login('2', 'protected')}>{t('Sicher anmelden')}</button>
        </div>
      </div>
      <p className="portal-note">
        {t('Die Anmeldung übernimmt unser Anmeldedienst Keycloak. Sie werden dafür auf seine Seiten weitergeleitet und danach hierher zurückgebracht.')}
      </p>
      <ButtonDiagrams
        entries={[
          { label: t('Anmelden'), diagram: 'webLoginLoa1' },
          { label: t('Sicher anmelden'), diagram: 'webLoginLoa2' },
        ]}
      />
    </>
  )

  const back = (
    <button className="portal-back" onClick={() => setView('home')}>
      {t('Zurück zur Übersicht')}
    </button>
  )

  const row = (label: string, value?: string) =>
    value ? (
      <li>
        <span className="label">{label}</span>
        <span className="value">{value}</span>
      </li>
    ) : null

  const signedIn: Record<PortalView, ReactNode> = {
    home: (
      <>
        <section className="portal-hero">
          <h1>{personName ? t('Willkommen, {name}!', { name: personName }) : t('Willkommen!')}</h1>
          <p>{role ? t('Sie sind als {status} angemeldet.', { status: role }) : t('Sie sind angemeldet.')}</p>
        </section>
        <ul className="portal-menu">
          <li>
            <button onClick={() => setView('profile')}>
              <strong>{t('Profil')}</strong>
              <span>{t('Ihre persönlichen Daten')}</span>
            </button>
          </li>
          <li>
            <button onClick={() => setView('security')}>
              <strong>{t('Sicherheit')}</strong>
              <span>{t('Sicherheitsniveau und Anmeldeverfahren')}</span>
            </button>
          </li>
          <li>
            <button onClick={() => setView('protected')}>
              <strong>
                <span aria-hidden="true">🔒 </span>
                {t('Gesundheitsdaten')}
              </strong>
              <span>{belowLoa2 ? t('Verlangt eine besonders gesicherte Anmeldung') : t('Befunde und Rechnungen')}</span>
            </button>
          </li>
        </ul>
      </>
    ),
    profile: (
      <>
        {back}
        <h1>{t('Profil')}</h1>
        <ul className="status-list portal-list">
          {row(t('Vor- und Nachname'), personName)}
          {row(t('Status'), role)}
          {row(t('Versichertennummer'), claimText(idClaims?.versnr))}
          {row(t('Partnernummer'), claimText(idClaims?.person_id))}
          {row(t('E-Mail'), claimText(idClaims?.email))}
        </ul>
      </>
    ),
    security: (
      <>
        {back}
        <h1>{t('Sicherheit')}</h1>
        <ul className="status-list portal-list">{row(t('Sicherheitsniveau'), currentAcr ?? '–')}</ul>
        {belowLoa2 && (
          <section className="portal-section">
            <h2>{t('Sicherheitsniveau erhöhen')}</h2>
            <p>{t('Bestätigen Sie Ihre Anmeldung mit einem zweiten Verfahren, dann erreichen Sie Sicherheitsniveau 2 - ohne sich neu anzumelden.')}</p>
            <button onClick={() => stepUp('security')}>{t('Sicherheitsniveau 2 anfordern')}</button>
          </section>
        )}
        <section className="portal-section">
          <h2>{t('Anmeldeverfahren verwalten')}</h2>
          <p>{t('Verfahren hinzufügen oder entfernen. Das geschieht auf den Seiten des Anmeldedienstes.')}</p>
          <button className="secondary" onClick={manageMethods}>
            {t('Anmeldeverfahren verwalten')}
          </button>
        </section>
        <ButtonDiagrams
          entries={[
            ...(belowLoa2 ? [{ label: t('Sicherheitsniveau 2 anfordern'), diagram: 'stepUp' as const }] : []),
            { label: t('Anmeldeverfahren verwalten'), diagram: 'manageMethods' },
          ]}
        />
      </>
    ),
    protected: (
      <>
        {back}
        <h1>
          <span aria-hidden="true">🔒 </span>
          {t('Gesundheitsdaten')}
        </h1>
        {belowLoa2 ? (
          <section className="portal-section">
            <p>{t('Dieser Bereich verlangt eine besonders gesicherte Anmeldung (Sicherheitsniveau 2). Bestätigen Sie Ihre Anmeldung mit einem zweiten Verfahren.')}</p>
            <button onClick={() => stepUp('protected')}>{t('Sicher anmelden')}</button>
            <ButtonDiagrams entries={[{ label: t('Sicher anmelden'), diagram: 'stepUp' }]} />
          </section>
        ) : (
          <ul className="status-list portal-list">
            {row(t('Letzter Befund'), t('Blutbild, 12.08. (Beispiel)'))}
            {row(t('Offene Rechnungen'), t('keine'))}
          </ul>
        )}
      </>
    ),
  }

  // The demo column's "why / what / who" for the page the website shows right now.
  const explanation = tokens
    ? {
        idleReason: t('Keycloak hat die Website angemeldet und ihr Tokens ausgestellt.'),
        does: t('Die Website zeigt, was im ID-Token steht, und hält das Zugangstoken für Anfragen bereit. Den Orchestrator fragt nur Keycloak, im Hintergrund - die Website spricht nie mit ihm.'),
        actor: t('Sie. Sicherheitsniveau erhöhen, Anmeldeverfahren verwalten und Abmelden führen jeweils wieder über Keycloak.'),
        technical: `Client ${tokens.clientId} · acr ${currentAcr ?? '-'}`,
      }
    : {
        idleReason: t('Auf der Website ist noch niemand angemeldet.'),
        does: t('„Anmelden“ fordert bei Keycloak Sicherheitsniveau 1 an, „Sicher anmelden“ Sicherheitsniveau 2. Welche Verfahren Keycloak dann anbietet, entscheidet im Hintergrund der Orchestrator.'),
        actor: t('Sie. Die Website hat noch kein Token.'),
        technical: `Client ${loginClient === 'orchestrator' ? keycloak.qrTestClientId : keycloak.browserClientId}`,
      }

  return (
    <DemoProvider>
      {(setDemoTarget) => (
        <div className="app-stage app-stage--web">
          <div className="app-stage__browser">
            <BrowserFrame url="kundenportal.example">
              <div className="portal">
                <header className="portal-header">
                  <span className="portal-brand">{t('Kundenportal')}</span>
                  <span className="portal-header__actions">
                  <LanguageSwitch className="portal-languages" buttonClassName="portal-language" />
                  {tokens ? (
                    <button className="secondary small" onClick={logout}>
                      {t('Abmelden')}
                    </button>
                  ) : (
                    <button className="small" onClick={() => login('1')}>
                      {t('Anmelden')}
                    </button>
                  )}
                  </span>
                </header>
                <main className="portal-main">
                  <Demo>
                    <StepExplanation {...explanation} />
                  </Demo>
                  {notice && <div className="hint">{notice}</div>}
                  {error && <div className="error-card">{error}</div>}
                  {tokens ? signedIn[view] : signedOut}
                </main>
              </div>
            </BrowserFrame>
          </div>

          <DemoArea
            targetRef={setDemoTarget}
            session={
              <SessionSummary
                signedIn={!!tokens}
                name={personName}
                role={role}
                acr={currentAcr}
                amr={Array.isArray(accessClaims?.amr) ? accessClaims.amr.map(String) : undefined}
              />
            }
            intro={
              !tokens && (
                <div className="card welcome-card">
                  <h2>{t('Dieser Tab ist eine Website')}</h2>
                  <p>
                    <Tx
                      text={
                        'Stellen Sie sich das Kundenportal Ihrer Versicherung im Browser vor. „Anmelden“ leitet Sie - wie bei ' +
                        'jeder großen Website - zu einem {keycloak} weiter (OpenID Connect mit PKCE), und ' +
                        'das AccessToken kommt direkt von dort zurück.'
                      }
                      keycloak={<strong>{t('echten Keycloak')}</strong>}
                    />
                  </p>
                  <p>
                    {t(
                      'Was Keycloak auf seinen Anmeldeseiten abfragt, entscheidet im Hintergrund derselbe Orchestrator wie in der ' +
                        'App: gleiche Konten, gleiche Verfahren, gleiche Sicherheitsniveaus. Ist die App schon angemeldet, können Sie ' +
                        'den Login dort per QR-Code bestätigen. Simuliert ist nur die Website selbst - sie ist diese Seite.',
                    )}
                  </p>
                </div>
              )
            }
          >
            {!tokens && (
              <div className="card">
                <h2>{t('So beginnt die Anmeldung')}</h2>
                <p>{t('Beide Male meldet Keycloak Sie an. Der Unterschied liegt nur in der ersten Anmeldeseite. Das gilt für „Anmelden“ und „Sicher anmelden“.')}</p>
                <div className="client-choice" role="radiogroup" aria-label={t('So beginnt die Anmeldung')}>
                  <label>
                    <input type="radio" name="login-client" checked={loginClient === 'native'} onChange={() => chooseClient('native')} />
                    <span>
                      <strong>{t('Erst das Passwort')}</strong>
                      <span>{t('Wie auf den meisten Websites: zuerst E-Mail-Adresse und Passwort. Weitere Verfahren kommen erst, wenn mehr Sicherheit verlangt ist.')}</span>
                    </span>
                  </label>
                  <label>
                    <input type="radio" name="login-client" checked={loginClient === 'orchestrator'} onChange={() => chooseClient('orchestrator')} />
                    <span>
                      <strong>{t('Gleich alle Verfahren zur Wahl')}</strong>
                      <span>{t('Schon die erste Seite bietet alles an: Passwort, Code per SMS oder E-Mail, oder die Bestätigung mit der App per QR-Code.')}</span>
                    </span>
                  </label>
                </div>
                <UnavailableTools channel="KEYCLOAK" />
              </div>
            )}

            <div className="card">
              <h2>{t('Aussehen der Anmeldeseiten')}</h2>
              <p>{t('Keycloak kann seine Seiten auf zwei Arten zeichnen. Inhalt und Ablauf sind gleich, nur die Technik dahinter unterscheidet sich. Die Wahl gilt sofort für alle Besucher der Demo.')}</p>
              <div className="client-choice" role="radiogroup" aria-label={t('Aussehen der Anmeldeseiten')}>
                <label>
                  <input type="radio" name="login-theme" checked={loginTheme === 'FREEMARKER'} onChange={() => chooseTheme('FREEMARKER')} />
                  <span>
                    <strong>FreeMarker</strong>
                    <span>{t('Keycloaks klassische Seitenvorlagen, auf dem Server erzeugt.')}</span>
                  </span>
                </label>
                <label>
                  <input type="radio" name="login-theme" checked={loginTheme === 'KEYCLOAKIFY'} onChange={() => chooseTheme('KEYCLOAKIFY')} />
                  <span>
                    <strong>Keycloakify</strong>
                    <span>{t('Dieselben Seiten als React-Oberfläche, im Browser gezeichnet.')}</span>
                  </span>
                </label>
              </div>
              {themeError && <div className="hint">{themeError}</div>}
            </div>

            {tokens && (
              <div className="card">
                <h3 className="section-heading">{t('Zugangstoken')}</h3>
                <ul className="status-list">
                  <li>
                    <span className="label">{t('Gültig noch')}</span>
                    <span className="value value-plain">{formatRemaining(tokens.expiresAt)}</span>
                  </li>
                  {/* Keycloak's refresh token is a JWT too - its own exp says how long the website
                      can still renew without a new sign-in (same line as the App's TokenPanel). */}
                  {refreshExpiresAt !== undefined && (
                    <li>
                      <span className="label">{t('RefreshToken gültig noch')}</span>
                      <span className="value value-plain">{formatRemaining(refreshExpiresAt)}</span>
                    </li>
                  )}
                </ul>
                <div className="form-actions">
                  <button className="secondary" onClick={refresh} disabled={!tokens.refreshToken}>
                    {t('AccessToken aktualisieren')}
                  </button>
                </div>

                <Disclosure summary={t('Technische Details (Token, Claims)')}>
                  <ul className="status-list">
                    <li>
                      <span className="label">AccessToken</span>
                      <span className="value" title={tokens.accessToken}>
                        {shorten(tokens.accessToken, 12, 8)}
                      </span>
                    </li>
                  </ul>
                  {accessClaims && <ClaimList title="AccessToken-Claims" claims={accessClaims} />}
                  {idClaims && <ClaimList title="IdToken-Claims" claims={idClaims} />}
                </Disclosure>
              </div>
            )}
          </DemoArea>
        </div>
      )}
    </DemoProvider>
  )
}

function ClaimList({ title, claims }: { title: string; claims: Record<string, unknown> }) {
  return (
    <>
      <h4>{title}</h4>
      <ul className="status-list">
        {Object.entries(claims).map(([key, value]) => (
          <li key={key}>
            <span className="label">{key}</span>
            <span className="value">{Array.isArray(value) ? value.join(', ') : typeof value === 'object' ? JSON.stringify(value) : String(value)}</span>
          </li>
        ))}
      </ul>
    </>
  )
}
