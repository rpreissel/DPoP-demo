import { useState } from 'react'

interface IdentificationFormProps {
  onSubmit: (kvnr: string, name: string, vorname: string, method: string) => void
  methods: string[]
}

export function IdentificationForm({ onSubmit, methods }: IdentificationFormProps) {
  const [kvnr, setKvnr] = useState('A123456789')
  const [name, setName] = useState('Muster')
  const [vorname, setVorname] = useState('Max')
  const method = methods[0] || 'fsc'

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit(kvnr, name, vorname, method)
  }

  return (
    <div className="card">
      <h2>Identifikation</h2>
      <p>Geben Sie Ihre Versichertennummer und Ihren Namen ein, um sich zu identifizieren.</p>
      <div className="hint">
        Testdaten vorbelegt: <code>{kvnr}</code> / <code>{name}</code>, <code>{vorname}</code>
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
          <label htmlFor="name">Name</label>
          <input
            id="name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="z.B. Muster"
            required
          />
        </div>
        <div className="form-group">
          <label htmlFor="vorname">Vorname</label>
          <input
            id="vorname"
            value={vorname}
            onChange={(e) => setVorname(e.target.value)}
            placeholder="z.B. Max"
            required
          />
        </div>
        <div className="form-actions">
          <button type="submit">Weiter zum Freischaltcode</button>
        </div>
      </form>
    </div>
  )
}
