import { useState } from 'react'

interface IdentEidFormProps {
  onSubmit: (fields: { kvnr: string; name: string; vorname: string }) => void
  error?: string
}

/** toolId=ident-eid / step=input: KVNR, name and vorname to find the person before the simulated card read. */
export function IdentEidForm({ onSubmit, error }: IdentEidFormProps) {
  const [kvnr, setKvnr] = useState('A123456789')
  const [name, setName] = useState('Muster')
  const [vorname, setVorname] = useState('Max')

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit({ kvnr, name, vorname })
  }

  return (
    <div className="card">
      <h2>Identifikation per eID</h2>
      <p>Geben Sie Ihre Versichertennummer sowie Ihren Namen und Vornamen ein.</p>
      <div className="hint">
        Testdaten vorbelegt: <code>{kvnr}</code> / <code>{name}</code>, <code>{vorname}</code>
      </div>
      <form onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <div className="form-group">
          <label htmlFor="eid-kvnr">KVNR</label>
          <input id="eid-kvnr" value={kvnr} onChange={(e) => setKvnr(e.target.value)} required />
        </div>
        <div className="form-group">
          <label htmlFor="eid-name">Name</label>
          <input id="eid-name" value={name} onChange={(e) => setName(e.target.value)} required />
        </div>
        <div className="form-group">
          <label htmlFor="eid-vorname">Vorname</label>
          <input id="eid-vorname" value={vorname} onChange={(e) => setVorname(e.target.value)} required />
        </div>
        {error && <div className="hint">{error}</div>}
        <div className="form-actions">
          <button type="submit">Weiter</button>
        </div>
      </form>
    </div>
  )
}
