import { submitViaPatch } from '../shared/defaultApi'
import type { ToolRenderContext } from '../types'

export function submitEidCard(
  ctx: ToolRenderContext,
  fields: {
    name: string
    vorname: string
    geburtsdatum: string
    strasse: string
    hausnummer: string
    plz: string
    ort: string
    restrictedId: string
  },
) {
  return submitViaPatch(ctx, fields)
}

export function submitEidPin(ctx: ToolRenderContext, pin: string) {
  return submitViaPatch(ctx, { pin })
}
