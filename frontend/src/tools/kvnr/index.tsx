import { submitViaPatch } from '../shared/defaultApi'
import type { ToolModule } from '../types'
import { IdentKvnrForm } from './IdentKvnrForm'

/** Kein Verfahrenswechsel, sondern ein freiwilliger Schritt - die Beschriftung des Auswegs, hier und in der Rahmen-UI. */
const SKIP_LABEL = 'Jetzt nicht'

export const identKvnr: ToolModule = {
  toolId: 'ident-kvnr',
  meta: {
    icon: '🗂️',
    label: 'Versichertennummer',
    hint: 'Konto der Registerperson zuordnen',
    // Wer abbricht, registriert weiter - das Konto bleibt Interessent (ADR-10/ADR-18). Gesetzt
    // heisst zugleich: Dieses Tool zeichnet den Ausweg selbst, die Rahmen-UI laesst ihn weg.
    skipLabel: SKIP_LABEL,
  },
  render(ctx) {
    if (ctx.step === 'input') {
      return (
        <IdentKvnrForm
          onSubmit={(kvnr) => submitViaPatch(ctx, { kvnr })}
          onSkip={ctx.onSkip}
          skipLabel={SKIP_LABEL}
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
