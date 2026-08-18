import { useState } from 'react'

interface FscFormProps {
  onSubmit: (kvnr: string, fsc: string) => void
}

export function FscForm({ onSubmit }: FscFormProps) {
  const [kvnr, setKvnr] = useState('A123456789')
  const [fsc, setFsc] = useState('VALIDCODE')

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit(kvnr, fsc)
  }

  return (
    <div className="card">
      <h2>Freischaltcode</h2>
      <p>Geben Sie Ihre KVNR und den Ihnen zugesandten Freischaltcode ein.</p>
      <div className="hint">
        Testdaten vorbelegt: KVNR <code>{kvnr}</code>, Code <code>{fsc}</code>
      </div>
      <form onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <div className="form-group">
          <label htmlFor="kvnr">KVNR</label>
          <input
            id="kvnr"
            value={kvnr}
            onChange={(e) => setKvnr(e.target.value)}
            placeholder="z.B. A123456789"
            required
          />
        </div>
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
