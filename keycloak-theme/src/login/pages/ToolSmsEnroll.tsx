import type { PageContext } from '../KcContext'
import { Field } from '../components/Field'
import { ToolForm } from '../components/ToolForm'
import { t } from '../../texts'

/** `tool-sms-enroll.ftl`: the phone number, then (step tanInput) the code sent to it. */
export function ToolSmsEnroll({ kcContext }: { kcContext: PageContext<'tool-sms-enroll.ftl'> }) {
  const { title, hint, step, demoTan } = kcContext
  return (
    <ToolForm kcContext={kcContext} title={title} hint={hint}>
      {step === 'tanInput' ? (
        <Field id="tan" label={t('SMS-Code')} autoComplete="one-time-code" hint={demoTan && t('Demo-Code: {wert}', { wert: demoTan })} />
      ) : (
        <Field id="phoneNumber" type="tel" label={t('Telefonnummer')} autoComplete="tel" />
      )}
    </ToolForm>
  )
}
