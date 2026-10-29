import type { QualityFlag, QuestionType, ReviewStatus, SpanInput, TestSet, TestSetStatus } from './api'
import type { Range } from './selection'

export const TYPE_LABEL: Record<QuestionType, string> = {
  FACTUAL: 'Factual',
  NUMERIC: 'Numeric',
  MULTI_HOP: 'Multi-hop',
  COMPARISON: 'Comparison',
  UNANSWERABLE: 'Unanswerable',
}

/** The types generation can produce. An unanswerable question is written around a passage but asks for a detail the corpus does not state; retrieval checks it. */
export const GENERATABLE: QuestionType[] = ['FACTUAL', 'NUMERIC', 'MULTI_HOP', 'COMPARISON', 'UNANSWERABLE']

export const FLAG_LABEL: Record<QualityFlag, { label: string; hint: string }> = {
  COPY_RISK: { label: 'Copies the text', hint: 'The question repeats most of the evidence, which makes keyword search look better than it is.' },
  AMBIGUOUS: { label: 'Not self-contained', hint: 'The question only makes sense next to the source ("the document says...").' },
  LOW_ANSWERABILITY: { label: 'Hard to answer', hint: 'A model given only the evidence did not reproduce the expected answer.' },
  DUPLICATE: { label: 'Duplicate', hint: 'Very similar to an earlier question in this set.' },
  STALE: { label: 'Evidence moved', hint: 'The document was replaced and this question\'s evidence could not be found in the new text. Check it, or mark the new passage.' },
  ANSWERABLE_RISK: { label: 'May be answerable', hint: 'Meant to be unanswerable, but a model answered it from what the search found. Check the corpus before you accept it.' },
}

export const STATUS_LABEL: Record<TestSetStatus, string> = {
  GENERATING: 'Generating',
  DRAFT: 'Draft',
  FINAL: 'Final',
  FAILED: 'Failed',
}

export const REVIEW_LABEL: Record<ReviewStatus, string> = {
  PENDING: 'Pending',
  ACCEPTED: 'Accepted',
  EDITED: 'Edited',
  REJECTED: 'Rejected',
}

/** Model calls a generation needs: one to write each question, plus two quality checks when those are on. */
export function estimateCalls(count: number, llmFilters = true): number {
  return Math.max(0, Math.round(count)) * (llmFilters ? 3 : 1)
}

export function percent(rate: number | undefined): string {
  return rate == null ? 'not reviewed yet' : `${Math.round(rate * 100)}%`
}

/** Progress of a generation, 0 to 100. */
export function progressPercent(t: Pick<TestSet, 'generatedCount' | 'targetCount'>): number {
  if (t.targetCount <= 0) return 0
  return Math.min(100, Math.round((t.generatedCount / t.targetCount) * 100))
}

/** Positive shares only, as the API expects. An empty result means "use the default mix". */
export function cleanMix(weights: Partial<Record<QuestionType, number>>): Record<string, number> {
  const out: Record<string, number> = {}
  for (const [k, v] of Object.entries(weights)) if (v != null && v > 0) out[k] = v
  return out
}

export type Mix = Partial<Record<QuestionType, number>>

/**
 * Splits `total` questions among the types in proportion to `weights`, in whole numbers that add up to exactly `total` (largest remainder: each type gets the
 * whole part of its share and the leftover questions go to the biggest fractions, earlier types first on a tie). All-zero weights share equally.
 */
export function apportion(total: number, weights: Mix, types: QuestionType[] = GENERATABLE): Mix {
  const n = Math.max(0, Math.floor(Number.isFinite(total) ? total : 0))
  const w = types.map((t) => Math.max(0, weights[t] ?? 0))
  const sum = w.reduce((a, b) => a + b, 0)
  const parts = sum > 0 ? w : w.map(() => 1)
  const whole = sum > 0 ? sum : parts.length
  const exact = parts.map((x) => (n * x) / whole)
  const counts = exact.map(Math.floor)
  let left = n - counts.reduce((a, b) => a + b, 0)
  const order = exact.map((x, i) => ({ i, frac: x - Math.floor(x) })).sort((a, b) => b.frac - a.frac || a.i - b.i)
  for (const { i } of order) {
    if (left <= 0) break
    counts[i]++
    left--
  }
  const out: Mix = {}
  types.forEach((t, i) => { out[t] = counts[i] })
  return out
}

/** Sets one type to `value` questions (kept between 0 and `total`) and spreads the rest of the total over the other types in their current proportions. */
export function setTypeCount(total: number, counts: Mix, type: QuestionType, value: number, types: QuestionType[] = GENERATABLE): Mix {
  const n = Math.max(0, Math.floor(total))
  const v = Math.min(n, Math.max(0, Math.round(Number.isFinite(value) ? value : 0)))
  const others = types.filter((t) => t !== type)
  const rest = apportion(n - v, Object.fromEntries(others.map((t) => [t, counts[t] ?? 0])) as Mix, others)
  return { ...rest, [type]: v }
}

export interface VersionGroup {
  name: string
  versions: TestSet[]
}

/** Test sets grouped by name, newest version first inside each group, groups ordered by their newest version's creation. */
export function groupVersions(sets: TestSet[]): VersionGroup[] {
  const byName = new Map<string, TestSet[]>()
  for (const s of sets) byName.set(s.name, [...(byName.get(s.name) ?? []), s])
  const groups = [...byName.entries()].map(([name, versions]) => ({ name, versions: versions.sort((a, b) => b.version - a.version) }))
  return groups.sort((a, b) => b.versions[0].createdAt.localeCompare(a.versions[0].createdAt))
}

/** Just the position of each piece of evidence: what the API accepts. Anything with these three fields will do. */
export const spanInputs = (evidence: SpanInput[]): SpanInput[] => evidence.map((e) => ({ documentId: e.documentId, charStart: e.charStart, charEnd: e.charEnd }))

/** Replaces the evidence at `index` with a new range of the same document (or of another one). */
export function replaceEvidence(evidence: SpanInput[], index: number, documentId: string, range: Range): SpanInput[] {
  const spans = spanInputs(evidence)
  spans[index] = { documentId, charStart: range.start, charEnd: range.end }
  return spans
}

export function addEvidence(evidence: SpanInput[], documentId: string, range: Range): SpanInput[] {
  return [...spanInputs(evidence), { documentId, charStart: range.start, charEnd: range.end }]
}

export function removeEvidence(evidence: SpanInput[], index: number): SpanInput[] {
  return spanInputs(evidence).filter((_, i) => i !== index)
}
