interface SelectMethodViewProps {
  options: string[]
  onSelect: (toolId: string) => void
}

interface ToolMeta {
  icon: string
  label: string
  hint: string
}

/**
 * Human-readable presentation for the toolIds `stepData.options` can contain - the API only
 * ever hands the client an opaque id (docs/05-api.md #2: "Auswahloptionen ... als vollständige
 * toolId-Werte"), so this mapping is purely a frontend display concern, never a routing
 * decision (routing.ts still owns that from `next`, unchanged).
 */
const TOOL_META: Record<string, ToolMeta> = {
  'ident-fsc': { icon: '🪪', label: 'Freischaltcode', hint: 'Versichertennummer, Name und Freischaltcode' },
  'enroll-sms': { icon: '📱', label: 'SMS', hint: 'Code an eine Telefonnummer' },
  'auth-sms': { icon: '📱', label: 'SMS', hint: 'Code an die hinterlegte Telefonnummer' },
  'auth-sms-lookup': { icon: '📱', label: 'SMS', hint: 'E-Mail-Adresse + SMS-Code' },
  'enroll-password': { icon: '🔑', label: 'Passwort', hint: 'Eigenes Passwort festlegen' },
  'auth-password': { icon: '🔑', label: 'Passwort', hint: 'Mit dem hinterlegten Passwort' },
  'auth-password-lookup': { icon: '🔑', label: 'Passwort', hint: 'E-Mail-Adresse + Passwort' },
  'enroll-email': { icon: '✉️', label: 'E-Mail', hint: 'Bestätigungscode an eine E-Mail-Adresse' },
  'auth-email': { icon: '✉️', label: 'E-Mail', hint: 'Code an die bestätigte E-Mail-Adresse' },
  'auth-email-lookup': { icon: '✉️', label: 'E-Mail', hint: 'E-Mail-Adresse + Bestätigungscode' },
}

function metaFor(toolId: string): ToolMeta {
  return TOOL_META[toolId] ?? { icon: '🔐', label: toolId, hint: '' }
}

/**
 * Generic selection page for `type=flow` steps with `stepData.options` (docs/10-frontend.md #3).
 * Entries are already complete toolId values; the client picks one, never constructs one itself.
 * Rendered as a small set of self-explanatory choice cards rather than a raw button-per-toolId
 * stack, since the same screen already competes for attention with nothing else while a tool
 * choice is pending (App.tsx's `inToolMode` hides every other action here).
 */
export function SelectMethodView({ options, onSelect }: SelectMethodViewProps) {
  return (
    <div className="card">
      <h2>Verfahren wählen</h2>
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
