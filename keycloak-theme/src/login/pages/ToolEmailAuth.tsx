import type { PageContext } from '../KcContext'
import { Field } from '../components/Field'
import { ToolForm } from '../components/ToolForm'
import { t } from '../../texts'

/** `tool-email-auth.ftl`: the code sent to the confirmed e-mail address. */
export function ToolEmailAuth({ kcContext }: { kcContext: PageContext<'tool-email-auth.ftl'> }) {
  const { title, hint, demoTan } = kcContext
  return (
    <ToolForm kcContext={kcContext} title={title} hint={hint}>
      <Field id="code" label={t('Bestätigungscode')} hint={demoTan && t('Demo-Code: {wert}', { wert: demoTan })} />
    </ToolForm>
  )
}
