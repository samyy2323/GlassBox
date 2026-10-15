import { useQuery } from '@tanstack/react-query'
import { FlaskConical, Plus } from 'lucide-react'
import { Link } from 'react-router-dom'
import { api, type Experiment, type ExperimentStatus } from '../lib/api'
import { findMetric, formatMetric, HEADLINE, isActive, metricLabel, runProgress } from '../lib/experiments'
import { queryKeys } from '../lib/queryKeys'
import { useWorkspace } from '../lib/workspace'

const DOT: Record<ExperimentStatus, string> = { QUEUED: 'bg-muted', RUNNING: 'bg-accent animate-pulse', DONE: 'bg-ok', FAILED: 'bg-red-500', CANCELLED: 'bg-muted', ABORTED: 'bg-warn' }
const LABEL: Record<ExperimentStatus, string> = { QUEUED: 'Queued', RUNNING: 'Running', DONE: 'Done', FAILED: 'Failed', CANCELLED: 'Cancelled', ABORTED: 'Stopped by budget' }

export function ExperimentStatusChip({ status }: { status: ExperimentStatus }) {
  return (
    <span className="chip">
      <span className={`h-1.5 w-1.5 rounded-full ${DOT[status]}`} aria-hidden />
      {LABEL[status]}
    </span>
  )
}

function Card({ e }: { e: Experiment }) {
  const headline = HEADLINE[e.mode]
  const best = e.status === 'DONE' ? [...e.runs].sort((a, b) => (findMetric(b.metrics, headline)?.value ?? 0) - (findMetric(a.metrics, headline)?.value ?? 0))[0] : undefined
  const progress = e.runs.length === 0 ? 0 : Math.round(e.runs.reduce((sum, r) => sum + runProgress(r, e.questionCount), 0) / e.runs.length)
  return (
    <li className="glass rounded-2xl p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <Link to={`/experiments/${e.id}`} className="block truncate text-[1.05rem] font-semibold hover:underline">{e.name}</Link>
          <p className="mt-0.5 text-xs text-muted">
            {e.testsetName} v{e.testsetVersion} · {e.mode === 'FULL' ? 'full answers and judging' : 'retrieval only'} · {e.runs.length} configuration{e.runs.length === 1 ? '' : 's'} · {e.questionCount} questions · {new Date(e.createdAt).toLocaleDateString()}
          </p>
        </div>
        <ExperimentStatusChip status={e.status} />
      </div>
      {isActive(e.status) && (
        <div className="mt-4 space-y-1.5" aria-live="polite">
          <div className="h-1.5 overflow-hidden rounded-full bg-fg/10" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress}>
            <div className="h-full rounded-full bg-accent transition-all" style={{ width: `${progress}%` }} />
          </div>
        </div>
      )}
      {e.status === 'FAILED' && <p className="mt-3 text-sm text-red-500">{e.error}</p>}
      {best && (
        <p className="mt-3 text-sm text-muted">
          Best on {metricLabel(headline).toLowerCase()}: <span className="font-semibold text-fg">{best.label}</span> at {formatMetric(headline, findMetric(best.metrics, headline)?.value)}
        </p>
      )}
    </li>
  )
}

export default function Experiments() {
  const { current, loading } = useWorkspace()
  const canEdit = current != null && current.role !== 'VIEWER'
  const list = useQuery({
    queryKey: queryKeys.experiments(current?.id),
    queryFn: api.experiments,
    enabled: !!current,
    refetchInterval: (q) => (q.state.data?.some((e) => isActive(e.status)) ? 3000 : false),
  })

  if (loading || list.isLoading) return <div className="mx-auto w-full max-w-4xl space-y-4 p-6" aria-label="Loading experiments"><div className="skeleton h-10 w-60" /><div className="skeleton h-32" /></div>
  if (!current) return <p className="p-6 text-muted">You are not a member of any workspace yet.</p>
  if (list.isError) return <p role="alert" className="p-6 text-red-500">Could not load experiments.</p>
  const items = list.data ?? []

  return (
    <div className="min-h-0 flex-1 overflow-y-auto">
      <div className="mx-auto w-full max-w-4xl space-y-6 p-4 sm:p-6">
        <header className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <h1 className="text-[1.65rem] font-semibold tracking-tight">Experiments</h1>
            <p className="mt-1 text-sm text-muted">Measure pipeline configurations on a final test set, and see whether a difference is real.</p>
          </div>
          {canEdit && <Link to="/experiments/new" className="btn-primary"><Plus className="h-[18px] w-[18px]" /> New experiment</Link>}
        </header>
        {items.length === 0 ? (
          <div className="glass flex flex-col items-center gap-3 rounded-3xl border-2 !border-dashed px-6 py-16 text-center animate-fade-up">
            <span className="grid h-14 w-14 place-items-center rounded-2xl bg-accent/15 text-accent"><FlaskConical className="h-7 w-7" /></span>
            <h2 className="text-lg font-semibold">No experiments yet</h2>
            <p className="max-w-md text-sm text-muted">{canEdit ? 'Pick a final test set and a few configurations, for example vector against hybrid search.' : 'You have read-only access to this workspace.'}</p>
          </div>
        ) : (
          <ul className="space-y-4">{items.map((e) => <Card key={e.id} e={e} />)}</ul>
        )}
      </div>
    </div>
  )
}
