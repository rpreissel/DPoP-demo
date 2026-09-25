import { useEffect, useState } from 'react'
import { DemoPersonPicker } from '../../components/DemoPersonPicker'
import type { DemoPerson } from '../../types'
import { t } from '../../texts'

interface EmailCodeLookupFormProps {
  onSubmit: (email: string) => void
  error?: string
  /** Demo-only: the fixed email every account in this demo is confirmed with, prefilled so testers don't have to remember it. */
  demoEmail?: string
  /** Demo-only: every register person, offered as a picker that fills the email field. */
  demoPersons?: DemoPerson[]
}

/** toolId=auth-email-lookup / step=auth: "Login ohne DPoP" - resolves the account by email and sends a confirmation code to that same address. */
export function EmailCodeLookupForm({ onSubmit, error, demoEmail, demoPersons }: EmailCodeLookupFormProps) {
  const [email, setEmail] = useState(demoEmail ?? '')

  useEffect(() => {
    if (demoEmail) setEmail(demoEmail)
  }, [demoEmail])

  function selectPerson(person: DemoPerson) {
    setEmail(person.email ?? '')
  }

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit(email)
  }

  return (
    <div className="card">
      <h2>{t('Mit E-Mail-Code anmelden')}</h2>
      <p>{t('Geben Sie die E-Mail-Adresse Ihres Kontos ein, um einen Bestätigungscode an diese Adresse zu erhalten.')}</p>
      {error && <div className="hint">{error}</div>}
      <form onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <DemoPersonPicker demoPersons={demoPersons} onSelect={selectPerson} />
        <div className="form-group">
          <label htmlFor="email">{t('E-Mail-Adresse')}</label>
          <input id="email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoFocus />
        </div>
        <div className="form-actions">
          <button type="submit">{t('Code anfordern')}</button>
        </div>
      </form>
    </div>
  )
}
