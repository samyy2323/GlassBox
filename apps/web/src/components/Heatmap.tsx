import type { RunSummary } from '../lib/api'
import { findMetric, formatInterval, formatMetric, heatCell, LOW_SAMPLE, slicesPresent } from '../lib/experiments'

const TYPE_TEXT: Record<string, string> = { FACTUAL: 'Factual', NUMERIC: 'Numeric', MULTI_HOP: 'Multi-hop', COMPARISON: 'Comparison', UNANSWERABLE: 'Unanswerable' }

/**
 * Configuration by question type. A configuration that scores 90% overall can still fail one kind of question, which only shows here.
 * Cells with fewer than 10 questions are hatched: they are shown, but not to be trusted.
 */
export function Heatmap({ runs, metric }: { runs: RunSummary[]; metric: string }) {
  const types = slicesPresent(runs, metric)
  if (types.length === 0) return <p className="text-sm text-muted">There are no results to slice by question type yet.</p>
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[32rem] border-separate border-spacing-1 text-sm">
        <caption className="sr-only">Result by configuration and question type</caption>
        <thead>
          <tr>
            <th className="w-40 text-left text-xs font-medium text-muted" scope="col">Configuration</th>
            <th className="text-center text-xs font-medium text-muted" scope="col">All</th>
            {types.map((t) => <th key={t} className="text-center text-xs font-medium text-muted" scope="col">{TYPE_TEXT[t] ?? t}</th>)}
          </tr>
        </thead>
        <tbody>
          {runs.map((r) => (
            <tr key={r.id}>
              <th scope="row" className="truncate pr-2 text-left text-[0.82rem] font-medium">{r.label}</th>
              {['all', ...types].map((slice) => {
                const v = findMetric(r.metrics, metric, slice)
                const cell = heatCell(v?.value, v?.n ?? 0)
                return (
                  <td
                    key={slice}
                    title={v ? `${formatInterval(metric, v)} on ${v.n} question${v.n === 1 ? '' : 's'}${cell.lowSample ? ` (fewer than ${LOW_SAMPLE}: low sample)` : ''}` : 'no results'}
                    className="relative rounded-lg px-2 py-2 text-center tabular-nums"
                    style={{
                      background: `rgb(var(--accent-rgb) / ${cell.opacity})`,
                      backgroundImage: cell.lowSample && v ? 'repeating-linear-gradient(135deg, transparent 0 5px, rgb(var(--bg-rgb) / 0.55) 5px 8px)' : undefined,
                    }}
                  >
                    <span className="font-semibold">{formatMetric(metric, v?.value)}</span>
                    <span className="block text-[0.65rem] text-fg/70">n={v?.n ?? 0}{cell.lowSample && v ? ' · low' : ''}</span>
                  </td>
                )
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
