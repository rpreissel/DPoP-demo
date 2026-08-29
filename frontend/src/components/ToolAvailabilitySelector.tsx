import { knownToolIds } from '../tools/registry'

interface ToolAvailabilitySelectorProps {
  availableTools: string[]
  onChange: (toolIds: string[]) => void
}

/**
 * Demo stand-in for "the user disabled a method locally" / "this client version doesn't support
 * it yet" (docs/03-tool-architektur.md, availability). The candidate set is `knownToolIds` - the
 * toolIds this client actually has a form for (routing.ts) - not the full backend catalog: a tool
 * without a routing entry couldn't be rendered here even if declared available.
 *
 * Same row shape as AdminToolAvailabilityView's list (label + status + toggle button) on purpose:
 * both are "make this tool available or not", just on different axes (this client vs. the whole
 * backend) - a checkbox grid here made the same kind of action look like a different one.
 */
export function ToolAvailabilitySelector({ availableTools, onChange }: ToolAvailabilitySelectorProps) {
  function toggle(toolId: string) {
    onChange(
      availableTools.includes(toolId) ? availableTools.filter((id) => id !== toolId) : [...availableTools, toolId],
    )
  }

  return (
    <div className="tool-availability-selector">
      <h3>Verfügbare Tools auf diesem Client ({availableTools.length}/{knownToolIds.length})</h3>
      <p>
        Abgewählte Tools werden dieser Journey nie angeboten - simuliert eine ältere Client-Version oder eine
        lokale Nutzer-Einstellung. Unabhängig davon kann das Backend Tools zusätzlich global sperren (siehe
        "Admin: Tool-Verfügbarkeit" oben) - beide Sperren wirken zusammen, keine hebt die andere auf.
      </p>
      <ul className="status-list">
        {knownToolIds.map((toolId) => {
          const enabled = availableTools.includes(toolId)
          return (
            <li key={toolId}>
              <span className="label">{toolId}</span>
              <span className="value-with-action">
                <span className="value">{enabled ? 'verfügbar' : 'nicht verfügbar'}</span>
                <button className="secondary small" onClick={() => toggle(toolId)}>
                  {enabled ? 'Entfernen' : 'Hinzufügen'}
                </button>
              </span>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
