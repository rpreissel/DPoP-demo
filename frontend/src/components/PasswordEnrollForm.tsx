import { useEffect, useState } from 'react'

interface PasswordEnrollFormProps {
  onSubmit: (fields: { password: string }) => void
  error?: string
  /** Demo-only: the same fixed password used everywhere in this demo, prefilled so testers don't have to invent or remember one. */
  demoPassword?: string
}

/** toolId=enroll-password / step=enroll: registers a password credential. Requires a confirmed account email first (requiresConfirmedEmail). */
export function PasswordEnrollForm({ onSubmit, error, demoPassword }: PasswordEnrollFormProps) {
  const [password, setPassword] = useState(demoPassword ?? '')
  const [passwordConfirm, setPasswordConfirm] = useState(demoPassword ?? '')
  const [validationError, setValidationError] = useState('')

  useEffect(() => {
    if (demoPassword) {
      setPassword(demoPassword)
      setPasswordConfirm(demoPassword)
    }
  }, [demoPassword])

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    if (password !== passwordConfirm) {
      setValidationError('Die Passwörter stimmen nicht überein.')
      return
    }
    setValidationError('')
    onSubmit({ password })
  }

  return (
    <div className="card">
      <h2>Passwort einrichten</h2>
      <p>Legen Sie ein Passwort als weiteren Faktor an. Ihre bestätigte E-Mail-Adresse dient dabei als Anmeldename.</p>
      <div className="hint">
        Testdaten vorbelegt: <code>{password}</code>
      </div>
      {(validationError || error) && <div className="hint">{validationError || error}</div>}
      <form onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <div className="form-group">
          <label htmlFor="password">Passwort</label>
          <input
            id="password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            minLength={8}
            required
            autoFocus
          />
        </div>
        <div className="form-group">
          <label htmlFor="passwordConfirm">Passwort wiederholen</label>
          <input
            id="passwordConfirm"
            type="password"
            value={passwordConfirm}
            onChange={(e) => setPasswordConfirm(e.target.value)}
            minLength={8}
            required
          />
        </div>
        <div className="form-actions">
          <button type="submit">Einrichten</button>
        </div>
      </form>
    </div>
  )
}
