import { failureBars, pct, valueWithInterval } from '../lib/report'
import { ParetoPlot } from './ParetoPlot'

interface Row { metric: string; name: string; value: number; ciLow?: number; ciHigh?: number; n: number; judged: boolean }
interface Snapshot {
  kind: 'EXPERIMENT' | 'AUTOTUNE'
  title: string
  generatedAt: string
  redacted: boolean
  summary: string[]
  testset?: { name: string; version: number; questions: number; accepted: number; edited: number; rejected: number; acceptanceRate?: number; fromFeedback: number; byType: Record<string, number> }
  configs?: { label: string; settings: string[] }[]
  metrics?: { label: string; rows: Row[] }[]
  comparisons?: { label: string; rows: { name: string; verdict: string }[] }[]
  failures?: Record<string, Record<string, number>>
  failedQuestions?: { run: string; question: string; stage: string }[]
  headline?: { winnerLabel: string; verdict: string; heldOut: { quality: number; ciLow: number; ciHigh: number; n: number }; baselineHeldOut: { quality: number; ciLow: number; ciHigh: number } }
  candidates?: { label: string; role: string; heldOut: { quality: number; ciLow: number; ciHigh: number; promptTokens: number; latencyMs: number }; vsBaseline?: { verdict: string } }[]
  pareto?: { points: { label: string; quality: number; cost: number; latencyMs: number; frontier: boolean; role: 'WINNER' | 'FINALIST' | 'BASELINE' }[] }
  limitations: string[]
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return <section className="glass space-y-3 rounded-2xl p-5"><h2 className="text-sm font-semibold">{title}</h2>{children}</section>
}

