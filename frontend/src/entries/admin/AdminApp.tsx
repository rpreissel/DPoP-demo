import { useCallback, useEffect, useState, type FormEvent } from 'react'
import '../../App.css'
import {
  checkAdminLogin,
  deleteAdminAccount,
  describeError,
  fetchAdminAccounts,
  fetchAdminJourneyLog,
  resetDemo,
  type AdminAccount,
} from '../../api'
import { adminAuthHeader, clearAdminCredentials, onAdminLoggedOut, setAdminCredentials } from '../../adminAuth'
import { AdminRegistrationOrderView } from '../../components/AdminRegistrationOrderView'
import { AdminToolAvailabilityView } from '../../components/AdminToolAvailabilityView'
import { ChannelNav, type NavTab } from '../../components/ChannelNav'
import { DeveloperToolsCard } from '../../components/DeveloperToolsCard'
import { JourneyLogView } from '../../components/JourneyLogView'
import { KeycloakSyncView } from '../../components/KeycloakSyncView'
import { useHashTab } from '../../useHashTab'
import { t } from '../../texts'
import { Tx } from '../../Tx'

type Tab = 'einstellungen' | 'journeylog' | 'konten'
const TAB_KEYS = ['einstellungen', 'journeylog', 'konten'] as const
const TABS: NavTab<Tab>[] = [
  { key: 'einstellungen', label: t('Einstellungen') },
  { key: 'journeylog', label: t('Journey-Log') },
  { key: 'konten', label: t('Konten') },
]

/**
 * The operator's page (docs/10-frontend.md #0): everything that switches the whole deployment,
 * plus the views across all accounts - behind the admin login (AdminSecurityConfig). The two
 * channels only show what a user of that channel would see; the journey log lives only here.
 */
export function AdminApp() {
  const [loggedIn, setLoggedIn] = useState(() => adminAuthHeader() !== null)
  const [tab, setTab] = useHashTab<Tab>(TAB_KEYS, 'einstellungen')

  useEffect(() => onAdminLoggedOut(() => setLoggedIn(false)), [])

  if (!loggedIn) {
    return (
      <div className="web-shell channel-admin">
        <ChannelNav badge={'🛠️ ' + t('Admin')} />
        <div className="web-page">
          <LoginForm onLoggedIn={() => setLoggedIn(true)} />
        </div>
      </div>
    )
  }

  return (
    <div className="web-shell channel-admin">
      <ChannelNav
        badge={'🛠️ ' + t('Admin')}
        tabs={TABS}
        sub={tab}
        onSelectTab={setTab}
        actions={
          <button className="secondary small" onClick={clearAdminCredentials}>
            {t('Abmelden')}
          </button>
        }
      />
      <div className={tab === 'journeylog' ? 'web-page web-page-wide' : 'web-page'}>
        {tab === 'einstellungen' && (
          <>
            <AdminToolAvailabilityView />
            <AdminRegistrationOrderView />
            <KeycloakSyncView />
            <DeveloperToolsCard />
          </>
        )}
        {tab === 'journeylog' && (
          <JourneyLogView fetchLog={fetchAdminJourneyLog} />
        )}
        {tab === 'konten' && <AccountsTab />}
      </div>
    </div>
  )
}

function LoginForm({ onLoggedIn }: { onLoggedIn: () => void }) {
  const [username, setUsername] = useState('admin')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setAdminCredentials(username, password)
    try {
      await checkAdminLogin()
      setError(null)
      onLoggedIn()
    } catch {
      setError(t('Anmeldung fehlgeschlagen - Benutzer oder Passwort falsch.'))
    }
  }

  return (
    <form className="card" onSubmit={submit}>
      <h2>{t('Admin-Anmeldung')}</h2>
      <p>{t('Betreiber-Zugang für Einstellungen, das Journey-Log aller Konten und die Kontenverwaltung.')}</p>
      <p className="hint">
        <Tx
          text="Demo-Zugang aus {datei} ({schluessel}): {benutzer} / {passwort}"
          datei={<code>application.yml</code>}
          schluessel={<code>demo.admin.*</code>}
          benutzer={<code>admin</code>}
          passwort={<code>admin</code>}
        />
      </p>
      <div className="form-group">
        <label htmlFor="admin-user">{t('Benutzer')}</label>
        <input id="admin-user" autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} />
      </div>
      <div className="form-group">
        <label htmlFor="admin-password">{t('Passwort')}</label>
        <input
          id="admin-password"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
      </div>
      {error && <p className="error-card">{error}</p>}
      <div className="form-actions">
        <button type="submit">{t('Anmelden')}</button>
      </div>
    </form>
  )
}

