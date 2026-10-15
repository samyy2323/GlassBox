import { frontierPath, placePoints, type PlotPoint } from '../lib/autotune'

const W = 520
const H = 260
const LEFT = 46
const BOTTOM = 34
const TOP = 12
const RIGHT = 16

const ROLE_FILL: Record<PlotPoint['role'], string> = {
  WINNER: 'rgb(var(--accent-rgb))',
  FINALIST: 'rgb(var(--accent2-rgb))',
  BASELINE: 'rgb(var(--muted-rgb))',
}

/** Held-out quality (up) against prompt tokens per question (right, so cheaper is left). The line joins the points nothing beats on quality, cost and speed together. */
export function ParetoPlot({ points, picks }: { points: PlotPoint[]; picks: Record<string, string | undefined> }) {
  if (points.length === 0) return <p className="text-sm text-muted">Nothing was measured on the held-out questions.</p>
  const innerW = W - LEFT - RIGHT
  const innerH = H - TOP - BOTTOM
  const { placed, xDomain, yDomain } = placePoints(points, innerW, innerH)
  const pickName = (key: string) => Object.entries(picks).filter(([, v]) => v === key).map(([k]) => ({ bestQuality: 'best quality', bestValue: 'best value', fastest: 'fastest' }[k] ?? k))
  const yTicks = [0, 0.25, 0.5, 0.75, 1].map((f) => yDomain[0] + f * (yDomain[1] - yDomain[0]))
  const xTicks = [0, 0.5, 1].map((f) => xDomain[0] + f * (xDomain[1] - xDomain[0]))

  return (
    <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Held-out quality against prompt tokens per question" className="w-full max-w-[640px] text-fg">
      <g transform={`translate(${LEFT},${TOP})`}>
        {yTicks.map((t) => {
          const y = innerH - ((t - yDomain[0]) / (yDomain[1] - yDomain[0])) * innerH
          return (
            <g key={t}>
              <line x1={0} x2={innerW} y1={y} y2={y} stroke="currentColor" strokeOpacity={0.1} strokeDasharray="2 3" />
              <text x={-8} y={y + 3} textAnchor="end" fontSize="10" fill="rgb(var(--muted-rgb))">{`${Math.round(t * 100)}%`}</text>
            </g>
          )
        })}
        {xTicks.map((t) => {
          const x = ((t - xDomain[0]) / (xDomain[1] - xDomain[0])) * innerW
          return <text key={t} x={x} y={innerH + 16} textAnchor="middle" fontSize="10" fill="rgb(var(--muted-rgb))">{Math.round(t)}</text>
        })}
        <text x={innerW / 2} y={innerH + 30} textAnchor="middle" fontSize="10" fill="rgb(var(--muted-rgb))">prompt tokens per question (less is cheaper)</text>
        <path d={frontierPath(placed)} fill="none" stroke="rgb(var(--accent-rgb))" strokeOpacity={0.5} strokeWidth="1.5" strokeDasharray="4 3" />
        {placed.map((p) => {
          const names = pickName(p.key)
          return (
            <g key={p.key}>
              <circle cx={p.x} cy={p.y} r={p.role === 'WINNER' ? 7 : 5.5} fill={ROLE_FILL[p.role]} fillOpacity={p.frontier ? 1 : 0.55} stroke="currentColor" strokeOpacity={p.frontier ? 0.6 : 0} />
              {names.length > 0 && <text x={p.x + 9} y={p.y - 8} fontSize="10" fill="currentColor">{names.join(', ')}</text>}
              <title>{`${p.label}: ${(p.quality * 100).toFixed(1)}% quality, ${Math.round(p.cost)} prompt tokens, ${Math.round(p.latencyMs)} ms${p.frontier ? ', on the frontier' : ''}`}</title>
            </g>
          )
        })}
      </g>
    </svg>
  )
}
