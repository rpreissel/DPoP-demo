import type { ToolModule } from '../types'
import { confirmEmail, requestEmailLookup, submitEmailCode } from './api'
import { EmailCodeInputForm } from './EmailCodeInputForm'
import { EmailCodeLookupForm } from './EmailCodeLookupForm'
import { EmailEnrollForm } from './EmailEnrollForm'

const ICON = '✉️'
const LABEL = 'E-Mail'

export const confirmEmailTool: ToolModule = {
  toolId: 'confirm-email',
  meta: { icon: ICON, label: LABEL, hint: 'E-Mail-Adresse bestätigen' },
  render(ctx) {
    if (ctx.step === 'input') {
      return (
        <EmailEnrollForm
          onSubmit={(email) => confirmEmail(ctx, email)}
          error={ctx.stepData?.error}
          demoEmail={ctx.demo?.email}
          demoPersons={ctx.demo?.persons}
        />
      )
    }
    if (ctx.step === 'codeInput') {
      return <EmailCodeInputForm onSubmit={(code) => submitEmailCode(ctx, code)} error={ctx.stepData?.error} demoTan={ctx.demo?.tan} />
    }
    return null
  },
}

export const authEmail: ToolModule = {
  toolId: 'auth-email',
  meta: { icon: ICON, label: LABEL, hint: 'Code an die bestätigte E-Mail-Adresse' },
  render(ctx) {
    if (ctx.step === 'auth') {
      return <EmailCodeInputForm onSubmit={(code) => submitEmailCode(ctx, code)} error={ctx.stepData?.error} demoTan={ctx.demo?.tan} />
    }
    return null
  },
}

export const authEmailLookup: ToolModule = {
  toolId: 'auth-email-lookup',
  meta: { icon: ICON, label: LABEL, hint: 'E-Mail-Adresse + Bestätigungscode' },
  render(ctx) {
    if (ctx.step === 'auth') {
      return (
        <EmailCodeLookupForm
          onSubmit={(email) => requestEmailLookup(ctx, email)}
          error={ctx.stepData?.error}
          demoEmail={ctx.demo?.email}
          demoPersons={ctx.demo?.persons}
        />
      )
    }
    if (ctx.step === 'codeInput') {
      return <EmailCodeInputForm onSubmit={(code) => submitEmailCode(ctx, code)} error={ctx.stepData?.error} demoTan={ctx.demo?.tan} />
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
  meta: { icon: ICON, label: LABEL, hint: 'E-Mail als Anmeldeverfahren aktivieren' },
  render() {
    return null
  },
}

const emailModules: ToolModule[] = [confirmEmailTool, enrollEmailTool, authEmail, authEmailLookup]
export default emailModules
