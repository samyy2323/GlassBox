import { forestDomain, scaleX, type ForestRow } from '../lib/experiments'

const TONE: Record<ForestRow['tone'], string> = {
  good: 'rgb(var(--ok-rgb))',
  bad: 'rgb(239 68 68)',
  none: 'rgb(var(--muted-rgb))',
}

const WIDTH = 560
const LABEL = 150
const ROW = 34
const TOP = 8
const AXIS = 26

/** Each run's difference from the baseline with its 95% interval. Green and red mean the interval clears zero (and the test agrees); grey means no real difference. */
export function ForestPlot({ rows, title }: { rows: ForestRow[]; title: string }) {
  if (rows.length === 0) return <p className="text-sm text-muted">Nothing to compare yet.</p>
  const domain = forestDomain(rows)
  const plot = WIDTH - LABEL - 16
  const height = TOP + rows.length * ROW + AXIS
  const x = (v: number) => LABEL + scaleX(v, domain, plot)
  const ticks: number[] = []
  for (let t = Math.round(domain[0] * 100); t <= Math.round(domain[1] * 100); t += 5) ticks.push(t / 100)

  return (
    <svg viewBox={`0 0 ${WIDTH} ${height}`} role="img" aria-label={title} className="w-full max-w-[640px] text-fg">
      {ticks.map((t) => (
        <g key={t}>
          <line x1={x(t)} x2={x(t)} y1={TOP} y2={height - AXIS} stroke="currentColor" strokeOpacity={t === 0 ? 0.55 : 0.1} strokeDasharray={t === 0 ? undefined : '2 3'} />
          <text x={x(t)} y={height - 8} textAnchor="middle" fontSize="10" fill="rgb(var(--muted-rgb))">{`${t > 0 ? '+' : ''}${Math.round(t * 100)}`}</text>
        </g>
      ))}
      {rows.map((r, i) => {
        const y = TOP + i * ROW + ROW / 2
        const color = TONE[r.tone]
        return (
          <g key={r.label}>
            <text x={LABEL - 10} y={y + 4} textAnchor="end" fontSize="12" fill="currentColor">{r.label.length > 22 ? `${r.label.slice(0, 21)}…` : r.label}</text>
            <line x1={x(r.low)} x2={x(r.high)} y1={y} y2={y} stroke={color} strokeWidth="2.5" strokeLinecap="round" />
            <line x1={x(r.low)} x2={x(r.low)} y1={y - 5} y2={y + 5} stroke={color} strokeWidth="2" />
            <line x1={x(r.high)} x2={x(r.high)} y1={y - 5} y2={y + 5} stroke={color} strokeWidth="2" />
            <circle cx={x(r.delta)} cy={y} r="5" fill={color} />
            <title>{`${r.label}: ${r.delta >= 0 ? '+' : ''}${(r.delta * 100).toFixed(1)} points, 95% interval ${(r.low * 100).toFixed(1)} to ${(r.high * 100).toFixed(1)}`}</title>
          </g>
        )
      })}
    </svg>
  )
}
