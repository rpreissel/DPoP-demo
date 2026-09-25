import type { PageContext } from '../KcContext'
import { EmailThenCode } from '../components/EmailThenCode'

/** `tool-email-enroll.ftl`: adding an e-mail address as a sign-in method. */
export function ToolEmailEnroll({ kcContext }: { kcContext: PageContext<'tool-email-enroll.ftl'> }) {
  return <EmailThenCode kcContext={kcContext} />
}
