import { resolveText } from '../texts'
import type { ConfirmPrompt } from '../types'

interface Props {
  prompt: ConfirmPrompt
  onAnswer: (accept: boolean) => void
  busy?: boolean
}

/**
 * Renders any AnswerableState purely from stepData.prompt - no per-prompt component and no
 * frontend release needed for a new backend-driven confirmation (docs/05-api.md, Prompt).
 */
export function PromptView({ prompt, onAnswer, busy }: Props) {
  return (
    <div className="card">
      <h2>{resolveText(prompt.title)}</h2>
      {prompt.description && <p className="muted">{resolveText(prompt.description)}</p>}
      <div className="actions">
        <button
          type="button"
          className={prompt.destructive ? 'destructive' : undefined}
          onClick={() => onAnswer(true)}
          disabled={busy}
        >
          {resolveText(prompt.confirmLabel)}
        </button>
        <button type="button" className="secondary" onClick={() => onAnswer(false)} disabled={busy}>
          {resolveText(prompt.cancelLabel)}
        </button>
      </div>
    </div>
  )
}
