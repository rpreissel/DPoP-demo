import type { ReactNode } from 'react'
import { resolveText, t } from '../texts'
import { Tx } from '../Tx'
import type { JourneyDebugStep } from '../types'

interface StepExplanationProps {
  /** The running journey chain, outermost first - its purposes say why this step is here at all. */
  journeys?: JourneyDebugStep[]
  /** When no journey runs (start screen, logged in): why the screen looks the way it does. */
  idleReason?: string
  does: string
  actor: string
  /** The address of the step (tool/orchestrator, step) - for those who want to find it in the code. */
  technical?: string
  /** Which journey runs right now (named after its real intent) - heads the box. */
  journeyTitle?: string
  /** The diagram trigger for that journey, next to its name. */
  journeyDiagram?: ReactNode
  /** Demo way out: forget the session locally and go back to the start screen. */
  onLeave?: () => void
}

/**
 * The demo column's answer to "what am I looking at?": why the orchestrator put this step here
 * (the journey's purpose, from the backend - DemoStepReason), what the step does and who is acting
 * on it right now (from the tool module itself, ToolModule.explain).
 */
export function StepExplanation({ journeys, idleReason, does, actor, technical, journeyTitle, journeyDiagram, onLeave }: StepExplanationProps) {
  const innermost = journeys?.at(-1)
  const outer = (journeys ?? []).slice(0, -1).filter((j) => j.purpose)
  const why = innermost?.purpose ? resolveText(innermost.purpose) : idleReason

  return (
    <>
    {(journeyTitle || onLeave) && (
      <div className="step-explanation__journey">
        {journeyTitle && (
          <span>
            <Tx text="Journey: {name}" name={<strong>{journeyTitle}</strong>} /> {journeyDiagram}
          </span>
        )}
        {onLeave && (
          <button className="secondary small" onClick={onLeave} title={t('Verlässt den Vorgang ganz und geht zurück zur Startauswahl.')}>
            {t('Zur Startseite')}
          </button>
        )}
      </div>
    )}
    <dl className="step-explanation">
      {why && (
        <>
          <dt>{t('Warum dieser Schritt')}</dt>
          <dd>
            {why}
            {innermost?.note && <span className="step-explanation__note">{resolveText(innermost.note)}</span>}
            {/* A sub-journey runs for its parent - e.g. a step-up so a method may be removed. */}
            {outer.map((j) => (
              <span key={j.journeyId} className="step-explanation__note">
                {t('Übergeordnet: {zweck}', { zweck: resolveText(j.purpose) })}
              </span>
            ))}
          </dd>
        </>
      )}
      <dt>{t('Was passiert')}</dt>
      <dd>{does}</dd>
      <dt>{t('Wer ist dran')}</dt>
      <dd>{actor}</dd>
      {technical && <dd className="step-explanation__technical">{technical}</dd>}
    </dl>
    </>
  )
}
