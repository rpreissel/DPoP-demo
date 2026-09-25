import { useEffect, useState } from 'react'
import { t } from '../../texts'
import { Tx } from '../../Tx'
import { DemoNote } from '../../components/DemoArea'

interface PasswordLoginFormProps {
  onSubmit: (fields: { password: string }) => void
  error?: string
  /** Demo-only: the fixed password every enroll-password credential in this demo uses, prefilled so testers don't have to remember it. */
  demoPassword?: string
}

/** toolId=auth-password / step=auth: confirms the password against the account's enrolled credential (device already recognized, no identifier needed). */
export function PasswordLoginForm({ onSubmit, error, demoPassword }: PasswordLoginFormProps) {
  const [password, setPassword] = useState(demoPassword ?? '')

  useEffect(() => {
    if (demoPassword) setPassword(demoPassword)
  }, [demoPassword])

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit({ password })
  }

  return (
    <div className="card">
      <h2>{t('Mit Passwort anmelden')}</h2>
      <p>{t('Geben Sie Ihr Passwort ein.')}</p>
      {demoPassword && (
        <DemoNote>
          <Tx text="Demo-Modus: Passwort ist bereits vorbelegt: {passwort}" passwort={<code>{demoPassword}</code>} />
        </DemoNote>
      )}
      {error && <div className="hint">{error}</div>}
      <form onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <div className="form-group">
          <label htmlFor="password">{t('Passwort')}</label>
          <input id="password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} required autoFocus />
        </div>
        <div className="form-actions">
          <button type="submit">{t('Anmelden')}</button>
        </div>
      </form>
    </div>
  )
}
