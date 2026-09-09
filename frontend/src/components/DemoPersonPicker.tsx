import type { DemoPerson } from '../types'

interface DemoPersonPickerProps {
  /** Demo-only: all seeded personas. Renders nothing when fewer than two are offered. */
  demoPersons?: DemoPerson[]
  onSelect: (person: DemoPerson) => void
}

/** Shared by ident forms (id_fsc/id_eid) that prefill several fields (KVNR, name, address, ...) at once from one persona. */
export function DemoPersonPicker({ demoPersons, onSelect }: DemoPersonPickerProps) {
  if (!demoPersons || demoPersons.length < 2) return null
  return (
    <div className="form-group">
      <label htmlFor="demoPerson">Demo-Person</label>
      <select
        id="demoPerson"
        defaultValue={demoPersons[0].kvnr}
        onChange={(e) => {
          const person = demoPersons.find((p) => p.kvnr === e.target.value)
          if (person) onSelect(person)
        }}
      >
        {demoPersons.map((person) => (
          <option key={person.kvnr} value={person.kvnr}>
            {person.vorname} {person.name} ({person.kvnr})
          </option>
        ))}
      </select>
    </div>
  )
}
