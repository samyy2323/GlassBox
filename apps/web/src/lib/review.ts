import type { QualityFlag, QuestionType, ReviewStatus, TestQuestion } from './api'

// Pure logic behind the keyboard-driven review screen: which key does what, how the cursor moves, how the list is filtered.

export type ReviewAction = 'accept' | 'reject' | 'edit' | 'next' | 'prev' | 'reset'

const KEYS: Record<string, ReviewAction> = {
  a: 'accept',
  r: 'reject',
  e: 'edit',
  j: 'next',
  k: 'prev',
  p: 'reset',
  ArrowDown: 'next',
  ArrowUp: 'prev',
}

/** The review action for a key press, or null. Combinations with Ctrl, Meta or Alt belong to the browser. */
export function actionForKey(e: { key: string; ctrlKey?: boolean; metaKey?: boolean; altKey?: boolean }): ReviewAction | null {
  if (e.ctrlKey || e.metaKey || e.altKey) return null
  return KEYS[e.key.length === 1 ? e.key.toLowerCase() : e.key] ?? null
}

/** True while the user is typing somewhere: shortcuts must not fire then. */
export function isTypingTarget(target: EventTarget | null): boolean {
  const el = target as { tagName?: string; isContentEditable?: boolean } | null
  if (!el?.tagName) return false
  const tag = el.tagName.toUpperCase()
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable === true
}

/** Moves the cursor by delta, staying inside [0, length - 1]. */
export function moveIndex(index: number, delta: number, length: number): number {
  if (length <= 0) return 0
  return Math.min(length - 1, Math.max(0, index + delta))
}

export interface ReviewFilter {
  status: ReviewStatus | 'ALL'
  qtype: QuestionType | 'ALL'
  /** A flag, NONE (no flag at all), ANY (at least one) or ALL (no filter). */
  flag: QualityFlag | 'NONE' | 'ANY' | 'ALL'
}

export const NO_FILTER: ReviewFilter = { status: 'ALL', qtype: 'ALL', flag: 'ALL' }

export function filterQuestions(questions: TestQuestion[], f: ReviewFilter): TestQuestion[] {
  return questions.filter(
    (q) =>
      (f.status === 'ALL' || q.reviewStatus === f.status) &&
      (f.qtype === 'ALL' || q.qtype === f.qtype) &&
      (f.flag === 'ALL' || (f.flag === 'NONE' ? q.flags.length === 0 : f.flag === 'ANY' ? q.flags.length > 0 : q.flags.includes(f.flag))),
  )
}

/** Where to look after a decision: the next question still pending after this one, else the one after it, else stay. */
export function afterDecision(questions: TestQuestion[], index: number): number {
  for (let i = index + 1; i < questions.length; i++) if (questions[i].reviewStatus === 'PENDING') return i
  return Math.min(index + 1, questions.length - 1)
}
