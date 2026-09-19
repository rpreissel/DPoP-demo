import { submitViaPatch } from '../shared/defaultApi'
import type { ToolModule } from '../types'
import { IdentKvnrForm } from './IdentKvnrForm'

export const identKvnr: ToolModule = {
  toolId: 'ident-kvnr',
  meta: {
    icon: '🗂️',
    label: 'Versichertennummer',
    hint: 'Konto der Registerperson zuordnen',
    // Kein Verfahrenswechsel, sondern ein optionaler Schritt: Wer abbricht, registriert
    // weiter - das Konto bleibt Interessent (ADR-10/ADR-18).
    skipLabel: 'Jetzt nicht',
  },
  render(ctx) {
    if (ctx.step === 'input') {
      return (
        <IdentKvnrForm
          onSubmit={(kvnr) => submitViaPatch(ctx, { kvnr })}
          error={ctx.stepData?.error}
          demoPersons={ctx.demo?.persons}
        />
      )
    }
    return null
  },
}

const kvnrModules: ToolModule[] = [identKvnr]
export default kvnrModules
