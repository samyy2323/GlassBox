import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { EyeOff, Loader2, Scale } from 'lucide-react'
import { Link } from 'react-router-dom'
import { api, ApiError, type CalibrationMetric, type CalibrationSample, type JudgedMetric } from '../lib/api'
import { confusionCell, JUDGED, kappaWords, labelsPayload, METRIC_TEXT, nextUnlabeled, optionForKey, optionText, statusLine } from '../lib/calibration'
import { isTypingTarget } from '../lib/review'
import { queryKeys } from '../lib/queryKeys'
import { useWorkspace } from '../lib/workspace'

const STATUS_TONE = { calibrated: 'bg-ok', uncalibrated: 'bg-warn', unknown: 'bg-muted' } as const

function Confusion({ m }: { m: CalibrationMetric }) {
  if (m.n === 0) return null
  return (
    <div className="space-y-2">
      <div className="overflow-x-auto">
        <table className="text-center text-sm">
          <caption className="mb-1 text-left text-xs text-muted">Rows: what a person said. Columns: what the judge said.</caption>
          <thead><tr><th className="p-1" />{m.labels.map((l) => <th key={l} scope="col" className="px-3 py-1 text-xs font-medium text-muted">{optionText(l)}</th>)}</tr></thead>
          <tbody>
            {m.labels.map((row, r) => {
              const total = m.confusion[r].reduce((a, b) => a + b, 0)
              return (
                <tr key={row}>
                  <th scope="row" className="pr-3 text-right text-xs font-medium text-muted">{optionText(row)}</th>
                  {m.labels.map((_, c) => {
                    const cell = confusionCell(r, c, m.confusion[r][c], total)
                    return (
                      <td key={c} className="h-11 w-16 rounded-lg tabular-nums" style={{ background: cell.opacity ? `rgb(${cell.tone === 'agree' ? 'var(--ok-rgb)' : '239 68 68'} / ${cell.opacity})` : 'transparent' }}>
                        {m.confusion[r][c]}
                      </td>
                    )
                  })}
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <table className="text-sm">
        <thead><tr className="text-xs text-muted"><th className="pr-4 text-left font-medium">Judge said</th><th className="pr-4 text-right font-medium" title="Of what the judge said, how much a person agreed">Precision</th><th className="text-right font-medium" title="Of what a person said, how much the judge found">Recall</th></tr></thead>
        <tbody>
          {m.labels.map((l) => (
            <tr key={l}><th scope="row" className="pr-4 text-left font-medium">{optionText(l)}</th><td className="pr-4 text-right tabular-nums">{m.precision[l] == null ? '–' : `${Math.round(m.precision[l] * 100)}%`}</td><td className="text-right tabular-nums">{m.recall[l] == null ? '–' : `${Math.round(m.recall[l] * 100)}%`}</td></tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function Labeller({ sample, labels, setLabels, onSave, saving, error }: { sample: CalibrationSample; labels: Record<string, string>; setLabels: (l: Record<string, string>) => void; onSave: () => void; saving: boolean; error: string | null }) {
  const [index, setIndex] = useState(0)
  const item = sample.items[index]
  const done = Object.keys(labels).length

  function choose(option: string) {
    if (!item) return
    const next = { ...labels, [item.labelId]: option }
    setLabels(next)
    const after = nextUnlabeled(sample.items, next, index)
    if (after >= 0) setIndex(after)
  }

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (isTypingTarget(e.target) || e.ctrlKey || e.metaKey || e.altKey || !item) return
      const option = optionForKey(e.key, item.options)
      if (option) {
        e.preventDefault()
        choose(option)
      } else if (e.key === 'ArrowRight' || e.key === 'j') setIndex((i) => Math.min(sample.items.length - 1, i + 1))
      else if (e.key === 'ArrowLeft' || e.key === 'k') setIndex((i) => Math.max(0, i - 1))
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  if (!item) return null
  const block = (label: string, text: string | undefined) => text == null ? null : (
    <div><p className="eyebrow">{label}</p><p className="mt-1 whitespace-pre-wrap text-[0.95rem] leading-relaxed">{text}</p></div>
  )
  return (
    <section className="glass space-y-4 rounded-2xl p-5" aria-label="Label this item">
      <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-muted">
        <span>Item {index + 1} of {sample.items.length} · {done} labelled</span>
        <span className="flex items-center gap-1.5"><EyeOff className="h-3.5 w-3.5" /> The judge's verdict is hidden from you</span>
      </div>
      <div className="h-1.5 overflow-hidden rounded-full bg-fg/10"><div className="h-full rounded-full bg-accent transition-all" style={{ width: `${(done / sample.items.length) * 100}%` }} /></div>
      <p className="text-sm font-medium">{METRIC_TEXT[item.metric].task}</p>
      <div className="space-y-3">
        {block('Question', item.question)}
        {block('Expected answer', item.expectedAnswer)}
        {block('Answer', item.answer)}
        {block('Statement', item.claim)}
        {block('Passage it was checked against', item.evidence)}
      </div>
      <div className="flex flex-wrap gap-2" role="group" aria-label="Your verdict">
        {item.options.map((o, i) => (
          <button key={o} type="button" onClick={() => choose(o)} aria-pressed={labels[item.labelId] === o}
            className={`btn-ghost !border !px-4 !py-2 ${labels[item.labelId] === o ? '!border-accent !bg-accent/10 !text-accent' : '!border-[var(--line)]'}`}>
            {optionText(o)} <span className="kbd">{i + 1}</span>
          </button>
        ))}
      </div>
      <div className="flex flex-wrap items-center justify-between gap-2 border-t border-[var(--line)] pt-3">
        <span className="text-xs text-muted">Keys: 1 2 3 to choose, J / K to move</span>
        {error && <span role="alert" className="text-sm text-red-500">{error}</span>}
        <button className="btn-primary" disabled={done === 0 || saving} onClick={onSave}>{saving && <Loader2 className="h-4 w-4 animate-spin" />} Save {done} label{done === 1 ? '' : 's'}</button>
      </div>
    </section>
  )
}

export default function Calibration() {
  const { current } = useWorkspace()
  const qc = useQueryClient()
  const canEdit = current != null && current.role !== 'VIEWER'
  const summary = useQuery({ queryKey: queryKeys.calibration(current?.id), queryFn: api.calibrationSummary, enabled: !!current })
  const [metric, setMetric] = useState<JudgedMetric>('correctness')
  const [sample, setSample] = useState<CalibrationSample | null>(null)
  const [labels, setLabels] = useState<Record<string, string>>({})

  const draw = useMutation({ mutationFn: () => api.calibrationSample(metric, 40), onSuccess: (s) => { setSample(s); setLabels({}) } })
  const save = useMutation({
    mutationFn: () => api.calibrationLabels(labelsPayload(sample!.items, labels)),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: queryKeys.calibration(current?.id) })
      void qc.invalidateQueries({ queryKey: ['experiment'] })
      setSample(null)
      setLabels({})
    },
  })
  const m = summary.data?.metrics.find((x) => x.metric === metric)
  const s = summary.data
  const error = (e: unknown, fallback: string) => (e instanceof ApiError ? e.message : e ? fallback : null)

  return (
    <div className="min-h-0 flex-1 overflow-y-auto">
      <div className="mx-auto w-full max-w-4xl space-y-5 p-4 sm:p-6">
        <header>
          <h1 className="text-[1.65rem] font-semibold tracking-tight">Judge calibration</h1>
          <p className="mt-1 text-sm text-muted">A language model grades the answers. Here you check its grades against yours, without seeing them first. Until the two agree well, every number it produced carries an “uncalibrated” badge.</p>
        </header>

        {summary.isLoading && <div className="skeleton h-40" />}
        {summary.isError && <p role="alert" className="text-sm text-red-500">Could not load the calibration summary.</p>}
        {s && (
          <>
            <div className="flex flex-wrap gap-2" role="tablist" aria-label="What to calibrate">
              {JUDGED.map((j) => {
                const x = s.metrics.find((mm) => mm.metric === j)
                return (
                  <button key={j} role="tab" aria-selected={metric === j} onClick={() => { setMetric(j); setSample(null); setLabels({}) }}
                    className={`btn-ghost !border ${metric === j ? '!border-accent !bg-accent/10 !text-accent' : '!border-[var(--line)]'}`}>
                    <span className={`h-1.5 w-1.5 rounded-full ${STATUS_TONE[x?.status ?? 'unknown']}`} aria-hidden /> {METRIC_TEXT[j].title}
                  </button>
                )
              })}
            </div>

            {m && (
              <section className="glass space-y-4 rounded-2xl p-5" aria-label="Agreement so far">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div>
                    <h2 className="text-sm font-semibold">{METRIC_TEXT[metric].title}: {m.judgeModel}, rubric {m.rubricVersion}</h2>
                    <p className="mt-0.5 text-sm text-muted" data-testid="calibration-status">{statusLine(m, s.minimumLabels, s.trustedKappa)}</p>
                  </div>
                  <span className="chip"><span className={`h-1.5 w-1.5 rounded-full ${STATUS_TONE[m.status]}`} aria-hidden />{m.status}</span>
                </div>
                <dl className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-4">
                  <div><dt className="eyebrow">Labels</dt><dd className="mt-0.5 font-semibold">{m.n} <span className="font-normal text-muted">of {s.minimumLabels}+</span></dd></div>
                  <div><dt className="eyebrow">Agreement</dt><dd className="mt-0.5 font-semibold">{m.agreement == null ? '–' : `${Math.round(m.agreement * 100)}%`}</dd></div>
                  <div><dt className="eyebrow" title="Agreement beyond what chance would give. 0 is chance, 1 is perfect; 0.6 is the bar.">Kappa</dt><dd className="mt-0.5 font-semibold" data-testid="kappa">{m.kappa == null ? '–' : m.kappa.toFixed(2)}</dd></div>
                  <div><dt className="eyebrow">Reading</dt><dd className="mt-0.5 text-sm">{kappaWords(m.kappa ?? undefined)}</dd></div>
                </dl>
                <Confusion m={m} />
                <p className="text-xs text-muted">{m.unlabeled} graded item{m.unlabeled === 1 ? '' : 's'} are waiting to be labelled.</p>
              </section>
            )}

            {s.warnings.map((w) => <p key={w} className="text-xs text-warn">{w}</p>)}

            {canEdit && !sample && (
              <section className="glass flex flex-wrap items-center justify-between gap-3 rounded-2xl p-5">
                <p className="max-w-lg text-sm text-muted">Draw up to 40 graded items, balanced across the judge's verdicts so that rare verdicts are checked too. You label them one by one.</p>
                <button className="btn-primary" onClick={() => draw.mutate()} disabled={draw.isPending}>{draw.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Scale className="h-4 w-4" />} Get a sample</button>
              </section>
            )}
            {draw.isError && <p role="alert" className="text-sm text-red-500">{error(draw.error, 'Could not draw a sample.')}</p>}
            {sample && sample.items.length === 0 && (
              <p className="glass rounded-2xl p-5 text-sm text-muted">There is nothing to label yet. Run a <Link to="/experiments/new" className="font-medium text-accent underline-offset-4 hover:underline">full experiment</Link> first: it produces the answers and grades that are checked here.</p>
            )}
            {sample && sample.items.length > 0 && (
              <Labeller sample={sample} labels={labels} setLabels={setLabels} onSave={() => save.mutate()} saving={save.isPending} error={error(save.error, 'Could not save the labels.')} />
            )}
            {!canEdit && <p className="text-xs text-muted">You have read-only access to this workspace, so you can see the calibration but not label.</p>}
          </>
        )}
      </div>
    </div>
  )
}
