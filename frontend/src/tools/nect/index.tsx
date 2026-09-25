import type { ToolModule } from '../types'
import { NectRedirectStep } from './NectRedirectStep'
import { t } from '../../texts'

export const identNect: ToolModule = {
  toolId: 'ident-nect',
  meta: { icon: '📲', label: t('Nect'), hint: t('Ausweis, Reisepass oder EUDI-Wallet bei Nect (simuliert)') },
  explain: () => ({
    does: t('Die App leitet zu Nect weiter. Dort weisen Sie sich mit Ausweis, Reisepass oder EUDI-Wallet aus, das Ergebnis geht zurück an das Tool.'),
    actor: t('Nect, ein Fremdsystem (simuliert). Die App wartet auf die Rückkehr.'),
  }),
  render(ctx) {
    if (ctx.step === 'redirect') return <NectRedirectStep ctx={ctx} />
    return null
  },
}

const nectModules: ToolModule[] = [identNect]
export default nectModules
