import { useEffect, useState, type FormEvent } from 'react'
import '../../App.css'
import { ChannelNav } from '../../components/ChannelNav'
import { registerApi, type RegisterPerson } from '../../extApi'
import { nectApi, type NectAttributes, type NectCaseView, type NectProcedure, type NectRequestable } from '../../nectApi'

/**
 * What each document can deliver at all - mirrors NectProcedure.deliverable on the backend
 * (docs/ideen/ident-nect.md, Abschnitt 7): the eID card by access right, the passport chip's MRZ
 * data as a whole, the wallet's PID by selective disclosure.
 */
const PROCEDURES: { key: NectProcedure; label: string; hint: string; deliverable: NectRequestable[] }[] = [
  {
    key: 'eid',
    label: '🪪 Personalausweis (eID)',
    hint: 'Karte ans Handy halten, PIN eingeben - die Karte gibt nur die angefragten Daten heraus.',
    deliverable: ['family_name', 'given_names', 'birth_date', 'address', 'eid_pseudonym'],
  },
  {
    key: 'epass',
    label: '🛂 Reisepass',
    hint: 'Chip auslesen, Selfie mit dem Passbild abgleichen - der Chip wird ganz gelesen, weitergegeben wird nur Angefragtes. Eine Adresse enthält der Pass nicht.',
    deliverable: ['family_name', 'given_names', 'birth_date', 'document_id'],
  },
  {
    key: 'eudi',
    label: '👛 EUDI-Wallet',
    hint: 'Die Wallet zeigt an, was angefragt wird - Sie geben nur frei, was Sie ankreuzen. Ein Pseudonym enthält die PID nicht.',
    deliverable: ['family_name', 'given_names', 'birth_date', 'address'],
  },
]

const REQUESTABLE_LABELS: Record<NectRequestable, string> = {
  family_name: 'Name',
  given_names: 'Vorname(n)',
  birth_date: 'Geburtsdatum',
  address: 'Anschrift',
  eid_pseudonym: 'Pseudonym der Karte (nur eID)',
  document_id: 'Dokumentnummer und Ausstellerstaat (nur Reisepass)',
}

type PersonFields = Pick<NectAttributes, 'name' | 'vorname' | 'geburtsdatum' | 'strasse' | 'hausnummer' | 'plz' | 'ort'>
const PERSON_FIELDS: { key: keyof PersonFields; label: string; attribute: NectRequestable; type?: string }[] = [
  { key: 'name', label: 'Name', attribute: 'family_name' },
  { key: 'vorname', label: 'Vorname', attribute: 'given_names' },
  { key: 'geburtsdatum', label: 'Geburtsdatum', attribute: 'birth_date', type: 'date' },
  { key: 'strasse', label: 'Straße', attribute: 'address' },
  { key: 'hausnummer', label: 'Hausnummer', attribute: 'address' },
  { key: 'plz', label: 'PLZ', attribute: 'address' },
  { key: 'ort', label: 'Ort', attribute: 'address' },
]

function fieldsOf(p: RegisterPerson): PersonFields {
  return { name: p.name, vorname: p.vorname, geburtsdatum: p.geburtsdatum, strasse: p.strasse, hausnummer: p.hausnummer, plz: p.plz, ort: p.ort }
}

function inYears(years: number): string {
  const d = new Date()
  d.setFullYear(d.getFullYear() + years)
  return d.toISOString().slice(0, 10)
}

/** A stable mock pseudonym per person and purpose - the same card reads the same every time. */
function pseudonym(prefix: string, p: PersonFields): string {
  const seed = `${p.vorname ?? ''}|${p.name ?? ''}|${p.geburtsdatum ?? ''}`
  let h = 0
  for (const c of seed) h = (h * 31 + c.charCodeAt(0)) >>> 0
  return `${prefix}-${h.toString(36).toUpperCase().padStart(7, '0')}`
}

/**
 * Nect's jump page, simulated (docs/ideen/ident-nect.md). The user is on the identification
 * service's own site here: this page talks to /mock-nect only, and hands the browser back to the
 * relying party with nothing but the case id - the result is fetched by our backend, not carried
 * by the browser.
 */
