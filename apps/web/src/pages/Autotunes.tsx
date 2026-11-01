import { useQuery } from '@tanstack/react-query'
import { Plus, Wand2 } from 'lucide-react'
import { Link } from 'react-router-dom'
import { api, type AutotuneSummary } from '../lib/api'
import { isActive, OBJECTIVE_LABEL, STEPS } from '../lib/autotune'
import { queryKeys } from '../lib/queryKeys'
import { useWorkspace } from '../lib/workspace'
import { ExperimentStatusChip } from './Experiments'

function Card({ a }: { a: AutotuneSummary }) {
  const step = STEPS.find((s) => s.id === a.stage)
  return (
    <li className="card glass-interactive animate-rise">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <Link to={`/autotune/${a.id}`} className="block truncate display text-[1.5rem] leading-tight hover:text-accent after:absolute after:inset-0 after:content-['']">{a.name}</Link>
          <p className="mt-0.5 text-xs text-muted">{a.testsetName} · {OBJECTIVE_LABEL[a.objective].title} · {new Date(a.createdAt).toLocaleDateString()}</p>
        </div>
        <ExperimentStatusChip status={a.status} />
      </div>
      {isActive(a.status) && step && <p className="mt-3 text-sm text-muted" aria-live="polite">Now: {step.label}. {step.text}</p>}
      {a.status === 'DONE' && a.headline && <p className="mt-3 text-sm">{a.headline}</p>}
    </li>
  )
}

export default function Autotunes() {
  const { current, loading } = useWorkspace()
  const canEdit = current != null && current.role !== 'VIEWER'
  const list = useQuery({
    queryKey: queryKeys.autotunes(current?.id),
    queryFn: api.autotunes,
    enabled: !!current,
    refetchInterval: (q) => (q.state.data?.some((a) => isActive(a.status)) ? 3000 : false),
  })

  if (loading || list.isLoading) return <div className="mx-auto w-full max-w-4xl space-y-4 p-6" aria-label="Loading AutoTune"><div className="skeleton h-10 w-60" /><div className="skeleton h-32" /></div>
  if (!current) return <p className="p-6 text-muted">You are not a member of any workspace yet.</p>
  if (list.isError) return <p role="alert" className="p-6 text-danger">Could not load AutoTune runs.</p>
  const items = list.data ?? []

  return (
    <div className="min-h-0 flex-1 overflow-y-auto">
      <div className="mx-auto w-full max-w-4xl space-y-6 p-4 sm:p-6">
        <header className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <h1 className="text-[2.6rem] leading-[1.05]">AutoTune</h1>
            <p className="mt-1 text-sm text-muted">Search chunking, embeddings and retrieval settings, then confirm the winner on questions it never saw.</p>
          </div>
          {canEdit && <Link to="/autotune/new" className="btn-primary"><Plus className="h-[18px] w-[18px]" /> New AutoTune</Link>}
        </header>
        {items.length === 0 ? (
          <div className="glass flex flex-col items-center gap-3 rounded-3xl border-2 !border-dashed px-6 py-16 text-center animate-fade-up">
            <span className="grid h-14 w-14 place-items-center rounded-2xl bg-accent/15 text-accent"><Wand2 className="h-7 w-7" /></span>
            <h2 className="text-[1.6rem] leading-tight">No AutoTune runs yet</h2>
            <p className="max-w-md text-sm text-muted">{canEdit ? 'Pick a final test set and a budget. The test set is split: choices are made on 70% of the questions, and the result is confirmed on the other 30%.' : 'You have read-only access to this workspace.'}</p>
          </div>
        ) : (
          <ul className="space-y-4">{items.map((a) => <Card key={a.id} a={a} />)}</ul>
        )}
      </div>
    </div>
  )
}
