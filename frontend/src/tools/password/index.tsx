import type { ToolModule } from '../types'
import { enrollPassword, submitPassword, submitPasswordLookup } from './api'
import { EmailPasswordLookupForm } from './EmailPasswordLookupForm'
import { PasswordEnrollForm } from './PasswordEnrollForm'
import { PasswordLoginForm } from './PasswordLoginForm'

const ICON = '🔑'
const LABEL = 'Passwort'

export const enrollPasswordTool: ToolModule = {
  toolId: 'enroll-password',
  meta: { icon: ICON, label: LABEL, hint: 'Eigenes Passwort festlegen' },
  render(ctx) {
    if (ctx.step === 'enroll') {
      return <PasswordEnrollForm onSubmit={(fields) => enrollPassword(ctx, fields)} error={ctx.stepData?.error} demoPassword={ctx.demo?.password} />
    }
    return null
  },
}

export const authPassword: ToolModule = {
  toolId: 'auth-password',
  meta: { icon: ICON, label: LABEL, hint: 'Mit dem hinterlegten Passwort' },
  render(ctx) {
    if (ctx.step === 'auth') {
      return <PasswordLoginForm onSubmit={(fields) => submitPassword(ctx, fields)} error={ctx.stepData?.error} demoPassword={ctx.demo?.password} />
    }
    return null
  },
}

export const authPasswordLookup: ToolModule = {
  toolId: 'auth-password-lookup',
  meta: { icon: ICON, label: LABEL, hint: 'E-Mail-Adresse + Passwort' },
  render(ctx) {
    if (ctx.step === 'auth') {
      return (
        <EmailPasswordLookupForm
          onSubmit={(fields) => submitPasswordLookup(ctx, fields)}
          error={ctx.stepData?.error}
          demoPassword={ctx.demo?.password}
          demoEmail={ctx.demo?.email}
        />
      )
    }
    return null
  },
}

const passwordModules: ToolModule[] = [enrollPasswordTool, authPassword, authPasswordLookup]
export default passwordModules
