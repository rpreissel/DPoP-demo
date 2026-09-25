import type { ToolModule } from '../types'
import { enrollPassword, submitPassword, submitPasswordLookup } from './api'
import { EmailPasswordLookupForm } from './EmailPasswordLookupForm'
import { PasswordEnrollForm } from './PasswordEnrollForm'
import { PasswordLoginForm } from './PasswordLoginForm'
import { attemptError } from '../stepData'
import { t } from '../../texts'

const ICON = '🔑'
const LABEL = t('Passwort')

export const enrollPasswordTool: ToolModule = {
  toolId: 'enroll-password',
  meta: { icon: ICON, label: LABEL, hint: t('Eigenes Passwort festlegen') },
  explain: () => ({
    does: t('Sie legen ein Passwort fest. Das Tool speichert davon nur einen Hash, nie das Passwort selbst.'),
    actor: t('Sie in der App, danach das Tool enroll-password im Orchestrator.'),
  }),
  render(ctx) {
    if (ctx.step === 'enroll') {
      return <PasswordEnrollForm onSubmit={(fields) => enrollPassword(ctx, fields)} error={attemptError(ctx)} demoPassword={ctx.demo?.password} />
    }
    return null
  },
}

export const authPassword: ToolModule = {
  toolId: 'auth-password',
  meta: { icon: ICON, label: LABEL, hint: t('Mit dem hinterlegten Passwort') },
  explain: () => ({
    does: t('Das Passwort wird mit dem gespeicherten Hash verglichen.'),
    actor: t('Sie geben das Passwort ein, das Tool auth-password prüft es.'),
  }),
  render(ctx) {
    if (ctx.step === 'auth') {
      return <PasswordLoginForm onSubmit={(fields) => submitPassword(ctx, fields)} error={attemptError(ctx)} demoPassword={ctx.demo?.password} />
    }
    return null
  },
}

export const authPasswordLookup: ToolModule = {
  toolId: 'auth-password-lookup',
  meta: { icon: ICON, label: LABEL, hint: t('E-Mail-Adresse + Passwort') },
  explain: () => ({
    does: t('Die E-Mail-Adresse sucht das Konto, das Passwort beweist, dass es Ihres ist - beides in einem Aufruf. Die Antwort verrät nicht, ob es die Adresse gibt.'),
    actor: t('Sie in der App, danach das Tool auth-password-lookup im Orchestrator.'),
  }),
  render(ctx) {
    if (ctx.step === 'auth') {
      return (
        <EmailPasswordLookupForm
          onSubmit={(fields) => submitPasswordLookup(ctx, fields)}
          error={attemptError(ctx)}
          demoPassword={ctx.demo?.password}
          demoEmail={ctx.demo?.email}
          demoPersons={ctx.demo?.persons}
        />
      )
    }
    return null
  },
}

const passwordModules: ToolModule[] = [enrollPasswordTool, authPassword, authPasswordLookup]
export default passwordModules
