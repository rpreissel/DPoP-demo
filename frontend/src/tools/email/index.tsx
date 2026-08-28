import type { ToolModule } from '../types'
import { enrollEmail, requestEmailLookup, submitEmailCode } from './api'
import { EmailCodeInputForm } from './EmailCodeInputForm'
import { EmailCodeLookupForm } from './EmailCodeLookupForm'
import { EmailEnrollForm } from './EmailEnrollForm'

const ICON = '✉️'
const LABEL = 'E-Mail'

export const enrollEmailTool: ToolModule = {
  toolId: 'enroll-email',
  meta: { icon: ICON, label: LABEL, hint: 'Bestätigungscode an eine E-Mail-Adresse' },
  render(ctx) {
    if (ctx.step === 'enroll') return <EmailEnrollForm onSubmit={(email) => enrollEmail(ctx, email)} error={ctx.stepData?.error} demoEmail={ctx.demo?.email} />
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
      return <EmailCodeLookupForm onSubmit={(email) => requestEmailLookup(ctx, email)} error={ctx.stepData?.error} demoEmail={ctx.demo?.email} />
    }
    if (ctx.step === 'codeInput') {
      return <EmailCodeInputForm onSubmit={(code) => submitEmailCode(ctx, code)} error={ctx.stepData?.error} demoTan={ctx.demo?.tan} />
    }
    return null
  },
}

const emailModules: ToolModule[] = [enrollEmailTool, authEmail, authEmailLookup]
export default emailModules
