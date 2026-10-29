import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, ChevronLeft, Loader2, ScanSearch, Share2, XCircle } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'
import { ForestPlot } from '../components/ForestPlot'
import { Diagnosis } from '../components/Diagnosis'
import { Heatmap } from '../components/Heatmap'
import { ShareDialog } from '../components/ShareDialog'
import { JudgeBadge } from '../components/JudgeBadge'
import { api, type ComparisonRow, type Experiment, type Flip, type RunSummary } from '../lib/api'
import {
  findMetric, forestRows, formatDelta, formatInterval, formatMetric, HEADLINE, isActive, METRIC_ORDER, METRICS, metricLabel, outcomeTone, runProgress,
} from '../lib/experiments'
import { queryKeys } from '../lib/queryKeys'
import { useWorkspace } from '../lib/workspace'
import { ExperimentStatusChip } from './Experiments'

const TONE_DOT = { good: 'bg-ok', bad: 'bg-danger', none: 'bg-muted/50' } as const

function rowFor(e: Experiment, runId: string, metric: string): ComparisonRow | undefined {
  return e.vsBaseline.find((c) => c.runId === runId)?.rows.find((r) => r.metric === metric)
}

function Section({ title, hint, children }: { title: string; hint?: string; children: React.ReactNode }) {
  return (
    <section className="card space-y-3">
      <div>
        <h2 className="eyebrow !text-fg">{title}</h2>
        {hint && <p className="mt-0.5 text-xs text-muted">{hint}</p>}
      </div>
      {children}
    </section>
  )
}

/** One sentence per configuration about the headline metric: how much better or worse than the baseline, how sure, and by which test. */
function Verdicts({ e, headline }: { e: Experiment; headline: string }) {
  if (e.vsBaseline.length === 0) return <p className="text-sm text-muted">{e.runs.length < 2 ? 'Add a second configuration to compare against the baseline.' : 'Comparisons appear when the runs finish.'}</p>
  const baseline = e.runs[0].label
  return (
    <ul className="space-y-3">
      {e.vsBaseline.map((c) => {
        const row = c.rows.find((r) => r.metric === headline)
        if (!row) return null
        const tone = outcomeTone(row.outcome, METRICS[headline]?.higherIsBetter ?? true)
        return (
          <li key={c.runId} className="rounded-xl border border-[var(--line)] p-3">
            <p className="flex flex-wrap items-center gap-2 text-sm font-semibold">
              <span className={`h-2 w-2 rounded-full ${TONE_DOT[tone]}`} aria-hidden />
              {c.label} <span className="font-normal text-muted">vs {baseline}</span>
              <span className="chip">{metricLabel(headline)}</span>
            </p>
            <p className="mt-1.5 text-[0.95rem]" data-testid="verdict">{row.verdict}</p>
            <p className="mt-1 text-xs text-muted">
              A is the baseline ({baseline}), B is {c.label}. {formatMetric(headline, row.a)} against {formatMetric(headline, row.b)} on {row.n} questions.
              {' '}Test: {row.test === 'mcnemar' ? 'exact McNemar on the questions that changed' : 'paired permutation of the per-question differences'}; p {row.pAdjusted < 0.001 ? '< 0.001' : `= ${row.pAdjusted.toFixed(3)}`}
              {e.runs.length > 2 ? ' (Holm-corrected across configurations)' : ''}.
            </p>
            {row.minDetectable != null && (
              <p className="mt-1 text-xs text-muted">With {row.n} questions this comparison can reliably detect a difference of about {(row.minDetectable * 100).toFixed(1)} points. Smaller gaps are noise; more questions would sharpen it.</p>
            )}
          </li>
        )
      })}
    </ul>
  )
}

