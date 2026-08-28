import type { ToolModule } from '../types'
import { submitEidCard, submitEidLookup, submitEidPin } from './api'
import { IdentEidCardForm } from './IdentEidCardForm'
import { IdentEidForm } from './IdentEidForm'
import { IdentEidPinForm } from './IdentEidPinForm'

export const identEid: ToolModule = {
  toolId: 'ident-eid',
  meta: { icon: '🆔', label: 'eID', hint: 'Online-Ausweisfunktion (simuliert)' },
  render(ctx) {
    if (ctx.step === 'input') return <IdentEidForm onSubmit={(fields) => submitEidLookup(ctx, fields)} />
    if (ctx.step === 'card') return <IdentEidCardForm onSubmit={(fields) => submitEidCard(ctx, fields)} />
    if (ctx.step === 'pin') return <IdentEidPinForm onSubmit={(pin) => submitEidPin(ctx, pin)} error={ctx.stepData?.error} />
    return null
  },
}

const eidModules: ToolModule[] = [identEid]
export default eidModules
