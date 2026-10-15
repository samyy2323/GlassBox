import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { FlaskConical, ScanSearch } from 'lucide-react'
import { Link, useNavigate } from 'react-router-dom'
import { api, type Experiment, type Recommendation } from '../lib/api'
import { followUpConfigs, STAGE_INFO, stageBars, stageTypeMatrix, SUB_LABEL } from '../lib/diagnosis'
import { QUESTION_TYPES } from '../lib/experiments'
import { queryKeys } from '../lib/queryKeys'
import { TYPE_LABEL } from '../lib/testsets'

/** Why the questions a run missed were missed: failures by pipeline stage, by stage and question type, and what to try about it. */
export function Diagnosis({ e, canEdit }: { e: Experiment; canEdit: boolean }) {
  const done = e.runs.filter((r) => r.status === 'DONE')
  const [runId, setRunId] = useState(done[0]?.id ?? '')
  const navigate = useNavigate()
  const report = useQuery({ queryKey: queryKeys.diagnosis(e.id, runId), queryFn: () => api.diagnosis(e.id, runId), enabled: !!runId })
  const d = report.data
  if (done.length === 0) return <p className="text-sm text-muted">No finished run to diagnose.</p>

  const tryIt = (rec: Recommendation) => {
    if (!d) return
    const configs = followUpConfigs(d, rec)
    if (!configs) return
    navigate('/experiments/new', { state: { testsetId: e.testsetId, mode: e.mode, name: `${d.runLabel}: ${rec.changeLabel ?? 'one change'}`, configs } })
  }

  const bars = d ? stageBars(d.byStage) : []
  const matrix = d ? stageTypeMatrix(d.byStageAndType, QUESTION_TYPES) : { types: [], rows: [] }
  return (
    <div className="space-y-5">
      <label className="flex items-center gap-2 text-xs text-muted">Configuration
        <select className="input-glass !w-auto !py-1 text-sm" value={runId} onChange={(ev) => setRunId(ev.target.value)}>{done.map((r) => <option key={r.id} value={r.id}>{r.label}</option>)}</select>
      </label>
      {report.isLoading && <div className="skeleton h-24" />}
      {report.isError && <p role="alert" className="text-sm text-red-500">Could not load the report.</p>}
      {d && d.failed === 0 && <p className="text-sm">No failures: every one of the {d.questions} questions passed in this run.</p>}
      {d && d.failed > 0 && (
        <>
          <p className="text-sm"><span className="font-semibold">{d.failed} of {d.questions}</span> questions failed. Each is labelled with the first stage of the pipeline that lost it.</p>

          <div>
            <h3 className="mb-2 text-xs font-semibold">Failures by stage</h3>
            <ul className="space-y-1.5" aria-label="Failures by stage">
              {bars.map((b) => (
                <li key={b.stage} className="grid grid-cols-[8rem_1fr_5rem] items-center gap-2 text-sm" title={STAGE_INFO[b.stage].hint}>
                  <span>{STAGE_INFO[b.stage].label}</span>
                  <span className="h-3 overflow-hidden rounded-full bg-fg/10"><span className="block h-full rounded-full bg-accent" style={{ width: `${Math.max(3, b.width * 100)}%` }} /></span>
                  <span className="text-xs tabular-nums text-muted">{b.count} · {Math.round(b.share * 100)}%</span>
                </li>
              ))}
            </ul>
          </div>

          {matrix.rows.length > 0 && (
            <div className="overflow-x-auto">
              <h3 className="mb-2 text-xs font-semibold">Stage by question type</h3>
              <table className="w-full min-w-[26rem] text-left text-sm">
                <thead><tr className="text-xs text-muted"><th className="py-1.5 pr-3 font-medium">Stage</th>{matrix.types.map((t) => <th key={t} className="px-2 font-medium">{TYPE_LABEL[t as keyof typeof TYPE_LABEL]}</th>)}<th className="px-2 font-medium">All</th></tr></thead>
                <tbody>
                  {matrix.rows.map((r) => (
                    <tr key={r.stage} className="border-t border-[var(--line)]">
                      <th scope="row" className="py-1.5 pr-3 font-medium" title={STAGE_INFO[r.stage].hint}>{STAGE_INFO[r.stage].label}</th>
                      {r.cells.map((c) => <td key={c.qtype} className={`px-2 tabular-nums ${c.count === 0 ? 'text-muted/50' : ''}`}>{c.count}</td>)}
                      <td className="px-2 font-semibold tabular-nums">{r.total}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {d.recommendations.length > 0 && (
            <div>
              <h3 className="mb-2 text-xs font-semibold">What to try</h3>
              <ul className="space-y-2">
                {d.recommendations.map((rec) => (
                  <li key={`${rec.stage}-${rec.sub ?? ''}`} className="flex flex-wrap items-center gap-3 rounded-xl border border-[var(--line)] p-3">
                    <p className="min-w-0 flex-1 text-sm">{rec.text}{rec.sub && <span className="text-muted"> ({SUB_LABEL[rec.sub] ?? rec.sub.toLowerCase()})</span>}</p>
                    {rec.change && canEdit && (
                      <button className="btn-ghost shrink-0 !border !border-[var(--line)]" onClick={() => tryIt(rec)} title="Opens a new experiment: this run as it was against this run with that one change">
                        <FlaskConical className="h-4 w-4" /> {rec.changeLabel ?? 'Try it'}
                      </button>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          )}

          <details>
            <summary className="cursor-pointer text-xs font-semibold">The {d.failures.length} failed questions</summary>
            <ul className="mt-2 space-y-1.5">
              {d.failures.map((f) => (
                <li key={f.questionId} className="flex items-start gap-2 rounded-lg border border-[var(--line)] p-2.5 text-sm">
                  <span className="min-w-0 flex-1">
                    <span className="block">{f.question}</span>
                    <span className="text-xs text-muted">{STAGE_INFO[f.stage].label}{f.sub ? `: ${SUB_LABEL[f.sub] ?? f.sub}` : ''}{f.lostAt ? ` · the missing part was lost at ${STAGE_INFO[f.lostAt].label.toLowerCase()}` : ''} · {f.qtype.toLowerCase().replace('_', '-')}</span>
                  </span>
                  {f.traceId && <Link to={`/xray/${f.traceId}`} className="btn-ghost shrink-0 !px-2 text-xs"><ScanSearch className="h-3.5 w-3.5" /> X-Ray</Link>}
                </li>
              ))}
            </ul>
          </details>
        </>
      )}
    </div>
  )
}
