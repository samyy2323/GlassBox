import { describe, expect, it } from 'vitest'
import type { Evidence, TestSet } from './api'
import { rangeFromEnds, trimRange } from './selection'
import { addEvidence, apportion, cleanMix, setTypeCount, estimateCalls, groupVersions, percent, progressPercent, removeEvidence, replaceEvidence } from './testsets'

function set(name: string, version: number, createdAt: string): TestSet {
  return {
    id: `${name}-${version}`, name, version, status: 'DRAFT', targetCount: 10, generatedCount: 4, createdAt,
    stats: { total: 4, pending: 4, accepted: 0, edited: 0, rejected: 0, byType: {}, byFlag: {} },
  }
}

const ev = (documentId: string, charStart: number, charEnd: number): Evidence => ({ documentId, title: documentId, charStart, charEnd, text: '' })

describe('selection ranges', () => {
  it('turns two ends into one range in either direction', () => {
    expect(rangeFromEnds({ start: 100, offset: 5 }, { start: 140, offset: 12 }, 1000)).toEqual({ start: 105, end: 152 })
    expect(rangeFromEnds({ start: 140, offset: 12 }, { start: 100, offset: 5 }, 1000)).toEqual({ start: 105, end: 152 })
  })

  it('is empty for a collapsed selection and clamps to the text', () => {
    expect(rangeFromEnds({ start: 10, offset: 3 }, { start: 13, offset: 0 }, 1000)).toBeNull()
    expect(rangeFromEnds({ start: 990, offset: 0 }, { start: 990, offset: 500 }, 1000)).toEqual({ start: 990, end: 1000 })
    expect(rangeFromEnds({ start: 0, offset: 0 }, { start: 0, offset: 0 }, 0)).toBeNull()
  })

  it('trims blanks from both ends and refuses a selection of only blanks', () => {
    const text = 'abc   hello world  \n xyz'
    expect(trimRange(text, { start: 3, end: 19 })).toEqual({ start: 6, end: 17 })
    expect(text.slice(6, 17)).toBe('hello world')
    expect(trimRange('a     b', { start: 1, end: 6 })).toBeNull()
  })
})

describe('test set helpers', () => {
  it('estimates one model call per question, three with the quality checks', () => {
    expect(estimateCalls(50)).toBe(150)
    expect(estimateCalls(50, false)).toBe(50)
    expect(estimateCalls(-3)).toBe(0)
  })

  it('formats the acceptance rate and the generation progress', () => {
    expect(percent(undefined)).toBe('not reviewed yet')
    expect(percent(0.8333)).toBe('83%')
    expect(percent(1)).toBe('100%')
    expect(progressPercent({ generatedCount: 25, targetCount: 50 })).toBe(50)
    expect(progressPercent({ generatedCount: 0, targetCount: 0 })).toBe(0)
    expect(progressPercent({ generatedCount: 60, targetCount: 50 })).toBe(100)
  })

  it('keeps only positive shares of the type mix', () => {
    expect(cleanMix({ FACTUAL: 50, NUMERIC: 0, MULTI_HOP: 25 })).toEqual({ FACTUAL: 50, MULTI_HOP: 25 })
    expect(cleanMix({})).toEqual({})
  })

  it('groups versions by name, newest version first, newest group first', () => {
    const groups = groupVersions([set('a', 1, '2026-01-01'), set('b', 1, '2026-03-01'), set('a', 2, '2026-02-01')])
    expect(groups.map((g) => g.name)).toEqual(['b', 'a'])
    expect(groups[1].versions.map((v) => v.version)).toEqual([2, 1])
  })

  it('edits the evidence list without touching the original', () => {
    const evidence = [ev('d1', 10, 20), ev('d2', 30, 40)]
    expect(replaceEvidence(evidence, 1, 'd2', { start: 31, end: 45 })).toEqual([
      { documentId: 'd1', charStart: 10, charEnd: 20 },
      { documentId: 'd2', charStart: 31, charEnd: 45 },
    ])
    expect(addEvidence(evidence, 'd3', { start: 1, end: 2 })).toHaveLength(3)
    expect(removeEvidence(evidence, 0)).toEqual([{ documentId: 'd2', charStart: 30, charEnd: 40 }])
    expect(evidence[1].charStart).toBe(30)
  })
})

describe('the mix of question types follows the number of questions', () => {
  const DEFAULT = { FACTUAL: 50, NUMERIC: 15, MULTI_HOP: 10, COMPARISON: 15, UNANSWERABLE: 10 }
  const total = (m: Record<string, number | undefined>) => Object.values(m).reduce<number>((a, b) => a + (b ?? 0), 0)

  it('adds up to exactly the number of questions, for every number', () => {
    for (let n = 0; n <= 200; n++) expect(total(apportion(n, DEFAULT))).toBe(n)
  })

  it('splits 10 questions from the default shares, and 100 back to the shares themselves', () => {
    expect(apportion(10, DEFAULT)).toEqual({ FACTUAL: 5, NUMERIC: 2, MULTI_HOP: 1, COMPARISON: 1, UNANSWERABLE: 1 })
    expect(apportion(100, DEFAULT)).toEqual(DEFAULT)
    expect(apportion(50, DEFAULT)).toEqual({ FACTUAL: 25, NUMERIC: 8, MULTI_HOP: 5, COMPARISON: 7, UNANSWERABLE: 5 })
  })

  it('shares equally when every weight is zero, and never goes negative', () => {
    expect(apportion(10, { FACTUAL: 0 })).toEqual({ FACTUAL: 2, NUMERIC: 2, MULTI_HOP: 2, COMPARISON: 2, UNANSWERABLE: 2 })
    expect(apportion(-5, DEFAULT)).toEqual({ FACTUAL: 0, NUMERIC: 0, MULTI_HOP: 0, COMPARISON: 0, UNANSWERABLE: 0 })
    expect(apportion(Number.NaN, DEFAULT).FACTUAL).toBe(0)
  })

  it('editing one type keeps the total and rebalances the others in their proportions', () => {
    const edited = setTypeCount(20, apportion(20, DEFAULT), 'FACTUAL', 4)
    expect(edited.FACTUAL).toBe(4)
    expect(total(edited)).toBe(20)
    expect(edited.NUMERIC).toBeGreaterThanOrEqual(1)
    expect(edited.UNANSWERABLE).toBeGreaterThanOrEqual(1)
  })

  it('clamps an edit to the total, and spreads the rest equally when the others were all zero', () => {
    expect(setTypeCount(10, apportion(10, DEFAULT), 'NUMERIC', 99)).toEqual({ FACTUAL: 0, NUMERIC: 10, MULTI_HOP: 0, COMPARISON: 0, UNANSWERABLE: 0 })
    const onlyFactual = { FACTUAL: 10, NUMERIC: 0, MULTI_HOP: 0, COMPARISON: 0, UNANSWERABLE: 0 }
    const spread = setTypeCount(10, onlyFactual, 'FACTUAL', 6)
    expect(total(spread)).toBe(10)
    expect(spread.FACTUAL).toBe(6)
    expect(spread.NUMERIC).toBe(1)
  })
})
