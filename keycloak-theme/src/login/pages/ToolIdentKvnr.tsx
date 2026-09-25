import type { PageContext } from '../KcContext'
import { DemoPersonPicker } from '../components/DemoPersonPicker'
import { Field } from '../components/Field'
import { PartnerNumber } from '../components/PartnerNumber'
import { ToolForm } from '../components/ToolForm'
import { t } from '../../texts'

/** `tool-ident-kvnr.ftl`: identity already proven - the insurance number must belong to that person. */
export function ToolIdentKvnr({ kcContext }: { kcContext: PageContext<'tool-ident-kvnr.ftl'> }) {
  const { title, hint, demoPersonsJson } = kcContext
  return (
    <ToolForm kcContext={kcContext} title={title} hint={hint}>
      <p className="orc-hint">
        {t('Ihre Identität ist bereits nachgewiesen. Die Versichertennummer - oder ohne sie die Partnernummer - muss zu dieser Person gehören.')}
      </p>
      <DemoPersonPicker personsJson={demoPersonsJson} fields={{ kvnr: 'kvnr', partnernr: 'personId' }} />
      <Field id="kvnr" label={t('Versichertennummer')} />
      <PartnerNumber />
    </ToolForm>
  )
}
