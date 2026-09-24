import type { ToolModule } from '../types'
import { submitEidCard, submitEidPin } from './api'
import { IdentEidCardForm } from './IdentEidCardForm'
import { IdentEidPinForm } from './IdentEidPinForm'
import { attemptError } from '../stepData'
import { t } from '../../texts'

export const identEid: ToolModule = {
  toolId: 'ident-eid',
  meta: { icon: '🆔', label: t('eID'), hint: t('Online-Ausweisfunktion (simuliert)') },
  render(ctx) {
    if (ctx.step === 'card') {
      return (
        <IdentEidCardForm
          onSubmit={(fields) => submitEidCard(ctx, fields)}
          error={attemptError(ctx)}
          demoPersons={ctx.demo?.persons}
        />
      )
    }
    if (ctx.step === 'pin') return <IdentEidPinForm onSubmit={(pin) => submitEidPin(ctx, pin)} error={attemptError(ctx)} />
    return null
  },
}

const eidModules: ToolModule[] = [identEid]
export default eidModules
