import { submitViaPatch } from '../shared/defaultApi'
import type { ToolModule } from '../types'
import { IdentKvnrForm } from './IdentKvnrForm'
import { attemptError } from '../stepData'
import { t } from '../../texts'

/** Kein Verfahrenswechsel, sondern ein freiwilliger Schritt - die Beschriftung des Auswegs, hier und in der Rahmen-UI. */
const SKIP_LABEL = t('Jetzt nicht')

export const identKvnr: ToolModule = {
  toolId: 'ident-kvnr',
  meta: {
    icon: '🗂️',
    label: t('Versichertennummer'),
    hint: t('Konto der eigenen Person im Personenverzeichnis zuordnen'),
    // Wer abbricht, registriert weiter - das Konto bleibt Interessent (ADR-10/ADR-18). Gesetzt
    // heisst zugleich: Dieses Tool zeichnet den Ausweg selbst, die Rahmen-UI laesst ihn weg.
    skipLabel: SKIP_LABEL,
  },
  render(ctx) {
    if (ctx.step === 'input') {
      return (
        <IdentKvnrForm
          onSubmit={(identifier) => submitViaPatch(ctx, identifier)}
          onSkip={ctx.onSkip}
          skipLabel={SKIP_LABEL}
          error={attemptError(ctx)}
          demoPersons={ctx.demo?.persons}
        />
      )
    }
    return null
  },
}

const kvnrModules: ToolModule[] = [identKvnr]
export default kvnrModules
