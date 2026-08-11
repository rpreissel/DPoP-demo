import { useState } from 'react'

interface FscFormProps {
  onSubmit: (fsc: string) => void
}

export function FscForm({ onSubmit }: FscFormProps) {
  const [fsc, setFsc] = useState('VALIDCODE')

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit(fsc)
  }

  return (
    <div className="card">
      <h2>Freischaltcode</h2>
      <p>Geben Sie den Ihnen zugesandten Freischaltcode ein.</p>
      <div className="hint">
        Testcode vorbelegt: <code>{fsc}</code>
      </div>
      <form onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <div className="form-group">
          <label htmlFor="fsc">Freischaltcode</label>
          <input
            id="fsc"
            value={fsc}
            onChange={(e) => setFsc(e.target.value)}
            placeholder="z.B. VALIDCODE"
            required
          />
        </div>
        <div className="form-actions">
          <button type="submit">Freischaltcode validieren</button>
        </div>
      </form>
    </div>
  )
}
