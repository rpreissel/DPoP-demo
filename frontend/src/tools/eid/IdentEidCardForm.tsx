import { useState } from 'react'
import { DemoPersonPicker } from '../../components/DemoPersonPicker'
import type { DemoPerson } from '../../types'
import { t } from '../../texts'

interface IdentEidCardFormProps {
  onSubmit: (fields: {
    name: string
    vorname: string
    geburtsdatum: string
    /** Street and house number in one line - the card's `Street` carries both. */
    strasse: string
    plz: string
    ort: string
    restrictedId: string
  }) => void
  error?: string
  /** Demo-only: every register person, offered as a picker that fills the whole card at once. */
  demoPersons?: DemoPerson[]
}

/** toolId=ident-eid / step=card: simulates reading the eID card's Ausweisdaten (possession factor). */
export function IdentEidCardForm({ onSubmit, error, demoPersons }: IdentEidCardFormProps) {
  // Prefilled from the first register persona - no hard-coded test person of our own.
  const first = demoPersons?.[0]
  const [name, setName] = useState(first?.name ?? '')
  const [vorname, setVorname] = useState(first?.vorname ?? '')
  const [geburtsdatum, setGeburtsdatum] = useState(first?.geburtsdatum ?? '')
  const [strasse, setStrasse] = useState(first?.strasse ?? '')
  const [plz, setPlz] = useState(first?.plz ?? '')
  const [ort, setOrt] = useState(first?.ort ?? '')
  const [restrictedId, setRestrictedId] = useState(first?.restrictedId ?? '')

  function selectPerson(person: DemoPerson) {
    setName(person.name ?? '')
    setVorname(person.vorname ?? '')
    setGeburtsdatum(person.geburtsdatum ?? '')
    setStrasse(person.strasse ?? '')
    setPlz(person.plz ?? '')
    setOrt(person.ort ?? '')
    setRestrictedId(person.restrictedId ?? '')
  }

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit({ name, vorname, geburtsdatum, strasse, plz, ort, restrictedId })
  }

  return (
    <div className="card">
      <h2>{t('eID-Karte auflegen')}</h2>
      <p>
        {t(
          'Halten Sie Ihren Personalausweis an das Lesegerät. Die Karte bezeugt, wer Sie sind - eine ' +
            'Zuordnung per Versichertennummer oder Partnernummer ist ein eigener Schritt danach.',
        )}
      </p>
      <div className="hint">{t('Demo-Modus: Das Auslesen der Karte wird simuliert.')}</div>
      <form onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <DemoPersonPicker demoPersons={demoPersons} onSelect={selectPerson} />
        <div className="form-group">
          <label htmlFor="eid-name">{t('Nachname')}</label>
          <input id="eid-name" value={name} onChange={(e) => setName(e.target.value)} required />
        </div>
        <div className="form-group">
          <label htmlFor="eid-vorname">{t('Vorname')}</label>
          <input id="eid-vorname" value={vorname} onChange={(e) => setVorname(e.target.value)} required />
        </div>
        <div className="form-group">
          <label htmlFor="eid-geburtsdatum">{t('Geburtsdatum')}</label>
          <input
            id="eid-geburtsdatum"
            type="date"
            value={geburtsdatum}
            onChange={(e) => setGeburtsdatum(e.target.value)}
            required
          />
        </div>
        <div className="form-group">
          <label htmlFor="eid-strasse">{t('Straße und Hausnummer')}</label>
          <input id="eid-strasse" value={strasse} onChange={(e) => setStrasse(e.target.value)} required />
        </div>
        <div className="form-group">
          <label htmlFor="eid-plz">{t('PLZ')}</label>
          <input id="eid-plz" value={plz} onChange={(e) => setPlz(e.target.value)} required />
        </div>
        <div className="form-group">
          <label htmlFor="eid-ort">{t('Ort')}</label>
          <input id="eid-ort" value={ort} onChange={(e) => setOrt(e.target.value)} required />
        </div>
        {/* Editierbar, obwohl eine echte Karte den Wert fest mitbringt: In der Demo ist das
            Feld die einzige Möglichkeit, eine ANDERE Karte derselben Person zu simulieren
            (ADR-19: neuer Wert, gleiches Konto) oder dieselbe Karte ein zweites Mal aufzulegen
            (Wiedererkennung). Der Demo-Personen-Picker füllt es weiterhin mit. */}
        <div className="form-group">
          <label htmlFor="eid-restricted-id">{t('Restricted-ID (kartengebunden)')}</label>
          <input
            id="eid-restricted-id"
            value={restrictedId}
            onChange={(e) => setRestrictedId(e.target.value)}
            required
          />
          <span className="hint">
            {t(
              'Das kartengebundene Pseudonym. Eine neue Karte derselben Person bringt einen neuen Wert ' +
                'mit - zum Ausprobieren hier änderbar.',
            )}
          </span>
        </div>
        {error && <div className="hint">{error}</div>}
        <div className="form-actions">
          <button type="submit">{t('Karte auflegen (simuliert)')}</button>
        </div>
      </form>
    </div>
  )
}
