import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, Check, ChevronLeft, Loader2, Rocket, Share2, XCircle } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'
import { ParetoPlot } from '../components/ParetoPlot'
import { ShareDialog } from '../components/ShareDialog'
import { api, ApiError, type Autotune, type AutotuneCandidate } from '../lib/api'
import { describeConfig, effectGroups, isActive, OBJECTIVE_LABEL, shrinkageText, STEPS, stepStates, type PlotPoint } from '../lib/autotune'
import { formatDelta } from '../lib/experiments'
import { queryKeys } from '../lib/queryKeys'
import { useWorkspace } from '../lib/workspace'
import { ExperimentStatusChip } from './Experiments'

const pct = (v: number | undefined) => (v == null ? '–' : `${Math.round(v * 1000) / 10}%`)
const interval = (h: { quality: number; ciLow: number; ciHigh: number }) => `${pct(h.quality)} [${pct(h.ciLow)}, ${pct(h.ciHigh)}]`

function Section({ title, hint, children }: { title: string; hint?: string; children: React.ReactNode }) {
  return (
    <section className="glass space-y-3 rounded-2xl p-5">
      <div>
        <h2 className="text-sm font-semibold">{title}</h2>
        {hint && <p className="mt-0.5 text-xs text-muted">{hint}</p>}
      </div>
      {children}
    </section>
  )
}

function Steps({ a }: { a: Autotune }) {
  const states = stepStates(a.stage, a.status, a.options.fullStage)
  const spent = a.spent ?? {}
  return (
    <div className="space-y-3" aria-live="polite">
      <ol className="space-y-2">
        {STEPS.map((s, i) => (
          <li key={s.id} className={`flex items-start gap-3 text-sm ${states[i] === 'skipped' ? 'text-muted/60' : ''}`}>
            <span className={`mt-0.5 grid h-5 w-5 shrink-0 place-items-center rounded-full text-[0.65rem] ${states[i] === 'done' ? 'bg-ok/20 text-ok' : states[i] === 'current' ? 'bg-accent text-bg' : 'bg-fg/10 text-muted'}`}>
              {states[i] === 'done' ? <Check className="h-3 w-3" /> : states[i] === 'current' ? <Loader2 className="h-3 w-3 animate-spin" /> : i + 1}
            </span>
            <span><span className="font-medium">{s.label}</span>{states[i] === 'skipped' ? ' (off)' : ''}<span className="block text-xs text-muted">{s.text}</span></span>
          </li>
        ))}
      </ol>
      <p className="text-xs text-muted">
        {spent.retrievalCalls ?? 0} searches · {spent.llmCalls ?? 0} model calls · {Math.round((spent.elapsedMs ?? 0) / 60000)} min used
        {a.candidates.STAGE1 ? ` · ${a.candidates.STAGE1} chunking and embedding candidates` : ''}{a.candidates.STAGE2 ? ` · ${a.candidates.STAGE2} retrieval candidates` : ''}
      </p>
    </div>
  )
}

function PickCard({ title, text, c }: { title: string; text: string; c: AutotuneCandidate | undefined }) {
  if (!c) return null
  return (
    <li className="rounded-xl border border-[var(--line)] p-3">
      <p className="eyebrow">{title}</p>
      <p className="mt-1 text-sm font-semibold">{c.label.replace(/ · /g, ' · ')}</p>
      <p className="mt-1 text-xs text-muted">{pct(c.heldOut.quality)} held-out quality · {Math.round(c.heldOut.promptTokens)} prompt tokens · {Math.round(c.heldOut.latencyMs)} ms</p>
      <p className="mt-1 text-xs text-muted">{text}</p>
    </li>
  )
}

