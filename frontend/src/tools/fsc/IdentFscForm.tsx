import { useState } from 'react'

interface IdentFscFormProps {
  onSubmit: (fields: { kvnr: string; name: string; vorname: string; fsc: string }) => void
}

/** toolId=ident-fsc / step=input (docs/06-ablaeufe.md #2): KVNR, name, and the mailed FSC together. */
export function IdentFscForm({ onSubmit }: IdentFscFormProps) {
  const [kvnr, setKvnr] = useState('A123456789')
  const [name, setName] = useState('Muster')
  const [vorname, setVorname] = useState('Max')
  const [fsc, setFsc] = useState('VALIDCODE')

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit({ kvnr, name, vorname, fsc })
  }

  return (
    <div className="card">
      <h2>Identifikation per Freischaltcode</h2>
      <p>Geben Sie Ihre Versichertennummer, Ihren Namen und den zugesandten Freischaltcode ein.</p>
      <div className="hint">
        Testdaten vorbelegt: <code>{kvnr}</code> / <code>{name}</code>, <code>{vorname}</code> / Code <code>{fsc}</code>
      </div>
      <form onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <div className="form-group">
          <label htmlFor="kvnr">KVNR</label>
          <input id="kvnr" value={kvnr} onChange={(e) => setKvnr(e.target.value)} required />
        </div>
        <div className="form-group">
          <label htmlFor="name">Name</label>
          <input id="name" value={name} onChange={(e) => setName(e.target.value)} required />
        </div>
        <div className="form-group">
          <label htmlFor="vorname">Vorname</label>
          <input id="vorname" value={vorname} onChange={(e) => setVorname(e.target.value)} required />
        </div>
        <div className="form-group">
          <label htmlFor="fsc">Freischaltcode</label>
          <input id="fsc" value={fsc} onChange={(e) => setFsc(e.target.value)} required />
        </div>
        <div className="form-actions">
          <button type="submit">Identifizieren</button>
        </div>
      </form>
    </div>
  )
}
