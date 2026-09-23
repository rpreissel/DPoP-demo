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

type Tab = 'einstellungen' | 'journeylog' | 'konten'
const TAB_KEYS = ['einstellungen', 'journeylog', 'konten'] as const
const TABS: NavTab<Tab>[] = [
  { key: 'einstellungen', label: 'Einstellungen' },
  { key: 'journeylog', label: 'Journey-Log' },
  { key: 'konten', label: 'Konten' },
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
        <ChannelNav badge="🛠️ Admin" />
        <div className="web-page">
          <LoginForm onLoggedIn={() => setLoggedIn(true)} />
        </div>
      </div>
    )
  }

  return (
    <div className="web-shell channel-admin">
      <ChannelNav
        badge="🛠️ Admin"
        tabs={TABS}
        sub={tab}
        onSelectTab={setTab}
        actions={
          <button className="secondary small" onClick={clearAdminCredentials}>
            Abmelden
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
      setError('Anmeldung fehlgeschlagen - Benutzer oder Passwort falsch.')
    }
  }

  return (
    <form className="card" onSubmit={submit}>
      <h2>Admin-Anmeldung</h2>
      <p>Betreiber-Zugang für Einstellungen, das Journey-Log aller Konten und die Kontenverwaltung.</p>
      <p className="hint">
        Demo-Zugang aus <code>application.yml</code> (<code>demo.admin.*</code>): <code>admin</code> / <code>admin</code>
      </p>
      <div className="form-group">
        <label htmlFor="admin-user">Benutzer</label>
        <input id="admin-user" autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} />
      </div>
      <div className="form-group">
        <label htmlFor="admin-password">Passwort</label>
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
        <button type="submit">Anmelden</button>
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
      .catch((err) => setError(describeError('Konten laden fehlgeschlagen', err)))
  }, [])
  useEffect(reload, [reload])

  async function run(action: () => Promise<string>) {
    try {
      setError(null)
      setNotice(await action())
      setConfirming(null)
      reload()
    } catch (err) {
      setError(describeError('Aktion fehlgeschlagen', err))
    }
  }

  return (
    <>
      <div className="card">
        <h2>Konten</h2>
        {error && <p className="error-card">{error}</p>}
        {notice && <p className="hint">{notice}</p>}
        {accounts === null ? (
          // A failed load already says so above - "Lädt…" next to it would claim it is still trying.
          !error && <p>Lädt…</p>
        ) : accounts.length === 0 ? (
          <p>Keine Konten.</p>
        ) : (
          <div className="journey-log-table-scroll">
            <table className="journey-log-table">
              <thead>
                <tr>
                  <th>Konto</th>
                  <th>Person</th>
                  <th>E-Mail</th>
                  <th>Verfahren</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {accounts.map((a) => (
                  <tr key={a.accountId}>
                    <td>{a.accountId}</td>
                    <td>{a.displayName ?? (a.personId == null ? 'nicht identifiziert' : `Person ${a.personId}`)}</td>
                    <td>{a.email ?? '–'}</td>
                    <td>{a.methods.length > 0 ? a.methods.join(', ') : '–'}</td>
                    <td>
                      {confirming === a.accountId ? (
                        <span className="value-with-action">
                          <button
                            className="destructive small"
                            onClick={() => run(async () => {
                              await deleteAdminAccount(a.accountId)
                              return `Konto ${a.accountId} gelöscht.`
                            })}
                          >
                            Wirklich löschen
                          </button>
                          <button className="secondary small" onClick={() => setConfirming(null)}>
                            Abbrechen
                          </button>
                        </span>
                      ) : (
                        <button className="secondary small" onClick={() => setConfirming(a.accountId)}>
                          Löschen
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
        <h2>Demo zurücksetzen</h2>
        <p>
          Löscht alle Konten (samt Geräten, Verfahren und Journey-Log), setzt die Verfahren je Kanal auf die Voreinstellung
          zurück (Reihenfolge und Sperren aus <code>demo.tool-defaults</code>) und stellt die
          Registrierungsreihenfolge auf „Identifikation zuerst“. Das Personenregister (/ext/) ist ein Fremdsystem und
          bleibt unverändert. Im Keycloak-Profil werden die Demo-Konten danach gleich wieder angelegt.
        </p>
        {confirming === 'reset' ? (
          <div className="form-actions">
            <button
              className="destructive"
              onClick={() => run(async () => {
                const r = await resetDemo()
                return `Demo zurückgesetzt: ${r.deletedAccounts} Konto/Konten gelöscht, ${r.seededAccounts} Demo-Konto/Konten neu angelegt.`
              })}
            >
              Ja, alles zurücksetzen
            </button>
            <button className="secondary" onClick={() => setConfirming(null)}>
              Abbrechen
            </button>
          </div>
        ) : (
          <button className="secondary" onClick={() => setConfirming('reset')}>
            Demo zurücksetzen…
          </button>
        )}
      </div>
    </>
  )
}
