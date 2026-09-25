import { t } from '../texts'
import { Tx } from '../Tx'
import type { DemoPerson } from '../types'
import { Demo } from './DemoArea'

interface DemoPersonPickerProps {
  /** Demo-only: every register person. Renders nothing when fewer than two are offered. */
  demoPersons?: DemoPerson[]
  /**
   * The Partnernummer of the person the form currently holds, '' when its fields match nobody.
   * When given, the picker follows it instead of starting over at the first person each time it
   * mounts - a form that remounts it (going back a page) would otherwise show one person above
   * another person's fields.
   */
  selectedPersonId?: string
  onSelect: (person: DemoPerson) => void
}

/** Shared by ident forms (id_fsc/id_eid) that prefill several fields (KVNR, name, address, ...) at once from one persona. */
export function DemoPersonPicker({ demoPersons, selectedPersonId, onSelect }: DemoPersonPickerProps) {
  if (!demoPersons || demoPersons.length < 2) return null
  const matched = selectedPersonId === undefined || demoPersons.some((p) => p.personId === selectedPersonId)
  const selection =
    selectedPersonId === undefined ? { defaultValue: demoPersons[0].personId } : { value: matched ? selectedPersonId : '' }
  // In the App channel this renders in the demo column next to the phone (DemoArea) - still part of
  // its form's tree, so picking a person fills the fields in the phone.
  return (
    <Demo>
      <div className="form-group demo-picker">
        <label htmlFor="demoPerson">
          <Tx text="{demo} Testperson übernehmen" demo={<span className="demo-picker__tag">{t('Demo')}</span>} />
        </label>
        <select
          id="demoPerson"
          {...selection}
          onChange={(e) => {
            const person = demoPersons.find((p) => p.personId === e.target.value)
            if (person) onSelect(person)
          }}
        >
          {!matched && (
            <option value="" disabled>
              — {t('eigene Eingabe')} —
            </option>
          )}
          {demoPersons.map((person) => (
            <option key={person.personId} value={person.personId}>
              {person.vorname} {person.name} ({person.kvnr ?? person.personId})
            </option>
          ))}
        </select>
      </div>
    </Demo>
  )
}
