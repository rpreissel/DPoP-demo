import type { ToolModule } from '../types'
import { submitEidCard, submitEidPin } from './api'
import { IdentEidCardForm } from './IdentEidCardForm'
import { IdentEidPinForm } from './IdentEidPinForm'
import { attemptError } from '../stepData'
import { t } from '../../texts'

export const identEid: ToolModule = {
  toolId: 'ident-eid',
  meta: { icon: '🆔', label: t('eID'), hint: t('Online-Ausweisfunktion (simuliert)') },
  explain: (step) =>
    step === 'pin'
      ? {
          does: t('Mit der PIN geben Sie den Ausweis frei. Danach liest der eID-Dienst Name und Geburtsdatum aus und meldet sie an das Tool.'),
          actor: t('Sie geben die PIN ein, der simulierte eID-Dienst liest den Ausweis.'),
        }
      : {
          does: t('Die Online-Ausweisfunktion weist Sie mit Ihrem Personalausweis aus - hier wählen Sie, welcher Ausweis aufgelegt wird.'),
          actor: t('Sie, mit dem simulierten eID-Dienst anstelle der AusweisApp.'),
        },
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
