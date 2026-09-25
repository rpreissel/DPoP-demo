import type { KcContext } from '../KcContext'
import { Layout } from '../Layout'
import { t } from '../../texts'

/**
 * `orchestrator-tool.ftl`: the generic page for any tool without its own - one text field per
 * entry of stepData.missingFields, named like the field, nothing tool-specific.
 */
export function OrchestratorTool({ kcContext }: { kcContext: Extract<KcContext, { pageId: 'orchestrator-tool.ftl' }> }) {
  const { url, toolId, title, hint, fields } = kcContext
  return (
    <Layout kcContext={kcContext} title={title ?? toolId}>
      {hint && <p className="orc-subtitle">{hint}</p>}
      <form id="kc-orchestrator-tool-form" action={url.loginAction} method="post">
        {Object.keys(fields).map((name) => (
          <div className="orc-field" key={name}>
            <label htmlFor={name}>{name}</label>
            <input type="text" id={name} name={name} defaultValue={fields[name] ?? ''} autoComplete="off" />
          </div>
        ))}
        <div className="orc-actions">
          <button className="orc-button orc-button-primary" type="submit">
            {t('Weiter')}
          </button>
          <button className="orc-button" type="submit" name="orchestrator_abandon" value="true">
            {t('Zurück')}
          </button>
        </div>
      </form>
    </Layout>
  )
}
