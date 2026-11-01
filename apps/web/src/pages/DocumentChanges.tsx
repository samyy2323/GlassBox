import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ChevronLeft } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'
import { api, type DocumentImpact } from '../lib/api'
import { formatDelta } from '../lib/experiments'
import { queryKeys } from '../lib/queryKeys'
import { useWorkspace } from '../lib/workspace'

const KIND = { SAME: '', ADDED: 'border-l-4 border-ok bg-ok/10', REMOVED: 'border-l-4 border-danger bg-danger/10' } as const
const MARK = { SAME: '  ', ADDED: '+ ', REMOVED: '- ' } as const

function Impact({ r }: { r: DocumentImpact }) {
  const v = r.verdict
  return (
    <div className="space-y-3">
      {r.status === 'PENDING' && <p className="text-sm text-muted">{r.note}</p>}
      {(r.status === 'SKIPPED' || r.status === 'NO_QUESTIONS' || r.status === 'FAILED') && <p className="text-sm">{r.note}</p>}
      {r.status === 'DONE' && (
        <>
          <p className="text-sm">
            In the test set, <span className="font-semibold">{r.relocated}</span> question{r.relocated === 1 ? '' : 's'} about this document kept their evidence (found again in the new text) and{' '}
            <span className={`font-semibold ${r.stale > 0 ? 'text-warn' : ''}`}>{r.stale}</span> could not be found and {r.stale === 1 ? 'is' : 'are'} marked stale, waiting for review.
            {r.testsetId && <> The new version is <Link className="font-medium text-accent underline-offset-4 hover:underline" to={`/testsets/${r.testsetId}/review`}>v{r.testsetVersion} of the test set</Link>.</>}
          </p>
          {r.note && <p className="text-xs text-muted">{r.note}</p>}
          {r.experimentStatus && r.experimentStatus !== 'DONE' && <p className="text-sm text-muted" aria-live="polite">The earlier measurement is being re-run ({r.experimentStatus.toLowerCase()}).</p>}
          {v ? (
            <div className="rounded-xl border border-[var(--line)] p-3">
              <p className="font-semibold" data-testid="impact-verdict">{v.text}</p>
              <p className="mt-1 text-xs text-muted">Evidence in the prompt: {Math.round(v.before * 1000) / 10}% before, {Math.round(v.after * 1000) / 10}% after ({formatDelta(v.delta)}) on {v.questions} questions that both runs measured.</p>
            </div>
          ) : r.experimentStatus === 'DONE' && <p className="text-sm text-muted">There was nothing to compare with.</p>}
          {r.passToFail.length > 0 && (
            <div>
              <h3 className="mb-1.5 text-xs font-semibold">Questions that now fail ({r.passToFail.length})</h3>
              <ul className="space-y-1.5">{r.passToFail.map((f) => <li key={f.questionId} className="rounded-lg border border-[var(--line)] p-2.5 text-sm">{f.question}<span className="block text-xs text-muted">{f.failureStage ? `lost at ${f.failureStage.toLowerCase().replace('_', ' ')}` : 'evidence no longer reaches the prompt'}</span></li>)}</ul>
            </div>
          )}
          {r.failToPass > 0 && <p className="text-xs text-muted">{r.failToPass} question{r.failToPass === 1 ? '' : 's'} went the other way, from failing to passing.</p>}
          {r.staleQuestions.length > 0 && (
            <details>
              <summary className="cursor-pointer text-xs font-semibold">The {r.staleQuestions.length} stale question{r.staleQuestions.length === 1 ? '' : 's'}</summary>
              <ul className="mt-1.5 space-y-1 text-sm">{r.staleQuestions.map((q) => <li key={q.questionId}>{q.question}</li>)}</ul>
            </details>
          )}
        </>
      )}
    </div>
  )
}

export default function DocumentChanges() {
  const { id = '' } = useParams()
  const { current } = useWorkspace()
  const versions = useQuery({ queryKey: queryKeys.documentVersions(id), queryFn: () => api.documentVersions(id), enabled: !!current })
  const [pick, setPick] = useState<number | null>(null)
  const list = versions.data ?? []
  const chosen = pick ?? (list.find((v) => v.version > 1)?.version ?? null)
  const impact = useQuery({
    queryKey: queryKeys.documentImpact(id, chosen ?? 0), queryFn: () => api.documentImpact(id, chosen!), enabled: chosen != null,
    refetchInterval: (q) => (q.state.data && (q.state.data.status === 'PENDING' || (q.state.data.status === 'DONE' && q.state.data.experimentStatus !== 'DONE')) ? 3000 : false),
  })
  const r = impact.data

  return (
    <div className="min-h-0 flex-1 overflow-y-auto">
      <div className="mx-auto w-full max-w-4xl space-y-5 p-4 sm:p-6">
        <header className="flex items-center gap-3">
          <Link to="/documents" className="btn-ghost !px-2" aria-label="Library"><ChevronLeft className="h-4 w-4" /></Link>
          <div>
            <h1 className="text-[2rem] leading-tight">Changes to this document</h1>
            <p className="text-sm text-muted">What a new version changed in the text, in the test set and in how well your pipeline finds things.</p>
          </div>
        </header>
        {versions.isLoading && <div className="skeleton h-24" />}
        {versions.isError && <p role="alert" className="text-sm text-danger">This document could not be found.</p>}
        {list.length === 1 && <p className="card text-sm text-muted">There is only one version. Replace the document with a new file to see what the change does.</p>}
        {list.length > 1 && (
          <>
            <div className="flex flex-wrap gap-1.5" role="tablist" aria-label="Versions">
              {list.filter((v) => v.version > 1).map((v) => (
                <button key={v.version} role="tab" aria-selected={chosen === v.version} className={`btn-ghost !border ${chosen === v.version ? '!border-accent' : '!border-[var(--line)]'}`} onClick={() => setPick(v.version)}>
                  v{v.version - 1} to v{v.version} <span className="ml-1 text-xs text-muted">{new Date(v.createdAt).toLocaleDateString()}</span>
                </button>
              ))}
            </div>
            {impact.isLoading && <div className="skeleton h-32" />}
            {r && (
              <>
                <section className="glass space-y-3 rounded-2xl p-5"><h2 className="text-sm font-semibold">Impact</h2><Impact r={r} /></section>
                <section className="glass space-y-3 rounded-2xl p-5">
                  <h2 className="text-sm font-semibold">What changed in the text</h2>
                  {r.diff && <p className="text-xs text-muted">{r.diff.summary.added} paragraph{r.diff.summary.added === 1 ? '' : 's'} added, {r.diff.summary.removed} removed, {r.diff.summary.same} unchanged.</p>}
                  <ul className="space-y-1 font-mono text-xs">
                    {r.diff?.ops.filter((o) => o.kind !== 'SAME').map((o, i) => <li key={i} className={`whitespace-pre-wrap rounded px-2 py-1 ${KIND[o.kind]}`}>{MARK[o.kind]}{o.text}</li>)}
                  </ul>
                </section>
              </>
            )}
          </>
        )}
      </div>
    </div>
  )
}
