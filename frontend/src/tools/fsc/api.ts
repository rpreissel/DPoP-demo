import { submitViaPatch } from '../shared/defaultApi'
import type { ToolRenderContext } from '../types'

export interface FscFields {
  kvnr: string
  name: string
  vorname: string
  /** ISO date (YYYY-MM-DD), as `<input type="date">` delivers it. */
  geburtsdatum: string
  fsc: string
}

/** Any subset: the backend merges each PATCH onto what it already has (docs/06-ablaeufe.md #2). */
export function submitFsc(ctx: ToolRenderContext, fields: Partial<FscFields>) {
  return submitViaPatch(ctx, fields)
}
