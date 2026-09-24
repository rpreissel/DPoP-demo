import { useState } from 'react'
import { DemoPersonPicker } from '../../components/DemoPersonPicker'
import type { DemoPerson } from '../../types'
import type { FscFields } from './api'

/** What the first screen collects - everything the backend stages before it asks for `fsc`. */
const PERSONAL_FIELDS = ['kvnr', 'name', 'vorname', 'geburtsdatum']

type Personalien = Omit<FscFields, 'fsc'>

interface IdentFscFormProps {
  onSubmit: (fields: Partial<FscFields>) => void
  /** stepData.missingFields, when the step carries it - undefined after a failed attempt. */
  missingFields?: string[]
  error?: string
  /** Demo-only: every register person, offered as a picker that fills the personal data and FSC together. */
  demoPersons?: DemoPerson[]
}

type Page = 'personalien' | 'code'

/**
 * toolId=ident-fsc / step=input (docs/06-ablaeufe.md #2). The backend keeps ONE step; the two
 * pages here - personal data first, then the code - are purely this form's choice
 * (docs/10-frontend.md). The backend checks the personal data the moment it is complete and only
 * then asks for `fsc`, so `missingFields` says which page is due.
 *
 * A failed attempt carries no `missingFields` - the form then stays on the page it was submitted
 * from: rejected personal data is corrected where it was typed, a rejected code on the code page.
 */
export function IdentFscForm({ onSubmit, missingFields, error, demoPersons }: IdentFscFormProps) {
  // Prefilled from the first register persona - no hard-coded test person of our own (ADR-31).
  const first = demoPersons?.[0]
  const [personalien, setPersonalien] = useState<Personalien>({
    vorname: first?.vorname ?? '',
    name: first?.name ?? '',
    geburtsdatum: first?.geburtsdatum ?? '',
    kvnr: first?.kvnr ?? '',
  })
  const [fsc, setFsc] = useState(first?.fscCode ?? '')
  const [submittedFrom, setSubmittedFrom] = useState<Page>('personalien')
  const [editing, setEditing] = useState(false)

  const dueByBackend: Page | undefined = missingFields
    ? missingFields.some((field) => PERSONAL_FIELDS.includes(field)) ? 'personalien' : 'code'
    : undefined
  const page: Page = editing ? 'personalien' : (dueByBackend ?? submittedFrom)

  function selectPerson(person: DemoPerson) {
    setPersonalien({
      vorname: person.vorname ?? '',
      name: person.name ?? '',
      geburtsdatum: person.geburtsdatum ?? '',
      kvnr: person.kvnr,
    })
    setFsc(person.fscCode ?? '')
  }

  function update(field: keyof Personalien) {
    return (event: React.ChangeEvent<HTMLInputElement>) => setPersonalien({ ...personalien, [field]: event.target.value })
  }

  function submitPersonalien(event: React.FormEvent) {
    event.preventDefault()
    setSubmittedFrom('personalien')
    setEditing(false)
    onSubmit(personalien)
  }

  function submitCode(event: React.FormEvent) {
    event.preventDefault()
    setSubmittedFrom('code')
    onSubmit({ fsc })
  }

  if (page === 'personalien') {
    return (
      <div className="card">
        <h2>Identifikation per Freischaltcode</h2>
        <p>Damit Sie Ihren Freischaltcode gleich eingeben können, brauchen wir noch diese Daten:</p>
        <form onSubmit={submitPersonalien} className="form-grid" style={{ marginTop: '1rem' }}>
          <DemoPersonPicker demoPersons={demoPersons} selectedKvnr={personalien.kvnr} onSelect={selectPerson} />
          <div className="form-group">
            <label htmlFor="vorname">Vorname</label>
            <input id="vorname" value={personalien.vorname} onChange={update('vorname')} autoComplete="given-name" required />
          </div>
          <div className="form-group">
            <label htmlFor="name">Nachname</label>
            <input id="name" value={personalien.name} onChange={update('name')} autoComplete="family-name" required />
          </div>
          <div className="form-group">
            <label htmlFor="geburtsdatum">Geburtsdatum</label>
            <input id="geburtsdatum" type="date" value={personalien.geburtsdatum} onChange={update('geburtsdatum')} autoComplete="bday" required />
          </div>
          <div className="form-group">
            <label htmlFor="kvnr">Versichertennummer</label>
            <input id="kvnr" value={personalien.kvnr} onChange={update('kvnr')} required />
          </div>
          {error && <div className="hint">{error}</div>}
          <div className="form-actions">
            <button type="submit">Weiter zur Freischaltcode-Eingabe</button>
          </div>
        </form>
      </div>
    )
  }

  return (
    <div className="card">
      <h2>Freischaltcode eingeben</h2>
      <p>
        Geben Sie den Freischaltcode ein, den wir Ihnen per Brief geschickt haben, für{' '}
        <strong>
          {personalien.vorname} {personalien.name}
        </strong>{' '}
        ({personalien.kvnr}).
      </p>
      {first && !fsc && <div className="hint">Kein gültiger Code im Briefkasten (Personenregister /ext/)</div>}
      <form onSubmit={submitCode} className="form-grid" style={{ marginTop: '1rem' }}>
        <div className="form-group">
          <label htmlFor="fsc">Freischaltcode</label>
          <input id="fsc" value={fsc} onChange={(e) => setFsc(e.target.value)} autoComplete="one-time-code" required />
        </div>
        {error && <div className="hint">{error}</div>}
        <div className="form-actions">
          <button type="button" className="secondary" onClick={() => setEditing(true)}>
            Angaben ändern
          </button>
          <button type="submit">Identifizieren</button>
        </div>
      </form>
    </div>
  )
}
