import type { ToolModule } from '../types'
import { submitFsc } from './api'
import { IdentFscForm } from './IdentFscForm'
import { attemptError } from '../stepData'

export const identFsc: ToolModule = {
  toolId: 'ident-fsc',
  meta: { icon: '🪪', label: 'Freischaltcode', hint: 'Versichertennummer, Name und Freischaltcode' },
  render(ctx) {
    if (ctx.step === 'input') {
      return (
        <IdentFscForm
          onSubmit={(fields) => submitFsc(ctx, fields)}
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
