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
export function monthProgress(spent: number, budget: number | null | undefined): number | null {
  if (budget == null || budget <= 0) return null
  return Math.max(0, Math.min(100, Math.round((spent / budget) * 100)))
}

/** The estimator's own error, in words: positive means runs used more than projected. */
export function estimatorError(error: number | null | undefined, runs: number): string {
  if (error == null || runs === 0) return 'No finished run has been measured yet, so the estimator’s error is not known.'
  const pct = Math.round(Math.abs(error) * 100)
  if (pct < 3) return `Over the last ${runs} run${runs === 1 ? '' : 's'} the estimate was within 3% of the tokens actually used.`
  return `Over the last ${runs} run${runs === 1 ? '' : 's'} runs used ${pct}% ${error < 0 ? 'fewer' : 'more'} tokens than estimated.`
}

/** The cost breakdown lines worth showing (those with anything in them), in the order SPEC names them. */
export function breakdownLines(breakdown: Record<string, unknown> | undefined): { key: string; label: string; tokens: number; calls: number; usd: number }[] {
  if (!breakdown) return []
  const labels: [string, string][] = [['embeddings', 'Embeddings'], ['generation', 'Answers'], ['judging', 'Judging'], ['rewriting', 'Query rewriting'], ['synthesis', 'Question synthesis']]
  return labels.flatMap(([key, label]) => {
    const l = breakdown[key] as { tokens: number; calls: number; usd: number } | undefined
    return l && (l.tokens > 0 || l.calls > 0) ? [{ key, label, tokens: l.tokens, calls: l.calls, usd: l.usd }] : []
  })
}