function AccountsTab() {
  const [accounts, setAccounts] = useState<AdminAccount[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  // Two-step confirmation inline instead of window.confirm (which would block the page).
  const [confirming, setConfirming] = useState<number | 'reset' | null>(null)

  const reload = useCallback(() => {
    fetchAdminAccounts()
      .then(setAccounts)
      .catch((err) => setError(describeError(t('Konten laden fehlgeschlagen'), err)))
  }, [])
  useEffect(reload, [reload])

  async function run(action: () => Promise<string>) {
    try {
      setError(null)
      setNotice(await action())
      setConfirming(null)
      reload()
    } catch (err) {
      setError(describeError(t('Aktion fehlgeschlagen'), err))
    }
  }

  return (
    <>
      <div className="card">
        <h2>{t('Konten')}</h2>
        {error && <p className="error-card">{error}</p>}
        {notice && <p className="hint">{notice}</p>}
        {accounts === null ? (
          // A failed load already says so above - "Lädt…" next to it would claim it is still trying.
          !error && <p>{t('Lädt…')}</p>
        ) : accounts.length === 0 ? (
          <p>{t('Keine Konten.')}</p>
        ) : (
          <div className="journey-log-table-scroll">
            <table className="journey-log-table">
              <thead>
                <tr>
                  <th>{t('Konto')}</th>
                  <th>{t('Person')}</th>
                  <th>{t('E-Mail')}</th>
                  <th>{t('Verfahren')}</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {accounts.map((a) => (
                  <tr key={a.accountId}>
                    <td>{a.accountId}</td>
                    <td>{a.displayName ?? (a.personId == null ? t('nicht identifiziert') : t('Person {id}', { id: a.personId }))}</td>
                    <td>{a.email ?? '–'}</td>
                    <td>{a.methods.length > 0 ? a.methods.join(', ') : '–'}</td>
                    <td>
                      {confirming === a.accountId ? (
                        <span className="value-with-action">
                          <button
                            className="destructive small"
                            onClick={() => run(async () => {
                              await deleteAdminAccount(a.accountId)
                              return t('Konto {id} gelöscht.', { id: a.accountId })
                            })}
                          >
                            {t('Wirklich löschen')}
                          </button>
                          <button className="secondary small" onClick={() => setConfirming(null)}>
                            {t('Abbrechen')}
                          </button>
                        </span>
                      ) : (
                        <button className="secondary small" onClick={() => setConfirming(a.accountId)}>
                          {t('Löschen')}
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="card">
        <h2>{t('Demo zurücksetzen')}</h2>
        <p>
          <Tx
            text={
              'Löscht alle Konten (samt Geräten, Verfahren und Journey-Log), setzt die Verfahren je Kanal auf die Voreinstellung ' +
              'zurück (Reihenfolge und Sperren aus {konfig}) und stellt die ' +
              'Registrierungsreihenfolge auf „Identifikation zuerst“. Das Personenverzeichnis (/personenverzeichnis/) ist ein Fremdsystem und ' +
              'bleibt unverändert. Im Keycloak-Profil werden die Demo-Konten danach gleich wieder angelegt.'
            }
            konfig={<code>demo.tool-defaults</code>}
          />
        </p>
        {confirming === 'reset' ? (
          <div className="form-actions">
            <button
              className="destructive"
              onClick={() => run(async () => {
                const r = await resetDemo()
                return t('Demo zurückgesetzt: {geloescht} Konto/Konten gelöscht, {angelegt} Demo-Konto/Konten neu angelegt.', {
                  geloescht: r.deletedAccounts,
                  angelegt: r.seededAccounts,
                })
              })}
            >
              {t('Ja, alles zurücksetzen')}
            </button>
            <button className="secondary" onClick={() => setConfirming(null)}>
              {t('Abbrechen')}
            </button>
          </div>
        ) : (
          <button className="secondary" onClick={() => setConfirming('reset')}>
            {t('Demo zurücksetzen…')}
          </button>
        )}
      </div>
    </>
  )
}
