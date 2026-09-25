import type { PageContext } from '../KcContext'
import { DemoPersonPicker } from '../components/DemoPersonPicker'
import { Field } from '../components/Field'
import { PartnerNumber } from '../components/PartnerNumber'
import { ToolForm } from '../components/ToolForm'
import { t } from '../../texts'

/** `tool-ident-fsc.ftl`: personal details first (personalienPage), then the activation code from the letter. */
export function ToolIdentFsc({ kcContext }: { kcContext: PageContext<'tool-ident-fsc.ftl'> }) {
  const { pageTitle: title, personalienPage, demoPersonsJson } = kcContext
  if (personalienPage) {
    return (
      <ToolForm
        kcContext={kcContext}
        title={title}
        hint={t('Damit Sie Ihren Freischaltcode gleich eingeben können, brauchen wir noch diese Daten:')}
        submitLabel={t('Weiter zur Freischaltcode-Eingabe')}
      >
        <DemoPersonPicker
          personsJson={demoPersonsJson}
          fields={{ vorname: 'vorname', name: 'name', geburtsdatum: 'geburtsdatum', kvnr: 'kvnr', partnernr: 'personId' }}
        />
        <Field id="vorname" label={t('Vorname')} autoComplete="given-name" required />
        <Field id="name" label={t('Nachname')} autoComplete="family-name" required />
        <Field id="geburtsdatum" type="date" label={t('Geburtsdatum')} autoComplete="bday" required />
        <Field id="kvnr" label={t('Versichertennummer')} />
        <PartnerNumber />
      </ToolForm>
    )
  }
  return (
    <ToolForm
      kcContext={kcContext}
      title={title}
      hint={t('Geben Sie den Freischaltcode ein, den wir Ihnen per Brief geschickt haben.')}
      submitLabel={t('Identifizieren')}
    >
      <DemoPersonPicker personsJson={demoPersonsJson} fields={{ fsc: 'fscCode' }} />
      <Field id="fsc" label={t('Freischaltcode')} autoComplete="one-time-code" required />
    </ToolForm>
  )
}
