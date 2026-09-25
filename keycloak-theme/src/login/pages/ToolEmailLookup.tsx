import type { PageContext } from '../KcContext'
import { EmailThenCode } from '../components/EmailThenCode'

/** `tool-email-lookup.ftl`: signing in by e-mail address, the account found by it. */
export function ToolEmailLookup({ kcContext }: { kcContext: PageContext<'tool-email-lookup.ftl'> }) {
  return <EmailThenCode kcContext={kcContext} />
}
