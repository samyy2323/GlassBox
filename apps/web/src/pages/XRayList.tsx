import { useInfiniteQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api } from '../lib/api'
import { useWorkspace } from '../lib/workspace'

const STATUS_CLS: Record<string, string> = {
  OK: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300',
  ABSTAINED: 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300',
  ERROR: 'bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-300',
  CANCELLED: 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300',
}

export default function XRayList() {
  const { current } = useWorkspace()
  const q = useInfiniteQuery({
    queryKey: ['traces', current?.id],
    queryFn: ({ pageParam }) => api.traces(pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor,
    enabled: !!current,
  })

  if (q.isLoading) return <div className="h-40 animate-pulse rounded-lg bg-slate-200 dark:bg-slate-800" aria-label="Loading traces" />
  if (q.isError) return <p role="alert" className="text-red-600">Could not load traces.</p>
  const items = q.data?.pages.flatMap((p) => p.items) ?? []

  return (
    <section className="space-y-4">
      <h1 className="text-lg font-semibold">X-Ray</h1>
      <p className="text-sm text-slate-500">Every answer leaves a trace: what was retrieved, how it was ranked, what reached the prompt, and why.</p>
      {items.length === 0 ? (
        <div className="card text-center text-sm text-slate-500">
          <p>No answers yet.</p>
          <p className="mt-1"><Link to="/chat" className="text-indigo-600 hover:underline">Ask a question in Chat</Link> and it will appear here.</p>
        </div>
      ) : (
        <ul className="divide-y divide-slate-200 overflow-hidden rounded-lg border border-slate-200 dark:divide-slate-800 dark:border-slate-800">
          {items.map((t) => (
            <li key={t.id}>
              <Link to={`/xray/${t.id}`} className="flex items-center gap-3 px-4 py-3 text-sm hover:bg-slate-100 dark:hover:bg-slate-900">
                <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_CLS[t.status] ?? ''}`}>{t.status.toLowerCase()}</span>
                <span className="flex-1 truncate font-medium">{t.question}</span>
                <span className="text-xs text-slate-500">{t.latencyMs != null ? `${(t.latencyMs / 1000).toFixed(1)} s` : ''}</span>
                <span className="hidden text-xs text-slate-500 sm:inline">{new Date(t.createdAt).toLocaleString()}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
      {q.hasNextPage && (
        <button className="btn" disabled={q.isFetchingNextPage} onClick={() => void q.fetchNextPage()}>
          {q.isFetchingNextPage ? 'Loading…' : 'Load more'}
        </button>
      )}
    </section>
  )
}