/** A frozen report, as the share page and the preview show it. It reads only the snapshot: no live data. */
export function ReportView({ snapshot }: { snapshot: Snapshot }) {
  const s = snapshot
  return (
    <div className="space-y-4">
      <header>
        <h1 className="text-[1.5rem] font-semibold tracking-tight">{s.title}</h1>
        <p className="text-xs text-muted">Generated {new Date(s.generatedAt).toLocaleString()}{s.redacted ? ' · question texts redacted' : ''}</p>
      </header>
      <Section title="Summary"><ul className="list-disc space-y-1 pl-5 text-sm">{s.summary.map((l, i) => <li key={i}>{l}</li>)}</ul></Section>

      {s.headline && (
        <Section title="Result">
          <p className="font-semibold">{s.headline.winnerLabel}</p>
          <p className="text-sm">{s.headline.verdict}</p>
          <p className="text-xs text-muted">Held-out {pct(s.headline.heldOut.quality)} [{pct(s.headline.heldOut.ciLow)}, {pct(s.headline.heldOut.ciHigh)}] on {s.headline.heldOut.n} questions; baseline {pct(s.headline.baselineHeldOut.quality)} [{pct(s.headline.baselineHeldOut.ciLow)}, {pct(s.headline.baselineHeldOut.ciHigh)}].</p>
        </Section>
      )}
      {s.candidates && s.candidates.length > 0 && (
        <Section title="Finalists and the baseline (held-out)">
          <ul className="space-y-2 text-sm">{s.candidates.map((c) => (
            <li key={c.label}><span className="font-medium">{c.label}</span> <span className="chip">{c.role.toLowerCase()}</span>
              <span className="block text-xs text-muted">{pct(c.heldOut.quality)} [{pct(c.heldOut.ciLow)}, {pct(c.heldOut.ciHigh)}] · {Math.round(c.heldOut.promptTokens)} prompt tokens · {Math.round(c.heldOut.latencyMs)} ms{c.vsBaseline ? ` · ${c.vsBaseline.verdict}` : ''}</span></li>
          ))}</ul>
        </Section>
      )}
      {s.pareto && s.pareto.points.length > 0 && (
        <Section title="Quality against cost"><ParetoPlot points={s.pareto.points.map((p) => ({ key: p.label, label: p.label, quality: p.quality, cost: p.cost, latencyMs: p.latencyMs, frontier: p.frontier, role: p.role }))} picks={{}} /></Section>
      )}

      {s.testset && (
        <Section title="Test set">
          <p className="text-sm">{s.testset.name} v{s.testset.version}: {s.testset.questions} questions ({s.testset.accepted} accepted, {s.testset.edited} edited, {s.testset.rejected} rejected{s.testset.acceptanceRate != null ? `, ${pct(s.testset.acceptanceRate)} kept` : ''}); {s.testset.fromFeedback} from feedback.</p>
          <p className="text-xs text-muted">{Object.entries(s.testset.byType).map(([t, n]) => `${t.toLowerCase().replace('_', '-')} ${n}`).join(', ')}</p>
        </Section>
      )}
      {s.configs && s.configs.length > 0 && (
        <Section title="Configurations"><ul className="space-y-1.5 text-sm">{s.configs.map((c) => <li key={c.label}><span className="font-medium">{c.label}</span><span className="block text-xs text-muted">{c.settings.join(', ')}</span></li>)}</ul></Section>
      )}
      {s.metrics && s.metrics.length > 0 && (
        <Section title="Metrics (95% bootstrap intervals)">
          {s.metrics.map((run) => (
            <div key={run.label}>
              <h3 className="text-xs font-semibold">{run.label}</h3>
              <dl className="mt-1 grid gap-x-6 gap-y-0.5 text-sm sm:grid-cols-2">{run.rows.map((r) => <div key={r.metric} className="flex justify-between gap-3"><dt>{r.name}{r.judged ? <span className="text-xs text-warn"> (judged)</span> : ''}</dt><dd className="tabular-nums text-muted">{valueWithInterval(r)} · n={r.n}</dd></div>)}</dl>
            </div>
          ))}
        </Section>
      )}
      {s.comparisons && s.comparisons.length > 0 && (
        <Section title="Is the difference real?">{s.comparisons.map((c) => <div key={c.label}><h3 className="text-xs font-semibold">{c.label}</h3><ul className="mt-1 list-disc space-y-0.5 pl-5 text-sm">{c.rows.map((r) => <li key={r.name}>{r.name}: {r.verdict}</li>)}</ul></div>)}</Section>
      )}
      {s.failures && Object.keys(s.failures).length > 0 && (
        <Section title="Where the misses were lost">
          {Object.entries(s.failures).map(([run, stages]) => {
            const bars = failureBars(stages)
            return (
              <div key={run}><h3 className="text-xs font-semibold">{run}</h3>
                {bars.length === 0 ? <p className="text-xs text-muted">No failures.</p> : <ul className="mt-1 space-y-1">{bars.map((b) => <li key={b.stage} className="grid grid-cols-[8rem_1fr_2rem] items-center gap-2 text-xs"><span>{b.stage}</span><span className="h-2.5 overflow-hidden rounded-full bg-fg/10"><span className="block h-full rounded-full bg-accent" style={{ width: `${Math.max(3, b.width * 100)}%` }} /></span><span className="tabular-nums">{b.count}</span></li>)}</ul>}
              </div>
            )
          })}
        </Section>
      )}
      {s.failedQuestions && s.failedQuestions.length > 0 && (
        <Section title="Failed questions"><ul className="list-disc space-y-0.5 pl-5 text-sm">{s.failedQuestions.map((q, i) => <li key={i}>{q.question} <span className="text-xs text-muted">({q.stage.toLowerCase().replace('_', ' ')}, {q.run})</span></li>)}</ul></Section>
      )}
      <Section title="Limitations"><ul className="list-disc space-y-1 pl-5 text-sm">{s.limitations.map((l, i) => <li key={i}>{l}</li>)}</ul></Section>
    </div>
  )
}

export type { Snapshot as ReportSnapshot }
