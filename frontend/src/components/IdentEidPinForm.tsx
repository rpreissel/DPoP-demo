import { useState } from 'react'

interface IdentEidPinFormProps {
  onSubmit: (pin: string) => void
  error?: string
}

/** toolId=ident-eid / step=pin: the eID PIN, the knowledge factor proven alongside the card. */
export function IdentEidPinForm({ onSubmit, error }: IdentEidPinFormProps) {
  const [pin, setPin] = useState('123456')

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit(pin)
  }

  return (
    <div className="card">
      <h2>eID-PIN eingeben</h2>
      <p>Geben Sie Ihre sechsstellige eID-PIN ein.</p>
      <div className="hint">
        Testdaten vorbelegt: PIN <code>{pin}</code>
      </div>
      {error && <div className="hint">{error}</div>}
      <form onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <div className="form-group">
          <label htmlFor="eid-pin">PIN</label>
          <input id="eid-pin" value={pin} onChange={(e) => setPin(e.target.value)} required />
        </div>
        <div className="form-actions">
          <button type="submit">Identifizieren</button>
        </div>
      </form>
    </div>
  )
}
