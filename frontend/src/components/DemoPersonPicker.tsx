import type { DemoPerson } from '../types'

interface DemoPersonPickerProps {
  /** Demo-only: every register person. Renders nothing when fewer than two are offered. */
  demoPersons?: DemoPerson[]
  /**
   * The KVNR the form currently holds. When given, the picker follows it instead of starting over
   * at the first person each time it mounts - a form that remounts it (going back a page) would
   * otherwise show one person above another person's fields.
   */
  selectedKvnr?: string
  onSelect: (person: DemoPerson) => void
}

/** Shared by ident forms (id_fsc/id_eid) that prefill several fields (KVNR, name, address, ...) at once from one persona. */
export function DemoPersonPicker({ demoPersons, selectedKvnr, onSelect }: DemoPersonPickerProps) {
  if (!demoPersons || demoPersons.length < 2) return null
  const matched = selectedKvnr === undefined || demoPersons.some((p) => p.kvnr === selectedKvnr)
  const selection =
    selectedKvnr === undefined ? { defaultValue: demoPersons[0].kvnr } : { value: matched ? selectedKvnr : '' }
  return (
    <div className="form-group demo-picker">
      <label htmlFor="demoPerson">
        <span className="demo-picker__tag">Demo</span> Testperson übernehmen
      </label>
      <select
        id="demoPerson"
        {...selection}
        onChange={(e) => {
          const person = demoPersons.find((p) => p.kvnr === e.target.value)
          if (person) onSelect(person)
        }}
      >
        {!matched && (
          <option value="" disabled>
            — eigene Eingabe —
          </option>
        )}
        {demoPersons.map((person) => (
          <option key={person.kvnr} value={person.kvnr}>
            {person.vorname} {person.name} ({person.kvnr})
          </option>
        ))}
      </select>
    </div>
  )
}
