import type { ToolModule } from '../types'
import { enrollSmsNumber, requestSmsLookup, submitSmsTan } from './api'
import { EmailLookupForm } from './EmailLookupForm'
import { SmsEnrollForm } from './SmsEnrollForm'
import { TanInputForm } from './TanInputForm'
import { attemptError } from '../stepData'

const ICON = '📱'
const LABEL = 'SMS'

export const enrollSms: ToolModule = {
  toolId: 'enroll-sms',
  meta: { icon: ICON, label: LABEL, hint: 'Code an eine Telefonnummer' },
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
  meta: { icon: ICON, label: LABEL, hint: 'Code an die hinterlegte Telefonnummer' },
  render(ctx) {
    if (ctx.step === 'auth') {
      return <TanInputForm onSubmit={(tan) => submitSmsTan(ctx, tan)} error={attemptError(ctx)} demoTan={ctx.demo?.tan} />
    }
    return null
  },
}

export const authSmsLookup: ToolModule = {
  toolId: 'auth-sms-lookup',
  meta: { icon: ICON, label: LABEL, hint: 'E-Mail-Adresse + SMS-Code' },
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
