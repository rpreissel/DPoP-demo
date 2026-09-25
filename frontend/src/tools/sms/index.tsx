import type { ToolModule } from '../types'
import { enrollSmsNumber, requestSmsLookup, submitSmsTan } from './api'
import { EmailLookupForm } from './EmailLookupForm'
import { SmsEnrollForm } from './SmsEnrollForm'
import { TanInputForm } from './TanInputForm'
import { attemptError } from '../stepData'
import { t } from '../../texts'

const ICON = '📱'
const LABEL = t('SMS')

export const enrollSms: ToolModule = {
  toolId: 'enroll-sms',
  meta: { icon: ICON, label: LABEL, hint: t('Code an eine Telefonnummer') },
  explain: (step) =>
    step === 'tanInput'
      ? {
          does: t('Der Code aus der SMS beweist, dass die Nummer Ihnen gehört. Danach ist sie als Anmeldeverfahren eingerichtet.'),
          actor: t('Sie geben den Code ein, das Tool enroll-sms prüft ihn.'),
        }
      : {
          does: t('Sie nennen eine Handynummer, das Tool schickt einen Code dorthin (hier nur simuliert).'),
          actor: t('Sie in der App, danach das Tool enroll-sms im Orchestrator.'),
        },
  render(ctx) {
    if (ctx.step === 'enroll') return <SmsEnrollForm onSubmit={(phoneNumber) => enrollSmsNumber(ctx, phoneNumber)} error={attemptError(ctx)} />
    if (ctx.step === 'tanInput') {
      return <TanInputForm onSubmit={(tan) => submitSmsTan(ctx, tan)} error={attemptError(ctx)} demoTan={ctx.demo?.tan} />
    }
    return null
  },
}

export const authSms: ToolModule = {
  toolId: 'auth-sms',
  meta: { icon: ICON, label: LABEL, hint: t('Code an die hinterlegte Telefonnummer') },
  explain: () => ({
    does: t('Ein Code geht per SMS an die hinterlegte Nummer. Wer ihn eingibt, hat das Handy.'),
    actor: t('Sie geben den Code ein, das Tool auth-sms prüft ihn.'),
  }),
  render(ctx) {
    if (ctx.step === 'auth') {
      return <TanInputForm onSubmit={(tan) => submitSmsTan(ctx, tan)} error={attemptError(ctx)} demoTan={ctx.demo?.tan} />
    }
    return null
  },
}

export const authSmsLookup: ToolModule = {
  toolId: 'auth-sms-lookup',
  meta: { icon: ICON, label: LABEL, hint: t('E-Mail-Adresse + SMS-Code') },
  explain: (step) =>
    step === 'tanInput'
      ? {
          does: t('Der SMS-Code beweist, dass das gefundene Konto Ihres ist.'),
          actor: t('Sie geben den Code ein, das Tool auth-sms-lookup prüft ihn.'),
        }
      : {
          does: t('Die E-Mail-Adresse sucht das Konto, ein Code geht an dessen Handynummer. Die Antwort verrät nicht, ob es die Adresse gibt.'),
          actor: t('Sie in der App, danach das Tool auth-sms-lookup im Orchestrator.'),
        },
  render(ctx) {
    if (ctx.step === 'auth') {
      return (
        <EmailLookupForm
          onSubmit={(email) => requestSmsLookup(ctx, email)}
          error={attemptError(ctx)}
          demoEmail={ctx.demo?.email}
          demoPersons={ctx.demo?.persons}
        />
      )
    }
    if (ctx.step === 'tanInput') {
      return <TanInputForm onSubmit={(tan) => submitSmsTan(ctx, tan)} error={attemptError(ctx)} demoTan={ctx.demo?.tan} />
    }
    return null
  },
}

const smsModules: ToolModule[] = [enrollSms, authSms, authSmsLookup]
export default smsModules
