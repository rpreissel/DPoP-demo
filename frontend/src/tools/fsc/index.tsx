import type { ToolModule } from '../types'
import { submitFsc } from './api'
import { IdentFscForm } from './IdentFscForm'
import { attemptError } from '../stepData'
import { stepDataOf } from '../../types'

export const identFsc: ToolModule = {
  toolId: 'ident-fsc',
  meta: { icon: '🪪', label: 'Freischaltcode', hint: 'Persönliche Daten und Freischaltcode' },
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
