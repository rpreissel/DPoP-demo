import type { PageContext } from '../KcContext'
import { Layout } from '../Layout'
import { t } from '../../texts'

/**
 * `orchestrator-confirm.ftl`: the generic yes/no prompt of every AnswerableState - title and labels
 * come resolved from the orchestrator's prompt, the defaults only when it sent none.
 */
export function OrchestratorConfirm({ kcContext }: { kcContext: PageContext<'orchestrator-confirm.ftl'> }) {
  const { url, pageTitle: title, confirmLabel, cancelLabel } = kcContext
  return (
    <Layout kcContext={kcContext} title={title ?? t('Bestätigung erforderlich')}>
      <form id="kc-orchestrator-confirm-form" action={url.loginAction} method="post">
        <div className="orc-actions">
          <button className="orc-button orc-button-primary" type="submit" name="orchestrator_answer" value="accept">
            {confirmLabel ?? t('Ja')}
          </button>
          <button className="orc-button" type="submit" name="orchestrator_answer" value="decline">
            {cancelLabel ?? t('Nein')}
          </button>
        </div>
      </form>
    </Layout>
  )
}