export function NectApp() {
  const caseId = new URLSearchParams(window.location.search).get('case')
  const [view, setView] = useState<NectCaseView | null>(null)
  const [error, setError] = useState<string | null>(caseId ? null : 'Kein Vorgang angegeben - diese Seite wird vom Anbieter aufgerufen.')

  useEffect(() => {
    if (!caseId) return
    nectApi.fall(caseId).then(setView).catch((e: Error) => setError(e.message))
  }, [caseId])

  return (
    <div className="web-shell channel-nect">
      <ChannelNav badge="🪪 Nect Ident" />
      <div className="web-page">
        <div className="ext-banner">
          Simulierter <strong>Identifizierungsdienst</strong> (Nect). Sie sind hier nicht mehr in der Demo-App: Diese Seite
          spricht nur mit Nect. Danach geht es mit der Vorgangsnummer zurück, und unser Backend holt das Ergebnis selbst ab.
        </div>
        {error && <div className="card error-card"><h2>Fehler</h2><p>{error}</p></div>}
        {caseId && view && view.status !== 'OPEN' && (
          <div className="card">
            <h2>Vorgang bereits beendet</h2>
            <p>Dieser Vorgang hat den Status <code>{view.status}</code>. Starten Sie die Identifizierung in der App neu.</p>
          </div>
        )}
        {caseId && view?.status === 'OPEN' && <IdentForm caseId={caseId} requested={view.requested} onError={setError} />}
      </div>
    </div>
  )
}

