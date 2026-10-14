import { useQuery } from '@tanstack/react-query'
import { api, type TraceSummary } from './api'
import { queryKeys } from './queryKeys'
import { useWorkspace } from './workspace'

/** Recent answers, newest first: the sidebar shows them as "recent chats" because every answer is stored as a trace. */
export function useRecentTraces() {
  const { current } = useWorkspace()
  return useQuery({ queryKey: queryKeys.recentTraces(current?.id), queryFn: () => api.traces(undefined, 'CHAT'), enabled: !!current, staleTime: 15_000 })
}

export interface Group {
  label: string
  items: TraceSummary[]
}

const DAY = 24 * 60 * 60 * 1000

/** Buckets items (newest first) into Today / Yesterday / Previous 7 days / Older, by local calendar day. */
export function groupByDay(items: TraceSummary[], now: Date = new Date()): Group[] {
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const buckets: Group[] = [
    { label: 'Today', items: [] },
    { label: 'Yesterday', items: [] },
    { label: 'Previous 7 days', items: [] },
    { label: 'Older', items: [] },
  ]
  for (const item of items) {
    const t = new Date(item.createdAt).getTime()
    const i = t >= startOfToday ? 0 : t >= startOfToday - DAY ? 1 : t >= startOfToday - 7 * DAY ? 2 : 3
    buckets[i].items.push(item)
  }
  return buckets.filter((b) => b.items.length > 0)
}
