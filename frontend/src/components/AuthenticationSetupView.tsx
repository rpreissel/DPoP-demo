import { useState } from 'react'

interface AuthenticationSetupViewProps {
  methods: string[]
  onSetupSmsStart: (phoneNumber: string) => Promise<{ smsSetupId: number; tan: string } | undefined>
  onSetupSmsVerify: (smsSetupId: number, tan: string) => Promise<boolean>
}

function isValidPhoneNumber(value: string): boolean {
  const normalized = value.replace(/\s+/g, '').trim()
  return /^\+?[0-9]{6,20}$/.test(normalized)
}

function isValidTan(value: string): boolean {
  return /^\d{6}$/.test(value.trim())
}

export function AuthenticationSetupView({
  methods,
  onSetupSmsStart,
  onSetupSmsVerify,
}: AuthenticationSetupViewProps) {
  const [phoneNumber, setPhoneNumber] = useState('+49 170 1234567')
  const [tan, setTan] = useState('')
  const [smsSetupId, setSmsSetupId] = useState<number | null>(null)
  const [sentTan, setSentTan] = useState('')
  const [error, setError] = useState('')
  const [success, setSuccess] = useState(false)

  async function handleStart(event: React.FormEvent) {
    event.preventDefault()
    setError('')

    const normalized = phoneNumber.replace(/\s+/g, '').trim()
    if (!isValidPhoneNumber(normalized)) {
      setError('Bitte eine gueltige Telefonnummer eingeben.')
      return
    }

    const result = await onSetupSmsStart(normalized)
    if (result) {
      setSmsSetupId(result.smsSetupId)
      setSentTan(result.tan)
    }
  }

  async function handleVerify(event: React.FormEvent) {
    event.preventDefault()
    setError('')

    if (smsSetupId == null) {
      setError('Bitte zuerst die Telefonnummer eingeben.')
      return
    }

    if (!isValidTan(tan)) {
      setError('Bitte eine gueltige 6-stellige TAN eingeben.')
      return
    }

    const ok = await onSetupSmsVerify(smsSetupId, tan.trim())
    if (ok) {
      setSuccess(true)
    } else {
      setError('TAN-Validierung fehlgeschlagen.')
    }
  }

  if (success) {
    return (
      <div className="card">
        <h2>SMS-Authentifizierung</h2>
        <p>Die TAN wurde bestaetigt und die SMS-Authentifizierungsmethode wurde gespeichert.</p>
      </div>
    )
  }

  return (
    <div className="card">
      <h2>Authentifizierung einrichten</h2>
      <p>Die Identifikation war erfolgreich. Waehlen Sie eine Authentifizierungsmethode aus:</p>

      {methods.map((method) => (
        <div key={method} style={{ marginTop: '1rem' }}>
          {method === 'sms' && (
            <>
              {!smsSetupId ? (
                <form onSubmit={handleStart} className="form-grid">
                  <div className="form-group">
                    <label htmlFor="phoneNumber">Telefonnummer</label>
                    <input
                      id="phoneNumber"
                      value={phoneNumber}
                      onChange={(e) => {
                        setPhoneNumber(e.target.value)
                        setError('')
                      }}
                      placeholder="z.B. +49 170 1234567"
                      required
                    />
                    <div className="hint">
                      Testnummer vorbelegt: <code>{phoneNumber}</code>
                    </div>
                  </div>
                  {error && <div className="form-error" style={{ color: 'var(--error-color, #ef4444)' }}>{error}</div>}
                  <div className="form-actions">
                    <button type="submit">{method.toUpperCase()} einrichten</button>
                  </div>
                </form>
              ) : (
                <form onSubmit={handleVerify} className="form-grid">
                  <div className="hint">
                    Test-TAN (Mock): <code>{sentTan}</code>
                  </div>
                  <div className="form-group">
                    <label htmlFor="tan">TAN</label>
                    <input
                      id="tan"
                      value={tan}
                      onChange={(e) => {
                        setTan(e.target.value)
                        setError('')
                      }}
                      placeholder="z.B. 123456"
                      required
                    />
                  </div>
                  {error && <div className="form-error" style={{ color: 'var(--error-color, #ef4444)' }}>{error}</div>}
                  <div className="form-actions">
                    <button type="submit">TAN bestaetigen</button>
                  </div>
                </form>
              )}
            </>
          )}
          {method !== 'sms' && (
            <button className="secondary" type="button">
              {method.toUpperCase()} einrichten
            </button>
          )}
        </div>
      ))}
    </div>
  )
}
