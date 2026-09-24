import { useCallback, useEffect, useState, type FormEvent } from 'react'
import '../../App.css'
import { ChannelNav, type NavTab } from '../../components/ChannelNav'
import { personenverzeichnisApi, type Brief, type Freischaltcode, type RegisterPerson } from '../../personenverzeichnisApi'
import { language, t } from '../../texts'
import { Tx } from '../../Tx'
import { useHashTab } from '../../useHashTab'

type Tab = 'personen' | 'freischaltcodes' | 'briefkasten'
const TAB_KEYS = ['personen', 'freischaltcodes', 'briefkasten'] as const
const TABS: NavTab<Tab>[] = [
  { key: 'personen', label: t('Personen') },
  { key: 'freischaltcodes', label: t('Freischaltcodes') },
  { key: 'briefkasten', label: t('Briefkasten') },
]

const FIELDS: { key: keyof RegisterPerson; label: string; type?: string }[] = [
  { key: 'name', label: t('Name') },
  { key: 'vorname', label: t('Vorname') },
  { key: 'geburtsdatum', label: t('Geburtsdatum'), type: 'date' },
  { key: 'strasse', label: t('Straße') },
  { key: 'hausnummer', label: t('Hausnummer') },
  { key: 'plz', label: t('PLZ') },
  { key: 'ort', label: t('Ort') },
]

function fullName(p: RegisterPerson | undefined): string {
  return p ? [p.vorname, p.name].filter(Boolean).join(' ') || `#${p.id}` : t('unbekannt')
}

/** "Straße Nr, PLZ Ort" - leaving out whatever part the register does not have. */
function address(p: RegisterPerson): string {
  const street = [p.strasse, p.hausnummer].filter(Boolean).join(' ')
  const city = [p.plz, p.ort].filter(Boolean).join(' ')
  return [street, city].filter(Boolean).join(', ') || '–'
}

