import { useEffect, useState } from 'react'

interface AuthenticationSetupViewProps {
  methods: string[]
  collectPhoneNumber: boolean
  initialSmsSetupId?: number
  initialTan?: string
  onSetupSmsStart: (phoneNumber?: string) => Promise<{ smsSetupId: number; tan: string } | undefined>
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
  collectPhoneNumber,
  initialSmsSetupId,
  initialTan,
  onSetupSmsStart,
  onSetupSmsVerify,
}: AuthenticationSetupViewProps) {
  const [phoneNumber, setPhoneNumber] = useState('+49 170 1234567')
  const [tan, setTan] = useState(initialTan ?? '')
  const [smsSetupId, setSmsSetupId] = useState<number | null>(initialSmsSetupId ?? null)
  const [sentTan, setSentTan] = useState(initialTan ?? '')
  const [error, setError] = useState('')
  const [success, setSuccess] = useState(false)

  useEffect(() => {
    if (initialSmsSetupId != null) {
      setSmsSetupId(initialSmsSetupId)
    }
    if (initialTan != null) {
      setTan(initialTan)
      setSentTan(initialTan)
    }
  }, [initialSmsSetupId, initialTan])

  async function handleStart(event: React.FormEvent) {
    event.preventDefault()
    setError('')

    let normalized: string | undefined
    if (collectPhoneNumber) {
      normalized = phoneNumber.replace(/\s+/g, '').trim()
      if (!isValidPhoneNumber(normalized)) {
        setError('Bitte eine gueltige Telefonnummer eingeben.')
        return
      }
    }

    const result = await onSetupSmsStart(normalized)
    if (result) {
      setSmsSetupId(result.smsSetupId)
      setSentTan(result.tan)
      setTan(result.tan)
    }
  }

  async function handleVerify(event: React.FormEvent) {
    event.preventDefault()
    setError('')

    if (smsSetupId == null) {
      setError('Bitte zuerst die SMS-Challenge starten.')
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
                  {collectPhoneNumber ? (
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
                  ) : (
                    <div className="hint">
                      Die hinterlegte SMS-Methode wird fuer die Challenge verwendet.
                    </div>
                  )}
                  {error && <div className="form-error" style={{ color: 'var(--error-color, #ef4444)' }}>{error}</div>}
                  <div className="form-actions">
                    <button type="submit">{method.toUpperCase()} Challenge starten</button>
                  </div>
                </form>
              ) : (
                <form onSubmit={handleVerify} className="form-grid">
                  <div className="hint">
                    Test-TAN (Mock): <code>{sentTan || initialTan}</code>
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
