import { describe, expect, it } from 'vitest'
import type { SpanInput } from './api'
import { addSpan, canConvert, defaultType, fromFeedbackText, toggleSpan } from './feedback'

const span = (documentId: string, charStart: number, charEnd: number): SpanInput => ({ documentId, charStart, charEnd })

describe('marking the evidence', () => {
  it('toggles a retrieved passage on and off', () => {
    const a = span('d1', 0, 10)
    const once = toggleSpan([], a)
    expect(once).toEqual([a])
    expect(toggleSpan(once, span('d1', 0, 10))).toEqual([])
    expect(toggleSpan(once, span('d1', 0, 11))).toHaveLength(2)
  })

  it('adds a selection once and ignores an empty one', () => {
    const a = span('d1', 5, 20)
    expect(addSpan([], a)).toEqual([a])
    expect(addSpan([a], span('d1', 5, 20))).toEqual([a])
    expect(addSpan([a], span('d1', 30, 30))).toEqual([a])
    expect(addSpan([a], span('d2', 5, 20))).toHaveLength(2)
  })
})

describe('saving as a test question', () => {
  it('is unanswerable by default exactly when nothing is marked', () => {
    expect(defaultType([])).toBe('UNANSWERABLE')
    expect(defaultType([span('d', 0, 5)])).toBe('FACTUAL')
  })

  it('needs a test set, and evidence plus an answer unless the question has no answer', () => {
    const ev = [span('d', 0, 5)]
    expect(canConvert(ev, 'FACTUAL', 'the answer', 'set')).toBe(true)
    expect(canConvert(ev, 'FACTUAL', '  ', 'set')).toBe(false)
    expect(canConvert([], 'FACTUAL', 'the answer', 'set')).toBe(false)
    expect(canConvert([], 'UNANSWERABLE', '', 'set')).toBe(true)
    expect(canConvert(ev, 'FACTUAL', 'the answer', '')).toBe(false)
  })
})

describe('the share that came from real failures', () => {
  it('is put in words', () => {
    const s = { open: 1, converted: 2, dismissed: 0, down: 3, up: 0, questionsFromFeedback: 3, questionsTotal: 12 }
    expect(fromFeedbackText(s)).toBe('3 of 12 questions in your latest test sets (25%) came from real failures.')
    expect(fromFeedbackText({ ...s, questionsTotal: 0, questionsFromFeedback: 0 })).toContain('No test set')
  })
})