export default function AutotuneDetail() {
  const { id = '' } = useParams()
  const { current } = useWorkspace()
  const qc = useQueryClient()
  const run = useQuery({
    queryKey: queryKeys.autotune(id),
    queryFn: () => api.autotune(id),
    enabled: !!current,
    refetchInterval: (q) => (q.state.data && isActive(q.state.data.status) ? 2500 : false),
  })
  const cancel = useMutation({ mutationFn: () => api.cancelAutotune(id), onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.autotune(id) }) })
  const promote = useMutation({ mutationFn: () => api.promoteAutotune(id) })
  const [showAll, setShowAll] = useState(false)
  const [sharing, setSharing] = useState(false)
  const a = run.data
  const canEdit = current != null && current.role !== 'VIEWER'
  const isOwner = current?.role === 'OWNER'

  if (run.isPending) return <div className="mx-auto w-full max-w-5xl space-y-4 p-6" aria-label="Loading AutoTune run"><div className="skeleton h-10 w-72" /><div className="skeleton h-64" /></div>
  if (run.isError || !a) return <p role="alert" className="p-6 text-sm">This AutoTune run could not be found. It may belong to another workspace.</p>

  const r = a.result
  const h = r?.headline
  const byKey = new Map((r?.candidates ?? []).map((c) => [c.key, c]))
  const points: PlotPoint[] = (r?.candidates ?? []).map((c) => ({ key: c.key, label: c.label, quality: c.heldOut.quality, cost: c.heldOut.promptTokens, latencyMs: c.heldOut.latencyMs, frontier: c.onFrontier, role: c.role }))
  const winnerBeat = h?.vsBaseline?.outcome === 'B_BETTER'
  const promoteError = promote.error instanceof ApiError ? promote.error.message : promote.error ? 'Could not promote.' : null

  return (
    <div className="min-h-0 flex-1 overflow-y-auto">
      <div className="mx-auto w-full max-w-5xl space-y-5 p-4 sm:p-6">
        {sharing && <ShareDialog kind="AUTOTUNE" refId={a.id} onClose={() => setSharing(false)} />}
        <header className="space-y-2">
          <div className="flex flex-wrap items-center gap-3">
            <Link to="/autotune" className="btn-ghost !px-2" aria-label="All AutoTune runs"><ChevronLeft className="h-4 w-4" /></Link>
            <h1 className="min-w-0 truncate text-[1.4rem] font-semibold tracking-tight">{a.name}</h1>
            <ExperimentStatusChip status={a.status} />
            <span className="chip">{OBJECTIVE_LABEL[a.objective].title}</span>
            {a.status === 'DONE' && <button className="btn-ghost ml-auto" onClick={() => setSharing(true)}><Share2 className="h-4 w-4" /> Share</button>}
            {isActive(a.status) && canEdit && <button className="btn-ghost ml-auto" onClick={() => cancel.mutate()} disabled={cancel.isPending}>{cancel.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <XCircle className="h-4 w-4" />} Cancel</button>}
          </div>
          <p className="text-xs text-muted">
            <Link to={`/testsets/${a.testsetId}/review`} className="font-medium text-accent underline-offset-4 hover:underline">{a.testsetName}</Link> · {a.split.tuning} questions to tune on, {a.split.heldOut} held out
            {a.stoppedBy === 'SPEND_CAP' && <span className="text-warn"> · stopped at the spend cap, best result so far</span>}
          </p>
          {a.status === 'FAILED' && <p role="alert" className="text-sm text-red-500">{a.error}</p>}
        </header>

        {isActive(a.status) && <Section title="Progress"><Steps a={a} /></Section>}

        {r && h && (
          <>
            <Section title="Result" hint="Judged on questions that no choice during the search was based on.">
              <p className="text-[1.05rem] font-semibold" data-testid="autotune-verdict">{h.winnerIsBaseline ? h.verdict : `${h.winnerLabel}: ${h.verdict}`}</p>
              <div className="grid gap-3 sm:grid-cols-2">
                <div className={`rounded-xl border p-3 ${winnerBeat ? 'border-ok/50' : 'border-[var(--line)]'}`}>
                  <p className="eyebrow">{h.winnerIsBaseline ? 'Baseline' : 'Winner'}, held-out</p>
                  <p className="mt-1 text-2xl font-semibold tabular-nums">{pct(h.heldOut.quality)}</p>
                  <p className="text-xs text-muted">95% interval [{pct(h.heldOut.ciLow)}, {pct(h.heldOut.ciHigh)}] on {h.heldOut.n} questions</p>
                </div>
                <div className="rounded-xl border border-[var(--line)] p-3">
                  <p className="eyebrow">Baseline, held-out</p>
                  <p className="mt-1 text-2xl font-semibold tabular-nums">{pct(h.baselineHeldOut.quality)}</p>
                  <p className="text-xs text-muted">95% interval [{pct(h.baselineHeldOut.ciLow)}, {pct(h.baselineHeldOut.ciHigh)}] · {h.baselineLabel.replace(/ · /g, ', ')}</p>
                </div>
              </div>
              <ul className="flex flex-wrap gap-1.5 text-xs">{describeConfig(h.winnerConfig).map((t) => <li key={t} className="chip">{t}</li>)}</ul>
              {shrinkageText(h.tuning?.quality, h.heldOut.quality, h.shrinkage) && <p className="text-sm">{shrinkageText(h.tuning?.quality, h.heldOut.quality, h.shrinkage)}</p>}
              {h.vsBaseline?.minDetectable != null && h.vsBaseline.minDetectable > 0 && (
                <p className="text-xs text-muted">With {h.heldOutQuestions} held-out questions only a difference of about {(h.vsBaseline.minDetectable * 100).toFixed(1)} points or more can be told apart from noise.</p>
              )}
              <p className="text-xs text-muted">Quality: {h.qualityDefinition}.</p>
              {h.mode === 'FULL' && <p className="flex items-start gap-2 text-xs text-warn"><AlertTriangle className="mt-px h-3.5 w-3.5 shrink-0" /> The answer scores rest on a language model that judges its own answers; see the calibration page before trusting them.</p>}
              {r.stoppedBy === 'SPEND_CAP' && <p className="flex items-start gap-2 text-xs text-warn"><AlertTriangle className="mt-px h-3.5 w-3.5 shrink-0" /> The search was cut short by its spend cap, so fewer configurations were compared than planned.</p>}
              {a.status === 'DONE' && isOwner && (
                <div className="flex flex-wrap items-center gap-3 pt-1">
                  <button className="btn-primary" onClick={() => promote.mutate()} disabled={promote.isPending || promote.isSuccess}>
                    {promote.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : promote.isSuccess ? <Check className="h-4 w-4" /> : <Rocket className="h-4 w-4" />} {promote.isSuccess ? 'Now the workspace default' : 'Promote the winner to workspace default'}
                  </button>
                  <span className="text-xs text-muted">Chat and search start from this configuration; new uploads are indexed for it.</span>
                  {promoteError && <span role="alert" className="text-sm text-red-500">{promoteError}</span>}
                </div>
              )}
            </Section>

            <Section title="Recommendations" hint="Three named picks from the configurations measured on the held-out questions.">
              <ul className="grid gap-3 sm:grid-cols-3">
                <PickCard title="Best quality" text="Highest held-out quality." c={r.recommendations.bestQuality ? byKey.get(r.recommendations.bestQuality) : undefined} />
                <PickCard title="Best value" text="Most quality per prompt token, among those within 10% of the best." c={r.recommendations.bestValue ? byKey.get(r.recommendations.bestValue) : undefined} />
                <PickCard title="Fastest within 3 points of the best" text="Quickest answer that gives up at most 3 points of quality." c={r.recommendations.fastest ? byKey.get(r.recommendations.fastest) : undefined} />
              </ul>
            </Section>

            <Section title="Quality against cost" hint="Each dot is a configuration. Up is better quality, left is fewer prompt tokens. The dashed line joins the ones nothing beats on quality, cost and speed together.">
              <ParetoPlot points={points} picks={r.recommendations} />
              <p className="flex flex-wrap gap-3 text-xs text-muted">
                <span><span className="mr-1 inline-block h-2 w-2 rounded-full bg-accent" />winner</span>
                <span><span className="mr-1 inline-block h-2 w-2 rounded-full bg-accent2" />finalist</span>
                <span><span className="mr-1 inline-block h-2 w-2 rounded-full bg-muted" />baseline</span>
              </p>
            </Section>

            <Section title="Finalists and the baseline" hint="On the held-out questions. The comparison with the baseline is paired, and corrected for the number of finalists compared.">
              <div className="overflow-x-auto">
                <table className="w-full min-w-[44rem] text-left text-sm">
                  <thead><tr className="text-xs text-muted"><th className="py-1.5 pr-3 font-medium">Configuration</th><th className="px-2 font-medium">Tuning</th><th className="px-2 font-medium">Held-out (95% CI)</th><th className="px-2 font-medium">Against the baseline</th><th className="px-2 font-medium">Tokens</th><th className="px-2 font-medium">Latency</th></tr></thead>
                  <tbody>
                    {r.candidates.map((c) => (
                      <tr key={c.key} className="border-t border-[var(--line)] align-top">
                        <th scope="row" className="py-2 pr-3 font-medium">
                          {c.label}
                          <span className="ml-2 chip">{c.role === 'WINNER' ? 'winner' : c.role === 'BASELINE' ? 'baseline' : 'finalist'}</span>{c.onFrontier && <span className="ml-1 chip" title="Nothing beats it on quality, cost and speed together">frontier</span>}
                        </th>
                        <td className="px-2 tabular-nums">{c.tuning ? pct(c.tuning.quality) : '–'}</td>
                        <td className="px-2 tabular-nums">{interval(c.heldOut)}</td>
                        <td className="px-2">{c.vsBaseline ? <span title={c.vsBaseline.verdict}>{formatDelta(c.vsBaseline.delta)} <span className="text-xs text-muted">p {c.vsBaseline.pAdjusted < 0.001 ? '< 0.001' : c.vsBaseline.pAdjusted.toFixed(3)}</span></span> : <span className="text-muted">–</span>}</td>
                        <td className="px-2 tabular-nums">{Math.round(c.heldOut.promptTokens)}</td>
                        <td className="px-2 tabular-nums">{Math.round(c.heldOut.latencyMs)} ms</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {a.experimentId && <p className="text-xs text-muted">Every question’s result, including where each failure was lost, is in <Link to={`/experiments/${a.experimentId}`} className="font-medium text-accent underline-offset-4 hover:underline">the measurements behind this run</Link>.</p>}
            </Section>

            {r.effects.length > 0 && (
              <Section title="What mattered" hint="Average context recall on the questions every candidate was scored on, for each value of each setting. The settings that moved the score most come first.">
                <ul className="grid gap-4 sm:grid-cols-2">
                  {effectGroups(r.effects).slice(0, showAll ? undefined : 6).map((g) => (
                    <li key={`${g.stage}-${g.dimension}`} className="rounded-xl border border-[var(--line)] p-3">
                      <p className="text-sm font-semibold">{g.dimension} <span className="text-xs font-normal text-muted">{g.spread >= 0.005 ? `moved the score by ${(g.spread * 100).toFixed(1)} points` : 'made little difference'}</span></p>
                      <ul className="mt-2 space-y-1">
                        {g.rows.map((row) => (
                          <li key={row.value} className="grid grid-cols-[7rem_1fr_3.5rem] items-center gap-2 text-xs">
                            <span className="truncate" title={row.value}>{row.value}</span>
                            <span className="h-2 overflow-hidden rounded-full bg-fg/10"><span className={`block h-full rounded-full ${row.best ? 'bg-accent' : 'bg-accent/40'}`} style={{ width: `${Math.max(2, row.averageQuality * 100)}%` }} /></span>
                            <span className="text-right tabular-nums text-muted">{pct(row.averageQuality)}</span>
                          </li>
                        ))}
                      </ul>
                    </li>
                  ))}
                </ul>
                {effectGroups(r.effects).length > 6 && <button className="btn-ghost text-xs" onClick={() => setShowAll(!showAll)}>{showAll ? 'Show fewer' : 'Show every setting'}</button>}
              </Section>
            )}

            <Section title="How the search went" hint="Successive halving: every candidate starts on a quarter of the tuning questions, the top third goes on to twice as many, and so on.">
              <div className="grid gap-4 sm:grid-cols-2">
                {([['Chunking and embeddings', r.stages.stage1], ['Retrieval settings', r.stages.stage2]] as const).map(([title, s]) => (
                  <div key={title}>
                    <p className="text-xs font-semibold">{title} · {s.candidates} candidates</p>
                    <ul className="mt-1 space-y-0.5 text-xs text-muted">{s.rounds.map((x, i) => <li key={i}>Round {i + 1}: {x.entered} candidates on {x.sample} questions, {x.kept} went on</li>)}</ul>
                  </div>
                ))}
              </div>
            </Section>
          </>
        )}
      </div>
    </div>
  )
}
