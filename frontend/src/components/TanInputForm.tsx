import { useState } from 'react'

interface TanInputFormProps {
  onSubmit: (tan: string) => void
}

export function TanInputForm({ onSubmit }: TanInputFormProps) {
  const [tan, setTan] = useState('')

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit(tan)
  }

  return (
    <div className="card">
      <h2>TAN eingeben</h2>
      <p>Geben Sie die TAN ein, die Sie per SMS erhalten haben.</p>
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
