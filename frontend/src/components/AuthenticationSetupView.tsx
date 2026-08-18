import { useState } from 'react'

interface AuthenticationSetupViewProps {
  methods: string[]
  mode?: string // "enroll" or "use"
  onSubmit: (method: string, mode: string, data?: { phoneNumber?: string }) => void
}

export function AuthenticationSetupView({ methods, mode, onSubmit }: AuthenticationSetupViewProps) {
  const [phoneNumber, setPhoneNumber] = useState('+49 170 1234567')
  const method = methods[0] || 'sms'
  const resolvedMode = mode || 'enroll' // Default to enroll

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit(method, resolvedMode, { phoneNumber })
  }

  return (
    <div className="card">
      <h2>SMS-Authentifizierung</h2>
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
        <div className="form-actions">
          <button type="submit">Code senden</button>
        </div>
      </form>
    </div>
  )
}
