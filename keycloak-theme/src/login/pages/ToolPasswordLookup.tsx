import type { PageContext } from '../KcContext'
import { DemoPersonPicker } from '../components/DemoPersonPicker'
import { Field } from '../components/Field'
import { ToolForm } from '../components/ToolForm'
import { t } from '../../texts'

/** `tool-password-lookup.ftl`: e-mail address and password - finds the account by its e-mail. */
export function ToolPasswordLookup({ kcContext }: { kcContext: PageContext<'tool-password-lookup.ftl'> }) {
  const { title, hint, demoEmail, demoPassword, demoPersonsJson } = kcContext
  return (
    <ToolForm kcContext={kcContext} title={title} hint={hint}>
      <DemoPersonPicker personsJson={demoPersonsJson} fields={{ email: 'email' }} />
      <Field id="email" type="email" label={t('E-Mail-Adresse')} hint={demoEmail && t('Demo-E-Mail: {wert}', { wert: demoEmail })} />
      <Field id="password" type="password" label={t('Passwort')} hint={demoPassword && t('Demo-Passwort: {wert}', { wert: demoPassword })} />
    </ToolForm>
  )
}
