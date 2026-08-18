import { useState } from 'react'

interface TanInputFormProps {
  onSubmit: (tan: string) => void
  demoTan?: string
}

export function TanInputForm({ onSubmit, demoTan }: TanInputFormProps) {
  const [tan, setTan] = useState(demoTan ?? '')

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit(tan)
  }

  return (
    <div className="card">
      <h2>TAN eingeben</h2>
      <p>Geben Sie die TAN ein, die Sie per SMS erhalten haben.</p>
      {demoTan && (
        <div className="hint">
          <strong>Demo-Modus:</strong> TAN vorbelegt: <code>{demoTan}</code>
          <br />
          <small style={{ opacity: 0.7 }}>In Produktion wird die TAN nur per SMS zugestellt und nie im API-Response übermittelt.</small>
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
