import { metaFor } from '../tools/registry'

interface SelectMethodViewProps {
  options: string[]
  title: string
  description?: string
  onSelect: (toolId: string) => void
}

/**
 * Generic selection page for `type=flow` steps with `stepData.options` (docs/10-frontend.md #3).
 * Entries are already complete toolId values; the client picks one, never constructs one itself.
 * `title`/`description` come from the backend (`stepData`, docs/05-api.md) - the same shared
 * `context`/`step` address (e.g. "auth"/"selectMethod") is reused across intents that ask
 * different things (log in vs. confirm an account deletion), so this screen can't guess the right
 * heading from the address alone. Rendered as a small set of self-explanatory choice cards rather
 * than a raw button-per-toolId stack, since the same screen already competes for attention with
 * nothing else while a tool choice is pending (App.tsx's `inToolMode` hides every other action here).
 */
export function SelectMethodView({ options, title, description, onSelect }: SelectMethodViewProps) {
  return (
    <div className="card">
      <h2>{title}</h2>
      {description && <p>{description}</p>}
      <ul className="method-choice-list">
        {options.map((toolId) => {
          const meta = metaFor(toolId)
          return (
            <li key={toolId}>
              <button className="method-choice" onClick={() => onSelect(toolId)}>
                <span className="method-choice-icon" aria-hidden="true">
                  {meta.icon}
                </span>
                <span className="method-choice-text">
                  <span className="method-choice-label">{meta.label}</span>
                  {meta.hint && <span className="method-choice-hint">{meta.hint}</span>}
                </span>
              </button>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
