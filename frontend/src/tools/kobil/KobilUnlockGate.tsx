import { useState } from 'react'
import { loadUnlockSecret } from '../../kobilUnlockSecret'

interface KobilUnlockGateProps {
  kobilUserId?: string
  /** The ways this credential can actually be unlocked, as the backend derived them. */
  options: string[]
  onRelease: (unlock: Record<string, unknown>) => void
  error?: string
}

/**
 * auth-kobil/unlock - the step that decides whether the backend hands the PIN over.
 *
 * Two means, one credential: the locally stored secret (which a real device would keep behind a
 * biometric prompt) or the account password. Both are offered unconditionally, because narrowing
 * the choice to what this account actually has would answer "is there a password here?" to anyone
 * who opens the screen.
 */
export function KobilUnlockGate({ kobilUserId, options, onRelease, error }: KobilUnlockGateProps) {
  const [password, setPassword] = useState('')
  const [usingPassword, setUsingPassword] = useState(false)
  const [busy, setBusy] = useState(false)

  // Both halves must hold: the backend says this credential has a biometric path (someone
  // consented at setup), and THIS browser actually holds the secret it would present.
  const storedSecret = options.includes('biometric') && kobilUserId ? loadUnlockSecret(kobilUserId) : null
  const passwordOffered = options.includes('password')

  function releaseViaBiometric() {
    if (!storedSecret) return
    setBusy(true)
    onRelease({ kind: 'biometric', unlockSecret: storedSecret })
    setBusy(false)
  }

  function releaseViaPassword(event: React.FormEvent) {
    event.preventDefault()
    setBusy(true)
    onRelease({ kind: 'password', password })
    setBusy(false)
  }

  return (
    <div className="card">
      <h2>Anmelden mit KOBIL</h2>
      <p>Entsperren Sie dieses Gerät, damit die Anmeldung bei KOBIL erfolgen kann.</p>
      <div className="hint">
        <strong>Demo-Modus:</strong> Biometrie wird simuliert. Die KOBIL-PIN liegt im Backend und
        wird nur für diesen einen Vorgang freigegeben.
      </div>
      {error && <div className="hint">{error}</div>}

      {!usingPassword && (
        <>
          {/*
            Only what can actually work: this project styles no `:disabled` state, so a dead
            control would look clickable and merely produce a failed attempt against the login
            throttle. Saying in words what is missing is the honest alternative.
          */}
          {storedSecret && (
            <div className="form-actions" style={{ marginTop: '1rem' }}>
              <button type="button" disabled={busy} onClick={releaseViaBiometric}>
                Mit Biometrie entsperren
              </button>
            </div>
          )}
          {!storedSecret && passwordOffered && (
            <p className="hint" style={{ marginTop: '1rem' }}>
              Für dieses Gerät ist keine Biometrie hinterlegt – bitte das Passwort verwenden.
            </p>
          )}
          {passwordOffered && (
            <p style={{ marginTop: '0.75rem' }}>
              <button type="button" className="secondary" disabled={busy} onClick={() => setUsingPassword(true)}>
                {storedSecret ? 'Stattdessen Passwort verwenden' : 'Mit Passwort entsperren'}
              </button>
            </p>
          )}
          {!storedSecret && !passwordOffered && (
            <p className="hint" style={{ marginTop: '1rem' }}>
              Für dieses Gerät gibt es derzeit keinen Entsperrweg – weder ein hinterlegtes
              Gerätegeheimnis noch ein Kontopasswort. Bitte ein anderes Verfahren wählen.
            </p>
          )}
        </>
      )}

      {usingPassword && (
        <form onSubmit={releaseViaPassword} className="form-grid" style={{ marginTop: '1rem' }}>
          <div className="form-group">
            <label htmlFor="kobil-password">Passwort</label>
            <input
              id="kobil-password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoFocus
            />
          </div>
          <div className="form-actions">
            <button type="submit" disabled={busy || password === ''}>
              Entsperren
            </button>
            <button type="button" className="secondary" disabled={busy} onClick={() => setUsingPassword(false)}>
              Zurück
            </button>
          </div>
        </form>
      )}
    </div>
  )
}
