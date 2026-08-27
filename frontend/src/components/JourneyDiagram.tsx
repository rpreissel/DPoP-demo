interface JourneyDiagramProps {
  title: string
  steps: string[]
}

const BOX_HEIGHT = 44
const CHAR_WIDTH = 7.2
const PADDING_X = 18
const MIN_BOX_WIDTH = 96
const MARGIN = 4

function boxWidth(label: string): number {
  return Math.max(MIN_BOX_WIDTH, Math.round(label.length * CHAR_WIDTH) + PADDING_X * 2)
}

/**
 * One horizontal chain of steps for a single journey (Registrierung/Login/...) - a static preview
 * of what happens, shown in WelcomeIntro before anything is clicked. Deliberately simple (no
 * branching, no live highlighting): the real, backend-driven order can vary case by case (see
 * docs/04-orchestrierung.md), this is an at-a-glance shape, not a spec. The last box is always the
 * outcome and gets the accent color to draw the eye to "where this ends up".
 */
export function JourneyDiagram({ title, steps }: JourneyDiagramProps) {
  const widths = steps.map(boxWidth)
  const arrowSpan = 26
  let x = MARGIN
  const boxes = widths.map((w) => {
    const box = { x, width: w }
    x += w + arrowSpan
    return box
  })
  const totalWidth = x - arrowSpan + MARGIN
  const centerY = MARGIN + BOX_HEIGHT / 2
  const totalHeight = BOX_HEIGHT + MARGIN * 2

  const label = `${title}: ${steps.join(' -> ')}`
  const markerId = `arrow-${title.replace(/[^a-zA-Z0-9]+/g, '-').toLowerCase()}`

  return (
    <figure className="journey-diagram">
      <figcaption>{title}</figcaption>
      <svg viewBox={`0 0 ${totalWidth} ${totalHeight}`} role="img" aria-label={label}>
        <defs>
          <marker id={markerId} viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0,0 L8,4 L0,8 Z" fill="currentColor" />
          </marker>
        </defs>
        {boxes.map((box, i) => {
          const isLast = i === boxes.length - 1
          return (
            <g key={i}>
              <rect
                x={box.x}
                y={MARGIN}
                width={box.width}
                height={BOX_HEIGHT}
                rx={8}
                fill={isLast ? 'var(--accent)' : 'none'}
                stroke="currentColor"
              />
              <text
                x={box.x + box.width / 2}
                y={centerY}
                textAnchor="middle"
                dominantBaseline="middle"
                fontSize="12"
                fill={isLast ? '#fff' : 'currentColor'}
              >
                {steps[i]}
              </text>
              {!isLast && (
                <line
                  x1={box.x + box.width}
                  y1={centerY}
                  x2={box.x + box.width + arrowSpan - 2}
                  y2={centerY}
                  stroke="currentColor"
                  markerEnd={`url(#${markerId})`}
                />
              )}
            </g>
          )
        })}
      </svg>
    </figure>
  )
}