function MetricTable({ e, grouped }: { e: Experiment; grouped: boolean }) {
  const shown = METRIC_ORDER.filter((m) => e.runs.some((r) => findMetric(r.metrics, m)))
  let lastGroup = ''
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[34rem] text-left text-sm">
        <caption className="sr-only">Every metric for every configuration, with 95% intervals</caption>
        <thead>
          <tr className="text-xs text-muted">
            <th scope="col" className="py-2 pr-3 font-medium">Metric</th>
            {e.runs.map((r, i) => <th key={r.id} scope="col" className="px-2 py-2 font-medium">{r.label}{i === 0 && <span className="ml-1.5 chip">baseline</span>}</th>)}
          </tr>
        </thead>
        <tbody>
          {shown.map((m) => {
            const group = METRICS[m]?.group ?? ''
            const header = group !== lastGroup
            lastGroup = group
            return [
              header && <tr key={`g-${group}`}><td colSpan={e.runs.length + 1} className="eyebrow pb-1 pt-4">{group}</td></tr>,
              <tr key={m} className="border-t border-[var(--line)] align-top">
                <th scope="row" className="py-2 pr-3 font-medium" title={METRICS[m]?.hint}>
                  {metricLabel(m)}<JudgeBadge metric={m} judge={e.judge} />
                </th>
                {e.runs.map((r, i) => {
                  const v = (grouped && findMetric(r.metrics, m, 'all', 'cluster')) || findMetric(r.metrics, m)
                  const row = i === 0 ? undefined : rowFor(e, r.id, m)
                  return (
                    <td key={r.id} className="px-2 py-2 tabular-nums">
                      <span className="block">{formatInterval(m, v)}</span>
                      {row && (
                        <span className="mt-0.5 flex items-center gap-1.5 text-xs text-muted" title={row.verdict}>
                          <span className={`h-1.5 w-1.5 rounded-full ${TONE_DOT[outcomeTone(row.outcome, METRICS[m]?.higherIsBetter ?? true)]}`} aria-hidden />
                          {formatDelta(row.delta)}
                        </span>
                      )}
                    </td>
                  )
                })}
              </tr>,
            ]
          })}
        </tbody>
      </table>
    </div>
  )
}

function FlipList({ title, flips, side }: { title: string; flips: Flip[]; side: 'A' | 'B' }) {
  if (flips.length === 0) return null
  return (
    <div className="space-y-1.5">
      <h3 className="text-xs font-semibold">{title} ({flips.length})</h3>
      <ul className="space-y-1.5">
        {flips.map((f) => {
          const trace = side === 'A' ? f.traceA : f.traceB
          return (
            <li key={f.questionId} className="flex items-start gap-2 rounded-lg border border-[var(--line)] p-2.5 text-sm">
              <span className="min-w-0 flex-1">
                <span className="block">{f.question}</span>
                <span className="text-xs text-muted">{f.qtype.toLowerCase().replace('_', '-')} · A {formatMetric('correctness', f.a)}, B {formatMetric('correctness', f.b)}</span>
              </span>
              {trace && <Link to={`/xray/${trace}`} className="btn-ghost shrink-0 !px-2 text-xs"><ScanSearch className="h-3.5 w-3.5" /> X-Ray</Link>}
            </li>
          )
        })}
      </ul>
    </div>
  )
}

