import { submitViaPatch } from '../shared/defaultApi'
import type { ToolRenderContext } from '../types'

export function submitEidLookup(ctx: ToolRenderContext, fields: { kvnr: string; name: string; vorname: string }) {
  return submitViaPatch(ctx, fields)
}

export function submitEidCard(
  ctx: ToolRenderContext,
  fields: { geburtsdatum: string; strasse: string; hausnummer: string; plz: string; ort: string },
) {
  return submitViaPatch(ctx, fields)
}

export function submitEidPin(ctx: ToolRenderContext, pin: string) {
  return submitViaPatch(ctx, { pin })
}
