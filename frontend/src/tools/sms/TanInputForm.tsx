import { useEffect, useState } from 'react'
import { t } from '../../texts'
import { Tx } from '../../Tx'
import { DemoNote } from '../../components/DemoArea'

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
      <h2>{t('TAN eingeben')}</h2>
      <p>{t('Wir haben Ihnen soeben eine TAN per SMS geschickt. Geben Sie sie hier ein.')}</p>
      <DemoNote>
        {demoTan ? (
          <Tx text="{modus} Die TAN ist bereits vorbelegt: {tan}" modus={<strong>{t('Demo-Modus:')}</strong>} tan={<code>{demoTan}</code>} />
        ) : (
          <Tx text="{modus} Die TAN wird nur ins Server-Log geschrieben ({log})." modus={<strong>{t('Demo-Modus:')}</strong>} log={<code>[MOCK SMS] ...</code>} />
        )}
      </DemoNote>
      {error && (
        <div className="hint" style={{ marginTop: '0.75rem' }}>
          {error}
        </div>
      )}
      <form onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <div className="form-group">
          <label htmlFor="tan">{t('TAN')}</label>
          <input
            id="tan"
            value={tan}
            onChange={(e) => setTan(e.target.value)}
            placeholder={t('6-stellige TAN')}
            maxLength={6}
            required
            autoFocus
          />
        </div>
        <div className="form-actions">
          <button type="submit">{t('TAN bestätigen')}</button>
        </div>
      </form>
    </div>
  )
}
