import type { ReactNode } from 'react'
import type { KcContext } from '../KcContext'
import { Layout } from '../Layout'
import { t } from '../../texts'

/**
 * The frame every tool page shares, as in the FreeMarker templates: title and hint from the
 * tool's renderer factory, one form posting to Keycloak, and "Weiter"/"Zurück" - "Zurück" leaves
 * the tool (orchestrator_abandon) and skips the browser's field validation.
 */
export function ToolForm({
  kcContext,
  title,
  hint,
  children,
  submitLabel,
  backLabel,
}: {
  kcContext: KcContext
  title: string
  hint?: ReactNode
  children?: ReactNode
  /** Default "Weiter"; null for a page without its own submit (waiting for the app). */
  submitLabel?: string | null
  backLabel?: string
}) {
  return (
    <Layout kcContext={kcContext} title={title}>
      {hint && <p className="orc-subtitle">{hint}</p>}
      <form id="kc-orchestrator-tool-form" action={kcContext.url.loginAction} method="post">
        {children}
        <div className="orc-actions">
          {submitLabel !== null && (
            <button className="orc-button orc-button-primary" type="submit">
              {submitLabel ?? t('Weiter')}
            </button>
          )}
          <button className="orc-button" type="submit" name="orchestrator_abandon" value="true" formNoValidate>
            {backLabel ?? t('Zurück')}
          </button>
        </div>
      </form>
    </Layout>
  )
}