function RunDiff({ e }: { e: Experiment }) {
  const done = e.runs.filter((r) => r.status === 'DONE')
  const [a, setA] = useState(done[0]?.id ?? '')
  const [b, setB] = useState(done[1]?.id ?? '')
  const diff = useQuery({ queryKey: queryKeys.runDiff(a, b), queryFn: () => api.compareRuns(a, b), enabled: !!a && !!b && a !== b })
  if (done.length < 2) return <p className="text-sm text-muted">Two finished runs are needed.</p>
  const select = 'input-glass !w-auto !py-1 text-sm'
  const d = diff.data
  const flipTitle = d?.flipMetric === 'correctness' ? ['Fully correct in A, not in B', 'Not fully correct in A, correct in B'] : ['Evidence reached the prompt in A, not in B', 'Evidence missed in A, reached the prompt in B']
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3 text-sm">
        <label className="flex items-center gap-2">A <select className={select} value={a} onChange={(ev) => setA(ev.target.value)}>{done.map((r) => <option key={r.id} value={r.id}>{r.label}</option>)}</select></label>
        <span className="text-muted">against</span>
        <label className="flex items-center gap-2">B <select className={select} value={b} onChange={(ev) => setB(ev.target.value)}>{done.map((r) => <option key={r.id} value={r.id}>{r.label}</option>)}</select></label>
      </div>
      {a === b && <p className="text-sm text-muted">Choose two different runs.</p>}
      {diff.isLoading && a !== b && <div className="skeleton h-24" />}
      {d && (
        <>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[30rem] text-left text-sm">
              <thead><tr className="text-xs text-muted"><th className="py-1.5 pr-3 font-medium">Metric</th><th className="px-2 font-medium">A</th><th className="px-2 font-medium">B</th><th className="px-2 font-medium">B minus A (95% CI)</th><th className="px-2 font-medium">p</th></tr></thead>
              <tbody>
                {d.rows.map((r) => (
                  <tr key={r.metric} className="border-t border-[var(--line)]" title={r.verdict}>
                    <th scope="row" className="py-1.5 pr-3 font-medium">{metricLabel(r.metric)}</th>
                    <td className="px-2 tabular-nums">{formatMetric(r.metric, r.a)}</td>
                    <td className="px-2 tabular-nums">{formatMetric(r.metric, r.b)}</td>
                    <td className="px-2 tabular-nums"><span className={`mr-1.5 inline-block h-1.5 w-1.5 rounded-full ${TONE_DOT[outcomeTone(r.outcome, METRICS[r.metric]?.higherIsBetter ?? true)]}`} />{formatDelta(r.delta)} [{(r.ciLow * 100).toFixed(1)}, {(r.ciHigh * 100).toFixed(1)}]</td>
                    <td className="px-2 tabular-nums">{r.pAdjusted < 0.001 ? '<0.001' : r.pAdjusted.toFixed(3)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="text-xs text-muted">{d.questions} questions in common.</p>
          {d.passToFail.length + d.failToPass.length === 0 && <p className="text-sm text-muted">No question changed sides between these runs.</p>}
          <FlipList title={flipTitle[0]} flips={d.passToFail} side="B" />
          <FlipList title={flipTitle[1]} flips={d.failToPass} side="B" />
        </>
      )}
    </div>
  )
}

function Questions({ e }: { e: Experiment }) {
  const [runId, setRunId] = useState(e.runs[0]?.id ?? '')
  const [onlyMisses, setOnlyMisses] = useState(true)
  const headline = HEADLINE[e.mode]
  const results = useQuery({ queryKey: queryKeys.experimentResults(e.id, runId), queryFn: () => api.experimentResults(e.id, runId), enabled: !!runId })
  const shown = (results.data ?? []).filter((r) => !onlyMisses || (r.metrics[headline] ?? 1) < 1)
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-3 text-sm">
        <select className="input-glass !w-auto !py-1 text-sm" value={runId} onChange={(ev) => setRunId(ev.target.value)} aria-label="Configuration">{e.runs.map((r) => <option key={r.id} value={r.id}>{r.label}</option>)}</select>
        <label className="flex items-center gap-2 text-xs text-muted"><input type="checkbox" checked={onlyMisses} onChange={(ev) => setOnlyMisses(ev.target.checked)} className="accent-[rgb(var(--accent-rgb))]" /> Only questions that missed on {metricLabel(headline).toLowerCase()}</label>
      </div>
      {results.isLoading ? <div className="skeleton h-20" /> : shown.length === 0 ? <p className="text-sm text-muted">{onlyMisses ? 'No misses in this run.' : 'No results.'}</p> : (
        <ul className="space-y-1.5">
          {shown.map((r) => (
            <li key={r.questionId} className="flex items-start gap-2 rounded-lg border border-[var(--line)] p-2.5 text-sm">
              <span className="min-w-0 flex-1">
                <span className="block">{r.question}</span>
                <span className="text-xs text-muted">{r.qtype.toLowerCase().replace('_', '-')} · {metricLabel(headline)} {formatMetric(headline, r.metrics[headline])}{r.failureStage ? ` · ${r.failureStage.toLowerCase().replace('_', ' ')}` : ''}</span>
              </span>
              {r.traceId && <Link to={`/xray/${r.traceId}`} className="btn-ghost shrink-0 !px-2 text-xs"><ScanSearch className="h-3.5 w-3.5" /> X-Ray</Link>}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

export default function ExperimentDetail() {
  const { id = '' } = useParams()
  const { current } = useWorkspace()
  const qc = useQueryClient()
  const exp = useQuery({
    queryKey: queryKeys.experiment(id),
    queryFn: () => api.experiment(id),
    enabled: !!current,
    refetchInterval: (q) => (q.state.data && isActive(q.state.data.status) ? 2000 : false),
  })
  const cancel = useMutation({ mutationFn: () => api.cancelExperiment(id), onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.experiment(id) }) })
  const [forestMetric, setForestMetric] = useState('')
  const [grouped, setGrouped] = useState(false)
  const [sharing, setSharing] = useState(false)
  const e = exp.data
  const canEdit = current != null && current.role !== 'VIEWER'

  const forestChoices = useMemo(() => (e ? METRIC_ORDER.filter((m) => e.vsBaseline.some((c) => c.rows.some((r) => r.metric === m))) : []), [e])
  if (exp.isPending) return <div className="mx-auto w-full max-w-5xl space-y-4 p-6" aria-label="Loading experiment"><div className="skeleton h-10 w-72" /><div className="skeleton h-64" /></div>
  if (exp.isError || !e) return <p role="alert" className="p-6 text-sm">This experiment could not be found. It may belong to another workspace.</p>

  const headline = HEADLINE[e.mode]
  const metric = forestChoices.includes(forestMetric) ? forestMetric : forestChoices.includes(headline) ? headline : forestChoices[0] ?? headline
  const finished = e.status === 'DONE'
  const actual = e.actualCost ?? {}

  return (
    <div className="min-h-0 flex-1 overflow-y-auto">
      <div className="mx-auto w-full max-w-5xl space-y-5 p-4 sm:p-6">
        {sharing && <ShareDialog kind="EXPERIMENT" refId={e.id} onClose={() => setSharing(false)} />}
        <header className="space-y-2">
          <div className="flex flex-wrap items-center gap-3">
            <Link to="/experiments" className="btn-ghost !px-2" aria-label="All experiments"><ChevronLeft className="h-4 w-4" /></Link>
            <h1 className="min-w-0 truncate text-[2rem] leading-tight">{e.name}</h1>
            <ExperimentStatusChip status={e.status} />
            <span className="chip">{e.mode === 'FULL' ? 'Full answers' : 'Retrieval only'}</span>
            {(e.status === 'DONE' || e.status === 'ABORTED') && <button className="btn-ghost ml-auto" onClick={() => setSharing(true)}><Share2 className="h-4 w-4" /> Share</button>}
            {isActive(e.status) && canEdit && (
              <button className="btn-ghost ml-auto" onClick={() => cancel.mutate()} disabled={cancel.isPending}>{cancel.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <XCircle className="h-4 w-4" />} Cancel</button>
            )}
          </div>
          <p className="text-xs text-muted">
            <Link to={`/testsets/${e.testsetId}/review`} className="font-medium text-accent underline-offset-4 hover:underline">{e.testsetName} v{e.testsetVersion}</Link> · {e.questionCount} questions measured
            {e.staleCount > 0 && <span className="text-warn"> · {e.staleCount} left out because their document changed</span>}
            {finished && ` · ${actual.tokensIn || actual.tokensOut ? `${Math.round(((actual.tokensIn ?? 0) + (actual.tokensOut ?? 0)) / 1000)}k tokens, ` : ''}$${(actual.usd ?? 0).toFixed(2)}`}
          </p>
          {e.status === 'FAILED' && <p role="alert" className="text-sm text-danger">{e.error}</p>}
        </header>

        {isActive(e.status) && (
          <Section title="Progress">
            <ul className="space-y-2" aria-live="polite">
              {e.runs.map((r) => (
                <li key={r.id} className="space-y-1">
                  <div className="flex justify-between text-xs"><span className="font-medium">{r.label}</span><span className="text-muted">{r.samplesDone} of {e.questionCount}</span></div>
                  <div className="h-1.5 overflow-hidden rounded-full bg-fg/10" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={runProgress(r, e.questionCount)}>
                    <div className="h-full rounded-full bg-accent transition-all" style={{ width: `${runProgress(r, e.questionCount)}%` }} />
                  </div>
                </li>
              ))}
            </ul>
          </Section>
        )}

        {e.judge && (
          <Section title="The judge" hint="Answers are graded by a language model. Its numbers are only trusted once it has been checked against human labels.">
            <p className="text-sm">
              <span className="font-medium">{e.judge.model}</span> grades the answers, rubric {e.judge.rubricVersion}; statements are checked by {e.judge.nliModel} and the judge.{' '}
              {(['faithfulness', 'correctness', 'relevance'] as const).map((m) => {
                const s = e.judge!.metrics[m]
                return <span key={m} className={`mr-2 chip ${s?.status === 'calibrated' ? '' : '!border-warn/50 !text-warn'}`}>{metricLabel(m)}: {s?.status ?? 'unknown'}{s?.kappa != null ? ` (κ ${s.kappa.toFixed(2)})` : ''}</span>
              })}
              <Link to="/calibration" className="text-xs font-medium text-accent underline-offset-4 hover:underline">Calibrate</Link>
            </p>
            {e.judge.warnings.map((w) => <p key={w} className="flex items-start gap-2 text-xs text-warn"><AlertTriangle className="mt-px h-3.5 w-3.5 shrink-0" /> {w}</p>)}
          </Section>
        )}

        {finished && (
          <>
            <Section title="Is the difference real?" hint="Every configuration is compared with the baseline on the same questions. A difference only counts when its interval excludes zero and the test agrees.">
              <Verdicts e={e} headline={headline} />
            </Section>

            {forestChoices.length > 0 && (
              <Section title="Difference from the baseline" hint="Each line is a configuration's change in points with its 95% interval. Lines that cross zero are not a real difference.">
                <label className="flex items-center gap-2 text-xs text-muted">Metric
                  <select className="input-glass !w-auto !py-1 text-sm" value={metric} onChange={(ev) => setForestMetric(ev.target.value)}>{forestChoices.map((m) => <option key={m} value={m}>{metricLabel(m)}</option>)}</select>
                </label>
                <ForestPlot title={`${metricLabel(metric)}: change from the baseline in points`} rows={forestRows(e.runs.slice(1).map((r) => ({ label: r.label, row: rowFor(e, r.id, metric) })), metric)} />
              </Section>
            )}

            <Section title="All metrics" hint="Value with its 95% bootstrap interval; the dot beside a change shows whether it is a real difference from the baseline.">
              <label className="flex items-center gap-2 text-xs text-muted"><input type="checkbox" checked={grouped} onChange={(ev) => setGrouped(ev.target.checked)} className="accent-[rgb(var(--accent-rgb))]" /> Intervals by source document (questions from one document move together, so these are wider and more honest)</label>
              <MetricTable e={e} grouped={grouped} />
            </Section>

            <Section title="By question type" hint={`${metricLabel(headline)} for each kind of question. A configuration that scores well overall can still fail one kind. Hatched cells have fewer than 10 questions.`}>
              <Heatmap runs={e.runs as RunSummary[]} metric={headline} />
            </Section>

            <Section title="Why questions failed" hint="Each failed question is labelled with the first stage of the pipeline that lost it, with advice for the stages that explain the most failures.">
              <Diagnosis e={e} canEdit={canEdit} />
            </Section>

            <Section title="Compare two runs" hint="The same table for any two runs, and the questions that changed sides.">
              <RunDiff e={e} />
            </Section>

            <Section title="Questions" hint="What happened to each question, and where it was lost.">
              <Questions e={e} />
            </Section>
          </>
        )}
      </div>
    </div>
  )
}
