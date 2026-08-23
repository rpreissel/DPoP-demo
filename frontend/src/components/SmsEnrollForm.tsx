import { useState } from 'react'

interface SmsEnrollFormProps {
  onSubmit: (phoneNumber: string) => void
}

// FE-9: client-side pre-validation; the backend still rejects malformed numbers with 400.
const PHONE_PATTERN = /^\+?[0-9]{6,20}$/

function isValidPhoneNumber(value: string): boolean {
  return PHONE_PATTERN.test(value.replace(/\s+/g, ''))
}

/** toolId=enroll-sms / step=enroll (docs/06-ablaeufe.md #4): registers a new phone number. */
export function SmsEnrollForm({ onSubmit }: SmsEnrollFormProps) {
  const [phoneNumber, setPhoneNumber] = useState('+49 170 1234567')
  const [validationError, setValidationError] = useState('')

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    if (!isValidPhoneNumber(phoneNumber)) {
      setValidationError('Bitte eine gültige Telefonnummer eingeben (z. B. +49 170 1234567).')
      return
    }
    setValidationError('')
    onSubmit(phoneNumber)
  }

  return (
    <div className="card">
      <h2>SMS als zweiten Faktor einrichten</h2>
      <p>Geben Sie Ihre Telefonnummer ein, um einen Verifizierungscode zu erhalten.</p>
      <form onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <div className="form-group">
          <label htmlFor="phoneNumber">Telefonnummer</label>
          <input
            id="phoneNumber"
            type="tel"
            value={phoneNumber}
            onChange={(e) => setPhoneNumber(e.target.value)}
            placeholder="+49 170 xxxxxxxx"
            required
          />
        </div>
        {validationError && <div className="hint">{validationError}</div>}
        <div className="form-actions">
          <button type="submit">Code senden</button>
        </div>
      </form>
    </div>
  )
}
