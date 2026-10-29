// Pure helpers behind the budget page and the cost lines of the experiment forms.

/** Dollars with the precision a small run needs: "$0.0042" under a cent, "$12.40" above a dollar, and "free" for a local model. */
export function formatUsd(v: number | undefined | null): string {
  if (v == null) return '–'
  if (v === 0) return 'free'
  if (v < 0.01) return `$${v.toFixed(4)}`
  if (v < 1) return `$${v.toFixed(3)}`
  return `$${v.toFixed(2)}`
}

export function formatTokens(n: number): string {
  if (n < 1000) return String(n)
  if (n < 1_000_000) return `${Math.round(n / 100) / 10}k`
  return `${Math.round(n / 100_000) / 10}M`
}

/** Share of the monthly budget that is spent, clamped for a progress bar; null when there is no budget. */
export function breakdownLines(breakdown: Record<string, unknown> | undefined): { key: string; label: string; tokens: number; calls: number; usd: number }[] {
  if (!breakdown) return []
  const labels: [string, string][] = [['embeddings', 'Embeddings'], ['generation', 'Answers'], ['judging', 'Judging'], ['rewriting', 'Query rewriting'], ['synthesis', 'Question synthesis']]
  return labels.flatMap(([key, label]) => {
    const l = breakdown[key] as { tokens: number; calls: number; usd: number } | undefined
    return l && (l.tokens > 0 || l.calls > 0) ? [{ key, label, tokens: l.tokens, calls: l.calls, usd: l.usd }] : []
  })
}
