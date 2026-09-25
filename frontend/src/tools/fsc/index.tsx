import type { ToolModule } from '../types'
import { submitFsc } from './api'
import { IdentFscForm } from './IdentFscForm'
import { attemptError } from '../stepData'
import { stepDataOf } from '../../types'
import { t } from '../../texts'

export const identFsc: ToolModule = {
  toolId: 'ident-fsc',
  meta: { icon: '🪪', label: t('Freischaltcode'), hint: t('Persönliche Daten und Freischaltcode') },
  explain: () => ({
    does: t('Ihre Angaben werden im Personenverzeichnis gesucht. Der Freischaltcode aus dem Brief bestätigt, dass Sie diese Person sind.'),
    actor: t('Sie in der App, danach das Tool ident-fsc mit einer Anfrage an das Personenverzeichnis (simuliert).'),
  }),
  render(ctx) {
    if (ctx.step === 'input') {
      return (
        <IdentFscForm
          onSubmit={(fields) => submitFsc(ctx, fields)}
          missingFields={stepDataOf(ctx.stepData, 'missing-fields')?.missingFields}
          error={attemptError(ctx)}
          demoPersons={ctx.demo?.persons}
        />
      )
    }
    return null
  },
}

const fscModules: ToolModule[] = [identFsc]
export default fscModules
