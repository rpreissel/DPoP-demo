import { useEffect, useState } from 'react'

interface EmailCodeInputFormProps {
  onSubmit: (code: string) => void
  error?: string
  /** Demo-only: the just-issued code, pre-filled here so testers don't need server-log access. */
  demoTan?: string
}

/** Shared by enroll-email/codeInput and auth-email/auth. */
export function EmailCodeInputForm({ onSubmit, error, demoTan }: EmailCodeInputFormProps) {
  const [code, setCode] = useState(demoTan ?? '')

  // A fresh code was issued (new tool session, or a resend) - replace whatever was typed before.
  useEffect(() => {
    if (demoTan) setCode(demoTan)
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
        </div>
      </form>
    </div>
  )
}
