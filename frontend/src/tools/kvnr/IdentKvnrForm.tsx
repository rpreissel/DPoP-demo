import { useState } from 'react'
import { DemoPersonPicker } from '../../components/DemoPersonPicker'
import type { DemoPerson } from '../../types'

interface IdentKvnrFormProps {
  onSubmit: (kvnr: string) => void
  error?: string
  /** Demo-only: all seeded personas, so a mismatching number is easy to try out too. */
  demoPersons?: DemoPerson[]
}

/**
 * toolId=ident-kvnr / step=input: the number an attestation cannot carry. Runs only after an
 * identity was attested (docs/12-entscheidungen.md ADR-18) and assigns the account to that
 * person's register record - it proves nothing on its own.
 */
export function IdentKvnrForm({ onSubmit, error, demoPersons }: IdentKvnrFormProps) {
  const [kvnr, setKvnr] = useState('A123456789')

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit(kvnr)
  }

  return (
    <div className="card">
      <h2>Versichertennummer angeben</h2>
      <p>
        Ihre Identität ist bereits nachgewiesen. Mit der Versichertennummer wird Ihr Konto Ihrem
        Datensatz bei der Krankenkasse zugeordnet - sie muss zu der nachgewiesenen Person gehören.
      </p>
      {error && <div className="hint">{error}</div>}
      <form onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <DemoPersonPicker demoPersons={demoPersons} onSelect={(person) => setKvnr(person.kvnr)} />
        <div className="form-group">
          <label htmlFor="kvnr-input">Versichertennummer</label>
          <input id="kvnr-input" value={kvnr} onChange={(e) => setKvnr(e.target.value)} required />
        </div>
        <div className="form-actions">
          <button type="submit">Zuordnen</button>
        </div>
      </form>
    </div>
  )
}