function IdentForm({ caseId, requested, onError }: { caseId: string; requested: NectRequestable[]; onError: (m: string | null) => void }) {
  const [procedure, setProcedure] = useState<NectProcedure>('eid')
  const [personen, setPersonen] = useState<RegisterPerson[]>([])
  const [person, setPerson] = useState<PersonFields>({})
  const [pin, setPin] = useState('')
  const [documentNumber, setDocumentNumber] = useState('C01X00T47')
  const [can, setCan] = useState('')
  const [expiryDate, setExpiryDate] = useState(inYears(5))
  const [selfieMatches, setSelfieMatches] = useState(true)
  // EUDI: what the holder releases. The name is what makes the identification worth anything.
  const [released, setReleased] = useState<Record<keyof PersonFields, boolean>>({
    name: true, vorname: true, geburtsdatum: true, strasse: true, hausnummer: true, plz: true, ort: true,
  })
  const [busy, setBusy] = useState(false)

  // Demo convenience only: prefill from the simulated register, as if the user owned that card.
  useEffect(() => {
    registerApi
      .personen()
      .then((ps) => {
        setPersonen(ps)
        if (ps[0]) setPerson(fieldsOf(ps[0]))
      })
      .catch(() => setPersonen([]))
  }, [])

  const leave = async (action: () => Promise<{ redirectUri: string }>) => {
    setBusy(true)
    onError(null)
    try {
      const { redirectUri } = await action()
      window.location.assign(redirectUri)
    } catch (e) {
      onError((e as Error).message)
      setBusy(false)
    }
  }

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (procedure === 'epass' && !selfieMatches) {
      void leave(() => nectApi.scheitern(caseId, 'Selfie passt nicht zum Passbild'))
      return
    }
    // What the document yields: the eID card only the requested rights, the wallet only what its
    // holder released - but the passport chip always its whole MRZ data. Nect itself then hands on
    // no more than was requested.
    const read = Object.fromEntries(fields.filter((f) => procedure !== 'eudi' || released[f.key]).map((f) => [f.key, person[f.key]]))
    const attributes: NectAttributes = procedure === 'eid'
      ? { ...read, ...(offered.includes('eid_pseudonym') ? { restrictedId: pseudonym('NECT-EID', person) } : {}) }
      : procedure === 'epass'
        ? { name: person.name, vorname: person.vorname, geburtsdatum: person.geburtsdatum, documentNumber, issuingState: 'D' }
        : read
    void leave(() =>
      nectApi.abschliessen(caseId, procedure, attributes, procedure === 'eid' ? pin : undefined, procedure === 'epass' ? expiryDate : undefined),
    )
  }

  const current = PROCEDURES.find((p) => p.key === procedure)!
  // Asked for and deliverable by this document - all a relying party can get from this procedure.
  const offered = requested.filter((r) => current.deliverable.includes(r))
  const fields = PERSON_FIELDS.filter((f) => offered.includes(f.attribute))

  return (
    <form className="card form-grid" onSubmit={submit}>
      <h2>Wie möchten Sie sich ausweisen?</h2>
      <div className="form-actions">
        {PROCEDURES.map((p) => (
          <button key={p.key} type="button" className={p.key === procedure ? undefined : 'secondary'} aria-pressed={p.key === procedure} onClick={() => setProcedure(p.key)}>
            {p.label}
          </button>
        ))}
      </div>
      <div className="hint">{current.hint}</div>
      <div className="hint">
        <strong>Angefragt von DPoP-Demo:</strong> {requested.map((r) => REQUESTABLE_LABELS[r]).join(', ')}.<br />
        <strong>Mit diesem Dokument übermittelt:</strong> {offered.map((r) => REQUESTABLE_LABELS[r]).join(', ') || 'nichts'}.
      </div>

      {personen.length > 0 && (
        <div className="form-group">
          <label htmlFor="nect-person">Demo: Dokument von …</label>
          <select
            id="nect-person"
            value=""
            onChange={(e) => {
              const p = personen.find((x) => String(x.id) === e.target.value)
              if (p) setPerson(fieldsOf(p))
            }}
          >
            <option value="">Person aus dem Register übernehmen …</option>
            {personen.map((p) => (
              <option key={p.id} value={p.id}>{[p.vorname, p.name].filter(Boolean).join(' ')}</option>
            ))}
          </select>
        </div>
      )}

      <h3>{procedure === 'eudi' ? 'Angefragte Daten – ankreuzen, was Sie freigeben' : 'Vom Dokument gelesen'}</h3>
      {fields.map((f) => (
        <div className="form-group" key={f.key}>
          <label htmlFor={`nect-${f.key}`}>
            {procedure === 'eudi' && (
              <input
                type="checkbox"
                aria-label={`${f.label} freigeben`}
                checked={released[f.key]}
                onChange={(e) => setReleased({ ...released, [f.key]: e.target.checked })}
              />
            )}{' '}
            {f.label}
          </label>
          <input
            id={`nect-${f.key}`}
            type={f.type ?? 'text'}
            value={person[f.key] ?? ''}
            disabled={procedure === 'eudi' && !released[f.key]}
            onChange={(e) => setPerson({ ...person, [f.key]: e.target.value || undefined })}
          />
        </div>
      ))}

      {procedure === 'eid' && (
        <div className="form-group">
          <label htmlFor="nect-pin">eID-PIN (Test: 123456)</label>
          <input id="nect-pin" type="password" inputMode="numeric" autoComplete="off" value={pin} onChange={(e) => setPin(e.target.value)} />
        </div>
      )}
      {procedure === 'epass' && (
        <>
          <div className="form-group">
            <label htmlFor="nect-docno">Dokumentnummer{offered.includes('document_id') ? '' : ' (nicht angefragt, wird nicht übermittelt)'}</label>
            <input id="nect-docno" value={documentNumber} onChange={(e) => setDocumentNumber(e.target.value)} />
          </div>
          <div className="form-group">
            <label htmlFor="nect-can">CAN (6 Ziffern auf der Datenseite)</label>
            <input id="nect-can" inputMode="numeric" value={can} onChange={(e) => setCan(e.target.value)} />
          </div>
          <div className="form-group">
            <label htmlFor="nect-expiry">Gültig bis (prüft Nect selbst, wird nicht übermittelt)</label>
            <input id="nect-expiry" type="date" value={expiryDate} onChange={(e) => setExpiryDate(e.target.value)} />
          </div>
          <div className="form-group">
            <label>
              <input type="checkbox" checked={selfieMatches} onChange={(e) => setSelfieMatches(e.target.checked)} /> Selfie passt zum Passbild
            </label>
          </div>
        </>
      )}

      <div className="form-actions">
        <button type="submit" disabled={busy}>Identifizieren</button>
        <button type="button" className="secondary" disabled={busy} onClick={() => void leave(() => nectApi.scheitern(caseId, 'Identifizierung fehlgeschlagen (simuliert)'))}>
          Fehlschlag simulieren
        </button>
        <button type="button" className="secondary" disabled={busy} onClick={() => void leave(() => nectApi.abbrechen(caseId))}>
          Abbrechen
        </button>
      </div>
    </form>
  )
}
