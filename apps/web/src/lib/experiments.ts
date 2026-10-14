import type { ComparisonRow, ConfigInput, Experiment, ExperimentMode, JudgeInfo, MetricValue, Outcome, RunSummary } from './api'

// Pure helpers behind the Eval Lab pages: how metrics are named and formatted, how a forest plot and a heatmap are laid out,
// which configurations a user can start from, and when a judged number must carry a warning.

export type MetricKind = 'rate' | 'ms'

export const METRICS: Record<string, { label: string; hint: string; group: 'Retrieval' | 'Answer' | 'Abstention' | 'System'; kind: MetricKind; higherIsBetter: boolean }> = {
  'recall@5': { label: 'Recall@5', hint: 'Share of the gold evidence found in the top 5 chunks.', group: 'Retrieval', kind: 'rate', higherIsBetter: true },
  'recall@10': { label: 'Recall@10', hint: 'Share of the gold evidence found in the top 10 chunks.', group: 'Retrieval', kind: 'rate', higherIsBetter: true },
  mrr: { label: 'MRR', hint: 'One over the rank of the first chunk that holds evidence.', group: 'Retrieval', kind: 'rate', higherIsBetter: true },
  'ndcg@10': { label: 'nDCG@10', hint: 'How early in the top 10 the evidence appears.', group: 'Retrieval', kind: 'rate', higherIsBetter: true },
  context_recall: { label: 'Context recall', hint: 'Share of the gold evidence that reached the prompt.', group: 'Retrieval', kind: 'rate', higherIsBetter: true },
  'pass@5': { label: 'Evidence in top 5', hint: 'Questions where every piece of evidence was in the top 5.', group: 'Retrieval', kind: 'rate', higherIsBetter: true },
  context_pass: { label: 'Evidence in prompt', hint: 'Questions where every piece of evidence reached the prompt.', group: 'Retrieval', kind: 'rate', higherIsBetter: true },
  faithfulness: { label: 'Faithfulness', hint: 'Share of the answer’s statements that the retrieved text supports.', group: 'Answer', kind: 'rate', higherIsBetter: true },
  correctness: { label: 'Correctness', hint: 'A judge compares the answer with the expected one: correct 1, partial 0.5, wrong 0.', group: 'Answer', kind: 'rate', higherIsBetter: true },
  relevance: { label: 'Relevance', hint: 'A judge rates whether the answer addresses the question.', group: 'Answer', kind: 'rate', higherIsBetter: true },
  citation_precision: { label: 'Citation precision', hint: 'Of the statements that cite a chunk, the share whose chunk really says it.', group: 'Answer', kind: 'rate', higherIsBetter: true },
  false_abstention: { label: 'False abstention', hint: 'Answerable questions the system wrongly refused. Lower is better.', group: 'Abstention', kind: 'rate', higherIsBetter: false },
  abstention_accuracy: { label: 'Abstention accuracy', hint: 'Unanswerable questions the system correctly said it could not answer.', group: 'Abstention', kind: 'rate', higherIsBetter: true },
  latency_p50_ms: { label: 'Latency p50', hint: 'Median time per question.', group: 'System', kind: 'ms', higherIsBetter: false },
  latency_p95_ms: { label: 'Latency p95', hint: 'The slowest 5% of questions took longer than this.', group: 'System', kind: 'ms', higherIsBetter: false },
}

/** The metrics in the order they are shown. */
export const METRIC_ORDER = Object.keys(METRICS)

/** The single number an experiment is summarised by: did the evidence reach the prompt, or was the answer right. */
export const HEADLINE: Record<ExperimentMode, string> = { RETRIEVAL: 'context_pass', FULL: 'correctness' }

/** Below this many questions a slice of the results is marked low-sample. */
export const LOW_SAMPLE = 10

export const QUESTION_TYPES = ['FACTUAL', 'NUMERIC', 'MULTI_HOP', 'COMPARISON', 'UNANSWERABLE'] as const

export const metricLabel = (metric: string) => METRICS[metric]?.label ?? metric

export function formatMetric(metric: string, v: number | undefined): string {
  if (v == null) return '–'
  return METRICS[metric]?.kind === 'ms' ? `${Math.round(v)} ms` : `${Math.round(v * 1000) / 10}%`
}

export function formatInterval(metric: string, m: MetricValue | undefined): string {
  if (!m || m.value == null) return '–'
  if (m.ciLow == null || m.ciHigh == null) return formatMetric(metric, m.value)
  return `${formatMetric(metric, m.value)} [${formatMetric(metric, m.ciLow)}, ${formatMetric(metric, m.ciHigh)}]`
}

/** A change in points of a 0 to 1 rate, with its sign: "+4.1 pts". */
export function formatDelta(delta: number): string {
  const points = Math.round(delta * 1000) / 10
  return `${points > 0 ? '+' : ''}${points === 0 ? '0.0' : points.toFixed(1)} pts`
}

export function findMetric(metrics: MetricValue[], metric: string, slice = 'all', method: 'question' | 'cluster' = 'question'): MetricValue | undefined {
  return metrics.find((m) => m.metric === metric && m.slice === slice && m.method === method)
}

