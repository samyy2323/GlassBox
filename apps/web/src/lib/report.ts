// Pure helpers behind the report pages: how a share link is written, how long it has left, and how a number with its interval reads.

export const shareUrl = (origin: string, token: string) => `${origin}/r/${token}`

/** "expires in 3 days", "expires today", "expired", for a link's expiry relative to now. */
export function expiryText(expiresAt: string, now: number = Date.now()): string {
  const ms = new Date(expiresAt).getTime() - now
  if (ms <= 0) return 'expired'
  const hours = ms / 3_600_000
  if (hours < 24) return hours < 1 ? 'expires within the hour' : `expires in ${Math.round(hours)} hour${Math.round(hours) === 1 ? '' : 's'}`
  const days = Math.round(hours / 24)
  return `expires in ${days} day${days === 1 ? '' : 's'}`
}

export const pct = (v: number | undefined | null) => (v == null || Number.isNaN(v) ? '–' : `${Math.round(v * 1000) / 10}%`)

/** "97.7% [93.0%, 100%]" for a metric row of a snapshot; latencies stay in milliseconds. */
export function valueWithInterval(row: { metric: string; value: number; ciLow?: number; ciHigh?: number }): string {
  if (row.metric.startsWith('latency')) return `${Math.round(row.value)} ms`
  return row.ciLow == null || row.ciHigh == null ? pct(row.value) : `${pct(row.value)} [${pct(row.ciLow)}, ${pct(row.ciHigh)}]`
}

/** Failure stages as a bar chart: widths relative to the longest bar, most failures first. */
export function failureBars(byStage: Record<string, number>): { stage: string; count: number; width: number }[] {
  const entries = Object.entries(byStage).filter(([, n]) => n > 0).sort((a, b) => b[1] - a[1])
  const max = entries.length ? entries[0][1] : 0
  return entries.map(([stage, count]) => ({ stage: stage.toLowerCase().replace(/_/g, ' '), count, width: max === 0 ? 0 : count / max }))
}
