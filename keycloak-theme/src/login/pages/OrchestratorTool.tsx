import type { PageContext } from '../KcContext'
import { Field } from '../components/Field'
import { ToolForm } from '../components/ToolForm'

/**
 * `orchestrator-tool.ftl`: the generic page for any tool without its own - one text field per
 * entry of stepData.missingFields, named like the field, nothing tool-specific.
 */
export function OrchestratorTool({ kcContext }: { kcContext: PageContext<'orchestrator-tool.ftl'> }) {
  const { toolId, title, hint, fields } = kcContext
  return (
    <ToolForm kcContext={kcContext} title={title ?? toolId} hint={hint}>
      {Object.keys(fields).map((name) => (
        <Field key={name} id={name} label={name} defaultValue={fields[name] ?? ''} />
      ))}
    </ToolForm>
  )
}
