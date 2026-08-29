import type { Next } from './types'

/**
 * Screens the orchestrator itself owns - selection pages, confirmations, the finished screen.
 * `context` names the KIND of offer (`auth`/`enrollment`/`registration`), not the intent asking
 * for it - reused across every intent that offers the same kind of candidate, so a new intent
 * needs a new table row only if it introduces a genuinely new kind of screen (like
 * `accountDeletion`'s confirmation), never merely for reusing an existing one. Not a tool; see
 * src/tools/registry.ts for those.
 */
const orchestratorRoutes: Record<string, Record<string, string>> = {
  registration: { selectIdentificationMethod: 'select-method' },
  enrollment: { selectMethod: 'select-method' },
  auth: { selectMethod: 'select-method' },
  authentication: { authenticated: 'authentication-completed' },
  // Every AnswerableState, of any intent, shares this one address (JourneyState.kt) - the screen
  // it renders is always the same generic prompt, driven entirely by stepData.prompt.
  prompt: { confirm: 'prompt' },
}

/** Determines which orchestrator screen to show, based solely on `next` - never on a URL. Tool steps go through src/tools/registry.ts's renderToolStep instead. */
export function getUIComponent(next: Next | undefined): string | null {
  if (!next) return null
  if (next.type === 'orchestrator' && next.context) {
    return orchestratorRoutes[next.context]?.[next.step] ?? null
  }
  return null
}
