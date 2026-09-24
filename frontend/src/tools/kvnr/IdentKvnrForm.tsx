import { useState } from 'react'
import { DemoPersonPicker } from '../../components/DemoPersonPicker'
import type { DemoPerson } from '../../types'
import { t } from '../../texts'

interface IdentKvnrFormProps {
  /** Either the KVNR or - only without one, for a Partner (ADR-34) - the Partnernummer. */
  onSubmit: (identifier: { kvnr: string } | { partnernr: string }) => void
  /** Skips the step (ToolRenderContext.onSkip) - rendered right next to the submit button, because that is where the decision is made. */
  onSkip?: () => void
  skipLabel: string
  error?: string
  /** Demo-only: all seeded personas, so a mismatching number is easy to try out too. */
  demoPersons?: DemoPerson[]
}

/**
 * toolId=ident-kvnr / step=input: the number an attestation cannot carry. Runs only after an
 * identity was attested (docs/12-entscheidungen.md ADR-18) and assigns the account to that
 * person's register record - it proves nothing on its own.
 */
export function IdentKvnrForm({ onSubmit, onSkip, skipLabel, error, demoPersons }: IdentKvnrFormProps) {
  const [kvnr, setKvnr] = useState('A123456789')
  const [partnernr, setPartnernr] = useState('')
  // The KVNR is asked for first; the Partnernummer only when there is none (ADR-34).
  const [withoutKvnr, setWithoutKvnr] = useState(false)

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit(withoutKvnr ? { partnernr } : { kvnr })
  }

  function selectPerson(person: DemoPerson) {
    setWithoutKvnr(!person.kvnr)
    if (person.kvnr) setKvnr(person.kvnr)
    else setPartnernr(person.personId)
  }

  return (
    <div className="card">
      <h2>{t('Versichertennummer angeben')}</h2>
      <p>
        {t(
          'Ihre Identität ist bereits nachgewiesen. Mit der Versichertennummer wird Ihr Konto Ihrem ' +
            'Datensatz bei der Krankenkasse zugeordnet - sie muss zu der nachgewiesenen Person gehören.',
        )}
      </p>
      <p className="hint">
        {t(
          'Der Schritt ist freiwillig: Mit „{skipLabel}" geht die Registrierung ohne diese Zuordnung ' +
            'weiter, das Konto bleibt nutzbar.',
          { skipLabel },
        )}
      </p>
      {error && <div className="hint">{error}</div>}
      <form onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <DemoPersonPicker demoPersons={demoPersons} onSelect={selectPerson} />
        {withoutKvnr ? (
          <div className="form-group">
            <label htmlFor="partnernr-input">{t('Partnernummer')}</label>
            <input id="partnernr-input" value={partnernr} placeholder="P000000000" onChange={(e) => setPartnernr(e.target.value)} required />
            <button type="button" className="secondary small" onClick={() => setWithoutKvnr(false)}>
              {t('Ich habe doch eine Versichertennummer')}
            </button>
          </div>
        ) : (
          <div className="form-group">
            <label htmlFor="kvnr-input">{t('Versichertennummer')}</label>
            <input id="kvnr-input" value={kvnr} onChange={(e) => setKvnr(e.target.value)} required />
            <button type="button" className="secondary small" onClick={() => setWithoutKvnr(true)}>
              {t('Ich habe keine Versichertennummer')}
            </button>
          </div>
        )}
        <div className="form-actions">
          <button type="submit">{t('Zuordnen')}</button>
          {onSkip && (
            <button type="button" className="secondary" onClick={onSkip}>
              {skipLabel}
            </button>
          )}
        </div>
      </form>
    </div>
  )
}
