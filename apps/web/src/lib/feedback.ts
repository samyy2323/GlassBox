import type { FeedbackSummary, SpanInput } from './api'

// Pure helpers behind the feedback inbox: which passages are marked as the evidence, whether the item can be saved, and how the share of
// questions that came from real failures is put.

export const sameSpan = (a: SpanInput, b: SpanInput) => a.documentId === b.documentId && a.charStart === b.charStart && a.charEnd === b.charEnd

/** Marks the passage, or unmarks it when it already is. */
export function toggleSpan(marked: SpanInput[], span: SpanInput): SpanInput[] {
  return marked.some((m) => sameSpan(m, span)) ? marked.filter((m) => !sameSpan(m, span)) : [...marked, span]
}

/** A selection in the document viewer becomes evidence unless it is empty or already marked. */
export function addSpan(marked: SpanInput[], span: SpanInput): SpanInput[] {
  if (span.charEnd <= span.charStart || marked.some((m) => sameSpan(m, span))) return marked
  return [...marked, span]
}

/** With evidence the question is a normal one; without, the only thing it can be is a question the documents cannot answer. */
export function defaultType(marked: SpanInput[]): 'FACTUAL' | 'UNANSWERABLE' {
  return marked.length === 0 ? 'UNANSWERABLE' : 'FACTUAL'
}

/** Whether the item can be saved as a test question: evidence (or an unanswerable question) and an expected answer for the answerable ones. */
export function canConvert(marked: SpanInput[], qtype: string, expected: string, testsetId: string): boolean {
  if (!testsetId) return false
  if (qtype === 'UNANSWERABLE') return true
  return marked.length > 0 && expected.trim().length > 0
}

export function fromFeedbackText(s: FeedbackSummary): string {
  if (s.questionsTotal === 0) return 'No test set has questions yet.'
  const pct = Math.round((s.questionsFromFeedback / s.questionsTotal) * 100)
  return `${s.questionsFromFeedback} of ${s.questionsTotal} questions in your latest test sets (${pct}%) came from real failures.`
}
