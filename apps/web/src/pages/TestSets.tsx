import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ListChecks, Plus } from 'lucide-react'
import { Link, useNavigate } from 'react-router-dom'
import { GenerateDialog } from '../components/GenerateDialog'
import { api, type TestSet, type TestSetStatus } from '../lib/api'
import { queryKeys } from '../lib/queryKeys'
import { groupVersions, percent, progressPercent, STATUS_LABEL } from '../lib/testsets'
import { useWorkspace } from '../lib/workspace'

const DOT: Record<TestSetStatus, string> = {
  GENERATING: 'bg-accent animate-pulse',
  DRAFT: 'bg-warn',
  FINAL: 'bg-ok',
  FAILED: 'bg-red-500',
}

export function SetStatus({ status }: { status: TestSetStatus }) {
  return (
    <span className="chip">
      <span className={`h-1.5 w-1.5 rounded-full ${DOT[status]}`} aria-hidden />
      {STATUS_LABEL[status]}
    </span>
  )
}

function GroupCard({ name, versions }: { name: string; versions: TestSet[] }) {
  const latest = versions[0]
  const s = latest.stats
  return (
    <li className="glass rounded-2xl p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <Link to={`/testsets/${latest.id}/review`} className="block truncate text-[1.05rem] font-semibold hover:underline" title={name}>{name}</Link>
          <p className="mt-0.5 text-xs text-muted">Version {latest.version} · created {new Date(latest.createdAt).toLocaleDateString()}</p>
        </div>
        <SetStatus status={latest.status} />
      </div>

      {latest.status === 'GENERATING' ? (
        <div className="mt-4 space-y-1.5" aria-live="polite">
          <div className="h-1.5 overflow-hidden rounded-full bg-fg/10" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progressPercent(latest)}>
            <div className="h-full rounded-full bg-accent transition-all" style={{ width: `${progressPercent(latest)}%` }} />
          </div>
          <p className="text-xs text-muted">{latest.generatedCount} of {latest.targetCount} questions written</p>
        </div>
      ) : latest.status === 'FAILED' ? (
        <p className="mt-4 text-sm text-red-500">{latest.error ?? 'Generation failed.'}</p>
      ) : (
        <dl className="mt-4 grid grid-cols-2 gap-3 text-sm sm:grid-cols-4">
          <div><dt className="eyebrow">Questions</dt><dd className="mt-0.5 font-semibold">{s.total}</dd></div>
          <div><dt className="eyebrow">Accepted</dt><dd className="mt-0.5 font-semibold text-ok">{s.accepted + s.edited}</dd></div>
          <div><dt className="eyebrow">To review</dt><dd className="mt-0.5 font-semibold text-warn">{s.pending}</dd></div>
          <div><dt className="eyebrow">Acceptance</dt><dd className="mt-0.5 font-semibold">{percent(s.acceptanceRate)}</dd></div>
        </dl>
      )}

      <div className="mt-4 flex flex-wrap items-center gap-x-4 gap-y-2 border-t border-[var(--line)] pt-3 text-sm">
        <Link to={`/testsets/${latest.id}/review`} className="font-medium text-accent underline-offset-4 hover:underline">
          {latest.status === 'DRAFT' ? 'Review questions →' : 'Open →'}
        </Link>
        {versions.length > 1 && (
          <span className="flex flex-wrap items-center gap-2 text-xs text-muted">
            Other versions:
            {versions.slice(1).map((v) => (
              <Link key={v.id} to={`/testsets/${v.id}/review`} className="rounded-full border border-[var(--line)] px-2 py-0.5 hover:text-accent">v{v.version} · {STATUS_LABEL[v.status]}</Link>
            ))}
          </span>
        )}
      </div>
    </li>
  )
}

export default function TestSets() {
  const { current, loading } = useWorkspace()
  const navigate = useNavigate()
  const [dialog, setDialog] = useState(false)
  const canEdit = current != null && current.role !== 'VIEWER'

  const sets = useQuery({
    queryKey: queryKeys.testsets(current?.id),
    queryFn: api.testsets,
    enabled: !!current,
    refetchInterval: (q) => (q.state.data?.some((t) => t.status === 'GENERATING') ? 3000 : false),
  })
  const groups = useMemo(() => groupVersions(sets.data ?? []), [sets.data])

  if (loading || sets.isLoading) {
    return (
      <div className="mx-auto w-full max-w-4xl space-y-4 p-6" aria-label="Loading test sets">
        <div className="skeleton h-10 w-60" />
        {[0, 1].map((i) => <div key={i} className="skeleton h-36" />)}
      </div>
    )
  }
  if (!current) return <p className="p-6 text-muted">You are not a member of any workspace yet.</p>
  if (sets.isError) return <p role="alert" className="p-6 text-red-500">Could not load test sets.</p>

  return (
    <div className="min-h-0 flex-1 overflow-y-auto">
      <div className="mx-auto w-full max-w-4xl space-y-6 p-4 sm:p-6">
        <header className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <h1 className="text-[1.65rem] font-semibold tracking-tight">Test sets</h1>
            <p className="mt-1 text-sm text-muted">Questions with known answers and exact evidence, written from your documents and checked by you.</p>
          </div>
          {canEdit && <button className="btn-primary" onClick={() => setDialog(true)}><Plus className="h-[18px] w-[18px]" /> Generate test set</button>}
        </header>

        {groups.length === 0 ? (
          <div className="glass flex flex-col items-center gap-3 rounded-3xl border-2 !border-dashed px-6 py-16 text-center animate-fade-up">
            <span className="grid h-14 w-14 place-items-center rounded-2xl bg-accent/15 text-accent"><ListChecks className="h-7 w-7" /></span>
            <h2 className="text-lg font-semibold">No test sets yet</h2>
            <p className="max-w-md text-sm text-muted">
              {canEdit ? 'Generate one from the documents in your library. You will review every question before it is used to measure anything.' : 'You have read-only access to this workspace.'}
            </p>
          </div>
        ) : (
          <ul className="space-y-4">{groups.map((g) => <GroupCard key={g.name} name={g.name} versions={g.versions} />)}</ul>
        )}
      </div>
      {dialog && (
        <GenerateDialog
          existingNames={groups.map((g) => g.name)}
          onClose={() => setDialog(false)}
          onCreated={(t) => {
            void sets.refetch()
            navigate(`/testsets/${t.id}/review`)
          }}
        />
      )}
    </div>
  )
}
