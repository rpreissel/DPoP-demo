import { useState } from 'react'
import { DemoPersonPicker } from '../../components/DemoPersonPicker'
import type { DemoPerson } from '../../types'
import { t } from '../../texts'
import { DemoNote } from '../../components/DemoArea'

interface IdentEidCardFormProps {
  onSubmit: (fields: {
    familyName: string
    givenNames: string
    birthDate: string
    /** Street and house number in one line - the card's `Street` carries both. */
    streetAddress: string
    postalCode: string
    locality: string
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
  const [familyName, setFamilyName] = useState(first?.familyName ?? '')
  const [givenNames, setGivenNames] = useState(first?.givenNames ?? '')
  const [birthDate, setBirthDate] = useState(first?.birthDate ?? '')
  const [streetAddress, setStreetAddress] = useState(first?.streetAddress ?? '')
  const [postalCode, setPostalCode] = useState(first?.postalCode ?? '')
  const [locality, setLocality] = useState(first?.locality ?? '')
  const [restrictedId, setRestrictedId] = useState(first?.restrictedId ?? '')

  function selectPerson(person: DemoPerson) {
    setFamilyName(person.familyName ?? '')
    setGivenNames(person.givenNames ?? '')
    setBirthDate(person.birthDate ?? '')
    setStreetAddress(person.streetAddress ?? '')
    setPostalCode(person.postalCode ?? '')
    setLocality(person.locality ?? '')
    setRestrictedId(person.restrictedId ?? '')
  }

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit({ familyName, givenNames, birthDate, streetAddress, postalCode, locality, restrictedId })
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
      <DemoNote>{t('Demo-Modus: Das Auslesen der Karte wird simuliert.')}</DemoNote>
      <form onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <DemoPersonPicker demoPersons={demoPersons} onSelect={selectPerson} />
        <div className="form-group">
          <label htmlFor="eid-familyName">{t('Nachname')}</label>
          <input id="eid-familyName" value={familyName} onChange={(e) => setFamilyName(e.target.value)} required />
        </div>
        <div className="form-group">
          <label htmlFor="eid-givenNames">{t('Vorname')}</label>
          <input id="eid-givenNames" value={givenNames} onChange={(e) => setGivenNames(e.target.value)} required />
        </div>
        <div className="form-group">
          <label htmlFor="eid-birthDate">{t('Geburtsdatum')}</label>
          <input
            id="eid-birthDate"
            type="date"
            value={birthDate}
            onChange={(e) => setBirthDate(e.target.value)}
            required
          />
        </div>
        <div className="form-group">
          <label htmlFor="eid-streetAddress">{t('Straße und Hausnummer')}</label>
          <input id="eid-streetAddress" value={streetAddress} onChange={(e) => setStreetAddress(e.target.value)} required />
        </div>
        <div className="form-group">
          <label htmlFor="eid-postalCode">{t('PLZ')}</label>
          <input id="eid-postalCode" value={postalCode} onChange={(e) => setPostalCode(e.target.value)} required />
        </div>
        <div className="form-group">
          <label htmlFor="eid-locality">{t('Ort')}</label>
          <input id="eid-locality" value={locality} onChange={(e) => setLocality(e.target.value)} required />
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
