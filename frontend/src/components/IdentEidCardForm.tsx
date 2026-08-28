import { useState } from 'react'

interface IdentEidCardFormProps {
  onSubmit: (fields: {
    geburtsdatum: string
    strasse: string
    hausnummer: string
    plz: string
    ort: string
  }) => void
}

/** toolId=ident-eid / step=card: simulates reading the eID card's Ausweisdaten (possession factor). */
export function IdentEidCardForm({ onSubmit }: IdentEidCardFormProps) {
  const [geburtsdatum, setGeburtsdatum] = useState('1985-06-15')
  const [strasse, setStrasse] = useState('Musterstraße')
  const [hausnummer, setHausnummer] = useState('1')
  const [plz, setPlz] = useState('12345')
  const [ort, setOrt] = useState('Musterstadt')

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit({ geburtsdatum, strasse, hausnummer, plz, ort })
  }

  return (
    <div className="card">
      <h2>eID-Karte auflegen</h2>
      <p>Halten Sie Ihren Personalausweis an das Lesegerät. Die ausgelesenen Ausweisdaten werden mit den Stammdaten abgeglichen.</p>
      <div className="hint">Demo-Modus: Das Auslesen der Karte wird simuliert; Testdaten sind bereits vorbelegt.</div>
      <form onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <div className="form-group">
          <label htmlFor="eid-geburtsdatum">Geburtsdatum</label>
          <input
            id="eid-geburtsdatum"
            type="date"
            value={geburtsdatum}
            onChange={(e) => setGeburtsdatum(e.target.value)}
            required
          />
        </div>
        <div className="form-group">
          <label htmlFor="eid-strasse">Straße</label>
          <input id="eid-strasse" value={strasse} onChange={(e) => setStrasse(e.target.value)} required />
        </div>
        <div className="form-group">
          <label htmlFor="eid-hausnummer">Hausnummer</label>
          <input id="eid-hausnummer" value={hausnummer} onChange={(e) => setHausnummer(e.target.value)} required />
        </div>
        <div className="form-group">
          <label htmlFor="eid-plz">PLZ</label>
          <input id="eid-plz" value={plz} onChange={(e) => setPlz(e.target.value)} required />
        </div>
        <div className="form-group">
          <label htmlFor="eid-ort">Ort</label>
          <input id="eid-ort" value={ort} onChange={(e) => setOrt(e.target.value)} required />
        </div>
        <div className="form-actions">
          <button type="submit">Karte auflegen (simuliert)</button>
        </div>
      </form>
    </div>
  )
}
