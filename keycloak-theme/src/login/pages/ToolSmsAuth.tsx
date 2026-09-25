import type { PageContext } from '../KcContext'
import { Field } from '../components/Field'
import { ToolForm } from '../components/ToolForm'
import { t } from '../../texts'

/** `tool-sms-auth.ftl`: the code sent by SMS. */
export function ToolSmsAuth({ kcContext }: { kcContext: PageContext<'tool-sms-auth.ftl'> }) {
  const { pageTitle: title, hint, demoTan } = kcContext
  return (
    <ToolForm kcContext={kcContext} title={title} hint={hint}>
      <Field id="tan" label={t('SMS-Code')} autoComplete="one-time-code" hint={demoTan && t('Demo-Code: {wert}', { wert: demoTan })} />
    </ToolForm>
  )
}
