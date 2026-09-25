import { t } from '../texts'
import { Demo } from './DemoArea'
import { DiagramHint } from './DiagramHint'
import { JOURNEY_DIAGRAMS } from '../journeyDiagrams'

/**
 * The journeys behind a screen's buttons, as diagrams in the demo column - the phone or website
 * shows the buttons a real client has, the demo column what each one sets going.
 */
export function ButtonDiagrams({ entries }: { entries: Array<{ label: string; diagram: keyof typeof JOURNEY_DIAGRAMS }> }) {
  return (
    <Demo>
      <div className="button-diagrams">
        <p className="button-diagrams__title">{t('Abläufe hinter den Buttons')}</p>
        {entries.map((entry) => (
          <p className="demo-diagram" key={entry.diagram}>
            {entry.label}
            <DiagramHint spec={JOURNEY_DIAGRAMS[entry.diagram]} inline openDown>
              <span className="diagram-hint-trigger" tabIndex={0} aria-label={t('Ablauf "{abschnitt}" als Diagramm anzeigen', { abschnitt: entry.label })}>
                ℹ️
              </span>
            </DiagramHint>
          </p>
        ))}
      </div>
    </Demo>
  )
}