/** Whether a metric rests on a model's judgement (or on claim verification), and so needs a calibrated judge. */
export const isJudged = (metric: string) => ['faithfulness', 'correctness', 'relevance', 'citation_precision'].includes(metric)

/**
 * The warning a judged number must carry: "uncalibrated" when the judge was measured against human labels and fell short, "unknown" when it
 * never was. Null for numbers that need no judge, and for a calibrated judge.
 */
export function judgeBadge(metric: string, judge: JudgeInfo | undefined): 'uncalibrated' | 'unknown' | null {
  if (!judge || !isJudged(metric)) return null
  const key = metric === 'citation_precision' ? 'faithfulness' : metric
  const status = judge.metrics[key]?.status ?? 'unknown'
  return status === 'calibrated' ? null : status
}

export const outcomeTone = (o: Outcome, higherIsBetter = true): 'good' | 'bad' | 'none' => {
  if (o === 'NO_DIFFERENCE') return 'none'
  const bBetter = o === 'B_BETTER'
  return bBetter === higherIsBetter ? 'good' : 'bad'
}

// ---------------------------------------------------------------------------------------------- forest plot

export interface ForestRow {
  label: string
  delta: number
  low: number
  high: number
  tone: 'good' | 'bad' | 'none'
}

export function forestRows(rows: { label: string; row: ComparisonRow | undefined }[], metric: string): ForestRow[] {
  const higher = METRICS[metric]?.higherIsBetter ?? true
  return rows.flatMap(({ label, row }) => (row ? [{ label, delta: row.delta, low: row.ciLow, high: row.ciHigh, tone: outcomeTone(row.outcome, higher) }] : []))
}

/** The axis range of a forest plot: wide enough for every interval, always including zero, rounded out to whole 5-point steps, never degenerate. */
export function forestDomain(rows: Pick<ForestRow, 'low' | 'high'>[]): [number, number] {
  let lo = 0, hi = 0
  for (const r of rows) {
    lo = Math.min(lo, r.low)
    hi = Math.max(hi, r.high)
  }
  const step = 0.05
  lo = Math.floor(lo / step) * step
  hi = Math.ceil(hi / step) * step
  if (hi - lo < step * 2) {
    lo -= step
    hi += step
  }
  return [Math.round(lo * 100) / 100, Math.round(hi * 100) / 100]
}

export const scaleX = (v: number, [lo, hi]: [number, number], width: number) => ((v - lo) / (hi - lo)) * width

// ---------------------------------------------------------------------------------------------- heatmap

/** How strongly to colour a heatmap cell: by the value, and flagged when there are too few questions to trust it. */
export function heatCell(value: number | undefined, n: number): { opacity: number; lowSample: boolean } {
  return { opacity: value == null ? 0 : 0.12 + 0.68 * Math.max(0, Math.min(1, value)), lowSample: n < LOW_SAMPLE }
}

// ---------------------------------------------------------------------------------------------- runs

export const isActive = (status: Experiment['status']) => status === 'QUEUED' || status === 'RUNNING'

export function runProgress(run: Pick<RunSummary, 'samplesDone' | 'status'>, questions: number): number {
  if (run.status === 'DONE') return 100
  return questions <= 0 ? 0 : Math.min(100, Math.round((run.samplesDone / questions) * 100))
}

/** The slices a heatmap has columns for: question types that actually have results, in a fixed order. */
export function slicesPresent(runs: RunSummary[], metric: string): string[] {
  const seen = new Set<string>()
  for (const r of runs) for (const m of r.metrics) if (m.metric === metric && m.slice !== 'all' && m.method === 'question') seen.add(m.slice)
  return QUESTION_TYPES.filter((t) => seen.has(t))
}

// ---------------------------------------------------------------------------------------------- starting points

export interface Preset {
  id: string
  title: string
  description: string
  config: ConfigInput
}

export const PRESETS: Preset[] = [
  { id: 'vector', title: 'Vector', description: 'Meaning only: nearest passages by embedding.', config: { name: 'Vector', mode: 'VECTOR' } },
  { id: 'keyword', title: 'Keyword', description: 'Words only: full-text match on any question word.', config: { name: 'Keyword', mode: 'KEYWORD' } },
  { id: 'hybrid', title: 'Hybrid', description: 'Vector and keyword results fused (reciprocal rank fusion).', config: { name: 'Hybrid', mode: 'HYBRID' } },
  { id: 'hybrid-rerank', title: 'Hybrid + rerank', description: 'Hybrid, then a cross-encoder re-sorts the candidates.', config: { name: 'Hybrid + rerank', mode: 'HYBRID', reranker: 'CROSS_ENCODER' } },
]

/** A copy of the preset with a name that is not taken yet ("Hybrid", "Hybrid 2", ...): configurations in one experiment need distinct names. */
export function uniquePreset(id: string, taken: string[]): ConfigInput {
  const base = PRESETS.find((p) => p.id === id)!.config
  let name = base.name
  for (let n = 2; taken.includes(name); n++) name = `${base.name} ${n}`
  return { ...base, name }
}
