interface SelectMethodViewProps {
  options: string[]
  onSelect: (toolId: string) => void
}

/**
 * Generic selection page for `type=flow` steps with `stepData.options` (docs/10-frontend.md #3).
 * Entries are already complete toolId values; the client picks one, never constructs one itself.
 */
export function SelectMethodView({ options, onSelect }: SelectMethodViewProps) {
  return (
    <div className="card">
      <h2>Methode wählen</h2>
      <div className="form-actions" style={{ flexDirection: 'column', alignItems: 'stretch' }}>
        {options.map((toolId) => (
          <button key={toolId} onClick={() => onSelect(toolId)}>
            {toolId}
          </button>
        ))}
      </div>
    </div>
  )
}
