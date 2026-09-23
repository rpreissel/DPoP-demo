import { useCallback, useEffect, useState, type FormEvent } from 'react'
import '../../App.css'
import { ChannelNav, type NavTab } from '../../components/ChannelNav'
import { registerApi, type Brief, type Freischaltcode, type RegisterPerson } from '../../extApi'
import { useHashTab } from '../../useHashTab'

type Tab = 'personen' | 'freischaltcodes' | 'briefkasten'
const TAB_KEYS = ['personen', 'freischaltcodes', 'briefkasten'] as const
const TABS: NavTab<Tab>[] = [
  { key: 'personen', label: 'Personen' },
  { key: 'freischaltcodes', label: 'Freischaltcodes' },
  { key: 'briefkasten', label: 'Briefkasten' },
]

const FIELDS: { key: keyof RegisterPerson; label: string; type?: string }[] = [
  { key: 'name', label: 'Name' },
  { key: 'vorname', label: 'Vorname' },
  { key: 'geburtsdatum', label: 'Geburtsdatum', type: 'date' },
  { key: 'strasse', label: 'Straße' },
  { key: 'hausnummer', label: 'Hausnummer' },
  { key: 'plz', label: 'PLZ' },
  { key: 'ort', label: 'Ort' },
]

function fullName(p: RegisterPerson | undefined): string {
  return p ? [p.vorname, p.name].filter(Boolean).join(' ') || `#${p.id}` : 'unbekannt'
}

/** "Straße Nr, PLZ Ort" - leaving out whatever part the register does not have. */
function address(p: RegisterPerson): string {
  const street = [p.strasse, p.hausnummer].filter(Boolean).join(' ')
  const city = [p.plz, p.ort].filter(Boolean).join(' ')
  return [street, city].filter(Boolean).join(', ') || '–'
}

function formatDate(iso: string | undefined): string {
  return iso ? new Date(iso).toLocaleString('de-DE', { dateStyle: 'medium', timeStyle: 'short' }) : '–'
}

function inOneYear(): string {
  const d = new Date()
  d.setFullYear(d.getFullYear() + 1)
  return d.toISOString().slice(0, 10)
}

/**
 * The simulated person register (ADR-31) - deliberately a page of its own, visibly a foreign
 * system: what is changed here reaches our application only the way a real register would, by
 * ident-fsc asking it. Nothing on this page talks to /orchestrator.
 */
export function ExtApp() {
  const [tab, setTab] = useHashTab<Tab>(TAB_KEYS, 'personen')
  const [personen, setPersonen] = useState<RegisterPerson[]>([])
  const [error, setError] = useState<string | null>(null)

  const reload = useCallback(() => {
    registerApi.personen().then(setPersonen).catch((e: Error) => setError(e.message))
  }, [])
  useEffect(reload, [reload])

  return (
    <div className="web-shell channel-ext">
      <ChannelNav badge="🏛️ Personenregister" tabs={TABS} sub={tab} onSelectTab={setTab} />
      <div className="web-page">
        <div className="ext-banner">
          Simuliertes <strong>Fremdsystem</strong>: das externe Personenregister. Unsere Anwendung liest es nur über{' '}
          <code>PersonDirectory</code> und fragt es beim Freischaltcode (<code>ident-fsc</code>).
        </div>
        {error && <div className="card error-card"><h2>Fehler</h2><p>{error}</p></div>}
        {tab === 'personen' && <PersonenTab personen={personen} onChanged={reload} onError={setError} />}
        {tab === 'freischaltcodes' && <FreischaltcodesTab personen={personen} onError={setError} />}
        {tab === 'briefkasten' && <BriefkastenTab personen={personen} onError={setError} />}
      </div>
    </div>
  )
}

