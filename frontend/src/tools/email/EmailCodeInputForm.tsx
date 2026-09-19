import { useEffect, useState } from 'react'

interface EmailCodeInputFormProps {
  onSubmit: (code: string) => void
  /**
   * Sends the code to a DIFFERENT address, where the flow allows it (confirm-email: submitting an
   * address wins over a code in every state, so this simply starts over at the new one). Without
   * a way back, a mistyped address - or one the backend refuses only after the code was entered -
   * left the step with nothing to do but abandon the whole run.
   */
  onChangeAddress?: (email: string) => void
  error?: string
  /** Demo-only: the just-issued code, pre-filled here so testers don't need server-log access. */
  demoTan?: string
}

/** Shared by confirm-email/codeInput and auth-email/auth. */
export function EmailCodeInputForm({ onSubmit, onChangeAddress, error, demoTan }: EmailCodeInputFormProps) {
  const [code, setCode] = useState(demoTan ?? '')
  const [newEmail, setNewEmail] = useState('')
  const [changingAddress, setChangingAddress] = useState(false)

  // A fresh code was issued (new tool session, or a resend) - replace whatever was typed before,
  // and close the address form: a new code means the switch went through.
  useEffect(() => {
    if (demoTan) setCode(demoTan)
    setChangingAddress(false)
  }, [demoTan])

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit(code)
  }

  return (
    <div className="card">
      <h2>Bestätigungscode eingeben</h2>
      <p>Wir haben Ihnen soeben einen Bestätigungscode per E-Mail geschickt. Geben Sie ihn hier ein.</p>
      <div className="hint">
        <strong>Demo-Modus:</strong>{' '}
        {demoTan ? (
          <>
            Der Code ist bereits vorbelegt: <code>{demoTan}</code>
          </>
        ) : (
          <>
            Der Code wird nur ins Server-Log geschrieben (<code>[MOCK EMAIL] ...</code>).
          </>
        )}
      </div>
      {error && (
        <div className="hint" style={{ marginTop: '0.75rem' }}>
          {error}
        </div>
      )}
      <form onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <div className="form-group">
          <label htmlFor="code">Code</label>
          <input
            id="code"
            value={code}
            onChange={(e) => setCode(e.target.value)}
            placeholder="6-stelliger Code"
            maxLength={6}
            required
            autoFocus
          />
        </div>
        <div className="form-actions">
          <button type="submit">Code bestätigen</button>
          {onChangeAddress && !changingAddress && (
            <button type="button" className="secondary" onClick={() => setChangingAddress(true)}>
              Andere Adresse
            </button>
          )}
        </div>
      </form>
      {onChangeAddress && changingAddress && (
        <form
          className="form-grid"
          style={{ marginTop: '1rem' }}
          onSubmit={(event) => {
            event.preventDefault()
            onChangeAddress(newEmail)
          }}
        >
          <div className="form-group">
            <label htmlFor="changed-email">Andere E-Mail-Adresse</label>
            <input
              id="changed-email"
              type="email"
              value={newEmail}
              onChange={(e) => setNewEmail(e.target.value)}
              placeholder="name@example.com"
              required
              autoFocus
            />
            <span className="hint">Der bisherige Code verfällt, ein neuer geht an diese Adresse.</span>
          </div>
          <div className="form-actions">
            <button type="submit">Code hierhin senden</button>
            <button type="button" className="secondary" onClick={() => setChangingAddress(false)}>
              Zurück
            </button>
          </div>
        </form>
      )}
    </div>
  )
}
