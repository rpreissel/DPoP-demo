import { useEffect, useState } from 'react'
import { t } from '../../texts'

type Person = Record<string, string | null | undefined>

/**
 * The demo's test persons (demo-person-picker.ftl, the App channel's DemoPersonPicker.tsx): picking
 * one fills the given inputs - `fields` maps input id to the person's field - and the first person
 * is taken on load. The inputs stay free text; without demo values (ADR-28) there is no list and
 * the form starts empty.
 */
export function DemoPersonPicker({ personsJson, fields }: { personsJson?: string | null; fields: Record<string, string> }) {
  const [persons] = useState<Person[]>(() => {
    if (!personsJson || personsJson === 'null') return []
    try {
      return JSON.parse(personsJson) as Person[]
    } catch {
      return []
    }
  })
  const [chosen, setChosen] = useState(persons.length > 0 ? '0' : '')

  useEffect(() => {
    if (persons.length > 0) apply(persons[0], fields)
    // Only once, on load - like the template's DOMContentLoaded.
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  if (persons.length === 0) return null
  return (
    <div className="orc-field orc-demo-picker">
      <label htmlFor="demoPerson">
        <span className="orc-demo-tag">{t('Demo')}</span> {t('Testperson übernehmen')}
      </label>
      <select
        id="demoPerson"
        value={chosen}
        onChange={(e) => {
          setChosen(e.target.value)
          if (e.target.value !== '') apply(persons[Number(e.target.value)], fields)
        }}
      >
        <option value="">{t('— manuell eingeben —')}</option>
        {persons.map((p, i) => (
          <option key={i} value={String(i)}>
            {p.vorname} {p.name} ({p.email || p.kvnr || p.personId})
          </option>
        ))}
      </select>
    </div>
  )
}

function apply(person: Person, fields: Record<string, string>) {
  for (const [inputId, key] of Object.entries(fields)) {
    const input = document.getElementById(inputId) as HTMLInputElement | null
    if (input && person[key] !== undefined) input.value = person[key] ?? ''
  }
}
