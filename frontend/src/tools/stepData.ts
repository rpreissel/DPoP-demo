import { stepDataOf } from '../types'
import { resolveText } from '../texts'
import type { ToolRenderContext } from './types'

/** Why the last attempt failed, if it did - what a tool shows next to its form. */
export function attemptError(ctx: Pick<ToolRenderContext, 'stepData'>): string | undefined {
  const error = stepDataOf(ctx.stepData, 'failed-attempt')?.error
  return error ? resolveText(error) : undefined
}
