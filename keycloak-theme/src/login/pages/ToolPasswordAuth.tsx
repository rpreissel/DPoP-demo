import type { PageContext } from '../KcContext'
import { Field } from '../components/Field'
import { ToolForm } from '../components/ToolForm'
import { t } from '../../texts'

/** `tool-password-auth.ftl`: the stored password. */
export function ToolPasswordAuth({ kcContext }: { kcContext: PageContext<'tool-password-auth.ftl'> }) {
  const { title, hint, demoPassword } = kcContext
  return (
    <ToolForm kcContext={kcContext} title={title} hint={hint}>
      <Field id="password" type="password" label={t('Passwort')} hint={demoPassword && t('Demo-Passwort: {wert}', { wert: demoPassword })} />
    </ToolForm>
  )
}
