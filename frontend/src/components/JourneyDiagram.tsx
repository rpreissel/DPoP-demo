export interface JourneyDiagramSpec {
  title: string
  steps: string[]
  /** Where the fallback splits off, and what it looks like - the ONE branch the argument hinges on, not every edge case. */
  branch?: { atIndex: number; mainLabel: string; label: string; steps: string[] }
}

/** Which box represents where a REAL running journey currently is - `branch` picks `steps` vs. `branch.steps`. Absent for the static hover previews (there's no live instance to point at). */
export interface JourneyDiagramCurrentStep {
  branch?: boolean
  index: number
}

const BOX_HEIGHT = 60
const ROW_GAP = 40
const CHAR_WIDTH = 10.2
const PADDING_X = 22
const MIN_BOX_WIDTH = 112
const MARGIN = 6
const ARROW_SPAN = 40
const DIAMOND_SIZE = 84
const BOX_FONT_SIZE = 16
const EDGE_FONT_SIZE = 14
const EDGE_CHAR_WIDTH = 7.5

function boxWidth(label: string): number {
  return Math.max(MIN_BOX_WIDTH, Math.round(label.length * CHAR_WIDTH) + PADDING_X * 2)
}

/** Space to leave after a decision diamond for its mainLabel ("Ja (ANONYMOUS)", ...) - a fixed ARROW_SPAN only fits an arrow, not text, and a long label would otherwise spill back over the diamond. */
function edgeGap(label?: string): number {
  return label ? Math.max(ARROW_SPAN, Math.round(label.length * EDGE_CHAR_WIDTH) + 16) : ARROW_SPAN
}

/**
 * One journey's shape as a small flow diagram: a straight chain of steps, with an optional single
 * branch point (a decision diamond) where the real backend logic forks - e.g. "Gerät erkannt?"
 * Ja/Nein. Deliberately only ONE branch, not a full state-machine graph: this is what a first
 * glance needs (docs/04-orchestrierung.md has the real, complete rules).
 */
