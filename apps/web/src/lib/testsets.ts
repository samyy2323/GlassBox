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
