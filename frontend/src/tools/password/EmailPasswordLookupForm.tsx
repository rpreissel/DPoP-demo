import { useEffect, useState } from 'react'

interface EmailPasswordLookupFormProps {
  onSubmit: (fields: { email: string; password: string }) => void
  error?: string
  /** Demo-only: the fixed password every enroll-password credential in this demo uses, prefilled so testers don't have to remember it. */
  demoPassword?: string
  /** Demo-only: the fixed email every account in this demo is confirmed with, prefilled so testers don't have to remember it. */
  demoEmail?: string
}

/** toolId=auth-password-lookup / step=auth: "Login ohne DPoP" - self-verifying, email+password submitted together in one call. */
export function EmailPasswordLookupForm({ onSubmit, error, demoPassword, demoEmail }: EmailPasswordLookupFormProps) {
  const [email, setEmail] = useState(demoEmail ?? '')
  const [password, setPassword] = useState(demoPassword ?? '')

  useEffect(() => {
    if (demoEmail) setEmail(demoEmail)
  }, [demoEmail])

  useEffect(() => {
    if (demoPassword) setPassword(demoPassword)
  }, [demoPassword])

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit({ email, password })
  }

  return (
    <div className="card">
      <h2>Login ohne DPoP: per Passwort</h2>
      <p>Geben Sie E-Mail-Adresse und Passwort Ihres Kontos ein.</p>
      {demoPassword && (
        <div className="hint">
          Demo-Modus: Passwort ist bereits vorbelegt: <code>{demoPassword}</code>
        </div>
      )}
      {error && <div className="hint">{error}</div>}
      <form onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <div className="form-group">
          <label htmlFor="email">E-Mail-Adresse</label>
          <input id="email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoFocus />
        </div>
        <div className="form-group">
          <label htmlFor="password">Passwort</label>
          <input id="password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </div>
        <div className="form-actions">
          <button type="submit">Anmelden</button>
        </div>
      </form>
    </div>
  )
}
