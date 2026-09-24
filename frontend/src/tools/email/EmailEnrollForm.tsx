import { useEffect, useState } from 'react'
import { DemoPersonPicker } from '../../components/DemoPersonPicker'
import type { DemoPerson } from '../../types'
import { t } from '../../texts'

interface EmailEnrollFormProps {
  onSubmit: (email: string) => void
  error?: string
  /** Demo-only: the fixed email this demo always confirms, prefilled so testers don't have to invent one. */
  demoEmail?: string
  /** Demo-only: every register person, offered as a picker that fills the email field. */
  demoPersons?: DemoPerson[]
}

/** toolId=confirm-email / step=input: confirms control over an email address - the account keeps it, no method is created. */
export function EmailEnrollForm({ onSubmit, error, demoEmail, demoPersons }: EmailEnrollFormProps) {
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
      <h2>{t('E-Mail-Adresse einrichten')}</h2>
      <p>{t('Geben Sie Ihre E-Mail-Adresse ein, um einen Bestätigungscode zu erhalten.')}</p>
      {error && <div className="hint">{error}</div>}
      <form onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <DemoPersonPicker demoPersons={demoPersons} onSelect={selectPerson} />
        <div className="form-group">
          <label htmlFor="email">{t('E-Mail-Adresse')}</label>
          <input id="email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoFocus />
        </div>
        <div className="form-actions">
          <button type="submit">{t('Code senden')}</button>
        </div>
      </form>
    </div>
  )
}
