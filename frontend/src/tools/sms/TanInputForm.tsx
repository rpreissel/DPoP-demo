import { useEffect, useState } from 'react'

interface TanInputFormProps {
  onSubmit: (tan: string) => void
  error?: string
  /** Demo-only: the just-issued TAN, pre-filled here so testers don't need server-log access. */
  demoTan?: string
}

/** Shared by enroll-sms/tanInput and auth-sms/auth. */
export function TanInputForm({ onSubmit, error, demoTan }: TanInputFormProps) {
  const [tan, setTan] = useState(demoTan ?? '')

  // A fresh TAN was issued (new tool session, or a resend) - replace whatever was typed before.
  useEffect(() => {
    if (demoTan) setTan(demoTan)
  }, [demoTan])

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit(tan)
  }

  return (
    <div className="card">
      <h2>TAN eingeben</h2>
      <p>Geben Sie die TAN ein, die per SMS zugestellt wurde.</p>
      <div className="hint">
        <strong>Demo-Modus:</strong>{' '}
        {demoTan ? (
          <>
            Die TAN ist bereits vorbelegt: <code>{demoTan}</code>
          </>
        ) : (
          <>
            Die TAN wird nur ins Server-Log geschrieben (<code>[MOCK SMS] ...</code>).
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
          <label htmlFor="tan">TAN</label>
          <input
            id="tan"
            value={tan}
            onChange={(e) => setTan(e.target.value)}
            placeholder="6-stellige TAN"
            maxLength={6}
            required
            autoFocus
          />
        </div>
        <div className="form-actions">
          <button type="submit">TAN bestätigen</button>
        </div>
      </form>
    </div>
  )
}