function PersonenTab({ personen, onChanged, onError }: { personen: RegisterPerson[]; onChanged: () => void; onError: (m: string | null) => void }) {
  // null = no form open; an object without id = new person.
  const [editing, setEditing] = useState<RegisterPerson | null>(null)

  async function save(e: FormEvent) {
    e.preventDefault()
    if (!editing) return
    try {
      if (editing.id) await registerApi.aendern(editing.id, editing)
      else await registerApi.anlegen(editing)
      onError(null)
      setEditing(null)
      onChanged()
    } catch (err) {
      onError((err as Error).message)
    }
  }

  return (
    <>
      <div className="card">
        <div className="card-heading-row">
          <h2>Personen</h2>
          <button className="secondary small" onClick={() => setEditing({})}>+ Neue Person</button>
        </div>
        <div className="journey-log-table-scroll">
          <table className="journey-log-table">
            <thead>
              <tr><th>KVNR</th><th>Name</th><th>Geburtsdatum</th><th>Adresse</th><th /></tr>
            </thead>
            <tbody>
              {personen.map((p) => (
                <tr key={p.id}>
                  <td><code>{p.kvnr}</code></td>
                  <td>{fullName(p)}</td>
                  <td>{p.geburtsdatum ?? '–'}</td>
                  <td>{address(p)}</td>
                  <td><button className="secondary small" onClick={() => setEditing({ ...p })}>Bearbeiten</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {editing && (
        <form className="card" onSubmit={save}>
          <h2>{editing.id ? `${fullName(editing)} bearbeiten` : 'Neue Person'}</h2>
          <div className="form-group">
            <label htmlFor="ext-kvnr">KVNR</label>
            <input
              id="ext-kvnr"
              value={editing.kvnr ?? ''}
              disabled={!!editing.id}
              placeholder="A123456789"
              onChange={(e) => setEditing({ ...editing, kvnr: e.target.value })}
            />
          </div>
          {FIELDS.map((f) => (
            <div className="form-group" key={f.key}>
              <label htmlFor={`ext-${f.key}`}>{f.label}</label>
              <input
                id={`ext-${f.key}`}
                type={f.type ?? 'text'}
                value={(editing[f.key] as string | undefined) ?? ''}
                onChange={(e) => setEditing({ ...editing, [f.key]: e.target.value || undefined })}
              />
            </div>
          ))}
          <div className="form-actions">
            <button type="submit">Speichern</button>
            <button type="button" className="secondary" onClick={() => setEditing(null)}>Abbrechen</button>
          </div>
        </form>
      )}
    </>
  )
}

function FreischaltcodesTab({ personen, onError }: { personen: RegisterPerson[]; onError: (m: string | null) => void }) {
  const [personId, setPersonId] = useState<number | null>(null)
  const [codes, setCodes] = useState<Freischaltcode[]>([])
  const [gueltigBis, setGueltigBis] = useState(inOneYear)
  const [issued, setIssued] = useState<Brief | null>(null)
  const selected = personId ?? personen[0]?.id ?? null

  const reload = useCallback(() => {
    if (selected == null) return
    registerApi.freischaltcodes(selected).then(setCodes).catch((e: Error) => onError(e.message))
  }, [selected, onError])
  useEffect(reload, [reload])

  async function ausstellen() {
    if (selected == null) return
    try {
      setIssued(await registerApi.ausstellen(selected, new Date(`${gueltigBis}T23:59:59`).toISOString()))
      reload()
    } catch (err) {
      onError((err as Error).message)
    }
  }

  async function widerrufen(id: number) {
    try {
      await registerApi.widerrufen(id)
      reload()
    } catch (err) {
      onError((err as Error).message)
    }
  }

  return (
    <div className="card">
      <h2>Freischaltcodes</h2>
      <div className="form-group">
        <label htmlFor="ext-person">Person</label>
        <select id="ext-person" value={selected ?? ''} onChange={(e) => { setPersonId(Number(e.target.value)); setIssued(null) }}>
          {personen.map((p) => <option key={p.id} value={p.id}>{fullName(p)} ({p.kvnr})</option>)}
        </select>
      </div>

      <div className="journey-log-table-scroll">
        <table className="journey-log-table">
          <thead><tr><th>#</th><th>Status</th><th>Gültig bis</th><th /></tr></thead>
          <tbody>
            {codes.length === 0 && <tr><td colSpan={4}>Keine Freischaltcodes.</td></tr>}
            {codes.map((c) => (
              <tr key={c.id}>
                <td>{c.id}</td>
                <td>{c.revokedAt ? `widerrufen ${formatDate(c.revokedAt)}` : c.valid ? '✅ gültig' : 'abgelaufen'}</td>
                <td>{formatDate(c.expiresAt)}</td>
                <td>{!c.revokedAt && <button className="secondary small" onClick={() => widerrufen(c.id)}>Widerrufen</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="hint">Das Register speichert nur den Hash. Den Klartext trägt allein der Brief, siehe Briefkasten.</p>

      <h3>Neuen Code ausstellen</h3>
      <div className="form-group">
        <label htmlFor="ext-gueltig">Gültig bis</label>
        <input id="ext-gueltig" type="date" value={gueltigBis} onChange={(e) => setGueltigBis(e.target.value)} />
      </div>
      <div className="form-actions">
        <button onClick={ausstellen} disabled={selected == null}>Ausstellen und Brief versenden</button>
      </div>
      {issued && <BriefCard brief={issued} person={personen.find((p) => p.id === issued.personId)} />}
    </div>
  )
}

function BriefkastenTab({ personen, onError }: { personen: RegisterPerson[]; onError: (m: string | null) => void }) {
  const [briefe, setBriefe] = useState<Brief[]>([])
  useEffect(() => {
    registerApi.briefe().then(setBriefe).catch((e: Error) => onError(e.message))
  }, [onError])

  return (
    <div className="card">
      <h2>Briefkasten</h2>
      <p>Alle verschickten Briefe, neueste zuerst. In der echten Welt läge jeder davon im Briefkasten der Person.</p>
      {briefe.length === 0 && <p>Noch keine Briefe.</p>}
      <div className="brief-list">
        {briefe.map((b) => <BriefCard key={b.id} brief={b} person={personen.find((p) => p.id === b.personId)} />)}
      </div>
    </div>
  )
}

function BriefCard({ brief, person }: { brief: Brief; person: RegisterPerson | undefined }) {
  return (
    <div className="brief">
      <div className="brief-meta">An {fullName(person)} · versandt {formatDate(brief.versandtAm)}</div>
      <div>Ihr Freischaltcode lautet:</div>
      <div className="brief-code">{brief.code}</div>
    </div>
  )
}
