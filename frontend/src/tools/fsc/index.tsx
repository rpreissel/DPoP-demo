import type { ToolModule } from '../types'
import { submitFsc } from './api'
import { IdentFscForm } from './IdentFscForm'

export const identFsc: ToolModule = {
  toolId: 'ident-fsc',
  meta: { icon: '🪪', label: 'Freischaltcode', hint: 'Versichertennummer, Name und Freischaltcode' },
  render(ctx) {
    if (ctx.step === 'input') return <IdentFscForm onSubmit={(fields) => submitFsc(ctx, fields)} />
    return null
  },
}

const fscModules: ToolModule[] = [identFsc]
export default fscModules
