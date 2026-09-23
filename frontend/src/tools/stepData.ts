import { stepDataOf } from '../types'
import type { ToolRenderContext } from './types'

/** Why the last attempt failed, if it did - what a tool shows next to its form. */
export function attemptError(ctx: Pick<ToolRenderContext, 'stepData'>): string | undefined {
  return stepDataOf(ctx.stepData, 'failed-attempt')?.error
}
