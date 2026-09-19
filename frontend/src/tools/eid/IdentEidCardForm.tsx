import { useState } from 'react'
import { DemoPersonPicker } from '../../components/DemoPersonPicker'
import type { DemoPerson } from '../../types'

interface IdentEidCardFormProps {
  onSubmit: (fields: {
    name: string
    vorname: string
    geburtsdatum: string
    strasse: string
    hausnummer: string
    plz: string
    ort: string
    restrictedId: string
  }) => void
  error?: string
  /** Demo-only: all seeded personas, offered as a picker that fills the whole card at once. */
  demoPersons?: DemoPerson[]
}

/** toolId=ident-eid / step=card: simulates reading the eID card's Ausweisdaten (possession factor). */
export function IdentEidCardForm({ onSubmit, error, demoPersons }: IdentEidCardFormProps) {
  const [name, setName] = useState('Muster')
  const [vorname, setVorname] = useState('Max')
  const [geburtsdatum, setGeburtsdatum] = useState('1985-06-15')
  const [strasse, setStrasse] = useState('Musterstraße')
  const [hausnummer, setHausnummer] = useState('1')
  const [plz, setPlz] = useState('12345')
  const [ort, setOrt] = useState('Musterstadt')
  const [restrictedId, setRestrictedId] = useState('T0103005K1D5S0V8T9W6UM2RTX')

  function selectPerson(person: DemoPerson) {
    setName(person.name)
    setVorname(person.vorname)
    setGeburtsdatum(person.geburtsdatum)
    setStrasse(person.strasse)
    setHausnummer(person.hausnummer)
    setPlz(person.plz)
    setOrt(person.ort)
    setRestrictedId(person.restrictedId)
  }

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit({ name, vorname, geburtsdatum, strasse, hausnummer, plz, ort, restrictedId })
  }

  return (
    <div className="card">
      <h2>eID-Karte auflegen</h2>
      <p>
        Halten Sie Ihren Personalausweis an das Lesegerät. Die Karte bezeugt, wer Sie sind - eine
        Zuordnung zu Ihrer Versichertennummer ist ein eigener Schritt danach.
      </p>
      <div className="hint">Demo-Modus: Das Auslesen der Karte wird simuliert; Testdaten sind bereits vorbelegt.</div>
      <form onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <DemoPersonPicker demoPersons={demoPersons} onSelect={selectPerson} />
        <div className="form-group">
          <label htmlFor="eid-name">Name</label>
          <input id="eid-name" value={name} onChange={(e) => setName(e.target.value)} required />
        </div>
        <div className="form-group">
          <label htmlFor="eid-vorname">Vorname</label>
          <input id="eid-vorname" value={vorname} onChange={(e) => setVorname(e.target.value)} required />
        </div>
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
        {/* Editierbar, obwohl eine echte Karte den Wert fest mitbringt: In der Demo ist das
            Feld die einzige Möglichkeit, eine ANDERE Karte derselben Person zu simulieren
            (ADR-19: neuer Wert, gleiches Konto) oder dieselbe Karte ein zweites Mal aufzulegen
            (Wiedererkennung). Der Demo-Personen-Picker füllt es weiterhin mit. */}
        <div className="form-group">
          <label htmlFor="eid-restricted-id">Restricted-ID (kartengebunden)</label>
          <input
            id="eid-restricted-id"
            value={restrictedId}
            onChange={(e) => setRestrictedId(e.target.value)}
            required
          />
          <span className="hint">
            Das kartengebundene Pseudonym. Eine neue Karte derselben Person bringt einen neuen Wert
            mit - zum Ausprobieren hier änderbar.
          </span>
        </div>
        {error && <div className="hint">{error}</div>}
        <div className="form-actions">
          <button type="submit">Karte auflegen (simuliert)</button>
        </div>
      </form>
    </div>
  )
}
