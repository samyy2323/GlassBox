import { describe, expect, it } from 'vitest'
import type { ReviewStatus, TestQuestion } from './api'
import { actionForKey, afterDecision, filterQuestions, isTypingTarget, moveIndex, NO_FILTER } from './review'

function q(id: string, reviewStatus: ReviewStatus, flags: TestQuestion['flags'] = [], qtype: TestQuestion['qtype'] = 'FACTUAL'): TestQuestion {
  return { id, stableId: id, testsetId: 't', question: id, expectedAnswer: 'a', qtype, origin: 'SYNTHETIC', reviewStatus, flags, evidence: [], createdAt: '' }
}

describe('actionForKey', () => {
  it('maps the review shortcuts, in either letter case', () => {
    expect(actionForKey({ key: 'a' })).toBe('accept')
    expect(actionForKey({ key: 'A' })).toBe('accept')
    expect(actionForKey({ key: 'r' })).toBe('reject')
    expect(actionForKey({ key: 'e' })).toBe('edit')
    expect(actionForKey({ key: 'j' })).toBe('next')
    expect(actionForKey({ key: 'k' })).toBe('prev')
    expect(actionForKey({ key: 'ArrowDown' })).toBe('next')
    expect(actionForKey({ key: 'ArrowUp' })).toBe('prev')
    expect(actionForKey({ key: 'p' })).toBe('reset')
  })

  it('ignores other keys and browser shortcuts', () => {
    expect(actionForKey({ key: 'x' })).toBeNull()
    expect(actionForKey({ key: 'Enter' })).toBeNull()
    expect(actionForKey({ key: 'r', ctrlKey: true })).toBeNull()
    expect(actionForKey({ key: 'a', metaKey: true })).toBeNull()
    expect(actionForKey({ key: 'e', altKey: true })).toBeNull()
  })
})

describe('isTypingTarget', () => {
  it('is true for form fields so shortcuts do not fire while typing', () => {
    expect(isTypingTarget({ tagName: 'TEXTAREA' } as unknown as EventTarget)).toBe(true)
    expect(isTypingTarget({ tagName: 'input' } as unknown as EventTarget)).toBe(true)
    expect(isTypingTarget({ tagName: 'DIV', isContentEditable: true } as unknown as EventTarget)).toBe(true)
    expect(isTypingTarget({ tagName: 'BUTTON' } as unknown as EventTarget)).toBe(false)
    expect(isTypingTarget(null)).toBe(false)
  })
})

describe('moveIndex', () => {
  it('stays inside the list', () => {
    expect(moveIndex(0, -1, 5)).toBe(0)
    expect(moveIndex(4, 1, 5)).toBe(4)
    expect(moveIndex(2, 1, 5)).toBe(3)
    expect(moveIndex(2, -1, 5)).toBe(1)
    expect(moveIndex(3, 1, 0)).toBe(0)
  })
})

describe('filterQuestions', () => {
  const all = [q('1', 'PENDING'), q('2', 'ACCEPTED', ['DUPLICATE']), q('3', 'PENDING', ['COPY_RISK', 'AMBIGUOUS'], 'NUMERIC'), q('4', 'REJECTED')]

  it('returns everything without a filter', () => {
    expect(filterQuestions(all, NO_FILTER)).toHaveLength(4)
  })

  it('filters by status, type and flag together', () => {
    expect(filterQuestions(all, { ...NO_FILTER, status: 'PENDING' }).map((x) => x.id)).toEqual(['1', '3'])
    expect(filterQuestions(all, { ...NO_FILTER, qtype: 'NUMERIC' }).map((x) => x.id)).toEqual(['3'])
    expect(filterQuestions(all, { ...NO_FILTER, flag: 'NONE' }).map((x) => x.id)).toEqual(['1', '4'])
    expect(filterQuestions(all, { ...NO_FILTER, flag: 'ANY' }).map((x) => x.id)).toEqual(['2', '3'])
    expect(filterQuestions(all, { ...NO_FILTER, flag: 'COPY_RISK' }).map((x) => x.id)).toEqual(['3'])
    expect(filterQuestions(all, { status: 'PENDING', qtype: 'FACTUAL', flag: 'NONE' }).map((x) => x.id)).toEqual(['1'])
  })
})

describe('afterDecision', () => {
  it('jumps to the next pending question', () => {
    const list = [q('1', 'ACCEPTED'), q('2', 'ACCEPTED'), q('3', 'PENDING'), q('4', 'ACCEPTED'), q('5', 'PENDING')]
    expect(afterDecision(list, 0)).toBe(2)
    expect(afterDecision(list, 2)).toBe(4)
  })

  it('moves to the following question when nothing is pending, and stays on the last one', () => {
    const done = [q('1', 'ACCEPTED'), q('2', 'ACCEPTED'), q('3', 'REJECTED')]
    expect(afterDecision(done, 0)).toBe(1)
    expect(afterDecision(done, 2)).toBe(2)
    expect(afterDecision([], 0)).toBe(-1)
  })
})
