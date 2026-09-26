import { submitViaPatch } from '../shared/defaultApi'
import type { ToolRenderContext } from '../types'

export function submitEidCard(
  ctx: ToolRenderContext,
  fields: {
    familyName: string
    givenNames: string
    birthDate: string
    streetAddress: string
    postalCode: string
    locality: string
    restrictedId: string
  },
) {
  return submitViaPatch(ctx, fields)
}

export function submitEidPin(ctx: ToolRenderContext, pin: string) {
  return submitViaPatch(ctx, { pin })
}
