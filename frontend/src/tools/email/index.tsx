import type { ToolModule } from '../types'
import { confirmEmail, requestEmailLookup, submitEmailCode } from './api'
import { EmailCodeInputForm } from './EmailCodeInputForm'
import { EmailCodeLookupForm } from './EmailCodeLookupForm'
import { EmailEnrollForm } from './EmailEnrollForm'
import { attemptError } from '../stepData'
import { t } from '../../texts'

const ICON = '✉️'
const LABEL = t('E-Mail')

export const confirmEmailTool: ToolModule = {
  toolId: 'confirm-email',
  meta: { icon: ICON, label: LABEL, hint: t('E-Mail-Adresse bestätigen') },
  explain: (step) =>
    step === 'codeInput'
      ? {
          does: t('Der Code aus der E-Mail beweist, dass die Adresse Ihnen gehört. Danach gilt sie als bestätigt.'),
          actor: t('Sie geben den Code ein, das Tool confirm-email prüft ihn.'),
        }
      : {
          does: t('Sie nennen eine E-Mail-Adresse, das Tool schickt einen Code dorthin (hier nur simuliert).'),
          actor: t('Sie in der App, danach das Tool confirm-email im Orchestrator.'),
        },
  render(ctx) {
    if (ctx.step === 'input') {
      return (
        <EmailEnrollForm
          onSubmit={(email) => confirmEmail(ctx, email)}
          error={attemptError(ctx)}
          demoEmail={ctx.demo?.email}
          demoPersons={ctx.demo?.persons}
        />
      )
    }
    if (ctx.step === 'codeInput') {
      return (
        <EmailCodeInputForm
          onSubmit={(code) => submitEmailCode(ctx, code)}
          // Nur hier: ConfirmEmailFlow nimmt eine neue Adresse in JEDEM Zustand an und faengt
          // damit von vorn an (auth-email hat keine Adresse zum Wechseln, auth-email-lookup
          // trennt Adress- und Code-Schritt im Backend).
          onChangeAddress={(email) => confirmEmail(ctx, email)}
          error={attemptError(ctx)}
          demoTan={ctx.demo?.tan}
        />
      )
    }
    return null
  },
}

export const authEmail: ToolModule = {
  toolId: 'auth-email',
  meta: { icon: ICON, label: LABEL, hint: t('Code an die bestätigte E-Mail-Adresse') },
  explain: () => ({
    does: t('Ein Code geht an die bestätigte E-Mail-Adresse des Kontos. Wer ihn eingibt, hat Zugriff auf das Postfach.'),
    actor: t('Sie geben den Code ein, das Tool auth-email prüft ihn.'),
  }),
  render(ctx) {
    if (ctx.step === 'auth') {
      return <EmailCodeInputForm onSubmit={(code) => submitEmailCode(ctx, code)} error={attemptError(ctx)} demoTan={ctx.demo?.tan} />
    }
    return null
  },
}

export const authEmailLookup: ToolModule = {
  toolId: 'auth-email-lookup',
  meta: { icon: ICON, label: LABEL, hint: t('E-Mail-Adresse + Bestätigungscode') },
  explain: (step) =>
    step === 'codeInput'
      ? {
          does: t('Der Code beweist den Zugriff auf das Postfach - und damit, dass das gefundene Konto Ihres ist.'),
          actor: t('Sie geben den Code ein, das Tool auth-email-lookup prüft ihn.'),
        }
      : {
          does: t('Die E-Mail-Adresse sucht das Konto. Die Antwort sieht immer gleich aus, ob es die Adresse gibt oder nicht - so lässt sich nichts ausforschen.'),
          actor: t('Sie in der App, danach das Tool auth-email-lookup im Orchestrator.'),
        },
  render(ctx) {
    if (ctx.step === 'auth') {
      return (
        <EmailCodeLookupForm
          onSubmit={(email) => requestEmailLookup(ctx, email)}
          error={attemptError(ctx)}
          demoEmail={ctx.demo?.email}
          demoPersons={ctx.demo?.persons}
        />
      )
    }
    if (ctx.step === 'codeInput') {
      return <EmailCodeInputForm onSubmit={(code) => submitEmailCode(ctx, code)} error={attemptError(ctx)} demoTan={ctx.demo?.tan} />
    }
    return null
  },
}

/**
 * Turning the confirmed address into a login method is a one shot: the backend completes it on
 * activation, so there is no step to render - confirming already proved control over the address.
 */
export const enrollEmailTool: ToolModule = {
  toolId: 'enroll-email',
  meta: { icon: ICON, label: LABEL, hint: t('Ihre bestätigte E-Mail-Adresse, ohne Code') },
  explain: () => ({
    does: t('Die schon bestätigte E-Mail-Adresse wird zum Anmeldeverfahren - ohne neuen Code, denn der Nachweis liegt schon vor.'),
    actor: t('Das Tool enroll-email im Orchestrator, ohne Eingabe.'),
  }),
  render() {
    return null
  },
}

const emailModules: ToolModule[] = [confirmEmailTool, enrollEmailTool, authEmail, authEmailLookup]
export default emailModules
