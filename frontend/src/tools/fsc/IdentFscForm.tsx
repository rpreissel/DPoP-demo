import { useState } from 'react'
import { DemoPersonPicker } from '../../components/DemoPersonPicker'
import type { DemoPerson } from '../../types'
import type { FscFields } from './api'
import { t } from '../../texts'
import { Tx } from '../../Tx'
import { DemoNote } from '../../components/DemoArea'

/** What the first screen collects - everything the backend stages before it asks for `fsc`. */
const PERSONAL_FIELDS = ['kvnr', 'name', 'vorname', 'geburtsdatum']
// `kvnr` in missingFields stands for "KVNR or Partnernummer" - the form asks for the KVNR first (ADR-34).

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
    partnernr: first?.kvnr ? '' : (first?.personId ?? ''),
  })
  // The KVNR is asked for first; the Partnernummer only when there is none (a Partner, ADR-34).
  const [withoutKvnr, setWithoutKvnr] = useState(first != null && !first.kvnr)
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
      kvnr: person.kvnr ?? '',
      partnernr: person.kvnr ? '' : person.personId,
    })
    setWithoutKvnr(!person.kvnr)
    setFsc(person.fscCode ?? '')
  }

  function update(field: keyof Personalien) {
    return (event: React.ChangeEvent<HTMLInputElement>) => setPersonalien({ ...personalien, [field]: event.target.value })
  }

  function submitPersonalien(event: React.FormEvent) {
    event.preventDefault()
    setSubmittedFrom('personalien')
    setEditing(false)
    const { kvnr, partnernr, ...rest } = personalien
    onSubmit(withoutKvnr ? { ...rest, partnernr } : { ...rest, kvnr })
  }

  const identifier = withoutKvnr ? personalien.partnernr : personalien.kvnr
  const selectedPersonId =
    demoPersons?.find((p) => (withoutKvnr ? p.personId === personalien.partnernr : p.kvnr === personalien.kvnr))?.personId ?? ''

  function submitCode(event: React.FormEvent) {
    event.preventDefault()
    setSubmittedFrom('code')
    onSubmit({ fsc })
  }

  if (page === 'personalien') {
    return (
      <div className="card">
        <h2>{t('Identifikation per Freischaltcode')}</h2>
        <p>{t('Damit Sie Ihren Freischaltcode gleich eingeben können, brauchen wir noch diese Daten:')}</p>
        <form onSubmit={submitPersonalien} className="form-grid" style={{ marginTop: '1rem' }}>
          <DemoPersonPicker demoPersons={demoPersons} selectedPersonId={selectedPersonId} onSelect={selectPerson} />
          <div className="form-group">
            <label htmlFor="vorname">{t('Vorname')}</label>
            <input id="vorname" value={personalien.vorname} onChange={update('vorname')} autoComplete="given-name" required />
          </div>
          <div className="form-group">
            <label htmlFor="name">{t('Nachname')}</label>
            <input id="name" value={personalien.name} onChange={update('name')} autoComplete="family-name" required />
          </div>
          <div className="form-group">
            <label htmlFor="geburtsdatum">{t('Geburtsdatum')}</label>
            <input id="geburtsdatum" type="date" value={personalien.geburtsdatum} onChange={update('geburtsdatum')} autoComplete="bday" required />
          </div>
          {withoutKvnr ? (
            <div className="form-group">
              <label htmlFor="partnernr">{t('Partnernummer')}</label>
              <input id="partnernr" value={personalien.partnernr} placeholder="P000000000" onChange={update('partnernr')} required />
              <button type="button" className="secondary small" onClick={() => setWithoutKvnr(false)}>
                {t('Ich habe doch eine Versichertennummer')}
              </button>
            </div>
          ) : (
            <div className="form-group">
              <label htmlFor="kvnr">{t('Versichertennummer')}</label>
              <input id="kvnr" value={personalien.kvnr} onChange={update('kvnr')} required />
              <button type="button" className="secondary small" onClick={() => setWithoutKvnr(true)}>
                {t('Ich habe keine Versichertennummer')}
              </button>
            </div>
          )}
          {error && <div className="hint">{error}</div>}
          <div className="form-actions">
            <button type="submit">{t('Weiter zur Freischaltcode-Eingabe')}</button>
          </div>
        </form>
      </div>
    )
  }

  return (
    <div className="card">
      <h2>{t('Freischaltcode eingeben')}</h2>
      <p>
        <Tx
          text="Geben Sie den Freischaltcode ein, den wir Ihnen per Brief geschickt haben, für {person} ({nummer})."
          person={
            <strong>
              {personalien.vorname} {personalien.name}
            </strong>
          }
          nummer={identifier}
        />
      </p>
      {first && !fsc && <DemoNote>{t('Für diese Person liegt kein gültiger Code im Briefkasten. Stellen Sie im Personenverzeichnis ({pfad}, Reiter „Freischaltcodes“) einen neuen aus.', { pfad: '/personenverzeichnis/' })}</DemoNote>}
      <form onSubmit={submitCode} className="form-grid" style={{ marginTop: '1rem' }}>
        <div className="form-group">
          <label htmlFor="fsc">{t('Freischaltcode')}</label>
          <input id="fsc" value={fsc} onChange={(e) => setFsc(e.target.value)} autoComplete="one-time-code" required />
        </div>
        {error && <div className="hint">{error}</div>}
        <div className="form-actions">
          <button type="button" className="secondary" onClick={() => setEditing(true)}>
            {t('Angaben ändern')}
          </button>
          <button type="submit">{t('Identifizieren')}</button>
        </div>
      </form>
    </div>
  )
}