function formatDate(iso: string | undefined): string {
  return iso ? new Date(iso).toLocaleString(language(), { dateStyle: 'medium', timeStyle: 'short' }) : '–'
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
export function PersonenverzeichnisApp() {
  const [tab, setTab] = useHashTab<Tab>(TAB_KEYS, 'personen')
  const [personen, setPersonen] = useState<RegisterPerson[]>([])
  const [error, setError] = useState<string | null>(null)

  const reload = useCallback(() => {
    personenverzeichnisApi.personen().then(setPersonen).catch((e: Error) => setError(e.message))
  }, [])
  useEffect(reload, [reload])

  return (
    <div className="web-shell channel-ext">
      <ChannelNav badge={`🏛️ ${t('Personenverzeichnis')}`} tabs={TABS} sub={tab} onSelectTab={setTab} />
      <div className="web-page">
        <div className="ext-banner">
          <Tx text="Simuliertes {fremdsystem}: das externe Personenverzeichnis." fremdsystem={<strong>{t('Fremdsystem')}</strong>} />{' '}
          <Tx
            text="Unsere Anwendung liest es nur über {schnittstelle} und fragt es beim Freischaltcode ({fsc}) und bei der Zuordnung ({zuordnung})."
            schnittstelle={<code>PersonDirectory</code>}
            fsc={<code>ident-fsc</code>}
            zuordnung={<code>ident-kvnr</code>}
          />{' '}
          {t('Was Sie hier ändern, meldet es an die Konten - und über sie an Keycloak.')}
        </div>
        {error && <div className="card error-card"><h2>{t('Fehler')}</h2><p>{error}</p></div>}
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
      if (editing.id) await personenverzeichnisApi.aendern(editing.id, editing)
      else await personenverzeichnisApi.anlegen(editing)
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
          <h2>{t('Personen')}</h2>
          <button className="secondary small" onClick={() => setEditing({})}>
            + {t('Neue Person')}
          </button>
        </div>
        <div className="journey-log-table-scroll">
          <table className="journey-log-table">
            <thead>
              <tr><th>{t('Partnernummer')}</th><th>{t('Rolle')}</th><th>{t('Versicherungsnummer')}</th><th>{t('KVNR')}</th><th>{t('Name')}</th><th>{t('Geburtsdatum')}</th><th>{t('Adresse')}</th><th /></tr>
            </thead>
            <tbody>
              {personen.map((p) => (
                <tr key={p.id}>
                  <td><code>{p.id}</code></td>
                  <td>{p.versnr ? t('Versicherter') : t('Partner')}</td>
                  <td>{p.versnr ? <code>{p.versnr}</code> : '–'}</td>
                  <td>{p.kvnr ? <code>{p.kvnr}</code> : '–'}</td>
                  <td>{fullName(p)}</td>
                  <td>{p.geburtsdatum ?? '–'}</td>
                  <td>{address(p)}</td>
                  <td><button className="secondary small" onClick={() => setEditing({ ...p })}>{t('Bearbeiten')}</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {editing && (
        <form className="card" onSubmit={save}>
          <h2>{editing.id ? t('{name} bearbeiten', { name: fullName(editing) }) : t('Neue Person')}</h2>
          <div className="form-group">
            <label htmlFor="ext-versnr">{t('Versicherungsnummer (nur wenn bei uns versichert)')}</label>
            <input
              id="ext-versnr"
              inputMode="numeric"
              maxLength={8}
              value={editing.versnr ?? ''}
              placeholder="12345678"
              onChange={(e) => setEditing({ ...editing, versnr: e.target.value })}
            />
          </div>
          <div className="form-group">
            <label htmlFor="ext-kvnr">{t('KVNR (nur mit Versicherungsnummer)')}</label>
            <input
              id="ext-kvnr"
              value={editing.kvnr ?? ''}
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
            <button type="submit">{t('Speichern')}</button>
            <button type="button" className="secondary" onClick={() => setEditing(null)}>{t('Abbrechen')}</button>
          </div>
        </form>
      )}
    </>
  )
}

function FreischaltcodesTab({ personen, onError }: { personen: RegisterPerson[]; onError: (m: string | null) => void }) {
  const [personId, setPersonId] = useState<string | null>(null)
  const [codes, setCodes] = useState<Freischaltcode[]>([])
  const [gueltigBis, setGueltigBis] = useState(inOneYear)
  const [issued, setIssued] = useState<Brief | null>(null)
  const selected = personId ?? personen[0]?.id ?? null

  const reload = useCallback(() => {
    if (selected == null) return
    personenverzeichnisApi.freischaltcodes(selected).then(setCodes).catch((e: Error) => onError(e.message))
  }, [selected, onError])
  useEffect(reload, [reload])

  async function ausstellen() {
    if (selected == null) return
    try {
      setIssued(await personenverzeichnisApi.ausstellen(selected, new Date(`${gueltigBis}T23:59:59`).toISOString()))
      reload()
    } catch (err) {
      onError((err as Error).message)
    }
  }

  async function widerrufen(id: number) {
    try {
      await personenverzeichnisApi.widerrufen(id)
      reload()
    } catch (err) {
      onError((err as Error).message)
    }
  }

  return (
    <div className="card">
      <h2>{t('Freischaltcodes')}</h2>
      <div className="form-group">
        <label htmlFor="ext-person">{t('Person')}</label>
        <select id="ext-person" value={selected ?? ''} onChange={(e) => { setPersonId(e.target.value); setIssued(null) }}>
          {personen.map((p) => <option key={p.id} value={p.id}>{p.kvnr ? `${fullName(p)} (${p.kvnr})` : fullName(p)}</option>)}
        </select>
      </div>

      <div className="journey-log-table-scroll">
        <table className="journey-log-table">
          <thead><tr><th>#</th><th>{t('Status')}</th><th>{t('Gültig bis')}</th><th /></tr></thead>
          <tbody>
            {codes.length === 0 && <tr><td colSpan={4}>{t('Keine Freischaltcodes.')}</td></tr>}
            {codes.map((c) => (
              <tr key={c.id}>
                <td>{c.id}</td>
                <td>{c.revokedAt ? t('widerrufen {datum}', { datum: formatDate(c.revokedAt) }) : c.valid ? `✅ ${t('gültig')}` : t('abgelaufen')}</td>
                <td>{formatDate(c.expiresAt)}</td>
                <td>{!c.revokedAt && <button className="secondary small" onClick={() => widerrufen(c.id)}>{t('Widerrufen')}</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="hint">
        {t('Das Personenverzeichnis speichert nur den Hash.')} {t('Den Klartext trägt allein der Brief, siehe Briefkasten.')}
      </p>

      <h3>{t('Neuen Code ausstellen')}</h3>
      <div className="form-group">
        <label htmlFor="ext-gueltig">{t('Gültig bis')}</label>
        <input id="ext-gueltig" type="date" value={gueltigBis} onChange={(e) => setGueltigBis(e.target.value)} />
      </div>
      <div className="form-actions">
        <button onClick={ausstellen} disabled={selected == null}>
          {t('Ausstellen und Brief versenden')}
        </button>
      </div>
      {issued && <BriefCard brief={issued} person={personen.find((p) => p.id === issued.personId)} />}
    </div>
  )
}

function BriefkastenTab({ personen, onError }: { personen: RegisterPerson[]; onError: (m: string | null) => void }) {
  const [briefe, setBriefe] = useState<Brief[]>([])
  useEffect(() => {
    personenverzeichnisApi.briefe().then(setBriefe).catch((e: Error) => onError(e.message))
  }, [onError])

  return (
    <div className="card">
      <h2>{t('Briefkasten')}</h2>
      <p>
        {t('Alle verschickten Briefe, neueste zuerst.')} {t('In der echten Welt läge jeder davon im Briefkasten der Person.')}
      </p>
      {briefe.length === 0 && <p>{t('Noch keine Briefe.')}</p>}
      <div className="brief-list">
        {briefe.map((b) => <BriefCard key={b.id} brief={b} person={personen.find((p) => p.id === b.personId)} />)}
      </div>
    </div>
  )
}

function BriefCard({ brief, person }: { brief: Brief; person: RegisterPerson | undefined }) {
  return (
    <div className="brief">
      <div className="brief-meta">{t('An {name} · versandt {datum}', { name: fullName(person), datum: formatDate(brief.versandtAm) })}</div>
      <div>{t('Ihr Freischaltcode lautet:')}</div>
      <div className="brief-code">{brief.code}</div>
    </div>
  )
}
