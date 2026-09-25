import type { PageContext } from '../KcContext'
import { DemoPersonPicker } from '../components/DemoPersonPicker'
import { Field } from '../components/Field'
import { ToolForm } from '../components/ToolForm'
import { t } from '../../texts'

/** `tool-ident-eid.ftl`: the (simulated) ID card read (step card), then the eID PIN (step pin). */
export function ToolIdentEid({ kcContext }: { kcContext: PageContext<'tool-ident-eid.ftl'> }) {
  const { pageTitle: title, hint, step, demoPersonsJson } = kcContext
  return (
    <ToolForm kcContext={kcContext} title={title} hint={hint}>
      {step === 'card' && (
        <>
          <p className="orc-hint">{t('Demo-Modus: Das Auslesen der Karte wird simuliert.')}</p>
          <DemoPersonPicker
            personsJson={demoPersonsJson}
            fields={{ name: 'name', vorname: 'vorname', geburtsdatum: 'geburtsdatum', strasse: 'strasse', plz: 'plz', ort: 'ort' }}
          />
          <div className="orc-grid-2">
            <Field id="name" label={t('Nachname')} />
            <Field id="vorname" label={t('Vorname')} />
          </div>
          <Field id="geburtsdatum" type="date" label={t('Geburtsdatum')} />
          {/* The card has street and house number in one field (Street). */}
          <Field id="strasse" label={t('Straße und Hausnummer')} />
          <div className="orc-grid-2">
            <Field id="plz" label={t('PLZ')} />
            <Field id="ort" label={t('Ort')} />
          </div>
        </>
      )}
      {step === 'pin' && <Field id="pin" label={t('eID-PIN')} defaultValue="123456" />}
    </ToolForm>
  )
}
