import { useEffect, useState } from 'react'
import { t } from '../../texts'
import { Tx } from '../../Tx'
import { DemoNote } from '../../components/DemoArea'

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
      <h2>{t('Bestätigungscode eingeben')}</h2>
      <p>{t('Wir haben Ihnen soeben einen Bestätigungscode per E-Mail geschickt. Geben Sie ihn hier ein.')}</p>
      <DemoNote>
        {demoTan ? (
          <Tx text="{modus} Der Code ist bereits vorbelegt: {code}" modus={<strong>{t('Demo-Modus:')}</strong>} code={<code>{demoTan}</code>} />
        ) : (
          <Tx text="{modus} Der Code wird nur ins Server-Log geschrieben ({log})." modus={<strong>{t('Demo-Modus:')}</strong>} log={<code>[MOCK EMAIL] ...</code>} />
        )}
      </DemoNote>
      {error && (
        <div className="hint" style={{ marginTop: '0.75rem' }}>
          {error}
        </div>
      )}
      <form onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <div className="form-group">
          <label htmlFor="code">{t('Code')}</label>
          <input
            id="code"
            value={code}
            onChange={(e) => setCode(e.target.value)}
            placeholder={t('6-stelliger Code')}
            maxLength={6}
            required
            autoFocus
          />
        </div>
        <div className="form-actions">
          <button type="submit">{t('Code bestätigen')}</button>
          {onChangeAddress && !changingAddress && (
            <button type="button" className="secondary" onClick={() => setChangingAddress(true)}>
              {t('Andere Adresse')}
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
            <label htmlFor="changed-email">{t('Andere E-Mail-Adresse')}</label>
            <input
              id="changed-email"
              type="email"
              value={newEmail}
              onChange={(e) => setNewEmail(e.target.value)}
              placeholder="name@example.com"
              required
              autoFocus
            />
            <span className="hint">{t('Der bisherige Code verfällt, ein neuer geht an diese Adresse.')}</span>
          </div>
          <div className="form-actions">
            <button type="submit">{t('Code hierhin senden')}</button>
            <button type="button" className="secondary" onClick={() => setChangingAddress(false)}>
              {t('Zurück')}
            </button>
          </div>
        </form>
      )}
    </div>
  )
}
