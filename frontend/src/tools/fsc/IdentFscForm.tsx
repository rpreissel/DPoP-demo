import { useState } from 'react'
import { DemoPersonPicker } from '../../components/DemoPersonPicker'
import type { DemoPerson } from '../../types'

interface IdentFscFormProps {
  onSubmit: (fields: { kvnr: string; name: string; vorname: string; fsc: string }) => void
  error?: string
  /** Demo-only: every register person, offered as a picker that fills KVNR/name/vorname/FSC together. */
  demoPersons?: DemoPerson[]
}

/** toolId=ident-fsc / step=input (docs/06-ablaeufe.md #2): KVNR, name, and the mailed FSC together. */
export function IdentFscForm({ onSubmit, error, demoPersons }: IdentFscFormProps) {
  // Prefilled from the first register persona - no hard-coded test person of our own (ADR-31).
  const first = demoPersons?.[0]
  const [kvnr, setKvnr] = useState(first?.kvnr ?? '')
  const [name, setName] = useState(first?.name ?? '')
  const [vorname, setVorname] = useState(first?.vorname ?? '')
  const [fsc, setFsc] = useState(first?.fscCode ?? '')

  function selectPerson(person: DemoPerson) {
    setKvnr(person.kvnr)
    setName(person.name ?? '')
    setVorname(person.vorname ?? '')
    setFsc(person.fscCode ?? '')
  }

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit({ kvnr, name, vorname, fsc })
  }

  return (
    <div className="card">
      <h2>Identifikation per Freischaltcode</h2>
      <p>Geben Sie Ihre Versichertennummer, Ihren Namen und den zugesandten Freischaltcode ein.</p>
      {first && (
        <div className="hint">
          Testdaten vorbelegt: <code>{kvnr}</code> / <code>{name}</code>, <code>{vorname}</code> /{' '}
          {fsc ? <>Code <code>{fsc}</code></> : <>kein gültiger Code im Briefkasten (Personenregister /ext/)</>}
        </div>
      )}
      <form onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <DemoPersonPicker demoPersons={demoPersons} onSelect={selectPerson} />
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
        {error && <div className="hint">{error}</div>}
        <div className="form-actions">
          <button type="submit">Identifizieren</button>
        </div>
      </form>
    </div>
  )
}
