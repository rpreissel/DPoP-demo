import type { ToolModule } from '../types'
import { NectRedirectStep } from './NectRedirectStep'
import { t } from '../../texts'

export const identNect: ToolModule = {
  toolId: 'ident-nect',
  meta: { icon: '📲', label: t('Nect'), hint: t('Ausweis, Reisepass oder EUDI-Wallet bei Nect (simuliert)') },
  render(ctx) {
    if (ctx.step === 'redirect') return <NectRedirectStep ctx={ctx} />
    return null
  },
}

const nectModules: ToolModule[] = [identNect]
export default nectModules
