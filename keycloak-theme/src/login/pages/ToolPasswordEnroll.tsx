import type { PageContext } from '../KcContext'
import { Field } from '../components/Field'
import { ToolForm } from '../components/ToolForm'
import { t } from '../../texts'

/** `tool-password-enroll.ftl`: choosing a new password. */
export function ToolPasswordEnroll({ kcContext }: { kcContext: PageContext<'tool-password-enroll.ftl'> }) {
  const { pageTitle: title, hint, demoPassword } = kcContext
  return (
    <ToolForm kcContext={kcContext} title={title} hint={hint}>
      <Field id="password" type="password" label={t('Neues Passwort')} hint={demoPassword && t('Demo-Passwort: {wert}', { wert: demoPassword })} />
    </ToolForm>
  )
}
