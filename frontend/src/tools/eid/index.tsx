import type { ToolModule } from '../types'
import { submitEidCard, submitEidPin } from './api'
import { IdentEidCardForm } from './IdentEidCardForm'
import { IdentEidPinForm } from './IdentEidPinForm'

export const identEid: ToolModule = {
  toolId: 'ident-eid',
  meta: { icon: '🆔', label: 'eID', hint: 'Online-Ausweisfunktion (simuliert)' },
  render(ctx) {
    if (ctx.step === 'card') {
      return (
        <IdentEidCardForm
          onSubmit={(fields) => submitEidCard(ctx, fields)}
          error={ctx.stepData?.error}
          demoPersons={ctx.demo?.persons}
        />
      )
    }
    if (ctx.step === 'pin') return <IdentEidPinForm onSubmit={(pin) => submitEidPin(ctx, pin)} error={ctx.stepData?.error} />
    return null
  },
}

const eidModules: ToolModule[] = [identEid]
export default eidModules
