import { useInfiniteQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api } from '../lib/api'
import { queryKeys } from '../lib/queryKeys'
import { useWorkspace } from '../lib/workspace'

const STATUS_CLS: Record<string, string> = {
  OK: 'bg-fg/10 text-fg',
  ABSTAINED: 'bg-fg/5 text-muted',
  ERROR: 'bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-300',
  CANCELLED: 'bg-fg/5 text-muted',
}

export default function XRayList() {
  const { current } = useWorkspace()
  const q = useInfiniteQuery({
    queryKey: queryKeys.traceList(current?.id),
    queryFn: ({ pageParam }) => api.traces(pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor,
    enabled: !!current,
  })

  if (q.isLoading) return <div className="skeleton h-40" aria-label="Loading traces" />
  if (q.isError) return <p role="alert" className="text-red-600">Could not load traces.</p>
  const items = q.data?.pages.flatMap((p) => p.items) ?? []

  return (
    <div className="min-h-0 flex-1 overflow-y-auto">
    <section className="mx-auto w-full max-w-4xl space-y-4 p-4 sm:p-6">
      <h1 className="text-[1.65rem] font-semibold tracking-tight">X-Ray</h1>
      <p className="text-sm text-muted">Every answer leaves a trace: what was retrieved, how it was ranked, what reached the prompt, and why.</p>
      {items.length === 0 ? (
        <div className="card text-center text-sm text-muted">
          <p>No answers yet.</p>
          <p className="mt-1"><Link to="/chat" className="underline underline-offset-4">Ask a question in Chat</Link> and it will appear here.</p>
        </div>
      ) : (
        <ul className="divide-y divide-[var(--line)] glass overflow-hidden rounded-2xl">
          {items.map((t) => (
            <li key={t.id}>
              <Link to={`/xray/${t.id}`} className="flex items-center gap-3 px-4 py-3 text-sm hover:bg-[var(--glass-hover)]">
                <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_CLS[t.status] ?? ''}`}>{t.status.toLowerCase()}</span>
                <span className="flex-1 truncate font-medium">{t.question}</span>
                <span className="text-xs text-muted">{t.latencyMs != null ? `${(t.latencyMs / 1000).toFixed(1)} s` : ''}</span>
                <span className="hidden text-xs text-muted sm:inline">{new Date(t.createdAt).toLocaleString()}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
      {q.hasNextPage && (
        <button className="btn-primary" disabled={q.isFetchingNextPage} onClick={() => void q.fetchNextPage()}>
          {q.isFetchingNextPage ? 'Loading…' : 'Load more'}
        </button>
      )}
    </section>
    </div>
  )
}