export function JourneyDiagram({ title, steps, branch, current }: JourneyDiagramSpec & { current?: JourneyDiagramCurrentStep }) {
  const markerId = `arrow-${title.replace(/[^a-zA-Z0-9]+/g, '-').toLowerCase()}`
  // Reserve room for the "● aktuell" label drawn above a highlighted box on the main row - it
  // would otherwise sit outside the viewBox, since row1 normally starts right at the top margin.
  const row1Y = current && !current.branch ? MARGIN + 18 : MARGIN
  const row1CenterY = row1Y + BOX_HEIGHT / 2

  let x = MARGIN
  const boxes = steps.map((s, i) => {
    const isDecision = branch && i === branch.atIndex
    // A fixed diamond size clips longer decision labels ("Gerät erkannt?") past the shape's
    // edges - widen it to fit its own text instead, same as a regular box.
    const w = isDecision ? Math.max(DIAMOND_SIZE, boxWidth(s)) : boxWidth(s)
    const box = { x, width: w, isDecision }
    x += w + (isDecision ? edgeGap(branch!.mainLabel) : ARROW_SPAN)
    return box
  })
  const row1Width = x - ARROW_SPAN + MARGIN

  let branchBoxes: { x: number; width: number }[] = []
  let branchWidth = 0
  let branchStartX = 0
  const row2Y = row1Y + BOX_HEIGHT + ROW_GAP + 20
  const row2CenterY = row2Y + BOX_HEIGHT / 2
  if (branch) {
    const decisionBox = boxes[branch.atIndex]
    branchStartX = decisionBox.x + decisionBox.width / 2
    let bx = branchStartX
    branchBoxes = branch.steps.map((s) => {
      const w = boxWidth(s)
      const b = { x: bx, width: w }
      bx += w + ARROW_SPAN
      return b
    })
    branchWidth = bx - ARROW_SPAN
  }

  const totalWidth = Math.max(row1Width, branchWidth + MARGIN)
  const totalHeight = branch ? row2Y + BOX_HEIGHT + MARGIN : row1Y + BOX_HEIGHT + MARGIN
  const currentStepName = current && (current.branch ? branch?.steps[current.index] : steps[current.index])
  const label =
    (branch
      ? `${title}: ${steps.join(' -> ')}, bei "${branch.label}": ${branch.steps.join(' -> ')}`
      : `${title}: ${steps.join(' -> ')}`) + (currentStepName ? ` - aktueller Schritt: ${currentStepName}` : '')

  return (
    <figure className="journey-diagram">
      <figcaption>{title}</figcaption>
      <svg width={totalWidth} height={totalHeight} viewBox={`0 0 ${totalWidth} ${totalHeight}`} role="img" aria-label={label}>
        <defs>
          <marker id={markerId} viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0,0 L8,4 L0,8 Z" fill="currentColor" />
          </marker>
        </defs>

        {boxes.map((box, i) => {
          const isLast = i === boxes.length - 1
          const isCurrent = !!current && !current.branch && current.index === i
          return (
            <g key={i}>
              {box.isDecision ? (
                <polygon
                  points={`${box.x + box.width / 2},${row1Y} ${box.x + box.width},${row1CenterY} ${box.x + box.width / 2},${row1Y + BOX_HEIGHT} ${box.x},${row1CenterY}`}
                  fill="none"
                  stroke={isCurrent ? 'var(--accent)' : 'currentColor'}
                  strokeWidth={isCurrent ? 3 : 1}
                />
              ) : (
                <rect
                  x={box.x} y={row1Y} width={box.width} height={BOX_HEIGHT} rx={10}
                  fill={isLast ? 'var(--accent)' : 'none'}
                  stroke={isCurrent ? 'var(--accent)' : 'currentColor'}
                  strokeWidth={isCurrent ? 3 : 1}
                />
              )}
              {isCurrent && (
                <text x={box.x + box.width / 2} y={row1Y - 10} textAnchor="middle" fontSize={EDGE_FONT_SIZE} fill="var(--accent)">
                  ● aktuell
                </text>
              )}
              <text x={box.x + box.width / 2} y={row1CenterY} textAnchor="middle" dominantBaseline="middle" fontSize={BOX_FONT_SIZE} fill={isLast ? '#fff' : 'currentColor'}>
                {steps[i]}
              </text>
              {i < boxes.length - 1 && (
                <>
                  <line x1={box.x + box.width} y1={row1CenterY} x2={boxes[i + 1].x - 2} y2={row1CenterY} stroke="currentColor" markerEnd={`url(#${markerId})`} />
                  {box.isDecision && (
                    <text x={(box.x + box.width + boxes[i + 1].x) / 2} y={row1CenterY - 10} textAnchor="middle" fontSize={EDGE_FONT_SIZE} fill="currentColor">
                      {branch!.mainLabel}
                    </text>
                  )}
                </>
              )}
            </g>
          )
        })}

        {branch && (
          <>
            <line x1={branchStartX} y1={row1CenterY + BOX_HEIGHT / 2} x2={branchStartX} y2={row2CenterY} stroke="currentColor" markerEnd={`url(#${markerId})`} />
            <text x={branchStartX + 6} y={row1CenterY + BOX_HEIGHT / 2 + 18} fontSize={EDGE_FONT_SIZE} fill="currentColor">
              {branch.label}
            </text>
            {branchBoxes.map((box, i) => {
              const isLast = i === branchBoxes.length - 1
              const isCurrent = !!current && current.branch === true && current.index === i
              return (
                <g key={i}>
                  <rect
                    x={box.x} y={row2Y} width={box.width} height={BOX_HEIGHT} rx={10}
                    fill={isLast ? 'var(--accent)' : 'none'}
                    stroke={isCurrent ? 'var(--accent)' : 'currentColor'}
                    strokeWidth={isCurrent ? 3 : 1}
                  />
                  {isCurrent && (
                    <text x={box.x + box.width / 2} y={row2Y - 10} textAnchor="middle" fontSize={EDGE_FONT_SIZE} fill="var(--accent)">
                      ● aktuell
                    </text>
                  )}
                  <text x={box.x + box.width / 2} y={row2CenterY} textAnchor="middle" dominantBaseline="middle" fontSize={BOX_FONT_SIZE} fill={isLast ? '#fff' : 'currentColor'}>
                    {branch.steps[i]}
                  </text>
                  {i < branchBoxes.length - 1 && (
                    <line x1={box.x + box.width} y1={row2CenterY} x2={branchBoxes[i + 1].x - 2} y2={row2CenterY} stroke="currentColor" markerEnd={`url(#${markerId})`} />
                  )}
                </g>
              )
            })}
          </>
        )}
      </svg>
    </figure>
  )
}
