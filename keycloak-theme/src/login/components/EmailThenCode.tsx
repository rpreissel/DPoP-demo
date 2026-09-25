import type { PageContext } from '../KcContext'
import { DemoPersonPicker } from './DemoPersonPicker'
import { Field } from './Field'
import { ToolForm } from './ToolForm'
import { t } from '../../texts'

/**
 * `tool-email-enroll.ftl` and `tool-email-lookup.ftl` look the same: first the e-mail address,
 * then (step codeInput) the code sent to it.
 */
export function EmailThenCode({ kcContext }: { kcContext: PageContext<'tool-email-enroll.ftl' | 'tool-email-lookup.ftl'> }) {
  const { title, hint, step, demoEmail, demoTan, demoPersonsJson } = kcContext
  return (
    <ToolForm kcContext={kcContext} title={title} hint={hint}>
      {step === 'codeInput' ? (
        <Field id="code" label={t('Bestätigungscode')} hint={demoTan && t('Demo-Code: {wert}', { wert: demoTan })} />
      ) : (
        <>
          <DemoPersonPicker personsJson={demoPersonsJson} fields={{ email: 'email' }} />
          <Field id="email" type="email" label={t('E-Mail-Adresse')} hint={demoEmail && t('Demo-E-Mail: {wert}', { wert: demoEmail })} />
        </>
      )}
    </ToolForm>
  )
}
